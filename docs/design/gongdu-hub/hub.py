"""共度大厅 coverflow mockup (light + dark). Creature art here is hand-drawn placeholder line work;
the app version will use the generated illustrations."""
import subprocess
from pathlib import Path

W, H = 390, 844
SANS = "Droid Sans Fallback, sans-serif"
LIGHT = dict(bg="#EFECE6", card="#FBFAF8", ink="#2B2927", soft="#6E675F", muted="#9A938A", line="#8C847A",
             hair="#D8D2C8", dot="#C9C2B7", glow="#FFFFFF")
DARK = dict(bg="#1C1B19", card="#2A2825", ink="#EDE6DA", soft="#BDB4A6", muted="#8E867B", line="#CFC6B8",
            hair="#4A463F", dot="#4F4A43", glow="#2E2C29")


def t(x, y, s, size, fill, weight=400, anchor="start", spacing=0, family=SANS):
    return (f'<text x="{x}" y="{y}" font-family="{family}" font-size="{size}" font-weight="{weight}" fill="{fill}" '
            f'text-anchor="{anchor}" letter-spacing="{spacing}">{s}</text>')


def octopus(c):
    s = f'fill="none" stroke="{c}" stroke-width="1.15" stroke-linecap="round" stroke-linejoin="round"'
    tentacles = ["M44 52 C36 62 26 66 22 76 C19 84 27 88 31 83 C34 79 30 76 28 79",
                 "M47 54 C44 66 38 74 37 84 C36 92 44 94 46 89 C47 85 43 84 42 87",
                 "M53 55 C54 67 52 77 55 86 C57 92 64 91 63 86 C62 83 58 84 59 87",
                 "M56 53 C63 62 72 66 76 75 C79 83 72 87 69 83 C67 80 71 77 72 80",
                 "M42 49 C32 54 20 52 14 58 C9 63 14 69 18 66 C21 63 18 61 16 63",
                 "M58 49 C68 53 80 50 86 56 C91 61 87 67 83 64 C80 62 83 59 85 61"]
    body = "M50 12 C64 12 70 24 69 34 C68 43 62 49 58 52 C54 55 46 55 42 52 C38 49 32 43 31 34 C30 24 36 12 50 12 Z"
    eyes = "M42 38 q3 -2.4 6 0 M52 38 q3 -2.4 6 0"
    suckers = "".join(f'<circle cx="{x}" cy="{y}" r="0.9" {s}/>' for x, y in [(27, 72), (33, 82), (40, 78), (40, 86), (56, 79), (58, 84), (70, 72), (73, 78)])
    return f'<path d="{body}" {s}/><path d="{eyes}" {s}/>' + "".join(f'<path d="{p}" {s}/>' for p in tentacles) + suckers


def fish(c):
    s = f'fill="none" stroke="{c}" stroke-width="1.15" stroke-linecap="round" stroke-linejoin="round"'
    body = "M18 50 C26 36 46 32 60 40 C66 44 69 48 70 50 C69 52 66 56 60 60 C46 68 26 64 18 50 Z"
    tail = "M68 50 C76 38 86 30 92 26 C88 38 86 44 88 50 C86 56 88 62 92 74 C86 70 76 62 68 50"
    tail_lines = "M74 47 C80 40 86 34 90 30 M74 53 C80 60 86 66 90 70 M76 50 C82 50 86 50 88 50"
    fins = "M40 37 C44 26 52 22 58 24 C56 30 52 35 48 38 M42 63 C44 72 50 76 54 75 C52 70 50 66 47 63"
    gill = "M30 42 C27 46 27 54 30 58"
    scales = "M38 46 q3 3 0 6 M44 44 q3 3 0 6 M50 45 q3 3 0 6 M44 52 q3 3 0 6 M50 52 q3 3 0 6 M56 48 q3 3 0 6"
    eye = f'<circle cx="25" cy="48" r="1.6" {s}/>'
    return "".join(f'<path d="{p}" {s}/>' for p in [body, tail, tail_lines, fins, gill, scales]) + eye


def jelly(c):
    s = f'fill="none" stroke="{c}" stroke-width="1.15" stroke-linecap="round" stroke-linejoin="round"'
    bell = "M24 38 C24 18 38 8 50 8 C62 8 76 18 76 38 C72 40 69 37 66 40 C63 37 60 40 56 39 C53 41 47 41 44 39 C40 40 37 37 34 40 C31 37 28 40 24 38 Z"
    ribs = "M50 9 C49 20 49 30 50 40 M38 13 C35 22 34 30 35 39 M62 13 C65 22 66 30 65 39 M30 22 C28 28 28 33 29 38 M70 22 C72 28 72 33 71 38"
    inner = "M38 34 C42 30 46 31 50 34 C54 31 58 30 62 34"
    arms = ["M42 41 C40 52 46 58 43 68 C40 78 46 84 44 94", "M50 42 C49 54 54 62 51 74 C49 84 53 90 52 98",
            "M58 41 C60 52 54 58 57 68 C60 78 54 84 56 94"]
    threads = ["M28 39 C26 52 30 66 27 82", "M35 41 C34 56 37 70 35 86", "M65 41 C66 56 63 70 65 86", "M72 39 C74 52 70 66 73 82"]
    return ("".join(f'<path d="{p}" {s}/>' for p in [bell, ribs, inner] + arms)
            + "".join(f'<path d="{p}" fill="none" stroke="{c}" stroke-width="0.7" stroke-linecap="round" opacity=".75"/>' for p in threads))


def card(p, cx, cy, w, h, scale, rot, opacity, room):
    num, name, sub, art, locked = room
    x, y = -w / 2, -h / 2
    out = [f'<g transform="translate({cx},{cy}) rotate({rot}) scale({scale})" opacity="{opacity}">',
           f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{p["card"]}" filter="url(#cardShadow)"/>',
           f'<rect x="{x + 10}" y="{y + 10}" width="{w - 20}" height="{h - 20}" rx="9" fill="none" stroke="{p["hair"]}" stroke-width=".8"/>']
    # art area
    ah = h * 0.56
    out.append(t(x + 22, y + 32, f"No.{num}", 10, p["muted"], spacing=1.5))
    out.append(t(x + w - 22, y + 32, "SPECIMEN", 9, p["muted"], anchor="end", spacing=1.8))
    if art:
        size = w * 0.66
        out.append(f'<g transform="translate({-size / 2},{y + 40 + (ah - size) / 2}) scale({size / 100})">{art(p["line"])}</g>')
    else:
        out.append(f'<circle cx="0" cy="{y + 40 + ah / 2}" r="{w * 0.18}" fill="none" stroke="{p["hair"]}" stroke-width="1" stroke-dasharray="3 4"/>')
        out.append(t(0, y + 46 + ah / 2, "?", 26, p["hair"], anchor="middle"))
    ty = y + 40 + ah + 18
    out.append(f'<path d="M{x + 22} {ty} h{w - 44}" stroke="{p["hair"]}" stroke-width=".8"/>')
    out.append(t(x + 22, ty + 36, name, 22, p["muted"] if locked else p["ink"], 700))
    out.append(t(x + 22, ty + 58, sub, 9.5, p["muted"], spacing=1.6))
    out.append(t(x + w - 22, y + h - 24, "筹备中" if locked else "轻点进入 →", 11, p["muted"] if locked else p["soft"], anchor="end"))
    out.append("</g>")
    return "".join(out)


def phone(p, ox, label):
    rooms = [("I", "一起逛", "LOOK · CHAT · SHARE", octopus, False),
             ("II", "一起读", "READ · NOTE · TALK", fish, False),
             ("III", "一起听", "LISTEN · LYRICS · MOOD", jelly, False),
             ("IV", "下一间", "COMING SOON", None, True)]
    out = [f'<g transform="translate({ox},0)"><clipPath id="clip{ox}"><rect width="{W}" height="{H}" rx="34"/></clipPath>',
           f'<g clip-path="url(#clip{ox})"><rect width="{W}" height="{H}" fill="{p["bg"]}"/>',
           f'<radialGradient id="glow{ox}" cx="50%" cy="8%" r="75%"><stop offset="0" stop-color="{p["glow"]}" stop-opacity=".7"/><stop offset="1" stop-color="{p["glow"]}" stop-opacity="0"/></radialGradient>',
           f'<rect width="{W}" height="{H}" fill="url(#glow{ox})"/>',
           t(20, 30, "23:38", 13, p["ink"], 600), t(370, 30, "●●● 41%", 11, p["soft"], anchor="end"),
           t(24, 84, "// GONGDU INDEX · OURS", 10.5, p["muted"], spacing=1.6),
           t(24, 124, "共度", 34, p["ink"], 700),
           t(24, 150, "每一张牌，通往一件一起做的事", 13, p["soft"]),
           f'<path d="M24 172 h28" stroke="{p["ink"]}" stroke-width="1.6" stroke-linecap="round"/>',
           t(366, 124, "02 / 04", 11, p["muted"], anchor="end", spacing=1.2)]
    cy, w, h = 452, 236, 392
    out.append(card(p, 452, cy + 12, w, h, 0.8, 7, .35, rooms[3]))   # far right peek
    out.append(card(p, -62, cy + 12, w, h, 0.8, -7, .35, rooms[2]))   # far left peek
    out.append(card(p, 38, cy, w, h, 0.86, -5, .62, rooms[0]))
    out.append(card(p, 352, cy, w, h, 0.86, 5, .62, rooms[2]))
    out.append(card(p, 195, cy - 6, w, h, 1, 0, 1, rooms[1]))
    dots_y = 694
    out.append(f'<circle cx="171" cy="{dots_y}" r="3" fill="{p["dot"]}"/>')
    out.append(f'<rect x="183" y="{dots_y - 3}" width="22" height="6" rx="3" fill="{p["ink"]}"/>')
    out.append(f'<circle cx="217" cy="{dots_y}" r="3" fill="{p["dot"]}"/>')
    out.append(f'<circle cx="229" cy="{dots_y}" r="3" fill="{p["dot"]}" opacity=".5"/>')
    out.append(t(195, 736, "左右滑动挑一间 · 轻点进入", 12, p["muted"], anchor="middle", spacing=1))
    out.append(t(195, 812, label, 10, p["muted"], anchor="middle", spacing=1.5))
    out.append(f'</g><rect width="{W}" height="{H}" rx="34" fill="none" stroke="#C9C6BF" stroke-width="1.5"/></g>')
    return "".join(out)


gap = 30
defs = ('<defs><filter id="cardShadow" x="-20%" y="-15%" width="140%" height="135%">'
        '<feDropShadow dx="0" dy="10" stdDeviation="12" flood-color="#2B2927" flood-opacity=".14"/></filter></defs>')
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W * 2 + gap * 3}" height="{H + gap * 2}">'
       f'<rect width="100%" height="100%" fill="#DEDCD7"/>{defs}<g transform="translate(0,{gap})">'
       f'{phone(LIGHT, gap, "浅色")}{phone(DARK, gap * 2 + W, "深色")}</g></svg>')
out = Path(__file__).with_name("gongdu-hub.svg")
out.write_text(svg, encoding="utf-8")
subprocess.run(["rsvg-convert", "-z", "2", "-o", str(out.with_suffix(".png")), str(out)], check=True)
print("rendered")
