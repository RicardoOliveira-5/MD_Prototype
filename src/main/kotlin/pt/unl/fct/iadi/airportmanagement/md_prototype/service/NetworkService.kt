package pt.unl.fct.iadi.airportmanagement.md_prototype.service

import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.CaminhoResponse
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.EstadoRedeResponse
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.EventoImpactoResponse
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.LigacaoTemporariaRequest
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.LigacaoTemporariaResponse
import pt.unl.fct.iadi.airportmanagement.md_prototype.domain.Paragem
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.ParagemResumo
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.RotaStatusRequest
import pt.unl.fct.iadi.airportmanagement.md_prototype.repo.EventoRepository
import pt.unl.fct.iadi.airportmanagement.md_prototype.repo.ParagemRepository
import pt.unl.fct.iadi.airportmanagement.md_prototype.repo.RotaRepository
import pt.unl.fct.iadi.airportmanagement.md_prototype.repo.ZonaRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class NetworkService(
    private val paragemRepo: ParagemRepository,
    private val rotaRepo: RotaRepository,
    private val eventoRepo: EventoRepository,
    private val zonaRepo: ZonaRepository,
    private val driver: Driver
) {

    // ── Estado geral ──────────────────────────────────────────

    fun getEstadoRede(): EstadoRedeResponse {
        val paragens        = paragemRepo.findAll().toList()
        val rotas           = rotaRepo.findAll().toList()
        val sobrecarregadas = paragemRepo.findSobrecarregadas(0.6)

        val totalPassageiros = paragens.sumOf { it.afluenciaAtual }
        val capacidadeTotal  = paragens.sumOf { it.capacidade }
        val ocupacaoGlobal   = if (capacidadeTotal > 0)
            (totalPassageiros * 100.0 / capacidadeTotal) else 0.0

        return EstadoRedeResponse(
            totalParagens = paragens.size,
            totalRotas = rotas.size,
            rotasAtivas = rotas.count { it.ativa },
            passageirosAtual = totalPassageiros,
            capacidadeTotal = capacidadeTotal,
            ocupacaoGlobalPercent = ocupacaoGlobal.round2(),
            paragensEmSobrecarga = sobrecarregadas.map { it.toResumo() }
        )
    }

    // ── Paragens ──────────────────────────────────────────────

    fun getAllParagens(): List<ParagemResumo> =
        paragemRepo.findAll().map { it.toResumo() }

    fun getParagensSobrecarregadas(threshold: Double = 0.6): List<ParagemResumo> =
        paragemRepo.findSobrecarregadas(threshold).map { it.toResumo() }

    fun getParagensPorZona(zonaId: String): List<ParagemResumo> =
        paragemRepo.findByZona(zonaId).map { it.toResumo() }

    @Transactional
    fun updateAfluencia(id: String, novaAfluencia: Int): ParagemResumo {
        val paragem = paragemRepo.updateAfluencia(id, novaAfluencia)
            ?: throw IllegalArgumentException("Paragem $id não encontrada")
        return paragem.toResumo()
    }

    // ── Pathfinding ───────────────────────────────────────────

    fun getCaminho(origemId: String, destinoId: String): CaminhoResponse {
        val resultado = paragemRepo.shortestPath(origemId, destinoId)
        if (resultado.isEmpty()) throw IllegalStateException("Sem caminho entre $origemId e $destinoId")

        val row = resultado.first()
        @Suppress("UNCHECKED_CAST")
        return CaminhoResponse(
            paragens = row["paragens"] as List<String>,
            saltos = (row["saltos"] as Long).toInt(),
            tempoTotalMin = (row["tempoTotal"] as Long).toInt()
        )
    }

    // ── Rotas ─────────────────────────────────────────────────

    fun getAllRotas() = rotaRepo.findAll().toList()

    @Transactional
    fun setRotaStatus(id: String, req: RotaStatusRequest) =
        rotaRepo.setAtiva(id, req.ativa, if (req.ativa) null else req.motivo)
            ?: throw IllegalArgumentException("Rota $id não encontrada")

    // ── Eventos ───────────────────────────────────────────────

    fun getEventosAtivos(): List<EventoImpactoResponse> {
        val agora = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return eventoRepo.findAtivos(agora).map { evento ->
            EventoImpactoResponse(
                evento = evento.nome,
                tipo = evento.tipo,
                afluenciaEsperada = evento.afluenciaEsperada,
                paragensAfetadas = listOfNotNull(evento.paragem?.toResumo()),
                zonaAfetada = evento.zona?.nome
            )
        }
    }

    @Transactional
    fun simularImpactoEvento(eventoId: String): Map<String, Any> {
        driver.session().use { session ->
            val result = session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (e:Evento { id: ${'$'}eventoId })-[:OCORRE_EM]->(p:Paragem)
                    SET p.afluencia_atual = p.afluencia_atual + toInteger(p.capacidade * 0.40)
                    RETURN p.nome AS paragem, p.afluencia_atual AS novaAfluencia, p.capacidade AS capacidade
                    """,
                    Values.parameters("eventoId", eventoId)
                )
            }
            return if (result.hasNext()) {
                val row = result.next()
                val nova = row["novaAfluencia"].asInt()
                val cap  = row["capacidade"].asInt()
                mapOf(
                    "paragem"       to row["paragem"].asString(),
                    "novaAfluencia" to nova,
                    "capacidade"    to cap,
                    "ocupacao"      to "${(nova * 100.0 / cap).round2()}%"
                )
            } else mapOf("erro" to "Evento $eventoId não encontrado ou sem paragem associada")
        }
    }

    // ── Ligações temporárias ──────────────────────────────────

    @Transactional
    fun criarLigacaoTemporaria(req: LigacaoTemporariaRequest): LigacaoTemporariaResponse {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (a:Paragem { id: ${'$'}origemId }), (b:Paragem { id: ${'$'}destinoId })
                    CREATE (a)-[:LIGACAO_TEMPORARIA {
                        motivo:    ${'$'}motivo,
                        criada_em: datetime(),
                        validade:  datetime(${'$'}validade),
                        tempo_min: ${'$'}tempMin,
                        distancia: ${'$'}distancia
                    }]->(b)
                    """,
                    Values.parameters(
                        "origemId",  req.origemId,
                        "destinoId", req.destinoId,
                        "motivo",    req.motivo,
                        "validade",  req.validadeAte.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        "tempMin",   req.tempMin,
                        "distancia", req.distancia
                    )
                )
            }
        }

        val origem  = paragemRepo.findById(req.origemId).orElseThrow()
        val destino = paragemRepo.findById(req.destinoId).orElseThrow()

        return LigacaoTemporariaResponse(
            origem = origem.nome,
            destino = destino.nome,
            motivo = req.motivo,
            validadeAte = req.validadeAte.toString(),
            tempMin = req.tempMin
        )
    }

    @Transactional
    fun removerLigacoesExpiradas(): Int {
        driver.session().use { session ->
            return session.executeWrite { tx ->
                val result = tx.run("""
                    MATCH ()-[lt:LIGACAO_TEMPORARIA]->()
                    WHERE lt.validade < datetime()
                    WITH collect(lt) AS ligacoes
                    FOREACH (lt IN ligacoes | DELETE lt)
                    RETURN size(ligacoes) AS total
                """)
                if (result.hasNext()) result.next()["total"].asInt() else 0
            }
        }
    }

    fun getLigacoesTemporarias(): List<LigacaoTemporariaResponse> {
        driver.session().use { session ->
            return session.executeRead { tx ->
                tx.run("""
                    MATCH (a:Paragem)-[lt:LIGACAO_TEMPORARIA]->(b:Paragem)
                    RETURN a.nome AS origem, b.nome AS destino,
                           lt.motivo AS motivo, toString(lt.validade) AS validade,
                           lt.tempo_min AS tempMin
                    ORDER BY lt.validade
                """).list { row ->
                    LigacaoTemporariaResponse(
                        origem = row["origem"].asString(),
                        destino = row["destino"].asString(),
                        motivo = row["motivo"].asString(),
                        validadeAte = row["validade"].asString(),
                        tempMin = row["tempMin"].asInt()
                    )
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun Paragem.toResumo() = ParagemResumo(
        id = id,
        nome = nome,
        zona = zona?.nome,
        afluenciaAtual = afluenciaAtual,
        capacidade = capacidade,
        ocupacaoPercent = if (capacidade > 0)
            (afluenciaAtual * 100.0 / capacidade).round2() else 0.0
    )

    private fun Double.round2() = Math.round(this * 100.0) / 100.0

    // Simular sobrecarga manual numa paragem (para testes e demo)
    fun simularSobrecargaManual(id: String, percentagem: Int): Map<String, Any> {
        driver.session().use { session ->
            val resultado = session.executeWrite { tx ->
                tx.run("""
                    MATCH (p:Paragem {id: ${'$'}id})
                    SET p.afluencia_atual = toInteger(p.capacidade * ${'$'}pct / 100.0)
                    RETURN p.nome AS nome, p.afluencia_atual AS afluencia,
                           p.capacidade AS capacidade,
                           round(100.0 * p.afluencia_atual / p.capacidade, 1) AS ocupacao
                """, mapOf("id" to id, "pct" to percentagem))
                    .list { r -> mapOf(
                        "paragem"    to r["nome"].asString(),
                        "afluencia"  to r["afluencia"].asInt(),
                        "capacidade" to r["capacidade"].asInt(),
                        "ocupacao"   to "${r["ocupacao"].asDouble()}%",
                        "mensagem"   to "Afluência definida a $percentagem%. O simulador vai reagir em até 60 segundos."
                    )}
            }
            return resultado.firstOrNull() ?: mapOf("erro" to "Paragem $id não encontrada")
        }
    }

}