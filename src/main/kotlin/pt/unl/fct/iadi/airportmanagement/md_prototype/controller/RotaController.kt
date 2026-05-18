package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pt.unl.fct.iadi.airportmanagement.md_prototype.dto.RotaStatusRequest
import pt.unl.fct.iadi.airportmanagement.md_prototype.service.NetworkService

@RestController
@RequestMapping("/api/rotas")
class RotaController(private val service: NetworkService) {

    @GetMapping
    fun getAll() = ResponseEntity.ok(service.getAllRotas())

    @PatchMapping("/{id}/status")
    fun setStatus(
        @PathVariable id: String,
        @RequestBody req: RotaStatusRequest
    ): ResponseEntity<Any> = try {
        ResponseEntity.ok(service.setRotaStatus(id, req))
    } catch (e: IllegalArgumentException) {
        ResponseEntity.notFound().build()
    }
}
