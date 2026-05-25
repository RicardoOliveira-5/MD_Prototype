package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.springframework.stereotype.Service

// ── DTOs da simulação ─────────────────────────────────────────

data class SimNodeDTO(
    val id: String,
    val nome: String,
    val capacidade: Int,
    val afluenciaAtual: Int,
    val zona: String?
)

data class SimEdgeDTO(
    val origem: String,
    val destino: String,
    val tempoMin: Int,
    val tipo: String   // SERVE | TRANSFERENCIA | LIGACAO_TEMPORARIA
)

data class SimGrafoDTO(
    val nodes: List<SimNodeDTO>,
    val edges: List<SimEdgeDTO>
)

data class NoTemporarioRequest(
    val nome: String,
    val ligadaA: String,
    val tempoMin: Int = 8,
    val motivo: String = "Evento"
)

data class LigacaoSimRequest(
    val origemId: String,
    val destinoId: String,
    val tempoMin: Int = 6,
    val motivo: String = "Ligação manual"
)

// ── IDs das estações-chave do MTA (real GTFS) ─────────────────
// Muda para dataset-mode=2 ou 3 no application.yml para usar estes IDs
// Com dataset-mode=1 (demo), usa os IDs DEMO_1..DEMO_5

@Service
class SimulacaoService(private val driver: Driver) {

    private val KEY_IDS = listOf(
        "127", "631", "A28", "635", "A24", "D24", "A38",
        "A36", "126", "H19", "H01", "A12", "116", "74", "701", "G22",
        // fallback: demo stations (dataset-mode=1)
        "DEMO_1", "DEMO_2", "DEMO_3", "DEMO_4", "DEMO_5"
    )

    // ── 1. Retorna grafo para visualização ────────────────────

    fun getGrafoVisual(): SimGrafoDTO {
        driver.session().use { session ->

            val nodes = session.executeRead { tx ->
                tx.run("""
                    MATCH (p:Paragem)
                    WHERE p.id IN ${'$'}ids
                    OPTIONAL MATCH (p)-[:PERTENCE_A]->(z:Zona)
                    RETURN p.id AS id, p.nome AS nome,
                           p.capacidade AS cap, p.afluencia_atual AS afl,
                           z.nome AS zona
                    ORDER BY p.id
                """, Values.parameters("ids", KEY_IDS)).list { r ->
                    SimNodeDTO(
                        id            = r["id"].asString(),
                        nome          = r["nome"].asString(),
                        capacidade    = r["cap"].asInt(1000),
                        afluenciaAtual = r["afl"].asInt(0),
                        zona          = if (r["zona"].isNull) null else r["zona"].asString()
                    )
                }
            }

            // Se não encontrou nada, devolve erro amigável
            if (nodes.isEmpty()) {
                return SimGrafoDTO(emptyList(), emptyList())
            }

            val foundIds = nodes.map { it.id }

            val edges = session.executeRead { tx ->
                tx.run("""
                    MATCH (a:Paragem)-[r:SERVE]->(b:Paragem)
                    WHERE a.id IN ${'$'}ids AND b.id IN ${'$'}ids
                    RETURN a.id AS orig, b.id AS dest,
                           coalesce(r.tempo_min, 5) AS t
                """, Values.parameters("ids", foundIds)).list { r ->
                    SimEdgeDTO(r["orig"].asString(), r["dest"].asString(), r["t"].asInt(), "SERVE")
                }
            }

            val transfers = session.executeRead { tx ->
                tx.run("""
                    MATCH (a:Paragem)-[r:TRANSFERENCIA]->(b:Paragem)
                    WHERE a.id IN ${'$'}ids AND b.id IN ${'$'}ids
                    RETURN a.id AS orig, b.id AS dest,
                           coalesce(r.tempo_min, 3) AS t
                """, Values.parameters("ids", foundIds)).list { r ->
                    SimEdgeDTO(r["orig"].asString(), r["dest"].asString(), r["t"].asInt(), "TRANSFERENCIA")
                }
            }

            val tempLinks = session.executeRead { tx ->
                tx.run("""
                    MATCH (a:Paragem)-[lt:LIGACAO_TEMPORARIA]->(b:Paragem)
                    WHERE lt.validade > datetime()
                    RETURN a.id AS orig, b.id AS dest,
                           lt.tempo_min AS t
                """).list { r ->
                    SimEdgeDTO(r["orig"].asString(), r["dest"].asString(), r["t"].asInt(), "LIGACAO_TEMPORARIA")
                }
            }

            return SimGrafoDTO(nodes, edges + transfers + tempLinks)
        }
    }

    // ── 2. Aplicar evento predefinido ─────────────────────────

    fun aplicarEvento(tipo: String): Map<String, Any> {
        return driver.session().use { session ->
            when (tipo) {

                "concert" -> {
                    session.executeWrite { tx ->
                        // Penn Station (~92% cap) + Times Square (~77%)
                        tx.run("""
                            MATCH (p:Paragem)
                            WHERE p.id IN ['A28','DEMO_3']
                            SET p.afluencia_atual = toInteger(p.capacidade * 0.92)
                        """)
                        tx.run("""
                            MATCH (p:Paragem)
                            WHERE p.id IN ['127','DEMO_1']
                            SET p.afluencia_atual = toInteger(p.capacidade * 0.77)
                        """)
                    }
                    mapOf(
                        "evento"   to "Concerto MSG",
                        "afetadas" to listOf("Penn Station → 92%", "Times Square → 77%"),
                        "cypher"   to "SET p.afluencia_atual = toInteger(p.capacidade * 0.92)"
                    )
                }

                "nye" -> {
                    session.executeWrite { tx ->
                        // Times Square a 100%
                        tx.run("""
                            MATCH (p:Paragem) WHERE p.id IN ['127','DEMO_1']
                            SET p.afluencia_atual = p.capacidade
                        """)
                        // Criar LIGACAO_TEMPORARIA Times Sq → 14th St (6 min)
                        tx.run("""
                            MATCH (a:Paragem), (b:Paragem)
                            WHERE a.id IN ['127','DEMO_1'] AND b.id IN ['A24','DEMO_4']
                            MERGE (a)-[lt:LIGACAO_TEMPORARIA {motivo:'NYE Times Square - Bypass'}]->(b)
                            ON CREATE SET lt.tempo_min = 6,
                                         lt.criada_em = datetime(),
                                         lt.validade  = datetime() + duration({hours: 4})
                        """)
                    }
                    mapOf(
                        "evento"           to "NYE Times Square",
                        "ligacaoTemporaria" to "Times Square → 14th St (6 min, 4h validade)",
                        "cypher"           to "CREATE (a)-[:LIGACAO_TEMPORARIA {tempo_min:6, validade:datetime()+duration({hours:4})}]->(b)"
                    )
                }

                "avaria_l" -> {
                    session.executeWrite { tx ->
                        tx.run("""
                            MATCH (r:Rota) WHERE r.id = 'R_L' OR r.nome CONTAINS 'Linha L'
                            SET r.ativa = false,
                                r.motivo_desativacao = 'Avaria técnica - Canarsie Tunnel'
                        """)
                        // Penaliza o peso da aresta Union Sq ↔ 14th St
                        tx.run("""
                            MATCH (a:Paragem)-[s:SERVE]->(b:Paragem)
                            WHERE (a.id IN ['635','DEMO_4'] AND b.id IN ['A24','DEMO_4'])
                               OR (a.id IN ['A24','DEMO_4'] AND b.id IN ['635','DEMO_4'])
                            SET s.peso = 999
                        """)
                    }
                    mapOf(
                        "evento"          to "Avaria Linha L",
                        "rotaDesativada"  to "R_L (Canarsie Tunnel)",
                        "cypher"          to "SET r.ativa = false, r.motivo_desativacao = 'Avaria'"
                    )
                }

                "yankees" -> {
                    session.executeWrite { tx ->
                        // Adicionar estação temporária Yankee Stadium
                        tx.run("""
                            MERGE (novo:Paragem {id: 'TMP_YANKEES'})
                            ON CREATE SET novo.nome          = '161st Yankee Stadium',
                                          novo.capacidade    = 5000,
                                          novo.afluencia_atual = 4700,
                                          novo.lat = 40.8275, novo.lon = -73.9259,
                                          novo.cidade = 'New York City', novo.temp = true
                        """)
                        // Ligar a 125th St (A12) e Columbus Circle (116)
                        tx.run("""
                            MATCH (est:Paragem {id:'TMP_YANKEES'}), (h:Paragem)
                            WHERE h.id IN ['A12','116','DEMO_1']
                            MERGE (est)-[:LIGACAO_TEMPORARIA {
                                motivo:'Yankees Game',
                                tempo_min: 12,
                                validade: datetime() + duration({hours:5}),
                                criada_em: datetime()
                            }]->(h)
                        """)
                    }
                    mapOf(
                        "evento"    to "Jogo Yankees",
                        "novoNo"    to "161st Yankee Stadium (TMP_YANKEES)",
                        "cypher"    to "CREATE (:Paragem {nome:'161st Stadium'})-[:LIGACAO_TEMPORARIA {tempo_min:12}]->(125th St)"
                    )
                }

                "rush" -> {
                    session.executeWrite { tx ->
                        tx.run("""
                            MATCH (p:Paragem) WHERE p.id IN ${'$'}ids
                            SET p.afluencia_atual = toInteger(p.afluencia_atual * 1.4)
                        """, Values.parameters("ids", KEY_IDS))
                    }
                    mapOf(
                        "evento" to "Rush Hour",
                        "fator"  to "+40% em toda a rede",
                        "cypher" to "SET p.afluencia_atual = toInteger(p.afluencia_atual * 1.4)"
                    )
                }

                "reset" -> resetarAfluencias()

                else -> mapOf("erro" to "Evento desconhecido: $tipo")
            }
        }
    }

    // ── 3. Reset ao estado inicial ────────────────────────────

    fun resetarAfluencias(): Map<String, Any> {
        driver.session().use { session ->
            session.executeWrite { tx ->
                // Repor afluência a ~30% da capacidade
                tx.run("""
                    MATCH (p:Paragem) WHERE p.id IN ${'$'}ids
                    SET p.afluencia_atual = toInteger(p.capacidade * 0.30)
                """, Values.parameters("ids", KEY_IDS))

                // Remover LIGACAO_TEMPORARIA criadas por eventos
                tx.run("""
                    MATCH ()-[lt:LIGACAO_TEMPORARIA]->()
                    WHERE lt.motivo CONTAINS 'NYE'
                       OR lt.motivo CONTAINS 'Bypass'
                       OR lt.motivo CONTAINS 'Yankees'
                       OR lt.motivo CONTAINS 'manual'
                    DELETE lt
                """)

                // Remover nós temporários
                tx.run("""
                    MATCH (p:Paragem) WHERE p.temp = true
                    DETACH DELETE p
                """)

                // Reativar rotas
                tx.run("MATCH (r:Rota) SET r.ativa = true, r.motivo_desativacao = null")

                // Restaurar pesos de arestas
                tx.run("MATCH ()-[s:SERVE]->() SET s.peso = 1")
            }
        }
        return mapOf("status" to "OK", "mensagem" to "Rede resetada ao estado inicial")
    }

    // ── 4. Adicionar nó temporário manualmente ────────────────

    fun adicionarNoTemp(req: NoTemporarioRequest): Map<String, Any> {
        driver.session().use { session ->
            val novoId = "TMP_${System.currentTimeMillis()}"
            session.executeWrite { tx ->
                tx.run("""
                    MATCH (origem:Paragem {id: ${'$'}ligadaA})
                    CREATE (novo:Paragem {
                        id: ${'$'}novoId, nome: ${'$'}nome,
                        capacidade: 2000, afluencia_atual: 200,
                        lat: origem.lat + 0.004, lon: origem.lon + 0.004,
                        cidade: 'New York City', temp: true
                    })
                    CREATE (novo)-[:LIGACAO_TEMPORARIA {
                        motivo:    ${'$'}motivo,
                        tempo_min: ${'$'}tempoMin,
                        validade:  datetime() + duration({hours: 8}),
                        criada_em: datetime()
                    }]->(origem)
                    RETURN novo.id AS id
                """, Values.parameters(
                    "novoId",   novoId,
                    "nome",     req.nome,
                    "ligadaA",  req.ligadaA,
                    "tempoMin", req.tempoMin,
                    "motivo",   req.motivo
                ))
            }
            return mapOf(
                "status"  to "OK",
                "novoId"  to novoId,
                "nome"    to req.nome,
                "cypher"  to "CREATE (:Paragem {nome:'${req.nome}'})-[:LIGACAO_TEMPORARIA {tempo_min:${req.tempoMin}}]->(Paragem {id:'${req.ligadaA}'})"
            )
        }
    }

    // ── 5. Adicionar ligação temporária manual ────────────────

    fun adicionarLigacao(req: LigacaoSimRequest): Map<String, Any> {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run("""
                    MATCH (a:Paragem {id: ${'$'}from}), (b:Paragem {id: ${'$'}to})
                    CREATE (a)-[:LIGACAO_TEMPORARIA {
                        motivo:    ${'$'}motivo,
                        tempo_min: ${'$'}t,
                        validade:  datetime() + duration({hours: 8}),
                        criada_em: datetime()
                    }]->(b)
                """, Values.parameters(
                    "from",   req.origemId,
                    "to",     req.destinoId,
                    "t",      req.tempoMin,
                    "motivo", req.motivo
                ))
            }
            return mapOf(
                "status"  to "OK",
                "origem"  to req.origemId,
                "destino" to req.destinoId,
                "tempoMin" to req.tempoMin
            )
        }
    }
}
