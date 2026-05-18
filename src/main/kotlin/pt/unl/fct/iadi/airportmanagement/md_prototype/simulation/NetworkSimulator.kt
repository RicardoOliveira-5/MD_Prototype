package pt.unl.fct.iadi.airportmanagement.md_prototype.simulation

import org.neo4j.driver.Driver
import org.neo4j.driver.Session
import org.neo4j.driver.Values
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class NetworkSimulator(private val driver: Driver) {

    private val log = LoggerFactory.getLogger(NetworkSimulator::class.java)

    // ─────────────────────────────────────────────────────────────
    //  1. FLUTUAÇÃO DE AFLUÊNCIA (cada 30 segundos)
    // ─────────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 30_000)
    fun simularFluctuacaoAfluencia() {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run("""
                    MATCH (p:Paragem)
                    WITH p, toInteger((rand() - 0.45) * p.capacidade * 0.15) AS variacao
                    SET p.afluencia_atual =
                        CASE
                            WHEN p.afluencia_atual + variacao < 0 THEN 0
                            WHEN p.afluencia_atual + variacao > p.capacidade THEN p.capacidade
                            ELSE p.afluencia_atual + variacao
                        END
                    RETURN count(p)
                """).list()
            }
            log.info("[SIMULAÇÃO] Afluências atualizadas em todas as paragens.")
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  2. DETETAR SOBRECARGA → CRIAR LIGAÇÃO TEMPORÁRIA (cada 60s)
    //
    //  LÓGICA COMPLETA:
    //  a) Encontrar paragens com ocupação > 80%
    //  b) Verificar se já tem ligação temporária ativa
    //  c) Encontrar vizinha com < 60% de ocupação
    //  d) Criar LIGACAO_TEMPORARIA válida 30 minutos
    //  e) Aumentar peso das ligações (pathfinding evita a paragem)
    //  f) Se sem vizinha → desativar rota que passa lá
    // ─────────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 60_000)
    fun detetarSobrecargaEAdaptar() {
        driver.session().use { session ->

            val sobrecarregadas = session.executeRead { tx ->
                tx.run("""
                    MATCH (p:Paragem)
                    WHERE p.afluencia_atual > p.capacidade * 0.8
                    RETURN p.id AS id, p.nome AS nome,
                           p.afluencia_atual AS afluencia,
                           p.capacidade AS capacidade
                    ORDER BY (1.0 * p.afluencia_atual / p.capacidade) DESC
                """).list { r ->
                    mapOf(
                        "id"         to r["id"].asString(),
                        "nome"       to r["nome"].asString(),
                        "afluencia"  to r["afluencia"].asInt(),
                        "capacidade" to r["capacidade"].asInt()
                    )
                }
            }

            if (sobrecarregadas.isEmpty()) {
                log.info("[ADAPTAÇÃO] Rede estável — nenhuma sobrecarga detetada.")
                return
            }

            sobrecarregadas.forEach { paragem ->
                val paragId   = paragem["id"] as String
                val paragNome = paragem["nome"] as String
                val ocup      = (paragem["afluencia"] as Int) * 100 / (paragem["capacidade"] as Int)

                log.warn("[ADAPTAÇÃO] ⚠ Sobrecarga em '$paragNome' ($ocup%) — a analisar...")

                val jaExiste = session.executeRead { tx ->
                    tx.run("""
                        MATCH (p:Paragem {id: ${'$'}id})-[lt:LIGACAO_TEMPORARIA]->()
                        WHERE lt.validade > datetime()
                        RETURN count(lt) AS total
                    """, Values.parameters("id", paragId))
                        .single()["total"].asLong() > 0
                }

                if (jaExiste) {
                    log.info("[ADAPTAÇÃO] '$paragNome' já tem ligação temporária ativa.")
                    aumentarPesoLigacoes(session, paragId, paragNome)
                    return@forEach
                }

                val vizinha = session.executeRead { tx ->
                    tx.run("""
                        MATCH (origem:Paragem {id: ${'$'}id})-[:SERVE]->(vizinha:Paragem)
                        WHERE vizinha.afluencia_atual < vizinha.capacidade * 0.6
                        RETURN vizinha.id AS id, vizinha.nome AS nome,
                               round(100.0 * vizinha.afluencia_atual / vizinha.capacidade, 1) AS ocup
                        ORDER BY (1.0 * vizinha.afluencia_atual / vizinha.capacidade) ASC
                        LIMIT 1
                    """, Values.parameters("id", paragId))
                        .list { r -> mapOf("id" to r["id"].asString(), "nome" to r["nome"].asString(), "ocup" to r["ocup"].asDouble()) }
                }

                if (vizinha.isEmpty()) {
                    log.warn("[ADAPTAÇÃO] Sem vizinha disponível para '$paragNome' — a desativar rota...")
                    desativarRotaSobrecarregada(session, paragId, paragNome)
                    return@forEach
                }

                val destId   = vizinha.first()["id"] as String
                val destNome = vizinha.first()["nome"] as String
                val destOcup = vizinha.first()["ocup"] as Double
                val validade = LocalDateTime.now().plusMinutes(30)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

                session.executeWrite { tx ->
                    tx.run("""
                        MATCH (a:Paragem {id: ${'$'}origemId}), (b:Paragem {id: ${'$'}destinoId})
                        CREATE (a)-[:LIGACAO_TEMPORARIA {
                            motivo:    ${'$'}motivo,
                            criada_em: datetime(),
                            validade:  datetime(${'$'}validade),
                            tempo_min: 5,
                            distancia: 800
                        }]->(b)
                        RETURN 1
                    """, Values.parameters(
                        "origemId",  paragId,
                        "destinoId", destId,
                        "motivo",    "Sobrecarga automática: $paragNome > 80%",
                        "validade",  validade
                    )).list()
                }

                log.info("[ADAPTAÇÃO] ✓ Ligação temporária criada: '$paragNome' ($ocup%) → '$destNome' ($destOcup%)")
                log.info("[ADAPTAÇÃO]   Válida 30 min — pathfinding redirecionado automaticamente")

                aumentarPesoLigacoes(session, paragId, paragNome)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  3. AUMENTAR PESO DAS LIGAÇÕES (penaliza pathfinding)
    //
    //  Peso alto → shortestPath evita esta paragem
    //  Peso normal = 1 → restaurado a cada 5 minutos
    // ─────────────────────────────────────────────────────────────
    private fun aumentarPesoLigacoes(session: Session, paragId: String, paragNome: String) {
        val afetadas = session.executeWrite { tx ->
            tx.run("""
                MATCH (p:Paragem {id: ${'$'}id})-[s:SERVE]->(destino)
                WHERE s.peso < 10
                SET s.peso = s.peso * 4
                RETURN count(s) AS total
            """, Values.parameters("id", paragId))
                .single()["total"].asInt()
        }
        if (afetadas > 0)
            log.info("[PESO] ↑ $afetadas ligações de '$paragNome' com peso aumentado — pathfinding vai desviar")
    }

    // ─────────────────────────────────────────────────────────────
    //  4. DESATIVAR ROTA SOBRECARREGADA
    //
    //  Quando não há vizinha disponível, desativa a rota
    //  que passa pela paragem sobrecarregada
    // ─────────────────────────────────────────────────────────────
    private fun desativarRotaSobrecarregada(session: Session, paragId: String, paragNome: String) {
        val rotasDesativadas = session.executeWrite { tx ->
            tx.run("""
                MATCH (r:Rota)-[:INCLUI]->(p:Paragem {id: ${'$'}id})
                WHERE r.ativa = true
                SET r.ativa = false,
                    r.motivo_desativacao = 'Sobrecarga automática em ' + p.nome
                RETURN r.nome AS nome
            """, Values.parameters("id", paragId))
                .list { r -> r["nome"].asString() }
        }
        if (rotasDesativadas.isNotEmpty())
            log.warn("[ROTA] ✗ Rotas desativadas por sobrecarga em '$paragNome': $rotasDesativadas")
    }

    // ─────────────────────────────────────────────────────────────
    //  5. COMPENSAR PARAGENS VAZIAS (cada 90 segundos)
    //
    //  Paragens com < 5% ocupação → aumenta peso de chegada
    //  O transporte "passa à frente" redirecionando para
    //  paragens com mais procura
    // ─────────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 90_000)
    fun compensarParagensVazias() {
        driver.session().use { session ->

            val vazias = session.executeRead { tx ->
                tx.run("""
                    MATCH (p:Paragem)
                    WHERE p.afluencia_atual < p.capacidade * 0.05
                      AND p.capacidade > 100
                    RETURN p.id AS id, p.nome AS nome
                    LIMIT 5
                """).list { r -> mapOf("id" to r["id"].asString(), "nome" to r["nome"].asString()) }
            }

            if (vazias.isEmpty()) return

            vazias.forEach { p ->
                session.executeWrite { tx ->
                    tx.run("""
                        MATCH ()-[s:SERVE]->(p:Paragem {id: ${'$'}id})
                        WHERE s.peso < 5
                        SET s.peso = s.peso * 2
                        RETURN count(s)
                    """, Values.parameters("id", p["id"]!!)).list()
                }
                log.info("[VAZIA] ↓ '${p["nome"]}' quase vazia — transportes redirecionados para paragens com mais procura")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  6. NORMALIZAR PESOS + REATIVAR ROTAS (cada 5 minutos)
    //
    //  Restaura pesos a 1 e reativa rotas desativadas automaticamente
    //  para que a rede volte ao normal quando a situação melhorar
    // ─────────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 300_000)
    fun normalizarRede() {
        driver.session().use { session ->

            val normalizadas = session.executeWrite { tx ->
                tx.run("""
                    MATCH ()-[s:SERVE]->()
                    WHERE s.peso > 1
                    SET s.peso = 1
                    RETURN count(s) AS total
                """).single()["total"].asInt()
            }
            if (normalizadas > 0)
                log.info("[NORMALIZAÇÃO] ✓ $normalizadas ligações restauradas ao peso normal")

            val reativadas = session.executeWrite { tx ->
                tx.run("""
                    MATCH (r:Rota)
                    WHERE r.ativa = false
                      AND r.motivo_desativacao STARTS WITH 'Sobrecarga automática'
                    SET r.ativa = true, r.motivo_desativacao = null
                    RETURN count(r) AS total
                """).single()["total"].asInt()
            }
            if (reativadas > 0)
                log.info("[NORMALIZAÇÃO] ✓ $reativadas rotas reativadas após normalização")
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  7. LIMPAR LIGAÇÕES TEMPORÁRIAS EXPIRADAS (cada 5 minutos)
    // ─────────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    fun limparLigacoesExpiradas() {
        driver.session().use { session ->
            val removidas = session.executeWrite { tx ->
                tx.run("""
                    MATCH ()-[lt:LIGACAO_TEMPORARIA]->()
                    WHERE lt.validade < datetime()
                    WITH collect(lt) AS expiradas
                    FOREACH (lt IN expiradas | DELETE lt)
                    RETURN size(expiradas) AS total
                """).single()["total"].asInt()
            }
            if (removidas > 0)
                log.info("[LIMPEZA] ✓ $removidas ligação(ões) temporária(s) expirada(s) removida(s)")
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  8. IMPACTO DE EVENTOS ATIVOS (cada 2 minutos)
    // ─────────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 120_000)
    fun simularImpactoEventoAtivo() {
        driver.session().use { session ->
            val agora = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val afetadas = session.executeWrite { tx ->
                tx.run("""
                    MATCH (e:Evento)-[:OCORRE_EM]->(p:Paragem)
                    WHERE e.inicio <= datetime(${'$'}agora) AND e.fim >= datetime(${'$'}agora)
                    SET p.afluencia_atual = toInteger(
                        CASE
                            WHEN p.afluencia_atual + (p.capacidade * 0.05) > p.capacidade
                            THEN p.capacidade
                            ELSE p.afluencia_atual + (p.capacidade * 0.05)
                        END
                    )
                    RETURN e.nome AS evento, p.nome AS paragem,
                           p.afluencia_atual AS afluencia, p.capacidade AS capacidade
                """, Values.parameters("agora", agora))
                    .list { r -> "${r["evento"].asString()} → ${r["paragem"].asString()} (${r["afluencia"].asInt()}/${r["capacidade"].asInt()})" }
            }
            if (afetadas.isNotEmpty())
                log.info("[EVENTO] Impacto ativo: $afetadas")
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  9. RELATÓRIO DE ESTADO (cada 2 minutos)
    // ─────────────────────────────────────────────────────────────
    @Scheduled(fixedDelay = 120_000, initialDelay = 15_000)
    fun relatorioEstadoRede() {
        driver.session().use { session ->

            val stats = session.executeRead { tx ->
                tx.run("""
                    MATCH (p:Paragem)
                    RETURN count(p) AS total,
                           sum(p.afluencia_atual) AS passageiros,
                           sum(p.capacidade) AS capacidade,
                           sum(CASE WHEN p.afluencia_atual > p.capacidade * 0.8 THEN 1 ELSE 0 END) AS sobrecarga,
                           sum(CASE WHEN p.afluencia_atual < p.capacidade * 0.05 THEN 1 ELSE 0 END) AS vazias
                """).single()
            }

            val ligTemp = session.executeRead { tx ->
                tx.run("MATCH ()-[lt:LIGACAO_TEMPORARIA]->() WHERE lt.validade > datetime() RETURN count(lt) AS t")
                    .single()["t"].asInt()
            }

            val rotasInativas = session.executeRead { tx ->
                tx.run("MATCH (r:Rota) WHERE r.ativa = false RETURN count(r) AS t")
                    .single()["t"].asInt()
            }

            val pass = stats["passageiros"].asInt()
            val cap  = stats["capacidade"].asInt()
            val ocup = if (cap > 0) pass * 100.0 / cap else 0.0

            log.info("""
                ┌──────────────────────────────────────────┐
                │      ESTADO DA REDE NYC (SNAPSHOT)       │
                ├──────────────────────────────────────────┤
                │ Paragens        : ${stats["total"].asInt()}
                │ Passageiros     : $pass / $cap
                │ Ocupação global : ${"%.1f".format(ocup)}%
                │ Em sobrecarga   : ${stats["sobrecarga"].asInt()} paragem(ns) >80%
                │ Quase vazias    : ${stats["vazias"].asInt()} paragem(ns) <5%
                │ Ligações temp.  : $ligTemp ativa(s)
                │ Rotas inativas  : $rotasInativas
                └──────────────────────────────────────────┘
            """.trimIndent())
        }
    }
}