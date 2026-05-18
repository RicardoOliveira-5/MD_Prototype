package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.neo4j.driver.Driver
import org.springframework.web.bind.annotation.*

// ── DTOs para visualização do grafo ──────────────────────────

data class GrafoNo(
    val id: String,
    val label: String,          // tipo: Paragem, Zona, Rota, Evento, Veiculo
    val nome: String,
    val propriedades: Map<String, Any?>
)

data class GrafoRelacao(
    val id: String,
    val origem: String,
    val destino: String,
    val tipo: String,           // SERVE, PERTENCE_A, INCLUI, etc.
    val propriedades: Map<String, Any?>
)

data class GrafoCompleto(
    val nos: List<GrafoNo>,
    val relacoes: List<GrafoRelacao>
)

data class MetaNo(
    val label: String,
    val count: Int
)

data class MetaRelacao(
    val origem: String,
    val tipo: String,
    val destino: String,
    val count: Int
)

data class MetaGrafo(
    val nos: List<MetaNo>,
    val relacoes: List<MetaRelacao>
)

data class CaminhoVisual(
    val paragens: List<String>,
    val nos: List<String>,      // IDs dos nós no caminho
    val relacoes: List<String>, // IDs das relações no caminho
    val saltos: Int,
    val tempoTotalMin: Int
)

// ── Controller ───────────────────────────────────────────────

@RestController
@RequestMapping("/api/grafo")
@CrossOrigin(origins = ["*"])
class GrafoController(private val driver: Driver) {

    // ── 1. Grafo completo (nós + relações) ───────────────────
    @GetMapping
    fun getGrafoCompleto(): GrafoCompleto {
        driver.session().use { session ->

            val nos = session.executeRead { tx ->
                tx.run("""
                    MATCH (n)
                    RETURN elementId(n) AS id,
                           labels(n)[0] AS label,
                           properties(n) AS props
                    ORDER BY labels(n)[0], n.nome
                """).list { r ->
                    val props = r["props"].asMap().toMutableMap()
                    val nome = when {
                        props["nome"] != null -> props["nome"].toString()
                        props["id"]  != null -> props["id"].toString()
                        else -> r["id"].asString()
                    }
                    GrafoNo(
                        id            = r["id"].asString(),
                        label         = r["label"].asString(),
                        nome          = nome,
                        propriedades  = props
                    )
                }
            }

            val relacoes = session.executeRead { tx ->
                tx.run("""
                    MATCH (a)-[r]->(b)
                    RETURN elementId(r)  AS id,
                           elementId(a)  AS origem,
                           elementId(b)  AS destino,
                           type(r)       AS tipo,
                           properties(r) AS props
                """).list { r ->
                    GrafoRelacao(
                        id           = r["id"].asString(),
                        origem       = r["origem"].asString(),
                        destino      = r["destino"].asString(),
                        tipo         = r["tipo"].asString(),
                        propriedades = r["props"].asMap()
                    )
                }
            }

            return GrafoCompleto(nos, relacoes)
        }
    }

    // ── 2. Meta-grafo / Schema emergente (T05) ───────────────
    @GetMapping("/meta")
    fun getMetaGrafo(): MetaGrafo {
        driver.session().use { session ->

            val nos = session.executeRead { tx ->
                tx.run("""
                    MATCH (n)
                    RETURN labels(n)[0] AS label, count(n) AS total
                    ORDER BY total DESC
                """).list { r ->
                    MetaNo(
                        label = r["label"].asString(),
                        count = r["total"].asInt()
                    )
                }
            }

            val relacoes = session.executeRead { tx ->
                tx.run("""
                    MATCH (a)-[r]->(b)
                    RETURN labels(a)[0] AS origem,
                           type(r)      AS tipo,
                           labels(b)[0] AS destino,
                           count(r)     AS total
                    ORDER BY total DESC
                """).list { r ->
                    MetaRelacao(
                        origem  = r["origem"].asString(),
                        tipo    = r["tipo"].asString(),
                        destino = r["destino"].asString(),
                        count   = r["total"].asInt()
                    )
                }
            }

            return MetaGrafo(nos, relacoes)
        }
    }

    // ── 3. Pathfinding visual entre duas paragens (T04) ──────
    @GetMapping("/caminho")
    fun getCaminhoVisual(
        @RequestParam origem: String,
        @RequestParam destino: String
    ): CaminhoVisual {
        driver.session().use { session ->
            val result = session.executeRead { tx ->
                tx.run("""
                    MATCH path = shortestPath(
                        (a:Paragem {id: ${'$'}origem})-[:SERVE|LIGACAO_TEMPORARIA*]-(b:Paragem {id: ${'$'}destino})
                    )
                    RETURN [n IN nodes(path) | n.nome]        AS nomes,
                           [n IN nodes(path) | elementId(n)]  AS nosIds,
                           [r IN relationships(path) | elementId(r)] AS relIds,
                           length(path) AS saltos,
                           reduce(t = 0, r IN relationships(path) | t + coalesce(r.tempo_min, 0)) AS tempo
                """, mapOf("origem" to origem, "destino" to destino))
                    .list { r -> r }
            }

            if (result.isEmpty()) throw IllegalStateException("Sem caminho entre $origem e $destino")

            val row = result.first()
            return CaminhoVisual(
                paragens     = row["nomes"].asList { it.asString() },
                nos          = row["nosIds"].asList { it.asString() },
                relacoes     = row["relIds"].asList { it.asString() },
                saltos       = row["saltos"].asInt(),
                tempoTotalMin = row["tempo"].asInt()
            )
        }
    }

    // ── 4. Adicionar paragem a uma rota ──────────────────────
    @PostMapping("/rotas/{rotaId}/paragens/{paragId}")
    fun adicionarParagemARota(
        @PathVariable rotaId: String,
        @PathVariable paragId: String
    ): Map<String, Any> {
        driver.session().use { session ->
            val maxOrdem = session.executeRead { tx ->
                tx.run("""
                    MATCH (r:Rota {id: ${'$'}rotaId})-[inc:INCLUI]->(:Paragem)
                    RETURN coalesce(max(inc.ordem), 0) AS maxOrdem
                """, mapOf("rotaId" to rotaId))
                    .single()["maxOrdem"].asInt()
            }

            session.executeWrite { tx ->
                tx.run("""
                    MATCH (r:Rota {id: ${'$'}rotaId}), (p:Paragem {id: ${'$'}paragId})
                    MERGE (r)-[inc:INCLUI {ordem: ${'$'}ordem}]->(p)
                    RETURN r.nome AS rota, p.nome AS paragem
                """, mapOf("rotaId" to rotaId, "paragId" to paragId, "ordem" to maxOrdem + 1))
                    .list()
            }
            return mapOf("sucesso" to true, "mensagem" to "Paragem $paragId adicionada à rota $rotaId")
        }
    }

    // ── 5. Remover paragem de uma rota ───────────────────────
    @DeleteMapping("/rotas/{rotaId}/paragens/{paragId}")
    fun removerParagemDeRota(
        @PathVariable rotaId: String,
        @PathVariable paragId: String
    ): Map<String, Any> {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run("""
                    MATCH (r:Rota {id: ${'$'}rotaId})-[inc:INCLUI]->(p:Paragem {id: ${'$'}paragId})
                    DELETE inc
                    RETURN count(inc) AS removidas
                """, mapOf("rotaId" to rotaId, "paragId" to paragId))
                    .list()
            }
            return mapOf("sucesso" to true, "mensagem" to "Paragem $paragId removida da rota $rotaId")
        }
    }
}