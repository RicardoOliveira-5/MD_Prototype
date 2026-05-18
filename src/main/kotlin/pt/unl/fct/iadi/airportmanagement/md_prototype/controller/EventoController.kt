package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pt.unl.fct.iadi.airportmanagement.md_prototype.service.NetworkService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.use

@RestController
@RequestMapping("/api/eventos")
class EventoController(
    private val service: NetworkService,
    private val driver: Driver
) {

    @GetMapping("/ativos")
    fun getAtivos() = ResponseEntity.ok(service.getEventosAtivos())

    @PostMapping("/{id}/simular-impacto")
    fun simularImpacto(@PathVariable id: String) =
        ResponseEntity.ok(service.simularImpactoEvento(id))

    /**
     * POST /api/eventos/criar
     * Cria um evento manualmente e aplica imediatamente o impacto
     * na paragem indicada. Perfeito para demonstração ao professor.
     *
     * Exemplo:
     * {
     *   "nome": "Concerto em Times Square",
     *   "tipo": "Concerto",
     *   "paragemId": "127",
     *   "afluenciaEsperada": 50000,
     *   "duracaoHoras": 3
     * }
     */
    @PostMapping("/criar")
    fun criarEvento(@RequestBody req: CriarEventoRequest): ResponseEntity<Map<String, Any>> {
        val agora    = LocalDateTime.now()
        val fim      = agora.plusHours(req.duracaoHoras.toLong())
        val fmt      = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val eventoId = "E_MANUAL_${System.currentTimeMillis()}"

        driver.session().use { session ->

            // 1. Criar o evento no grafo
            session.executeWrite { tx ->
                tx.run("""
                    CREATE (:Evento {
                        id:                 ${'$'}id,
                        nome:               ${'$'}nome,
                        tipo:               ${'$'}tipo,
                        inicio:             datetime(${'$'}inicio),
                        fim:                datetime(${'$'}fim),
                        afluencia_esperada: ${'$'}afl,
                        cidade:             "New York City",
                        criado_manualmente: true
                    })
                """, Values.parameters(
                    "id",eventoId,
                    "nome",   req.nome,
                    "tipo",   req.tipo,
                    "inicio", agora.format(fmt),
                    "fim",    fim.format(fmt),
                    "afl",    req.afluenciaEsperada
                )).list()
            }

            // 2. Ligar evento à paragem
            session.executeWrite { tx ->
                tx.run("""
                    MATCH (e:Evento {id: ${'$'}eid}), (p:Paragem {id: ${'$'}pid})
                    CREATE (e)-[:OCORRE_EM]->(p)
                """, Values.parameters("eid", eventoId, "pid", req.paragemId)).list()
            }

            // 3. Aplicar impacto imediato na afluência (+40% da capacidade)
            val resultado = session.executeWrite { tx ->
                tx.run("""
                    MATCH (e:Evento {id: ${'$'}eid})-[:OCORRE_EM]->(p:Paragem)
                    SET p.afluencia_atual = toInteger(
                        CASE
                            WHEN p.afluencia_atual + (p.capacidade * 0.40) > p.capacidade
                            THEN p.capacidade
                            ELSE p.afluencia_atual + (p.capacidade * 0.40)
                        END
                    )
                    RETURN p.nome AS paragem,
                           p.afluencia_atual AS novaAfluencia,
                           p.capacidade AS capacidade,
                           round(100.0 * p.afluencia_atual / p.capacidade, 1) AS ocupacao
                """, Values.parameters("eid", eventoId))
                    .list { r -> mapOf(
                        "paragem"       to r["paragem"].asString(),
                        "novaAfluencia" to r["novaAfluencia"].asInt(),
                        "capacidade"    to r["capacidade"].asInt(),
                        "ocupacao"      to r["ocupacao"].asDouble()
                    )}
            }

            val paragInfo = resultado.firstOrNull() ?: mapOf()

            return ResponseEntity.ok(mapOf(
                "sucesso"           to true,
                "eventoId"         to eventoId,
                "evento"           to req.nome,
                "tipo"             to req.tipo,
                "inicio"           to agora.format(fmt),
                "fim"              to fim.format(fmt),
                "paragem"          to (paragInfo["paragem"] ?: "desconhecida"),
                "novaAfluencia"    to (paragInfo["novaAfluencia"] ?: 0),
                "capacidade"       to (paragInfo["capacidade"] ?: 0),
                "ocupacao"         to "${paragInfo["ocupacao"] ?: 0}%",
                "mensagem"         to "Evento criado! Impacto aplicado. O simulador vai reagir em breve."
            ))
        }
    }

    // Listar todos os eventos (ativos e futuros)
    @GetMapping
    fun getTodos(): ResponseEntity<Any> {
        driver.session().use { session ->
            val eventos = session.executeRead { tx ->
                tx.run("""
                    MATCH (e:Evento)
                    OPTIONAL MATCH (e)-[:OCORRE_EM]->(p:Paragem)
                    OPTIONAL MATCH (e)-[:AFETA_ZONA]->(z:Zona)
                    RETURN e.id AS id, e.nome AS nome, e.tipo AS tipo,
                           e.afluencia_esperada AS afluencia,
                           toString(e.inicio) AS inicio,
                           toString(e.fim) AS fim,
                           p.nome AS paragem,
                           z.nome AS zona,
                           (e.inicio <= datetime() AND e.fim >= datetime()) AS ativo
                    ORDER BY e.inicio DESC
                """).list { r -> mapOf(
                    "id"        to r["id"].asString(),
                    "nome"      to r["nome"].asString(),
                    "tipo"      to r["tipo"].asString(),
                    "afluencia" to r["afluencia"].asInt(),
                    "inicio"    to r["inicio"].asString(),
                    "fim"       to r["fim"].asString(),
                    "paragem"   to r["paragem"].asString(),
                    "zona"      to r["zona"].asString(),
                    "ativo"     to r["ativo"].asBoolean()
                )}
            }
            return ResponseEntity.ok(eventos)
        }
    }
}
