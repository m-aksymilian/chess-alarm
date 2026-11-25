"""Convert the cburnett chess piece SVGs (lichess-org/lila public/piece/cburnett) to Android VectorDrawables.

Usage: python3 tools/svg_to_vector.py <svg-dir> app/src/main/res/drawable
Only handles what those files use: nested <g>, <path>, <circle> and presentation attributes.
"""
import os, sys
import xml.etree.ElementTree as ET

INHERIT = ["fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule"]

def color(c):
    if c is None or c == "none":
        return None
    if c.startswith("#") and len(c) == 4:
        c = "#" + "".join(ch * 2 for ch in c[1:])
    return {"black": "#000000", "white": "#FFFFFF"}.get(c, c).upper()

def walk(el, attrs, out):
    tag = el.tag.split("}")[-1]
    a = dict(attrs)
    for k in INHERIT:
        if k in el.attrib:
            a[k] = el.attrib[k]
    if tag == "path":
        out.append((el.attrib["d"], a))
    elif tag == "circle":
        cx, cy, r = (float(el.attrib[k]) for k in ("cx", "cy", "r"))
        out.append((f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0", a))
    for child in el:
        walk(child, a, out)

def convert(svg_path):
    root = ET.parse(svg_path).getroot()
    paths = []
    walk(root, {"fill": "#000", "stroke": "none", "stroke-width": "1"}, paths)
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- cburnett chess pieces by Colin M.L. Burnett, via lichess-org/lila (GPLv2+ / BSD / CC BY-SA 3.0). -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="45dp" android:height="45dp" android:viewportWidth="45" android:viewportHeight="45">']
    for d, a in paths:
        p = [f'android:pathData="{d}"']
        fc, sc = color(a.get("fill")), color(a.get("stroke"))
        if fc:
            p.append(f'android:fillColor="{fc}"')
            if a.get("fill-rule") == "evenodd":
                p.append('android:fillType="evenOdd"')
        if sc:
            p.append(f'android:strokeColor="{sc}"')
            p.append(f'android:strokeWidth="{a.get("stroke-width", "1")}"')
            p.append(f'android:strokeLineCap="{a.get("stroke-linecap", "butt")}"')
            p.append(f'android:strokeLineJoin="{a.get("stroke-linejoin", "miter")}"')
        lines.append("    <path " + "\n        ".join(p) + " />")
    lines.append("</vector>")
    return "\n".join(lines) + "\n"

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
for f in sorted(os.listdir(src)):
    if f.endswith(".svg"):
        name = "piece_" + f[0].lower() + f[1].lower()  # e.g. piece_wn
        with open(os.path.join(dst, name + ".xml"), "w") as o:
            o.write(convert(os.path.join(src, f)))
        print(name)
