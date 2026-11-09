"""Gongdu (共度) redesign mockup, direction B: clean Xiaohongshu-style, follows the app theme."""
import subprocess
from pathlib import Path

W, H = 390, 844
FONT = "Droid Sans Fallback, sans-serif"
C = dict(bg="#F6F6FA", surface="#FFFFFF", track="#ECEDF3", primary="#475A8A", primary_soft="#E6EAF6",
         ink="#1F2128", ink2="#5E6372", muted="#9A9EAB", line="#ECEDF2", green="#3FB27F", red="#FF4D5E")


def t(x, y, s, size=14, fill=C["ink"], weight=400, anchor="start"):
    return f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{s}</text>'


def phone(content, ox):
    return (f'<g transform="translate({ox},0)"><clipPath id="clip{ox}"><rect width="{W}" height="{H}" rx="34"/></clipPath>'
            f'<g clip-path="url(#clip{ox})"><rect width="{W}" height="{H}" fill="{C["bg"]}"/>{content}</g>'
            f'<rect width="{W}" height="{H}" rx="34" fill="none" stroke="#D9DBE3" stroke-width="1.5"/></g>')


def header(active):
    out = [t(20, 30, "22:43", 13, C["ink"], 600), t(370, 30, "●●● 46%", 11, C["ink2"], anchor="end")]
    # back chevron + title + subtitle, status dot on the right
    out.append(f'<path d="M30 66 l-8 8 8 8" fill="none" stroke="{C["ink"]}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>')
    out.append(t(44, 74, "共度", 22, C["ink"], 700))
    out.append(t(44, 93, "一起看，也一起聊", 12, C["muted"]))
    out.append(f'<rect x="294" y="64" width="78" height="26" rx="13" fill="{C["surface"]}" stroke="{C["line"]}"/>')
    out.append(f'<circle cx="309" cy="77" r="4" fill="{C["green"]}"/>')
    out.append(t(318, 81, "已连接", 11.5, C["ink2"]))
    # segmented pill tabs
    out.append(f'<rect x="20" y="112" width="350" height="40" rx="20" fill="{C["track"]}"/>')
    tabs = ["推荐", "搜索", "消息", "设置"]
    seg = 350 / 4
    for i, name in enumerate(tabs):
        cx = 20 + seg * i + seg / 2
        if i == active:
            out.append(f'<rect x="{20 + seg * i + 4}" y="116" width="{seg - 8}" height="32" rx="16" fill="{C["surface"]}" filter="url(#lift)"/>')
        out.append(t(cx, 137, name, 13.5, C["ink"] if i == active else C["ink2"], 700 if i == active else 400, "middle"))
    return "".join(out)


def card(x, y, w, img_h, grad, art, title, author, likes, cid):
    g = (f'<linearGradient id="g{cid}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{grad[0]}"/>'
         f'<stop offset="1" stop-color="{grad[1]}"/></linearGradient>')
    body_h = 86
    out = [f'<defs>{g}<clipPath id="c{cid}"><rect x="{x}" y="{y}" width="{w}" height="{img_h + body_h}" rx="14"/></clipPath></defs>',
           f'<rect x="{x}" y="{y}" width="{w}" height="{img_h + body_h}" rx="14" fill="{C["surface"]}" filter="url(#soft)"/>',
           f'<g clip-path="url(#c{cid})"><rect x="{x}" y="{y}" width="{w}" height="{img_h}" fill="url(#g{cid})"/>{art}</g>']
    lines = title if isinstance(title, list) else [title]
    for i, line in enumerate(lines[:2]):
        out.append(t(x + 10, y + img_h + 22 + i * 19, line, 13, C["ink"], 600))
    ay = y + img_h + body_h - 18
    out.append(f'<circle cx="{x + 18}" cy="{ay}" r="8" fill="{C["primary_soft"]}"/>')
    out.append(t(x + 18, ay + 4, author[0], 9, C["primary"], 700, "middle"))
    out.append(t(x + 31, ay + 4, author, 11, C["ink2"]))
    # heart + likes
    hx = x + w - 52
    out.append(f'<path d="M{hx} {ay - 1} c-3-4-9-1-6 3 l6 5 6-5 c3-4-3-7-6-3z" fill="none" stroke="{C["muted"]}" stroke-width="1.3"/>')
    out.append(t(hx + 10, ay + 4, likes, 11, C["muted"]))
    return "".join(out)


def recommend():
    out = [header(0)]
    out.append(t(20, 186, "一起逛逛", 18, C["ink"], 700))
    out.append(t(20, 205, "挑一篇，带回聊天", 12, C["muted"]))
    out.append(f'<rect x="300" y="170" width="70" height="30" rx="15" fill="{C["primary_soft"]}"/>')
    out.append(f'<path d="M318 180 a6 6 0 1 0 2 5" fill="none" stroke="{C["primary"]}" stroke-width="1.6" stroke-linecap="round"/><path d="M320 176 v4 h-4" fill="none" stroke="{C["primary"]}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>')
    out.append(t(328, 190, "换一批", 12, C["primary"], 600))
    cw, gap, top = 169, 12, 222
    lx, rx = 20, 20 + cw + gap
    out.append(card(lx, top, cw, 196, ("#F1F0EC", "#DCD9D2"),
                    t(lx + 14, top + 72, "char查user手机", 15, "#2B2927", 700) + t(lx + 14, top + 100, "想捉奸却发现了", 15, "#2B2927", 700)
                    + f'<path d="M{lx + 14} {top + 108} h70" stroke="#E07A3A" stroke-width="2.5" stroke-linecap="round"/>' + t(lx + 14, top + 128, "更糟糕的事", 15, "#2B2927", 700),
                    ["char查user手机", "想捉奸却翻出更糟的事"], "羽落", "2.1w", 1))
    out.append(card(rx, top, cw, 250, ("#C9C8EC", "#7F8FC4"),
                    f'<circle cx="{rx + 84}" cy="{top + 70}" r="26" fill="#ffffff66"/><rect x="{rx + 22}" y="{top + 116}" width="125" height="96" rx="14" fill="#ffffff4d"/>'
                    f'<circle cx="{rx + 64}" cy="{top + 150}" r="13" fill="#ffffff80"/><circle cx="{rx + 104}" cy="{top + 150}" r="13" fill="#ffffff80"/>',
                    ["毛玻璃恋爱小组件", "一套可以直接抄"], "Amber", "8632", 2))
    top2 = top + 196 + 86 + gap
    out.append(card(lx, top2, cw, 236, ("#EEF3E6", "#C8D9BE"),
                    f'<rect x="{lx + 14}" y="{top2 + 20}" width="62" height="74" rx="10" fill="#ffffffb3"/><rect x="{lx + 84}" y="{top2 + 20}" width="62" height="74" rx="10" fill="#ffffffb3"/>'
                    f'<rect x="{lx + 14}" y="{top2 + 108}" width="132" height="60" rx="10" fill="#ffffff99"/>' + t(lx + 22, top2 + 196, "Cute little girl", 13, "#8FA58A"),
                    ["堆堆前端美化", "手账风聊天界面"], "青山劝月", "1.4w", 3))
    top3 = top + 250 + 86 + gap
    out.append(card(rx, top3, cw, 180, ("#FBEDE6", "#F3CDBE"),
                    f'<circle cx="{rx + 84}" cy="{top3 + 96}" r="44" fill="#E8B58F"/><circle cx="{rx + 70}" cy="{top3 + 90}" r="5" fill="#3B2B22"/><circle cx="{rx + 98}" cy="{top3 + 90}" r="5" fill="#3B2B22"/>'
                    f'<path d="M{rx + 50} {top3 + 64} l8-22 12 16z M{rx + 118} {top3 + 64} l-8-22-12 16z" fill="#E8B58F"/>',
                    ["小猫第一次见雪", "原地愣住三秒"], "momo", "3.9w", 4))
    return "".join(out)


def messages():
    out = [header(2)]
    out.append(t(20, 186, "小红书消息", 18, C["ink"], 700))
    out.append(t(20, 205, "4 条新消息", 12, C["muted"]))
    out.append(f'<rect x="300" y="170" width="70" height="30" rx="15" fill="{C["primary_soft"]}"/>' + t(335, 190, "检查", 12, C["primary"], 600, "middle"))
    # toggle card
    out.append(f'<rect x="20" y="222" width="350" height="64" rx="16" fill="{C["surface"]}" filter="url(#soft)"/>')
    out.append(t(36, 250, "新消息告诉当前聊天", 14, C["ink"], 600))
    out.append(t(36, 270, "App 打开时每分钟检查一次", 11.5, C["muted"]))
    out.append(f'<rect x="312" y="240" width="44" height="26" rx="13" fill="{C["primary"]}"/><circle cx="343" cy="253" r="10" fill="#fff"/>')
    out.append(t(20, 322, "最近", 12, C["muted"], 600))
    rows = [("关注了你", "期待你的回关", "11-04 21:40", "follow", True),
            ("赞了你的笔记", "《南枳的小相册开张啦》", "11-04 20:12", "like", True),
            ("评论了你", "小鸡小鸡，知道我是谁嘛～", "11-04 19:03", "comment", True),
            ("赞了你的笔记", "《兔兔图标验收现场》", "11-03 23:51", "like", False)]
    y0, rh = 334, 74
    out.append(f'<rect x="20" y="{y0}" width="350" height="{rh * len(rows)}" rx="16" fill="{C["surface"]}" filter="url(#soft)"/>')
    for i, (act, sub, when, kind, unread) in enumerate(rows):
        y = y0 + i * rh
        if i:
            out.append(f'<path d="M78 {y} h276" stroke="{C["line"]}"/>')
        out.append(f'<circle cx="52" cy="{y + 37}" r="20" fill="#F4D9C6"/>' + t(52, y + 43, "m", 16, "#A0613D", 700, "middle"))
        badge = {"follow": (C["primary"], "+"), "like": (C["red"], "♥"), "comment": ("#F0A33A", "…")}[kind]
        out.append(f'<circle cx="66" cy="{y + 51}" r="8" fill="{badge[0]}" stroke="#fff" stroke-width="2"/>' + t(66, y + 55, badge[1], 10, "#fff", 700, "middle"))
        out.append(t(84, y + 32, f'<tspan font-weight="700">momo</tspan>  {act}', 14, C["ink"]))
        out.append(t(84, y + 53, sub, 12, C["ink2"]))
        out.append(t(352, y + 31, when, 10.5, C["muted"], anchor="end"))
        if unread:
            out.append(f'<circle cx="348" cy="{y + 50}" r="4" fill="{C["red"]}"/>')
    return "".join(out)


defs = ('<defs><filter id="soft" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="#1F2128" flood-opacity=".06"/></filter>'
        '<filter id="lift" x="-10%" y="-20%" width="120%" height="150%"><feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="#1F2128" flood-opacity=".12"/></filter></defs>')
gap = 30
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W * 2 + gap * 3}" height="{H + gap * 2}" viewBox="0 0 {W * 2 + gap * 3} {H + gap * 2}">'
       f'<rect width="100%" height="100%" fill="#E4E5EC"/>{defs}<g transform="translate(0,{gap})">'
       f'{phone(recommend(), gap)}{phone(messages(), gap * 2 + W)}</g></svg>')
out = Path(__file__).with_name("gongdu-b.svg")
out.write_text(svg, encoding="utf-8")
subprocess.run(["rsvg-convert", "-z", "2", "-o", str(out.with_suffix(".png")), str(out)], check=True)
print("rendered", out.with_suffix(".png"))
