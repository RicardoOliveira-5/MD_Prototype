"""
NYC Subway GTFS → Neo4j Cypher Importer
========================================
Faz fetch dos dados reais do MTA (Metropolitan Transportation Authority)
e gera um ficheiro Cypher para importar no Neo4j.

Uso:
    pip install requests
    python gtfs_to_cypher.py

Output:
    nyc_subway_real.cypher  (importa no Neo4j Browser ou DataInitializer)
"""

import csv
import io
import math
import zipfile
import requests
import os

# ── CONFIGURAÇÃO ──────────────────────────────────────────────────────────────

# Fonte 1: MTA oficial (mais completo, ~472 estações)
MTA_GTFS_URL = "https://api.mta.info/GTFS.zip"

# Fonte 2: Transitland (backup, sem registo)
TRANSITLAND_URL = "https://www.transit.land/api/v2/feeds/f-dr5r-nyctsubway/download_latest_feed_version"

# Fonte 3: NYC Open Data (mais estável)
NYC_OPEN_DATA_URL = "https://data.ny.gov/api/views/fgm6-ccue/rows.csv?accessType=DOWNLOAD"

OUTPUT_FILE = "nyc_subway_real.cypher"

# Limitar a X paragens (None = todas ~472)
MAX_STOPS = None

# Só incluir paragens do subway (filtrar buses)
SUBWAY_ONLY = False

# ── DOWNLOAD ──────────────────────────────────────────────────────────────────

def download_gtfs():
    """Tenta descarregar o GTFS do MTA. Testa várias fontes."""
    
    sources = [
        ("MTA Oficial", MTA_GTFS_URL),
        ("Transitland", "https://www.transit.land/feeds/f-dr5r-nyctsubway"),
    ]
    
    # Primeiro tentar descarregar diretamente
    print("A descarregar dados GTFS do MTA NYC...")
    
    try:
        resp = requests.get(MTA_GTFS_URL, timeout=30, 
                           headers={"User-Agent": "Mozilla/5.0"})
        if resp.status_code == 200 and len(resp.content) > 10000:
            print(f"✓ Descarregado da MTA ({len(resp.content)//1024} KB)")
            return resp.content
    except Exception as e:
        print(f"MTA falhou: {e}")

    # Backup: tentar URL alternativa do MTA
    alt_urls = [
        "http://web.mta.info/developers/data/nyct/subway/google_transit.zip",
        "https://rrgtfs.com/gtfs/nyct.zip",
        "https://github.com/google/transit/raw/master/gtfs/spec/en/examples/sample-feed.zip",
    ]
    
    for url in alt_urls:
        try:
            print(f"A tentar: {url}")
            resp = requests.get(url, timeout=30, allow_redirects=True)
            if resp.status_code == 200 and len(resp.content) > 1000:
                print(f"✓ Descarregado ({len(resp.content)//1024} KB)")
                return resp.content
        except Exception as e:
            print(f"Falhou: {e}")
    
    raise Exception("Não foi possível descarregar o GTFS. Ver instruções manuais abaixo.")

def read_csv_from_zip(zip_bytes, filename):
    """Lê um ficheiro CSV de dentro do ZIP GTFS."""
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        # Listar ficheiros disponíveis
        names = zf.namelist()
        # Encontrar o ficheiro (pode estar numa subpasta)
        target = next((n for n in names if n.endswith(filename)), None)
        if not target:
            print(f"  ⚠ {filename} não encontrado. Disponíveis: {names}")
            return []
        with zf.open(target) as f:
            content = f.read().decode("utf-8-sig")
            reader = csv.DictReader(io.StringIO(content))
            return list(reader)

# ── HAVERSINE ─────────────────────────────────────────────────────────────────

def haversine(lat1, lon1, lat2, lon2):
    R = 6371000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlambda/2)**2
    return int(2 * R * math.asin(math.sqrt(a)))

# ── BOROUGH DETECTOR ──────────────────────────────────────────────────────────

def get_borough(lat, lon):
    """Determina o borough de NYC baseado nas coordenadas."""
    if lat > 40.796 and lon > -73.93:
        return "Bronx"
    elif lat > 40.739 and lon < -74.04:
        return "Manhattan"
    elif lat > 40.70 and lon > -73.97 and lon < -73.85:
        return "Queens"
    elif lat < 40.739 and lon > -74.05 and lon < -73.83:
        return "Brooklyn"
    elif lon < -74.05:
        return "Staten Island"
    elif lat > 40.69 and lon < -73.97:
        return "Manhattan"
    elif lon > -73.85:
        return "Queens"
    else:
        return "Brooklyn"

# ── GERADOR DE CYPHER ─────────────────────────────────────────────────────────

def generate_cypher(stops, routes, stop_times_by_route):
    lines = []
    
    lines.append("// ============================================================")
    lines.append("//  NYC SUBWAY — Dataset Real (MTA GTFS)")
    lines.append(f"//  {len(stops)} estações | {len(routes)} linhas")
    lines.append("//  Fonte: Metropolitan Transportation Authority (MTA)")
    lines.append("//  https://www.mta.info/developers")
    lines.append("// ============================================================")
    lines.append("")
    
    # Limpar dados existentes
    lines.append("// Limpar dados anteriores")
    lines.append("MATCH (n) DETACH DELETE n;")
    lines.append("")
    
    # Constraints
    lines.append("// ── CONSTRAINTS ──────────────────────────────────────────────")
    for label in ["Paragem", "Zona", "Rota", "Evento"]:
        lines.append(f"CREATE CONSTRAINT {label.lower()}_id IF NOT EXISTS FOR (n:{label}) REQUIRE n.id IS UNIQUE;")
    lines.append("")
    
    # Zonas (Boroughs)
    boroughs = {
        "Manhattan":   "Commercial",
        "Brooklyn":    "Residential",
        "Queens":      "Mixed",
        "Bronx":       "Residential",
        "Staten Island": "Residential"
    }
    lines.append("// ── ZONAS (BOROUGHS) ─────────────────────────────────────────")
    for b, tipo in boroughs.items():
        bid = b.replace(" ", "_")
        lines.append(f'CREATE (:Zona {{ id: "Z_{bid}", nome: "{b}", tipo: "{tipo}", cidade: "New York City" }});')
    lines.append("")
    
    # Paragens
    lines.append(f"// ── PARAGENS ({len(stops)} estações reais do MTA) ────────────────────")
    for s in stops:
        sid = s["stop_id"].replace('"', '')
        nome = s["stop_name"].replace('"', '\\"')
        lat = float(s["stop_lat"])
        lon = float(s["stop_lon"])
        borough = get_borough(lat, lon)
        cap = 5000  # capacidade base
        afl = int(cap * 0.3)
        
        lines.append(f'CREATE (:Paragem {{ id: "{sid}", nome: "{nome}", '
                     f'lat: {lat}, lon: {lon}, '
                     f'capacidade: {cap}, afluencia_atual: {afl}, '
                     f'cidade: "New York City" }});')
    lines.append("")
    
    # Paragens → Zonas
    lines.append("// ── PARAGENS → ZONAS ────────────────────────────────────────")
    for s in stops:
        sid = s["stop_id"].replace('"', '')
        lat = float(s["stop_lat"])
        lon = float(s["stop_lon"])
        borough = get_borough(lat, lon).replace(" ", "_")
        lines.append(f'MATCH (p:Paragem {{id:"{sid}"}}),(z:Zona {{id:"Z_{borough}"}}) CREATE (p)-[:PERTENCE_A]->(z);')
    lines.append("")
    
    # Rotas
    lines.append(f"// ── ROTAS ({len(routes)} linhas reais do MTA) ────────────────────────")
    route_colors = {
        "1": "#EE352E", "2": "#EE352E", "3": "#EE352E",
        "4": "#00933C", "5": "#00933C", "6": "#00933C",
        "7": "#B933AD",
        "A": "#0039A6", "C": "#0039A6", "E": "#0039A6",
        "B": "#FF6319", "D": "#FF6319", "F": "#FF6319", "M": "#FF6319",
        "G": "#6CBE45",
        "J": "#996633", "Z": "#996633",
        "L": "#A7A9AC",
        "N": "#FCCC0A", "Q": "#FCCC0A", "R": "#FCCC0A", "W": "#FCCC0A",
        "S": "#808183",
    }
    for r in routes:
        rid = r["route_id"].replace('"', '')
        nome = r.get("route_long_name", r.get("route_short_name", rid)).replace('"', '\\"')
        short = r.get("route_short_name", rid)
        cor = route_colors.get(short, "#808183")
        lines.append(f'CREATE (:Rota {{ id: "R_{rid}", nome: "{nome}", '
                     f'tipo: "Subway", cor: "{cor}", ativa: true, '
                     f'operador: "MTA New York City Transit" }});')
    lines.append("")
    
    # Relações INCLUI (rota → paragem)
    lines.append("// ── ROTAS → PARAGENS (INCLUI) ────────────────────────────────")
    for rid, stop_seq in stop_times_by_route.items():
        for ordem, sid in enumerate(stop_seq, 1):
            sid_clean = sid.replace('"', '')
            rid_clean = rid.replace('"', '')
            lines.append(f'MATCH (r:Rota {{id:"R_{rid_clean}"}}),(p:Paragem {{id:"{sid_clean}"}}) '
                         f'MERGE (r)-[:INCLUI {{ordem:{ordem}}}]->(p);')
    lines.append("")
    
    # Ligações SERVE (entre paragens consecutivas)
    lines.append("// ── LIGAÇÕES SERVE (ENTRE ESTAÇÕES CONSECUTIVAS) ────────────")
    created = set()
    stops_map = {s["stop_id"]: s for s in stops}
    
    for rid, stop_seq in stop_times_by_route.items():
        for i in range(len(stop_seq) - 1):
            sid_a = stop_seq[i]
            sid_b = stop_seq[i + 1]
            key = tuple(sorted([sid_a, sid_b]))
            if key in created:
                continue
            created.add(key)
            
            sa = stops_map.get(sid_a)
            sb = stops_map.get(sid_b)
            if sa and sb:
                try:
                    dist = haversine(float(sa["stop_lat"]), float(sa["stop_lon"]),
                                     float(sb["stop_lat"]), float(sb["stop_lon"]))
                    tempo = max(1, int(dist / 400))
                    sa_id = sid_a.replace('"', '')
                    sb_id = sid_b.replace('"', '')
                    lines.append(f'MATCH (a:Paragem {{id:"{sa_id}"}}),(b:Paragem {{id:"{sb_id}"}}) '
                                 f'CREATE (a)-[:SERVE {{distancia:{dist}, tempo_min:{tempo}, peso:1}}]->(b);')
                except:
                    pass
    lines.append("")
    lines.append(f"// Total ligações SERVE: {len(created)}")
    
    # Eventos
    lines.append("")
    lines.append("// ── EVENTOS ──────────────────────────────────────────────────")
    events = [
        ("E1", "Knicks Game at Madison Square Garden", "Jogo",    "127N", "Z_Manhattan", "2025-06-15T19:00", "2025-06-15T23:00", 20000),
        ("E2", "Concert at Barclays Center",           "Concerto", "D24N", "Z_Brooklyn",  "2025-06-20T20:00", "2025-06-20T23:30", 19000),
        ("E3", "US Open Tennis (Willets Point)",       "Desporto", "702N", "Z_Queens",    "2025-08-25T11:00", "2025-09-07T22:00", 25000),
        ("E4", "NYE Times Square Celebration",         "Festival", "R16N", "Z_Manhattan", "2025-12-31T20:00", "2026-01-01T01:00", 100000),
        ("E5", "Yankees Game (via Grand Central)",     "Jogo",     "631N", "Z_Manhattan", "2025-07-04T13:00", "2025-07-04T17:00", 50000),
    ]
    for eid, enome, etipo, parag_id, zona_id, inicio, fim, afl in events:
        enome_esc = enome.replace('"', '\\"')
        lines.append(f'CREATE (:Evento {{ id: "{eid}", nome: "{enome_esc}", tipo: "{etipo}", '
                     f'inicio: datetime("{inicio}:00"), fim: datetime("{fim}:00"), '
                     f'afluencia_esperada: {afl}, cidade: "New York City" }});')
        # Só criar relações se as paragens existirem no dataset
        lines.append(f'MATCH (e:Evento {{id:"{eid}"}}),(p:Paragem {{id:"{parag_id}"}}) '
                     f'FOREACH (_ IN CASE WHEN p IS NOT NULL THEN [1] ELSE [] END | CREATE (e)-[:OCORRE_EM]->(p));')
        lines.append(f'MATCH (e:Evento {{id:"{eid}"}}),(z:Zona {{id:"{zona_id}"}}) CREATE (e)-[:AFETA_ZONA]->(z);')
    
    lines.append("")
    lines.append("// ── FIM DO DATASET NYC SUBWAY (MTA GTFS REAL) ───────────────")
    
    return "\n".join(lines)

# ── MAIN ──────────────────────────────────────────────────────────────────────

def main():
    print("=" * 60)
    print("  NYC Subway GTFS → Neo4j Cypher Importer")
    print("=" * 60)
    
    try:
        zip_bytes = download_gtfs()
    except Exception as e:
        print(f"\n❌ {e}")
        print("\n📋 INSTRUÇÃO MANUAL:")
        print("  1. Vai a: https://www.mta.info/developers")
        print("  2. Clica em 'GTFS' → 'NYC Subway'")
        print("  3. Descarrega o ZIP")
        print("  4. Mete o ZIP na mesma pasta que este script")
        print("  5. Corre: python gtfs_to_cypher.py --local nyct_subway.zip")
        return
    
    # Ler ficheiros GTFS
    print("A processar ficheiros GTFS...")
    
    stops_raw = read_csv_from_zip(zip_bytes, "stops.txt")
    routes_raw = read_csv_from_zip(zip_bytes, "routes.txt")
    trips_raw = read_csv_from_zip(zip_bytes, "trips.txt")
    stop_times_raw = read_csv_from_zip(zip_bytes, "stop_times.txt")
    
    print(f"  Paragens raw: {len(stops_raw)}")
    print(f"  Rotas raw: {len(routes_raw)}")
    print(f"  Trips raw: {len(trips_raw)}")
    print(f"  Stop times raw: {len(stop_times_raw)}")
    
    # Filtrar só paragens do subway (sem buses)
    # GTFS do MTA tem stops com IDs numéricos para subway
    if SUBWAY_ONLY:
        stops = [s for s in stops_raw 
                 if s.get("stop_lat") and s.get("stop_lon")
                 and float(s["stop_lat"]) != 0
                 and s.get("location_type", "0") in ["0", "1", ""]]
    else:
        stops = stops_raw
    
    if MAX_STOPS:
        stops = stops[:MAX_STOPS]
    
    print(f"  Paragens filtradas: {len(stops)}")
    
    # Mapa trip_id → route_id
    trip_to_route = {t["trip_id"]: t["route_id"] for t in trips_raw}
    
    # Construir sequência de paragens por rota
    # Agrupar stop_times por trip, ordenar por stop_sequence
    trip_stops = {}
    for st in stop_times_raw:
        tid = st["trip_id"]
        if tid not in trip_stops:
            trip_stops[tid] = []
        trip_stops[tid].append((int(st["stop_sequence"]), st["stop_id"]))
    
    # Para cada rota, pegar a trip mais representativa
    route_trips = {}
    for tid, rid in trip_to_route.items():
        if rid not in route_trips:
            route_trips[rid] = []
        route_trips[rid].append(tid)
    
    stop_times_by_route = {}
    stops_ids = {s["stop_id"] for s in stops}
    
    for rid, tids in route_trips.items():
        # Pegar a trip com mais paragens
        best_trip = max(tids, key=lambda t: len(trip_stops.get(t, [])), default=None)
        if best_trip and best_trip in trip_stops:
            seq = sorted(trip_stops[best_trip], key=lambda x: x[0])
            stop_seq = [sid for _, sid in seq if sid in stops_ids]
            if stop_seq:
                stop_times_by_route[rid] = stop_seq
    
    print(f"  Rotas com sequências: {len(stop_times_by_route)}")
    
    # Filtrar rotas para só incluir rotas com paragens
    routes = [r for r in routes_raw if r["route_id"] in stop_times_by_route]
    
    # Gerar Cypher
    print("\nA gerar Cypher...")
    cypher = generate_cypher(stops, routes, stop_times_by_route)
    
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write(cypher)
    
    print(f"\n✅ Gerado: {OUTPUT_FILE}")
    print(f"   Linhas: {cypher.count(chr(10))}")
    print(f"   Paragens: {len(stops)}")
    print(f"   Rotas: {len(routes)}")
    print(f"\n📋 PRÓXIMO PASSO:")
    print(f"   1. Abre o Neo4j Browser em http://localhost:7474")
    print(f"   2. Ativa 'Enable multi-statement query editor' nas settings")
    print(f"   3. Cola o conteúdo de '{OUTPUT_FILE}' e corre")

if __name__ == "__main__":
    import sys
    
    # Suporte para ficheiro local: python gtfs_to_cypher.py --local ficheiro.zip
    if "--local" in sys.argv:
        idx = sys.argv.index("--local")
        local_file = sys.argv[idx + 1]
        print(f"A usar ficheiro local: {local_file}")
        with open(local_file, "rb") as f:
            zip_bytes = f.read()
        
        stops_raw = read_csv_from_zip(zip_bytes, "stops.txt")
        routes_raw = read_csv_from_zip(zip_bytes, "routes.txt")
        trips_raw = read_csv_from_zip(zip_bytes, "trips.txt")
        stop_times_raw = read_csv_from_zip(zip_bytes, "stop_times.txt")
        
        stops = [s for s in stops_raw if s.get("stop_lat") and float(s.get("stop_lat", 0)) != 0]
        if MAX_STOPS:
            stops = stops[:MAX_STOPS]
        
        trip_to_route = {t["trip_id"]: t["route_id"] for t in trips_raw}
        trip_stops = {}
        for st in stop_times_raw:
            tid = st["trip_id"]
            if tid not in trip_stops:
                trip_stops[tid] = []
            trip_stops[tid].append((int(st["stop_sequence"]), st["stop_id"]))
        
        route_trips = {}
        for tid, rid in trip_to_route.items():
            if rid not in route_trips:
                route_trips[rid] = []
            route_trips[rid].append(tid)
        
        stops_ids = {s["stop_id"] for s in stops}
        stop_times_by_route = {}
        for rid, tids in route_trips.items():
            best_trip = max(tids, key=lambda t: len(trip_stops.get(t, [])), default=None)
            if best_trip and best_trip in trip_stops:
                seq = sorted(trip_stops[best_trip], key=lambda x: x[0])
                stop_seq = [sid for _, sid in seq if sid in stops_ids]
                if stop_seq:
                    stop_times_by_route[rid] = stop_seq
        
        routes = [r for r in routes_raw if r["route_id"] in stop_times_by_route]
        cypher = generate_cypher(stops, routes, stop_times_by_route)
        
        with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
            f.write(cypher)
        
        print(f"✅ {OUTPUT_FILE} gerado com {len(stops)} paragens e {len(routes)} rotas!")
    else:
        main()
