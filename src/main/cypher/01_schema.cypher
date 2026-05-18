// ============================================================
//  NYC SUBWAY GRAPH — Schema & Constraints
//  Projeto: Adaptação Dinâmica de Redes de Transporte
//  Dataset: MTA GTFS Real (493 estações, 29 linhas)
// ============================================================

// ---------- Constraints de unicidade ----------

CREATE CONSTRAINT paragem_id IF NOT EXISTS
  FOR (p:Paragem) REQUIRE p.id IS UNIQUE;

CREATE CONSTRAINT zona_id IF NOT EXISTS
  FOR (z:Zona) REQUIRE z.id IS UNIQUE;

CREATE CONSTRAINT rota_id IF NOT EXISTS
  FOR (r:Rota) REQUIRE r.id IS UNIQUE;

CREATE CONSTRAINT evento_id IF NOT EXISTS
  FOR (e:Evento) REQUIRE e.id IS UNIQUE;

// ---------- Índices de performance ----------

CREATE INDEX paragem_nome IF NOT EXISTS
  FOR (p:Paragem) ON (p.nome);

CREATE INDEX paragem_cidade IF NOT EXISTS
  FOR (p:Paragem) ON (p.cidade);

CREATE INDEX zona_tipo IF NOT EXISTS
  FOR (z:Zona) ON (z.tipo);

CREATE INDEX evento_inicio IF NOT EXISTS
  FOR (e:Evento) ON (e.inicio);

CREATE INDEX rota_ativa IF NOT EXISTS
  FOR (r:Rota) ON (r.ativa);

// ============================================================
//  MODELO DE NÓS
//
//  :Paragem  { id, nome, lat, lon, capacidade, afluencia_atual, cidade }
//  :Zona     { id, nome, tipo, descricao, cidade }  -- Boroughs de NYC
//  :Rota     { id, nome, tipo, cor, ativa, operador }
//  :Evento   { id, nome, tipo, inicio, fim, afluencia_esperada, cidade }
//
//  RELAÇÕES
//
//  (Paragem)-[:PERTENCE_A]->(Zona)
//  (Paragem)-[:SERVE]->(Paragem)          { distancia, tempo_min, peso, linha }
//  (Paragem)-[:TRANSFERENCIA]->(Paragem)  { tempo_min }
//  (Rota)-[:INCLUI]->(Paragem)            { ordem }
//  (Evento)-[:OCORRE_EM]->(Paragem)
//  (Evento)-[:AFETA_ZONA]->(Zona)
//  (Paragem)-[:LIGACAO_TEMPORARIA]->(Paragem) { motivo, validade, tempo_min }
// ============================================================

