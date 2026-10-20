#!/usr/bin/env python3
"""Natural Earth 50m landen (GeoJSON) -> compacte src/main/assets/landen.bin voor de landkaart in de viewer (Kaart.kt).
Vereenvoudigd met Douglas-Peucker (0,015°), piepkleine eilandjes weg; ~250 kB, ~60k punten.
Opbouw (big-endian): int16 aantal landen; per land: int16 naamlengte + utf-8 naam, 4x int16 bbox (×100: minLon, minLat,
maxLon, maxLat), int16 aantal ringen; per ring: int16 aantal punten, dan per punt int16 lon×100, int16 lat×100.
Gebruik:
    python3 landen_bin.py ne_50m_admin_0_countries.geojson src/main/assets/landen.bin
De GeoJSON komt van naturalearthdata.com (Admin 0 – Countries, 1:50m; public domain), bijv. omgezet met ogr2ogr of
gedownload als GeoJSON van github.com/nvkelso/natural-earth-vector (geojson/ne_50m_admin_0_countries.geojson)."""
import json, math, struct, sys


def dp(pts, tol):
    """Douglas-Peucker op een open lijn."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    st = [(0, len(pts) - 1)]
    while st:
        a, b = st.pop()
        ax, ay = pts[a]; bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        L = math.hypot(dx, dy) or 1e-12
        best, bi = -1, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = abs(dy * px - dx * py + bx * ay - by * ax) / L
            if d > best:
                best, bi = d, i
        if best > tol:
            keep[bi] = True
            st += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]


d = json.load(open(sys.argv[1]))
landen = []
for f in d["features"]:
    g = f["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    ringen = []
    for poly in polys:
        r = poly[0]  # buitenring; gaten (meren) doen niet mee
        xs = [p[0] for p in r]; ys = [p[1] for p in r]
        if (max(xs) - min(xs)) * (max(ys) - min(ys)) < 0.002:
            continue  # piepkleine eilandjes
        m = len(r) // 2
        s = dp(r[:m + 1], 0.015) + dp(r[m:], 0.015)[1:]  # gesloten ring: in twee helften, anders valt begin = eind samen
        if len(s) >= 4:
            ringen.append([round(v, 2) for p in s for v in p])
    if not ringen:
        continue
    alle = [v for r in ringen for v in r]
    xs = alle[0::2]; ys = alle[1::2]
    landen.append((f["properties"].get("NAME") or f["properties"].get("name") or "?", [min(xs), min(ys), max(xs), max(ys)], ringen))

uit = bytearray(struct.pack(">h", len(landen)))
punten = 0
for naam, bbox, ringen in landen:
    n = naam.encode("utf-8")
    uit += struct.pack(">h", len(n)) + n
    uit += struct.pack(">4h", *[round(v * 100) for v in bbox])
    uit += struct.pack(">h", len(ringen))
    for r in ringen:
        k = len(r) // 2
        uit += struct.pack(">h", k) + struct.pack(">%dh" % (2 * k), *[round(v * 100) for v in r])
        punten += k
open(sys.argv[2], "wb").write(uit)
print(len(landen), "landen,", punten, "punten,", len(uit), "bytes")
