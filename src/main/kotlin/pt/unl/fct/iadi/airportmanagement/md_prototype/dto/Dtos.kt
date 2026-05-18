package pt.unl.fct.iadi.airportmanagement.md_prototype.dto

import java.time.LocalDateTime

data class ParagemResumo(
    val id: String,
    val nome: String,
    val zona: String?,
    val afluenciaAtual: Int,
    val capacidade: Int,
    val ocupacaoPercent: Double
)

data class CaminhoResponse(
    val paragens: List<String>,
    val saltos: Int,
    val tempoTotalMin: Int
)

data class EstadoRedeResponse(
    val totalParagens: Int,
    val totalRotas: Int,
    val rotasAtivas: Int,
    val passageirosAtual: Int,
    val capacidadeTotal: Int,
    val ocupacaoGlobalPercent: Double,
    val paragensEmSobrecarga: List<ParagemResumo>
)

data class LigacaoTemporariaResponse(
    val origem: String,
    val destino: String,
    val motivo: String,
    val validadeAte: String,
    val tempMin: Int
)

data class EventoImpactoResponse(
    val evento: String,
    val tipo: String,
    val afluenciaEsperada: Int,
    val paragensAfetadas: List<ParagemResumo>,
    val zonaAfetada: String?
)

data class LigacaoTemporariaRequest(
    val origemId: String,
    val destinoId: String,
    val motivo: String,
    val validadeAte: LocalDateTime,
    val tempMin: Int,
    val distancia: Int
)

data class AfluenciaUpdateRequest(
    val paragemaId: String,
    val novaAfluencia: Int
)

data class RotaStatusRequest(
    val ativa: Boolean,
    val motivo: String? = null
)