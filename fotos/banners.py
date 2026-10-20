#!/usr/bin/env python3
"""Banners (640×360) en iconen (192×192) voor de uiterlijk-varianten (activity-aliassen in het manifest), in één stijl:
donker kleurverloop, witomrande fotokaartjes, grote witte titel. De titel staat per taal in TITELS; per taal komt er
een drawable-map (drawable-nodpi = Engels/standaard, drawable-nl-nodpi, …), de iconen hebben geen tekst.
De hoofdbanner ("Photos") gebruikt de tekstloze bron banners_bron/fotos_zonder_tekst.png (het motief en het verloop
zijn daar al getekend); het bijbehorende icoon (ic_fotos.png) blijft zoals het is.
Draaien (PIL nodig):
    python3 banners.py [src/main/res]
Eigen ontwerpen (geen Immich-logo of ander merk)."""
import math, os, sys
from PIL import Image, ImageDraw, ImageFont

S = 4  # supersampling
RES = sys.argv[1] if len(sys.argv) > 1 else "src/main/res"
FONT = os.path.join(RES, "font/overpass.ttf")
BRON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "banners_bron")

# titels per taal; "" = de standaardmap (Engels), de rest = taalcode in de mapnaam (drawable-<taal>-nodpi)
TITELS = {
    "": {"fotos": "Photos", "album": "Photo album", "herinneringen": "Memories", "familie": "Family photos"},
    "nl": {"fotos": "Foto’s", "album": "Fotoalbum", "herinneringen": "Herinneringen", "familie": "Familiefoto’s"},
    "de": {"fotos": "Fotos", "album": "Fotoalbum", "herinneringen": "Erinnerungen", "familie": "Familienfotos"},
    "fr": {"fotos": "Photos", "album": "Album photo", "herinneringen": "Souvenirs", "familie": "Photos de famille"},
    "es": {"fotos": "Fotos", "album": "Álbum de fotos", "herinneringen": "Recuerdos", "familie": "Fotos familiares"},
}


def font(px):
    try:
        f = ImageFont.truetype(FONT, px)
        f.set_variation_by_axes([700])
        return f
    except Exception:
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", px)


def verloop(w, h, links, rechts, vanaf=0.35):
    im = Image.new("RGB", (w, h))
    px = im.load()
    for x in range(w):
        t = min(1.0, max(0.0, (x / w - vanaf) / (1 - vanaf)))  # links effen, vanaf [vanaf] naar donker
        t = t * t
        c = tuple(int(links[i] * (1 - t) + rechts[i] * t) for i in range(3))
        for y in range(h):
            px[x, y] = c
    return im


def foto(d, x, y, w, h, hoek, lucht=(176, 206, 246), berg=(34, 40, 48), rand=14, zon=True):
    """Witomrand fotokaartje met lucht, zon en bergen, gedraaid om zijn middelpunt (alles in supersample-px)."""
    laag = Image.new("RGBA", (w + 2 * rand + 40, h + 2 * rand + 40), (0, 0, 0, 0))
    ld = ImageDraw.Draw(laag)
    ox, oy = 20, 20
    ld.rounded_rectangle([ox, oy, ox + w + 2 * rand, oy + h + 2 * rand], radius=10 * S, fill=(255, 255, 255, 255))
    ld.rounded_rectangle([ox + rand, oy + rand, ox + rand + w, oy + rand + h], radius=4 * S, fill=lucht + (255,))
    if zon:
        r = int(h * 0.11)
        ld.ellipse([ox + rand + w * 0.72 - r, oy + rand + h * 0.22 - r, ox + rand + w * 0.72 + r, oy + rand + h * 0.22 + r], fill=(255, 248, 225, 255))
    b = oy + rand + h
    ld.polygon([(ox + rand, b), (ox + rand + w * 0.33, oy + rand + h * 0.42), (ox + rand + w * 0.62, b)], fill=berg + (255,))
    ld.polygon([(ox + rand + w * 0.4, b), (ox + rand + w * 0.7, oy + rand + h * 0.55), (ox + rand + w, b)], fill=tuple(min(255, c + 30) for c in berg) + (255,))
    laag = laag.rotate(hoek, resample=Image.BICUBIC, expand=True)
    return laag, (int(x - laag.width / 2), int(y - laag.height / 2))


def ster(d, cx, cy, r, kleur):
    pts = []
    for i in range(8):
        a = math.pi / 4 * i - math.pi / 2
        rr = r if i % 2 == 0 else r * 0.38
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=kleur)


def hart(d, cx, cy, r, kleur):
    pts = []
    for i in range(200):
        t = 2 * math.pi * i / 200
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((cx + x * r / 16, cy + y * r / 16))
    d.polygon(pts, fill=kleur)


def titel_laag(w, h, titel, x):
    """Titel op een transparante laag (supersample), links uitgelijnd op [x]; lettergrootte zó dat hij past."""
    laag = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(laag)
    maat = 78 * S
    while True:
        f = font(int(maat))
        if d.textlength(titel, font=f) <= w - 24 * S - x or maat < 30 * S:
            break
        maat *= 0.94
    d.text((x, h / 2), titel, font=f, fill=(255, 255, 255, 255), anchor="lm")
    return laag.resize((w // S, h // S), Image.LANCZOS)


def bewaar(im, taal, naam):
    map_ = os.path.join(RES, "drawable-%snodpi" % (taal + "-" if taal else ""))
    os.makedirs(map_, exist_ok=True)
    im.convert("RGB").save(os.path.join(map_, naam), optimize=True)


def banner(naam, links, rechts, motief):
    w, h = 640 * S, 360 * S
    basis = verloop(w, h, links, rechts).convert("RGBA")
    motief(basis, w, h, cx=140 * S, cy=180 * S, schaal=0.8)
    basis = basis.resize((640, 360), Image.LANCZOS)
    for taal, titels in TITELS.items():
        im = basis.copy()
        im.alpha_composite(titel_laag(w, h, titels[naam], 285 * S))
        bewaar(im, taal, "banner_%s.png" % naam)
    # icoon (zonder tekst): zelfde motief, gecentreerd op het effen deel van het verloop
    ik = verloop(192 * S, 192 * S, links, rechts, vanaf=0.55).convert("RGBA")
    motief(ik, 192 * S, 192 * S, cx=96 * S, cy=96 * S, schaal=0.62)
    ik = ik.convert("RGB").resize((192, 192), Image.LANCZOS)
    ik.save(os.path.join(RES, "mipmap-xhdpi", "ic_%s.png" % naam), optimize=True)


def hoofdbanner():
    """banner.png (standaardvariant "Photos"): tekstloze bron + titel per taal."""
    bron = Image.open(os.path.join(BRON, "fotos_zonder_tekst.png")).convert("RGBA")
    for taal, titels in TITELS.items():
        im = bron.copy()
        im.alpha_composite(titel_laag(640 * S, 360 * S, titels["fotos"], 325 * S))
        bewaar(im, taal, "banner.png")


def m_album(im, w, h, cx, cy, schaal=1.0):
    """Opengeslagen album: twee pagina's met elk een foto, ruggetje in het midden."""
    d = ImageDraw.Draw(im)
    pw, ph = int(150 * S * schaal), int(190 * S * schaal)
    for kant, hoek in ((-1, 6), (1, -6)):
        laag = Image.new("RGBA", (pw + 40, ph + 40), (0, 0, 0, 0))
        ld = ImageDraw.Draw(laag)
        ld.rounded_rectangle([20, 20, 20 + pw, 20 + ph], radius=8 * S, fill=(250, 244, 232, 255))
        laag = laag.rotate(hoek, resample=Image.BICUBIC, expand=True)
        im.alpha_composite(laag, (int(cx + kant * pw * 0.52 - laag.width / 2), int(cy - laag.height / 2)))
    for kant, hoek, lucht in ((-1, 6, (176, 206, 246)), (1, -6, (250, 200, 150))):
        laag, pos = foto(d, cx + kant * pw * 0.52, cy - ph * 0.1, int(pw * 0.72), int(pw * 0.55), hoek, lucht=lucht, rand=8, zon=kant > 0)
        im.alpha_composite(laag, pos)
    d.rounded_rectangle([cx - 5 * S * schaal, cy - ph * 0.48, cx + 5 * S * schaal, cy + ph * 0.48], radius=4 * S, fill=(120, 60, 30, 255))


def m_herinneringen(im, w, h, cx, cy, schaal=1.0):
    """Eén fotokaartje, iets gedraaid, met een gouden ster op de hoek."""
    d = ImageDraw.Draw(im)
    pw, ph = int(200 * S * schaal), int(150 * S * schaal)
    laag, pos = foto(d, cx, cy + 10 * S, pw, ph, 8, lucht=(214, 196, 250), berg=(60, 34, 90))
    im.alpha_composite(laag, pos)
    d = ImageDraw.Draw(im)
    ster(d, cx + pw * 0.5, cy - ph * 0.5, 34 * S * schaal, (255, 214, 102))
    ster(d, cx + pw * 0.68, cy - ph * 0.15, 16 * S * schaal, (255, 236, 180))


def m_familie(im, w, h, cx, cy, schaal=1.0):
    """Twee gestapelde fotokaartjes met een rood hart."""
    d = ImageDraw.Draw(im)
    pw, ph = int(190 * S * schaal), int(140 * S * schaal)
    laag, pos = foto(d, cx - 14 * S, cy - 8 * S, pw, ph, 9, lucht=(200, 236, 210), berg=(28, 70, 48), zon=False)
    im.alpha_composite(laag, pos)
    laag, pos = foto(d, cx + 12 * S, cy + 12 * S, pw, ph, -5, lucht=(180, 220, 250), berg=(30, 60, 70))
    im.alpha_composite(laag, pos)
    d = ImageDraw.Draw(im)
    hart(d, cx + pw * 0.42, cy + ph * 0.42, 30 * S * schaal, (232, 64, 84))
    hart(d, cx + pw * 0.42, cy + ph * 0.42 - 3 * S, 24 * S * schaal, (250, 90, 110))


hoofdbanner()
banner("album", (122, 52, 18), (14, 8, 4), m_album)
banner("herinneringen", (62, 30, 110), (10, 6, 20), m_herinneringen)
banner("familie", (18, 76, 48), (5, 20, 12), m_familie)
print("klaar")
