package pt.unl.fct.iadi.airportmanagement.md_prototype.data

import org.neo4j.driver.Session
import org.springframework.stereotype.Component

@Component
class EventLoader {

    fun load(session: Session) {
        session.executeWrite { tx ->
            tx.run("""CREATE (:Evento { id:"E1", nome:"Knicks Game at Madison Square Garden", tipo:"Jogo", inicio:datetime("2025-06-15T19:00:00"), fim:datetime("2025-06-15T23:00:00"), afluencia_esperada:20000, cidade:"New York City" })""").list()
            tx.run("""MATCH (e:Evento {id:"E1"}),(p:Paragem {id:"R16"}) FOREACH(_ IN CASE WHEN p IS NOT NULL THEN [1] ELSE [] END | CREATE (e)-[:OCORRE_EM]->(p))""").list()
            tx.run("""MATCH (e:Evento {id:"E1"}),(z:Zona {id:"Z_Manhattan"}) CREATE (e)-[:AFETA_ZONA]->(z)""").list()
            tx.run("""CREATE (:Evento { id:"E2", nome:"Concert at Barclays Center Brooklyn", tipo:"Concerto", inicio:datetime("2025-06-20T20:00:00"), fim:datetime("2025-06-20T23:30:00"), afluencia_esperada:19000, cidade:"New York City" })""").list()
            tx.run("""MATCH (e:Evento {id:"E2"}),(p:Paragem {id:"D24"}) FOREACH(_ IN CASE WHEN p IS NOT NULL THEN [1] ELSE [] END | CREATE (e)-[:OCORRE_EM]->(p))""").list()
            tx.run("""MATCH (e:Evento {id:"E2"}),(z:Zona {id:"Z_Brooklyn"}) CREATE (e)-[:AFETA_ZONA]->(z)""").list()
            tx.run("""CREATE (:Evento { id:"E3", nome:"US Open Tennis at Flushing Meadows", tipo:"Desporto", inicio:datetime("2025-08-25T11:00:00"), fim:datetime("2025-09-07T22:00:00"), afluencia_esperada:25000, cidade:"New York City" })""").list()
            tx.run("""MATCH (e:Evento {id:"E3"}),(p:Paragem {id:"702"}) FOREACH(_ IN CASE WHEN p IS NOT NULL THEN [1] ELSE [] END | CREATE (e)-[:OCORRE_EM]->(p))""").list()
            tx.run("""MATCH (e:Evento {id:"E3"}),(z:Zona {id:"Z_Queens"}) CREATE (e)-[:AFETA_ZONA]->(z)""").list()
            tx.run("""CREATE (:Evento { id:"E4", nome:"NYE Times Square Celebration", tipo:"Festival", inicio:datetime("2025-12-31T20:00:00"), fim:datetime("2026-01-01T01:00:00"), afluencia_esperada:100000, cidade:"New York City" })""").list()
            tx.run("""MATCH (e:Evento {id:"E4"}),(p:Paragem {id:"127"}) FOREACH(_ IN CASE WHEN p IS NOT NULL THEN [1] ELSE [] END | CREATE (e)-[:OCORRE_EM]->(p))""").list()
            tx.run("""MATCH (e:Evento {id:"E4"}),(z:Zona {id:"Z_Manhattan"}) CREATE (e)-[:AFETA_ZONA]->(z)""").list()
            tx.run("""CREATE (:Evento { id:"E5", nome:"Yankees Game at Yankee Stadium", tipo:"Jogo", inicio:datetime("2025-07-04T13:00:00"), fim:datetime("2025-07-04T17:00:00"), afluencia_esperada:50000, cidade:"New York City" })""").list()
            tx.run("""MATCH (e:Evento {id:"E5"}),(p:Paragem {id:"D11"}) FOREACH(_ IN CASE WHEN p IS NOT NULL THEN [1] ELSE [] END | CREATE (e)-[:OCORRE_EM]->(p))""").list()
            tx.run("""MATCH (e:Evento {id:"E5"}),(z:Zona {id:"Z_Bronx"}) CREATE (e)-[:AFETA_ZONA]->(z)""").list()
        }
    }
}