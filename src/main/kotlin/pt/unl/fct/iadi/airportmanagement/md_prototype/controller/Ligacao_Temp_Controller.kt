package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.LigacaoTemporariaRequest
import pt.unl.fct.iadi.airportmanagement.md_prototype.service.NetworkService

@RestController
@RequestMapping("/api/ligacoes-temporarias")
class LigacaoController(private val service: NetworkService) {

    @GetMapping
    fun getAll() = ResponseEntity.ok(service.getLigacoesTemporarias())

    @PostMapping
    fun criar(@RequestBody req: LigacaoTemporariaRequest) =
        ResponseEntity.ok(service.criarLigacaoTemporaria(req))

    @DeleteMapping("/expiradas")
    fun removerExpiradas() =
        ResponseEntity.ok(mapOf("removidas" to service.removerLigacoesExpiradas()))
}