package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pt.unl.fct.iadi.airportmanagement.md_prototype.service.NetworkService

@RestController
@RequestMapping("/api/rede")
class RedeController(private val service: NetworkService) {

    @GetMapping("/estado")
    fun getEstado() = ResponseEntity.ok(service.getEstadoRede())
}
