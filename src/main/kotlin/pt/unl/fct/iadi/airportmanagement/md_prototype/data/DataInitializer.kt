package pt.unl.fct.iadi.airportmanagement.md_prototype.data

import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val driver: Driver,
    private val schemaLoader: SchemaLoader,
    private val subwayLoader: SubwayLoader,
    private val busLoader: BusLoader,
    private val eventLoader: EventLoader,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DataInitializer::class.java)

    override fun run(args: ApplicationArguments) {
        driver.session().use { session ->
            val count = session.executeRead { tx ->
                tx.run("MATCH (n) RETURN count(n) AS total").single()["total"].asLong()
            }
            if (count > 0L) {
                log.info("Neo4j já tem ${"$"}count nós — a saltar inicialização.")
                return
            }
            log.info("A carregar schema...")
            schemaLoader.load(session)
            log.info("A carregar 493 estações subway...")
            subwayLoader.load(session)
            log.info("A carregar 685 paragens de bus...")
            busLoader.load(session)
            log.info("A carregar eventos NYC...")
            eventLoader.load(session)
            log.info("Dataset NYC completo: 1178 paragens, 53 linhas!")
        }
    }
}