package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * DemoController — Modo de demonstração com 5 paragens reais de NYC
 *
 * GET  /api/demo/carregar   → limpa BD e carrega 5 paragens simples
 * GET  /api/demo/restaurar  → limpa BD e avisa para reiniciar a app
 * POST /api/demo/sobrecarga → força sobrecarga numa paragem
 * POST /api/demo/evento     → cria evento e aplica impacto
 * GET  /api/demo/estado     → mostra estado atual das 5 paragens
 */
@RestController
@RequestMapping("/api/demo")
@CrossOrigin(origins = ["*"])
class DemoController(private val driver: Driver) {

    // ── 5 paragens reais de NYC ───────────────────────────────
    private val demoParagens = listOf(
        mapOf("id" to "DEMO_1", "nome" to "Times Square",    "cap" to 1000, "afl" to 200, "tipo" to "Subway"),
        mapOf("id" to "DEMO_2", "nome" to "Grand Central",   "cap" to 800,  "afl" to 150, "tipo" to "Subway"),
        mapOf("id" to "DEMO_3", "nome" to "Penn Station",    "cap" to 900,  "afl" to 180, "tipo" to "Subway"),
        mapOf("id" to "DEMO_4", "nome" to "Union Square",    "cap" to 600,  "afl" to 100, "tipo" to "Subway"),
        mapOf("id" to "DEMO_5", "nome" to "Columbus Circle", "cap" to 500,  "afl" to 80,  "tipo" to "Bus"),
    )

    // ─────────────────────────────────────────────────────────
    //  CARREGAR DEMO
    //  Limpa a BD e coloca só 5 paragens com ligações entre elas
    // ─────────────────────────────────────────────────────────
    @GetMapping("/carregar")
    fun carregarDemo(): Map<String, Any> {
        driver.session().use { session ->

            // 1. Limpar tudo
            session.executeWrite { tx ->
                tx.run("MATCH (n) DETACH DELETE n").list()
            }

            // 2. Criar zonas
            session.executeWrite { tx ->
                tx.run("""CREATE (:Zona {id:"Z_Manhattan", nome:"Manhattan", tipo:"Commercial"})""").list()
                tx.run("""CREATE (:Zona {id:"Z_Brooklyn",  nome:"Brooklyn",  tipo:"Residential"})""").list()
            }

            // 3. Criar as 5 paragens
            session.executeWrite { tx ->
                demoParagens.forEach { p ->
                    tx.run("""
                        CREATE (:Paragem {
                            id: ${'$'}id, nome: ${'$'}nome,
                            capacidade: ${'$'}cap, afluencia_atual: ${'$'}afl,
                            tipo: ${'$'}tipo, cidade: "New York City",
                            lat: 40.75, lon: -73.98
                        })
                    """, p).list()
                }
            }

            // 4. Ligar todas a Manhattan
            session.executeWrite { tx ->
                demoParagens.forEach { p ->
                    tx.run("""
                        MATCH (p:Paragem {id:${'$'}id}),(z:Zona {id:"Z_Manhattan"})
                        CREATE (p)-[:PERTENCE_A]->(z)
                    """, mapOf("id" to p["id"]!!)).list()
                }
            }

            // 5. Criar rota demo
            session.executeWrite { tx ->
                tx.run("""CREATE (:Rota {id:"R_DEMO", nome:"Linha Demo - Manhattan", tipo:"Subway", cor:"#EE352E", ativa:true, operador:"MTA"})""").list()
                listOf("DEMO_1","DEMO_2","DEMO_3","DEMO_4","DEMO_5").forEachIndexed { i, sid ->
                    tx.run("""
                        MATCH (r:Rota {id:"R_DEMO"}),(p:Paragem {id:${'$'}id})
                        CREATE (r)-[:INCLUI {ordem:${'$'}ordem}]->(p)
                    """, mapOf("id" to sid, "ordem" to i+1)).list()
                }
            }

            // 6. Criar ligações SERVE entre paragens consecutivas
            val ligacoes = listOf(
                "DEMO_1" to "DEMO_2",
                "DEMO_2" to "DEMO_3",
                "DEMO_3" to "DEMO_4",
                "DEMO_4" to "DEMO_5",
                "DEMO_5" to "DEMO_1",  // circular
            )
            session.executeWrite { tx ->
                ligacoes.forEach { (a, b) ->
                    tx.run("""
                        MATCH (a:Paragem {id:${'$'}a}),(b:Paragem {id:${'$'}b})
                        CREATE (a)-[:SERVE {distancia:800, tempo_min:3, peso:1}]->(b)
                    """, mapOf("a" to a, "b" to b)).list()
                }
            }

            // 7. Criar 1 evento demo
            session.executeWrite { tx ->
                tx.run("""
                    CREATE (:Evento {
                        id:"E_DEMO", nome:"Concerto Demo em Times Square",
                        tipo:"Concerto",
                        inicio:datetime("2025-06-15T20:00:00"),
                        fim:datetime("2025-06-15T23:00:00"),
                        afluencia_esperada:5000,
                        cidade:"New York City"
                    })
                """).list()
                tx.run("""
                    MATCH (e:Evento {id:"E_DEMO"}),(p:Paragem {id:"DEMO_1"})
                    CREATE (e)-[:OCORRE_EM]->(p)
                """).list()
                tx.run("""
                    MATCH (e:Evento {id:"E_DEMO"}),(z:Zona {id:"Z_Manhattan"})
                    CREATE (e)-[:AFETA_ZONA]->(z)
                """).list()
            }
        }

        return mapOf(
            "sucesso"   to true,
            "mensagem"  to "Modo demo ativo com 5 paragens!",
            "paragens"  to demoParagens.map { it["nome"] },
            "proximos"  to listOf(
                "Ver estado:       GET  /api/demo/estado",
                "Ver grafo:        http://localhost:8080/grafo.html",
                "Forçar sobrecarga:POST /api/demo/sobrecarga  body: {\"paragemId\":\"DEMO_1\"}",
                "Criar evento:     POST /api/demo/evento      body: {\"nome\":\"Jogo\",\"paragemId\":\"DEMO_2\"}",
                "Restaurar dados:  GET  /api/demo/restaurar"
            )
        )
    }

    // ─────────────────────────────────────────────────────────
    //  ESTADO ATUAL DAS 5 PARAGENS
    // ─────────────────────────────────────────────────────────
    @GetMapping("/estado")
    fun estadoDemo(): Map<String, Any> {
        driver.session().use { session ->

            val paragens = session.executeRead { tx ->
                tx.run("""
                    MATCH (p:Paragem)
                    RETURN p.id AS id, p.nome AS nome,
                           p.afluencia_atual AS afluencia,
                           p.capacidade AS capacidade,
                           round(100.0 * p.afluencia_atual / p.capacidade, 1) AS ocupacao
                    ORDER BY ocupacao DESC
                """).list { r -> mapOf(
                    "id"        to r["id"].asString(),
                    "nome"      to r["nome"].asString(),
                    "afluencia" to r["afluencia"].asInt(),
                    "capacidade" to r["capacidade"].asInt(),
                    "ocupacao"  to "${r["ocupacao"].asDouble()}%",
                    "estado"    to when {
                        r["ocupacao"].asDouble() >= 80 -> "🔴 SOBRECARGA"
                        r["ocupacao"].asDouble() >= 60 -> "🟠 AVISO"
                        r["ocupacao"].asDouble() <= 10 -> "⚪ VAZIA"
                        else                           -> "🟢 NORMAL"
                    }
                )}
            }

            val ligacoesTemp = session.executeRead { tx ->
                tx.run("""
                    MATCH (a:Paragem)-[lt:LIGACAO_TEMPORARIA]->(b:Paragem)
                    WHERE lt.validade > datetime()
                    RETURN a.nome AS origem, b.nome AS destino, lt.motivo AS motivo
                """).list { r -> "${r["origem"].asString()} → ${r["destino"].asString()}" }
            }

            val rotas = session.executeRead { tx ->
                tx.run("""
                    MATCH (r:Rota)
                    RETURN r.nome AS nome, r.ativa AS ativa
                """).list { r -> mapOf("nome" to r["nome"].asString(), "ativa" to r["ativa"].asBoolean()) }
            }

            return mapOf(
                "paragens"          to paragens,
                "ligacoesTemporarias" to ligacoesTemp,
                "rotas"             to rotas,
                "dica"              to "O simulador verifica sobrecargas a cada 60 segundos automaticamente"
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    //  FORÇAR SOBRECARGA MANUAL
    //  Coloca uma paragem a 95% para disparar a adaptação
    // ─────────────────────────────────────────────────────────
    @PostMapping("/sobrecarga")
    fun forcarSobrecarga(@RequestBody body: Map<String, String>): Map<String, Any> {
        val paragId = body["paragemId"] ?: "DEMO_1"
        val pct     = body["percentagem"]?.toIntOrNull() ?: 95

        driver.session().use { session ->
            val resultado = session.executeWrite { tx ->
                tx.run("""
                    MATCH (p:Paragem {id:${'$'}id})
                    SET p.afluencia_atual = toInteger(p.capacidade * ${'$'}pct / 100.0)
                    RETURN p.nome AS nome, p.afluencia_atual AS afl, p.capacidade AS cap
                """, Values.parameters("id", paragId, "pct", pct))
                    .list { r -> mapOf(
                        "nome" to r["nome"].asString(),
                        "afl"  to r["afl"].asInt(),
                        "cap"  to r["cap"].asInt()
                    )}
            }

            val info = resultado.firstOrNull() ?: return mapOf("erro" to "Paragem não encontrada")

            return mapOf(
                "sucesso"   to true,
                "paragem"   to info["nome"]!!,
                "afluencia" to info["afl"]!!,
                "capacidade" to  info["cap"]!!,
                "ocupacao"  to "$pct%",
                "mensagem"  to "Sobrecarga forçada! Aguarda até 60 segundos e vê os logs — o simulador vai criar uma ligação temporária automaticamente.",
                "observar"  to "Nos logs vais ver: [ADAPTAÇÃO] ✓ Ligação temporária criada"
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    //  CRIAR EVENTO E APLICAR IMPACTO IMEDIATO
    // ─────────────────────────────────────────────────────────
    @PostMapping("/evento")
    fun criarEvento(@RequestBody body: Map<String, String>): Map<String, Any> {
        val nome     = body["nome"]      ?: "Evento Demo"
        val paragId  = body["paragemId"] ?: "DEMO_1"
        val afl      = body["afluencia"]?.toIntOrNull() ?: 3000

        driver.session().use { session ->

            // Criar evento ativo agora
            session.executeWrite { tx ->
                tx.run("""
                    CREATE (:Evento {
                        id:   "E_" + toString(timestamp()),
                        nome: ${'$'}nome,
                        tipo: "Demo",
                        inicio: datetime() - duration("PT1M"),
                        fim:    datetime() + duration("PT3H"),
                        afluencia_esperada: ${'$'}afl,
                        cidade: "New York City"
                    })
                """, Values.parameters("nome", nome, "afl", afl)).list()

                tx.run("""
                    MATCH (e:Evento {nome:${'$'}nome}),(p:Paragem {id:${'$'}pid})
                    CREATE (e)-[:OCORRE_EM]->(p)
                """, Values.parameters("nome", nome, "pid", paragId)).list()
            }

            // Aplicar impacto imediato (+40% na paragem)
            val resultado = session.executeWrite { tx ->
                tx.run("""
                    MATCH (p:Paragem {id:${'$'}id})
                    SET p.afluencia_atual = toInteger(
                        CASE WHEN p.afluencia_atual + p.capacidade * 0.40 > p.capacidade
                        THEN p.capacidade
                        ELSE p.afluencia_atual + p.capacidade * 0.40 END
                    )
                    RETURN p.nome AS nome,
                           p.afluencia_atual AS afl,
                           p.capacidade AS cap,
                           round(100.0 * p.afluencia_atual / p.capacidade, 1) AS ocup
                """, Values.parameters("id", paragId))
                    .list { r -> mapOf(
                        "nome" to r["nome"].asString(),
                        "afl"  to r["afl"].asInt(),
                        "cap"  to r["cap"].asInt(),
                        "ocup" to r["ocup"].asDouble()
                    )}
            }

            val info = resultado.firstOrNull() ?: return mapOf("erro" to "Paragem não encontrada")

            return mapOf(
                "sucesso"   to true,
                "evento"    to nome,
                "paragem"   to info["nome"]!!,
                "afluencia" to info["afl"]!!,
                "ocupacao"  to "${info["ocup"]}%",
                "mensagem"  to "Evento criado! Afluência aumentou +40%. Aguarda 60s para ver a adaptação automática.",
                "observar"  to listOf(
                    "GET /api/demo/estado — ver estado atualizado",
                    "Logs: [ADAPTAÇÃO] ✓ Ligação temporária criada",
                    "http://localhost:8080/dashboard.html"
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    //  RESTAURAR DADOS COMPLETOS
    // ─────────────────────────────────────────────────────────
    @GetMapping("/restaurar")
    fun restaurar(): Map<String, Any> {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run("MATCH (n) DETACH DELETE n").list()
            }
        }
        return mapOf(
            "sucesso"  to true,
            "mensagem" to "Base de dados limpa! Reinicia a aplicação para carregar os dados reais de NYC (1178 paragens).",
            "passo"    to "No IntelliJ: Stop → Run"
        )
    }
}