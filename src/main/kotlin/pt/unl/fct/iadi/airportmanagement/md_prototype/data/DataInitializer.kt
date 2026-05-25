package pt.unl.fct.iadi.airportmanagement.md_prototype.data

import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import pt.unl.fct.iadi.airportmanagement.md_prototype.controller.DemoController

@Component
class DataInitializer(
    private val driver: Driver,
    private val schemaLoader: SchemaLoader,
    private val subwayLoader: SubwayLoader,
    private val busLoader: BusLoader,
    private val eventLoader: EventLoader,
    private val demoController: DemoController,
    @Value("\${app.dataset-mode:1}") private val datasetMode: Int
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DataInitializer::class.java)

    override fun run(args: ApplicationArguments) {
        driver.session().use { session ->

            // Limpar sempre
            val count = session.executeRead { tx ->
                tx.run("MATCH (n) RETURN count(n) AS total").single()["total"].asLong()
            }
            if (count > 0L) {
                log.info("A limpar $count nós anteriores...")
                session.executeWrite { tx -> tx.run("MATCH (n) DETACH DELETE n").list() }
            }

            when (datasetMode) {
                1 -> {
                    log.info("MODO 1 — Demo (5 paragens)")
                    demoController.carregarDemo()
                }
                2 -> {
                    log.info("MODO 2 — Subway NYC (493 estações)")
                    schemaLoader.load(session)
                    subwayLoader.load(session)
                    eventLoader.load(session)
                    log.info("Subway carregado!")
                }
                else -> {
                    log.info("MODO 3 — Completo (subway + bus = 1178 paragens)")
                    schemaLoader.load(session)
                    subwayLoader.load(session)
                    busLoader.load(session)
                    eventLoader.load(session)
                    log.info("Dataset completo carregado!")
                }
            }
        }
    }
}