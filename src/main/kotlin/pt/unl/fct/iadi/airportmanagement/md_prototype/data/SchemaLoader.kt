package pt.unl.fct.iadi.airportmanagement.md_prototype.data

import org.neo4j.driver.Session
import org.springframework.stereotype.Component

@Component
class SchemaLoader {

    fun load(session: Session) {

        // ── Constraints ───────────────────────────────────────────
        session.executeWrite { tx ->
            tx.run("CREATE CONSTRAINT paragem_id IF NOT EXISTS FOR (n:Paragem) REQUIRE n.id IS UNIQUE").list()
            tx.run("CREATE CONSTRAINT zona_id    IF NOT EXISTS FOR (n:Zona)    REQUIRE n.id IS UNIQUE").list()
            tx.run("CREATE CONSTRAINT rota_id    IF NOT EXISTS FOR (n:Rota)    REQUIRE n.id IS UNIQUE").list()
            tx.run("CREATE CONSTRAINT evento_id  IF NOT EXISTS FOR (n:Evento)  REQUIRE n.id IS UNIQUE").list()
        }

        // ── Índices ───────────────────────────────────────────────
        session.executeWrite { tx ->
            tx.run("CREATE INDEX paragem_tipo   IF NOT EXISTS FOR (p:Paragem) ON (p.tipo)").list()
            tx.run("CREATE INDEX paragem_cidade IF NOT EXISTS FOR (p:Paragem) ON (p.cidade)").list()
            tx.run("CREATE INDEX rota_ativa     IF NOT EXISTS FOR (r:Rota)    ON (r.ativa)").list()
            tx.run("CREATE INDEX evento_inicio  IF NOT EXISTS FOR (e:Evento)  ON (e.inicio)").list()
        }

        // ── Zonas (NYC Boroughs) ──────────────────────────────────
        session.executeWrite { tx ->
            tx.run("""CREATE (:Zona { id:"Z_Manhattan",    nome:"Manhattan",    tipo:"Commercial",  descricao:"Central business district", cidade:"New York City" })""").list()
            tx.run("""CREATE (:Zona { id:"Z_Brooklyn",     nome:"Brooklyn",     tipo:"Residential", descricao:"Most populous borough",      cidade:"New York City" })""").list()
            tx.run("""CREATE (:Zona { id:"Z_Queens",       nome:"Queens",       tipo:"Mixed",       descricao:"Most ethnically diverse",    cidade:"New York City" })""").list()
            tx.run("""CREATE (:Zona { id:"Z_Bronx",        nome:"Bronx",        tipo:"Residential", descricao:"Only mainland borough",      cidade:"New York City" })""").list()
            tx.run("""CREATE (:Zona { id:"Z_Staten_Island",nome:"Staten Island",tipo:"Residential", descricao:"Least dense borough",        cidade:"New York City" })""").list()
        }
    }
}