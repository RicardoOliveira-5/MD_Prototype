package pt.unl.fct.iadi.airportmanagement.md_prototype.repo

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pt.unl.fct.iadi.airportmanagement.md_prototype.domain.Evento
import pt.unl.fct.iadi.airportmanagement.md_prototype.domain.Paragem
import pt.unl.fct.iadi.airportmanagement.md_prototype.domain.Rota
import pt.unl.fct.iadi.airportmanagement.md_prototype.domain.Veiculo
import pt.unl.fct.iadi.airportmanagement.md_prototype.domain.Zona

@Repository
interface ParagemRepository : Neo4jRepository<Paragem, String> {

    @Query("""
        MATCH (p:Paragem)
        WHERE p.afluencia_atual > p.capacidade * :#{#threshold}
        RETURN p
        ORDER BY (1.0 * p.afluencia_atual / p.capacidade) DESC
    """)
    fun findSobrecarregadas(@Param("threshold") threshold: Double): List<Paragem>

    @Query("""
        MATCH (p:Paragem)-[:PERTENCE_A]->(z:Zona { id: :#{#zonaId} })
        RETURN p
    """)
    fun findByZona(@Param("zonaId") zonaId: String): List<Paragem>

    @Query("""
        MATCH path = shortestPath(
            (origem:Paragem { id: :#{#origemId} })-[:SERVE|LIGACAO_TEMPORARIA*]-(destino:Paragem { id: :#{#destinoId} })
        )
        RETURN [n IN nodes(path) | n.nome] AS paragens,
               length(path) AS saltos,
               reduce(t = 0, r IN relationships(path) | t + r.tempo_min) AS tempoTotal
    """)
    fun shortestPath(
        @Param("origemId") origemId: String,
        @Param("destinoId") destinoId: String
    ): List<Map<String, Any>>

    @Query("""
        MATCH (p:Paragem { id: :#{#id} })
        SET p.afluencia_atual = :#{#afluencia}
        RETURN p
    """)
    fun updateAfluencia(@Param("id") id: String, @Param("afluencia") afluencia: Int): Paragem?
}

@Repository
interface RotaRepository : Neo4jRepository<Rota, String> {

    fun findByAtiva(ativa: Boolean): List<Rota>
    fun findByTipo(tipo: String): List<Rota>

    @Query("""
        MATCH (r:Rota { id: :#{#id} })
        SET r.ativa = :#{#ativa},
            r.motivo_desativacao = :#{#motivo}
        RETURN r
    """)
    fun setAtiva(
        @Param("id") id: String,
        @Param("ativa") ativa: Boolean,
        @Param("motivo") motivo: String?
    ): Rota?
}

@Repository
interface EventoRepository : Neo4jRepository<Evento, String> {

    @Query("""
        MATCH (e:Evento)
        WHERE e.inicio <= :#{#momento} AND e.fim >= :#{#momento}
        RETURN e
        ORDER BY e.afluencia_esperada DESC
    """)
    fun findAtivos(@Param("momento") momento: String): List<Evento>

    @Query("""
        MATCH (e:Evento)-[:AFETA_ZONA]->(z:Zona { id: :#{#zonaId} })
        RETURN e
    """)
    fun findByZona(@Param("zonaId") zonaId: String): List<Evento>
}

@Repository
interface ZonaRepository : Neo4jRepository<Zona, String>

@Repository
interface VeiculoRepository : Neo4jRepository<Veiculo, String>