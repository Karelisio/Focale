import math, sys
from shapely.geometry import Point, Polygon, LineString
from shapely.ops import unary_union
from PIL import Image, ImageDraw

CX, CY = 54.0, 54.0
R = 28.0       # rayon du disque
r = 9.5        # rayon de l ouverture
GAP = 2.0      # ecart
ROT = 12       # rotation globale (degrés)

def pt(rad, deg):
    a = math.radians(deg + ROT)
    return (CX + rad * math.cos(a), CY + rad * math.sin(a))

hexa = [pt(r, 60 * k) for k in range(6)]
disc = Point(CX, CY).buffer(R, resolution=96)
ring = disc.difference(Polygon(hexa).buffer(GAP / 2, join_style=2))

cuts = []
for k in range(6):
    a, b = hexa[k], hexa[(k + 1) % 6]
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy)
    end = (b[0] + dx / n * 60, b[1] + dy / n * 60)
    cuts.append(LineString([b, end]).buffer(GAP / 2, cap_style=2))
blades = ring.difference(unary_union(cuts))
geoms = [g for g in getattr(blades, "geoms", [blades]) if g.area > 1]
geoms = sorted(geoms, key=lambda g: math.atan2(g.centroid.y - CY, g.centroid.x - CX))
print("blades", len(geoms), file=sys.stderr)
# coins légèrement arrondis
geoms = [g.buffer(-0.6, join_style=1).buffer(0.6, join_style=1) for g in geoms]

def path(poly):
    coords = list(poly.exterior.coords)
    s = "M%.2f,%.2f" % coords[0]
    for x, y in coords[1:-1]:
        s += "L%.2f,%.2f" % (x, y)
    return s + "Z"

paths = [path(g.simplify(0.04)) for g in geoms]
with open(sys.argv[1], "w") as f:
    for p in paths:
        f.write(p + "\n")

S = 8
img = Image.new("RGB", (108 * S, 108 * S), (232, 96, 60))
d = ImageDraw.Draw(img)
for i, g in enumerate(geoms):
    shade = (255, 255, 255) if i % 2 == 0 else (255, 228, 218)
    d.polygon([(x * S, y * S) for x, y in g.exterior.coords], fill=shade)
d.ellipse([(54 - 33) * S, (54 - 33) * S, (54 + 33) * S, (54 + 33) * S], outline=(0, 0, 0))
img.save(sys.argv[2])
