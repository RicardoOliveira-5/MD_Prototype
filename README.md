# Transport Graph — Backend API

Backend **Kotlin + Spring Boot** que expõe a rede de transporte Neo4j via REST.

## Pré-requisitos

- JDK 17+
- Neo4j Desktop a correr em `bolt://localhost:7687`
- Dataset carregado (`01_schema.cypher` → `02_dataset.cypher`)

## Configuração

Edita `src/main/resources/application.yml` e define a tua password:

```yaml
spring.neo4j.authentication.password: <a-tua-password>
```

## Correr

```bash
./gradlew bootRun
```

A API fica disponível em `http://localhost:8080`.

---

## Endpoints

### Estado da Rede
| Método | URL | Descrição |
|--------|-----|-----------|
| GET | `/api/rede/estado` | Visão geral — ocupação global, rotas ativas, sobrecargas |

### Paragens
| Método | URL | Descrição |
|--------|-----|-----------|
| GET | `/api/paragens` | Lista todas as paragens |
| GET | `/api/paragens/sobrecarga?threshold=0.6` | Paragens com ocupação > threshold |
| GET | `/api/paragens/zona/{zonaId}` | Paragens de uma zona (ex: Z3) |
| GET | `/api/paragens/caminho?origem=P04&destino=P07` | Caminho mais curto |
| PUT | `/api/paragens/{id}/afluencia` | Atualizar afluência |

### Rotas
| Método | URL | Descrição |
|--------|-----|-----------|
| GET | `/api/rotas` | Lista todas as rotas |
| PATCH | `/api/rotas/{id}/status` | Ativar/desativar rota |

### Eventos
| Método | URL | Descrição |
|--------|-----|-----------|
| GET | `/api/eventos/ativos` | Eventos a decorrer agora |
| POST | `/api/eventos/{id}/simular-impacto` | Simula aumento de afluência |

### Ligações Temporárias
| Método | URL | Descrição |
|--------|-----|-----------|
| GET | `/api/ligacoes-temporarias` | Lista ligações temporárias ativas |
| POST | `/api/ligacoes-temporarias` | Criar nova ligação temporária |
| DELETE | `/api/ligacoes-temporarias/expiradas` | Remove ligações expiradas |

---

## Exemplos cURL

```bash
# Estado geral da rede
curl http://localhost:8080/api/rede/estado

# Caminho mais rápido Benfica → Oriente
curl "http://localhost:8080/api/paragens/caminho?origem=P04&destino=P07"

# Desativar linha vermelha por sobrecarga
curl -X PATCH http://localhost:8080/api/rotas/R3/status \
  -H "Content-Type: application/json" \
  -d '{"ativa": false, "motivo": "Sobrecarga no Oriente"}'

# Criar ligação temporária
curl -X POST http://localhost:8080/api/ligacoes-temporarias \
  -H "Content-Type: application/json" \
  -d '{
    "origemId": "P08",
    "destinoId": "P10",
    "motivo": "Concerto Altice Arena",
    "validadeAte": "2025-06-15T23:59:00",
    "tempMin": 8,
    "distancia": 3500
  }'

# Simular impacto do concerto em Oriente
curl -X POST http://localhost:8080/api/eventos/E1/simular-impacto

# Limpar ligações temporárias expiradas
curl -X DELETE http://localhost:8080/api/ligacoes-temporarias/expiradas
```

---

## Estrutura do projeto

```
src/main/kotlin/pt/iscte/transport/
├── TransportApplication.kt       ← entry point
├── domain/
│   └── Entities.kt               ← Paragem, Zona, Rota, Evento, Veiculo
├── repository/
│   └── Repositories.kt           ← Spring Data Neo4j + queries Cypher
├── service/
│   └── NetworkService.kt         ← lógica de adaptação dinâmica
└── controller/
    └── Controllers.kt            ← endpoints REST
```
