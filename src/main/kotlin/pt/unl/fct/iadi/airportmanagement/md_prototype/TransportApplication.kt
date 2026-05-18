package pt.unl.fct.iadi.airportmanagement.md_prototype

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class TransportApplication

fun main(args: Array<String>) {
    runApplication<TransportApplication>(*args)
}
