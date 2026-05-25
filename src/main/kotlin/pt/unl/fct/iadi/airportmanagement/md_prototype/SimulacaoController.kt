package pt.unl.fct.iadi.airportmanagement.md_prototype.controller

import org.springframework.web.bind.annotation.*

/**
 * SimulacaoController — Demo interativo para o professor
 *
 * GET  /api/simulacao/grafo            → nós + arestas para o frontend
 * POST /api/simulacao/evento/{tipo}    → concert | nye | avaria_l | yankees | rush | reset
 * POST /api/simulacao/no-temporario   → adicionar nó ao grafo em tempo real
 * POST /api/simulacao/ligacao         → criar LIGACAO_TEMPORARIA manual
 * POST /api/simulacao/reset           → repor rede ao estado inicial
 */
@RestController
@RequestMapping("/api/simulacao")
@CrossOrigin(origins = ["*"])
class SimulacaoController(private val service: SimulacaoService) {

    @GetMapping("/grafo")
    fun grafo(): SimGrafoDTO = service.getGrafoVisual()

    @PostMapping("/evento/{tipo}")
    fun evento(@PathVariable tipo: String): Map<String, Any> =
        service.aplicarEvento(tipo)

    @PostMapping("/no-temporario")
    fun addNo(@RequestBody req: NoTemporarioRequest): Map<String, Any> =
        service.adicionarNoTemp(req)

    @PostMapping("/ligacao")
    fun addLigacao(@RequestBody req: LigacaoSimRequest): Map<String, Any> =
        service.adicionarLigacao(req)

    @PostMapping("/reset")
    fun reset(): Map<String, Any> =
        service.resetarAfluencias()
}
