package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.AfluenciaUpdateRequest
import pt.unl.fct.iadi.airportmanagement.md_prototype.service.NetworkService

// ── DTOs para criação manual de eventos ──────────────────────
data class CriarEventoRequest(
    val nome: String,
    val tipo: String,                  // Concerto | Jogo | Festival | Desporto
    val paragemId: String,             // ID da paragem onde ocorre
    val afluenciaEsperada: Int,
    val duracaoHoras: Int = 3          // duração em horas a partir de agora
)

data class SimularSobrecargaRequest(
    val paragemId: String,
    val percentagem: Int = 95          // % da capacidade a definir
)

@RestController
@RequestMapping("/api/paragens")
class ParagemController(private val service: NetworkService) {

    @GetMapping
    fun getAll() = ResponseEntity.ok(service.getAllParagens())

    @GetMapping("/sobrecarga")
    fun getSobrecarregadas(
        @RequestParam(defaultValue = "0.6") threshold: Double
    ) = ResponseEntity.ok(service.getParagensSobrecarregadas(threshold))

    @GetMapping("/zona/{zonaId}")
    fun getByZona(@PathVariable zonaId: String) =
        ResponseEntity.ok(service.getParagensPorZona(zonaId))

    @PutMapping("/{id}/afluencia")
    fun updateAfluencia(
        @PathVariable id: String,
        @RequestBody req: AfluenciaUpdateRequest
    ) = ResponseEntity.ok(service.updateAfluencia(id, req.novaAfluencia))

    @GetMapping("/caminho")
    fun getCaminho(
        @RequestParam origem: String,
        @RequestParam destino: String
    ): ResponseEntity<Any> = try {
        ResponseEntity.ok(service.getCaminho(origem, destino))
    } catch (e: IllegalStateException) {
        ResponseEntity.notFound().build()
    }

    // Simular sobrecarga manual numa paragem (para testes)
    @PostMapping("/{id}/simular-sobrecarga")
    fun simularSobrecarga(
        @PathVariable id: String,
        @RequestBody req: SimularSobrecargaRequest
    ) = ResponseEntity.ok(service.simularSobrecargaManual(id, req.percentagem))
}

