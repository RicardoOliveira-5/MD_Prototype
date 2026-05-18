// ============================================================
//  NYC SUBWAY GRAPH — Queries de Adaptação Dinâmica
//  Projeto: Adaptação Dinâmica de Redes de Transporte
//  Dataset: MTA GTFS Real (493 estações, 29 linhas NYC)
// ============================================================

// ─────────────────────────────────────────────────────────────
//  1. EXPLORAÇÃO BÁSICA DA REDE
// ─────────────────────────────────────────────────────────────

// 1a. Quantos nós e relações existem no grafo?
MATCH (n)
RETURN labels(n) AS Tipo, count(n) AS Total
ORDER BY Total DESC;

// 1b. Ver todas as linhas do subway com número de paragens
MATCH (r:Rota)-[:INCLUI]->(p:Paragem)
RETURN r.nome AS Linha, r.cor AS Cor, count(p) AS NumParagens
ORDER BY NumParagens DESC;

// 1c. Distribuição de estações por Borough
MATCH (p:Paragem)-[:PERTENCE_A]->(z:Zona)
RETURN z.nome AS Borough, count(p) AS Estacoes
ORDER BY Estacoes DESC;

// 1d. Ocupação atual de todas as estações (top 10 mais cheias)
MATCH (p:Paragem)
RETURN p.nome AS Estacao,
       p.afluencia_atual AS Passageiros,
       p.capacidade AS Capacidade,
       round(100.0 * p.afluencia_atual / p.capacidade, 1) AS `Ocupacao %`
ORDER BY `Ocupacao %` DESC
LIMIT 10;


// ─────────────────────────────────────────────────────────────
//  2. PATHFINDING — Shortest Path (T04 - Semântica de GQL)
// ─────────────────────────────────────────────────────────────

// 2a. Caminho mais curto entre Times Square e Coney Island
MATCH path = shortestPath(
  (origem:Paragem {id: '127'})-[:SERVE|TRANSFERENCIA*]-(destino:Paragem {id: 'H19'})
)
RETURN [n IN nodes(path) | n.nome] AS Paragens,
       length(path) AS Saltos,
       reduce(t = 0, r IN relationships(path) | t + coalesce(r.tempo_min, 0)) AS Tempo_Min;

// 2b. Caminho mais curto de Grand Central até Flushing (linha 7)
MATCH path = shortestPath(
  (origem:Paragem {id: '631'})-[:SERVE|TRANSFERENCIA*]-(destino:Paragem {id: '701'})
)
RETURN [n IN nodes(path) | n.nome] AS Paragens,
       length(path) AS Saltos,
       reduce(t = 0, r IN relationships(path) | t + coalesce(r.tempo_min, 0)) AS Tempo_Min;

// 2c. Todos os caminhos possíveis entre duas estações (máx 5 saltos)
MATCH path = (origem:Paragem {id: '127'})-[:SERVE*1..5]->(destino:Paragem {id: 'A27'})
RETURN [n IN nodes(path) | n.nome] AS Caminho,
       length(path) AS Saltos,
       reduce(t = 0, r IN relationships(path) | t + r.tempo_min) AS Tempo_Min
ORDER BY Tempo_Min
LIMIT 5;

// 2d. Pathfinding incluindo ligações temporárias (adaptação dinâmica)
MATCH path = shortestPath(
  (origem:Paragem {id: '127'})-[:SERVE|TRANSFERENCIA|LIGACAO_TEMPORARIA*]-(destino:Paragem {id: 'H19'})
)
RETURN [n IN nodes(path) | n.nome] AS Paragens,
       length(path) AS Saltos,
       reduce(t = 0, r IN relationships(path) | t + coalesce(r.tempo_min, 0)) AS Tempo_Min,
       [r IN relationships(path) | type(r)] AS TiposRelacao;


// ─────────────────────────────────────────────────────────────
//  3. DETEÇÃO DE SOBRECARGA
// ─────────────────────────────────────────────────────────────

// 3a. Estações com ocupação acima de 80%
MATCH (p:Paragem)
WHERE p.afluencia_atual > p.capacidade * 0.8
RETURN p.id AS ID, p.nome AS Estacao,
       p.afluencia_atual AS Atual, p.capacidade AS Capacidade,
       round(100.0 * p.afluencia_atual / p.capacidade, 1) AS `Ocupacao %`
ORDER BY `Ocupacao %` DESC;

// 3b. Boroughs com maior pressão média
MATCH (p:Paragem)-[:PERTENCE_A]->(z:Zona)
RETURN z.nome AS Borough,
       round(avg(100.0 * p.afluencia_atual / p.capacidade), 1) AS `Ocupacao Media %`,
       sum(p.afluencia_atual) AS Total_Passageiros,
       count(p) AS Num_Estacoes
ORDER BY `Ocupacao Media %` DESC;

// 3c. Estações sobrecarregadas e os seus vizinhos imediatos com espaço
MATCH (p:Paragem)-[:SERVE]->(vizinha:Paragem)
WHERE p.afluencia_atual > p.capacidade * 0.8
  AND vizinha.afluencia_atual < vizinha.capacidade * 0.6
RETURN p.nome AS Sobrecarregada,
       round(100.0 * p.afluencia_atual / p.capacidade, 1) AS `Ocup %`,
       vizinha.nome AS Alternativa,
       round(100.0 * vizinha.afluencia_atual / vizinha.capacidade, 1) AS `Ocup Alternativa %`
ORDER BY `Ocup %` DESC
LIMIT 10;


// ─────────────────────────────────────────────────────────────
//  4. IMPACTO DE EVENTOS
// ─────────────────────────────────────────────────────────────

// 4a. Ver todos os eventos e as estações que afetam
MATCH (e:Evento)-[:OCORRE_EM]->(p:Paragem),
      (e)-[:AFETA_ZONA]->(z:Zona)
RETURN e.nome AS Evento, e.tipo AS Tipo,
       e.afluencia_esperada AS Afluencia_Esperada,
       p.nome AS Estacao_Principal,
       z.nome AS Borough_Afetado,
       e.inicio AS Inicio, e.fim AS Fim;

// 4b. Simular aumento de afluência durante NYE no Times Square (+50% da capacidade)
MATCH (e:Evento {id: 'E4'})-[:OCORRE_EM]->(p:Paragem)
SET p.afluencia_atual = toInteger(p.capacidade * 0.95)
RETURN p.nome AS Estacao, p.afluencia_atual AS Nova_Afluencia,
       p.capacidade AS Capacidade,
       round(100.0 * p.afluencia_atual / p.capacidade, 1) AS `Ocupacao %`;

// 4c. Estações afetadas indiretamente (até 2 saltos de distância do evento)
MATCH (e:Evento {id: 'E4'})-[:OCORRE_EM]->(epicentro:Paragem)
MATCH (epicentro)-[:SERVE*1..2]->(afetada:Paragem)
WHERE afetada <> epicentro
RETURN DISTINCT afetada.nome AS Estacao_Afetada,
       afetada.capacidade AS Capacidade,
       round(100.0 * afetada.afluencia_atual / afetada.capacidade, 1) AS `Ocupacao %`
ORDER BY `Ocupacao %` DESC
LIMIT 10;


// ─────────────────────────────────────────────────────────────
//  5. ADAPTAÇÃO DINÂMICA — Criar Ligações Temporárias
// ─────────────────────────────────────────────────────────────

// 5a. Criar ligação temporária durante NYE (Times Square → Hudson Yards)
//     Para desviar passageiros da estação mais congestionada
MATCH (a:Paragem {id: '127'}), (b:Paragem {id: '720'})
CREATE (a)-[:LIGACAO_TEMPORARIA {
  motivo:    'NYE Times Square - Sobrecarga Crítica',
  criada_em: datetime(),
  validade:  datetime('2026-01-01T01:30:00'),
  tempo_min: 8,
  distancia: 1200
}]->(b)
RETURN 'Ligação temporária criada' AS Resultado;

// 5b. Ver todas as ligações temporárias ativas
MATCH (a:Paragem)-[lt:LIGACAO_TEMPORARIA]->(b:Paragem)
WHERE lt.validade > datetime()
RETURN a.nome AS Origem, b.nome AS Destino,
       lt.motivo AS Motivo,
       lt.validade AS Valida_Ate,
       lt.tempo_min AS Tempo_Min
ORDER BY lt.validade;

// 5c. Pathfinding COM ligações temporárias ativas (vs sem)
//     Demonstra como a adaptação muda as rotas disponíveis
MATCH path = shortestPath(
  (a:Paragem {id: '127'})-[:SERVE|LIGACAO_TEMPORARIA*]-(b:Paragem {id: '720'})
)
RETURN 'Com adaptação' AS Modo,
       [n IN nodes(path) | n.nome] AS Rota,
       reduce(t=0, r IN relationships(path) | t + coalesce(r.tempo_min,0)) AS Tempo_Min;

// 5d. Remover ligações temporárias expiradas
MATCH ()-[lt:LIGACAO_TEMPORARIA]->()
WHERE lt.validade < datetime()
WITH collect(lt) AS expiradas
FOREACH (lt IN expiradas | DELETE lt)
RETURN size(expiradas) AS Ligacoes_Removidas;


// ─────────────────────────────────────────────────────────────
//  6. AJUSTE DE ROTAS
// ─────────────────────────────────────────────────────────────

// 6a. Desativar uma linha por sobrecarga/avaria
MATCH (r:Rota {id: 'R_L'})
SET r.ativa = false, r.motivo_desativacao = 'Avaria técnica - Canarsie Tunnel'
RETURN r.nome AS Linha, r.ativa AS Ativa;

// 6b. Aumentar peso das ligações de uma estação congestionada
//     (penaliza o pathfinding para evitar aquela estação)
MATCH (p:Paragem {id: '127'})-[s:SERVE]->(destino:Paragem)
WHERE p.afluencia_atual > p.capacidade * 0.9
SET s.peso = s.peso * 5
RETURN p.nome AS Estacao, destino.nome AS Destino, s.peso AS Novo_Peso;

// 6c. Restaurar pesos normais após normalização
MATCH ()-[s:SERVE]->()
WHERE s.peso > 1
SET s.peso = 1
RETURN count(s) AS Ligacoes_Normalizadas;


// ─────────────────────────────────────────────────────────────
//  7. ANÁLISE DE CONECTIVIDADE (T03 - Labeled Property Graphs)
// ─────────────────────────────────────────────────────────────

// 7a. Estações mais centrais (maior número de ligações diretas)
MATCH (p:Paragem)-[r:SERVE|TRANSFERENCIA]-()
RETURN p.nome AS Estacao, count(r) AS Grau
ORDER BY Grau DESC
LIMIT 15;

// 7b. Estações hub (servidas por mais linhas)
MATCH (r:Rota)-[:INCLUI]->(p:Paragem)
RETURN p.nome AS Estacao, count(r) AS NumLinhas,
       collect(r.nome) AS Linhas
ORDER BY NumLinhas DESC
LIMIT 10;

// 7c. Estações isoladas (sem ligações SERVE)
MATCH (p:Paragem)
WHERE NOT (p)-[:SERVE]-() AND NOT ()-[:SERVE]->(p)
RETURN p.nome AS Estacao_Isolada, p.id AS ID;

// 7d. Componentes conexas — quantas sub-redes existem?
MATCH (p:Paragem)
WITH collect(p) AS paragens
CALL {
  WITH paragens
  MATCH (a:Paragem)-[:SERVE*]-(b:Paragem)
  RETURN count(DISTINCT a) AS EstacoesCnectadas
}
RETURN EstacoesCnectadas;


// ─────────────────────────────────────────────────────────────
//  8. META-GRAFO / SCHEMA EMERGENTE (T05 - Schemas for GDBs)
// ─────────────────────────────────────────────────────────────

// 8a. Schema emergente: que tipos de nós existem?
MATCH (n)
RETURN DISTINCT labels(n) AS TipoNo, count(n) AS Total
ORDER BY Total DESC;

// 8b. Schema emergente: que relações existem entre que tipos?
MATCH (a)-[r]->(b)
RETURN DISTINCT labels(a)[0] AS De, type(r) AS Relacao,
       labels(b)[0] AS Para, count(r) AS Total
ORDER BY Total DESC;

// 8c. Grafo de transferências: estações com mais conexões inter-linhas
MATCH (p:Paragem)-[:TRANSFERENCIA]-(outra:Paragem)
RETURN p.nome AS Estacao, count(DISTINCT outra) AS Transferencias
ORDER BY Transferencias DESC
LIMIT 10;


// ─────────────────────────────────────────────────────────────
//  9. RELATÓRIO GERAL DO ESTADO DA REDE
// ─────────────────────────────────────────────────────────────

MATCH (p:Paragem)
WITH count(p) AS totalParagens,
     sum(p.afluencia_atual) AS totalPassageiros,
     sum(p.capacidade) AS capacidadeTotal,
     sum(CASE WHEN p.afluencia_atual > p.capacidade * 0.8 THEN 1 ELSE 0 END) AS emSobrecarga

MATCH (r:Rota)
WITH totalParagens, totalPassageiros, capacidadeTotal, emSobrecarga,
     count(r) AS totalRotas,
     sum(CASE WHEN r.ativa THEN 1 ELSE 0 END) AS rotasAtivas

OPTIONAL MATCH ()-[lt:LIGACAO_TEMPORARIA]->()
WHERE lt.validade > datetime()

RETURN totalParagens       AS `Estacoes`,
       totalRotas          AS `Linhas`,
       rotasAtivas         AS `Linhas Ativas`,
       totalPassageiros    AS `Passageiros Atuais`,
       capacidadeTotal     AS `Capacidade Total`,
       round(100.0 * totalPassageiros / capacidadeTotal, 1) AS `Ocupacao Global %`,
       emSobrecarga        AS `Estacoes em Sobrecarga`,
       count(lt)           AS `Ligacoes Temporarias Ativas`;

