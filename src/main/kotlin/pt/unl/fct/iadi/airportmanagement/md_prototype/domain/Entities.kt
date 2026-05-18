package pt.unl.fct.iadi.airportmanagement.md_prototype.domain

import org.springframework.data.neo4j.core.schema.*
import java.time.LocalDateTime

@Node("Zona")
data class Zona(
    @Id val id: String,
    val nome: String,
    val tipo: String
)

@Node("Paragem")
data class Paragem(
    @Id val id: String,
    val nome: String,
    val lat: Double,
    val lon: Double,
    val capacidade: Int,

    // Neo4j guarda como "afluencia_atual" → mapeamos com @Property
    @Property("afluencia_atual")
    var afluenciaAtual: Int = 0,

    @Relationship(type = "PERTENCE_A", direction = Relationship.Direction.OUTGOING)
    val zona: Zona? = null
)

@Node("Rota")
data class Rota(
    @Id val id: String,
    val nome: String,
    val tipo: String,
    var ativa: Boolean = true,

    // Neo4j guarda como "motivo_desativacao" → mapeamos com @Property
    @Property("motivo_desativacao")
    var motivoDesativacao: String? = null,

    @Relationship(type = "INCLUI", direction = Relationship.Direction.OUTGOING)
    val paragens: List<RotaParagem> = emptyList()
)

@RelationshipProperties
data class RotaParagem(
    @RelationshipId val relId: Long? = null,
    val ordem: Int,

    @TargetNode
    val paragem: Paragem
)

@Node("Veiculo")
data class Veiculo(
    @Id val id: String,
    val tipo: String,
    val capacidade: Int,

    @Property("rota_id")
    val rotaId: String,

    @Relationship(type = "ALOCADO_A", direction = Relationship.Direction.OUTGOING)
    val rota: Rota? = null
)

@Node("Evento")
data class Evento(
    @Id val id: String,
    val nome: String,
    val tipo: String,
    val inicio: LocalDateTime,
    val fim: LocalDateTime,

    @Property("afluencia_esperada")
    val afluenciaEsperada: Int,

    @Relationship(type = "OCORRE_EM", direction = Relationship.Direction.OUTGOING)
    val paragem: Paragem? = null,

    @Relationship(type = "AFETA_ZONA", direction = Relationship.Direction.OUTGOING)
    val zona: Zona? = null
)