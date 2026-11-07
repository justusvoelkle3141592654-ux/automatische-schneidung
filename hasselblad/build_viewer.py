#!/usr/bin/env python3
"""Erzeugt Vorher/Nachher-Vergleiche als eigenstaendige HTML-Dateien.

out/<name>_vergleich.html  - ein Bild
out/index.html             - alle Bilder aus config.json
Bilder werden als base64 eingebettet (max. 1600 px lange Seite), keine
externen Ressourcen, funktioniert offline.
"""
import base64
import html
import io
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
MAX_SIDE = 1600

CSS = """
:root{--bg:#0e0e0f;--fg:#ececec;--muted:#9a9a9a;--line:#fff}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{padding:16px;max-width:1100px;margin:0 auto}
h1{font-size:18px;font-weight:600;margin:4px 0 14px}
figure{margin:0 0 32px}
figcaption{color:var(--muted);font-size:13px;margin-top:8px;text-align:center}
figcaption a{color:var(--muted)}
.cmp{position:relative;margin:0 auto;width:min(100%,calc(84svh * var(--ar)));aspect-ratio:var(--ar);
  overflow:hidden;touch-action:pan-y;user-select:none;-webkit-user-select:none;cursor:ew-resize;background:#000;border-radius:4px}
.cmp img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;pointer-events:none;-webkit-user-drag:none}
.cmp .after{clip-path:inset(0 0 0 var(--pos))}
.bar{position:absolute;top:0;bottom:0;left:var(--pos);width:2px;margin-left:-1px;background:var(--line);
  box-shadow:0 0 4px rgba(0,0,0,.6);pointer-events:none}
.knob{position:absolute;top:50%;left:var(--pos);width:44px;height:44px;margin:-22px 0 0 -22px;border-radius:50%;
  background:rgba(14,14,15,.55);border:2px solid var(--line);display:flex;align-items:center;justify-content:center;
  outline:none;touch-action:none}
.knob:focus-visible{box-shadow:0 0 0 3px #6aa9ff}
.knob::before{content:"\\2039\\00a0\\203A";font-size:20px;line-height:1;color:var(--line);margin-top:-2px}
.lbl{position:absolute;top:10px;padding:3px 8px;font-size:12px;letter-spacing:.02em;background:rgba(0,0,0,.55);
  border-radius:3px;pointer-events:none}
.lbl.l{left:10px}.lbl.r{right:10px}
"""

JS = """
document.querySelectorAll('.cmp').forEach(function(el){
  var knob=el.querySelector('.knob'), pos=50, dragging=false;
  function set(p){pos=Math.max(0,Math.min(100,p));el.style.setProperty('--pos',pos+'%');
    knob.setAttribute('aria-valuenow',Math.round(pos));}
  function fromEvent(e){var r=el.getBoundingClientRect();set((e.clientX-r.left)/r.width*100);}
  el.addEventListener('pointerdown',function(e){if(e.button>0)return;dragging=true;
    try{el.setPointerCapture(e.pointerId);}catch(_){}fromEvent(e);knob.focus({preventScroll:true});});
  el.addEventListener('pointermove',function(e){if(dragging)fromEvent(e);});
  ['pointerup','pointercancel','lostpointercapture'].forEach(function(t){el.addEventListener(t,function(){dragging=false;});});
  knob.addEventListener('keydown',function(e){var s=e.shiftKey?10:2;
    if(e.key==='ArrowLeft'||e.key==='ArrowDown'){set(pos-s);e.preventDefault();}
    else if(e.key==='ArrowRight'||e.key==='ArrowUp'){set(pos+s);e.preventDefault();}
    else if(e.key==='Home'){set(0);e.preventDefault();}
    else if(e.key==='End'){set(100);e.preventDefault();}});
  set(50);
});
"""


def b64_jpeg(path):
    im = Image.open(path)
    icc = im.info.get("icc_profile")
    im = im.convert("RGB")
    im.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
    buf = io.BytesIO()
    kw = dict(quality=88, subsampling=0, optimize=True)
    if icc:
        kw["icc_profile"] = icc  # Farbraum (z. B. Display P3) bleibt erhalten
    im.save(buf, "JPEG", **kw)
    return base64.b64encode(buf.getvalue()).decode("ascii"), im.size


def figure(name, src, link=None):
    before, (w, h) = b64_jpeg(ROOT / src)
    after, _ = b64_jpeg(ROOT / "out" / f"{name}_hasselblad.jpg")
    n = html.escape(name)
    cap = f'<a href="{n}_vergleich.html">{n}</a>' if link else n
    return f"""<figure>
<div class="cmp" style="--ar:{w}/{h};--pos:50%">
<img class="before" alt="{n} vorher" draggable="false" src="data:image/jpeg;base64,{before}">
<img class="after" alt="{n} nachher" draggable="false" src="data:image/jpeg;base64,{after}">
<span class="lbl l">Vorher</span><span class="lbl r">Nachher</span>
<div class="bar"></div>
<div class="knob" tabindex="0" role="slider" aria-label="Vergleich {n}: Vorher/Nachher" aria-valuemin="0" aria-valuemax="100" aria-valuenow="50"></div>
</div>
<figcaption>{cap} &middot; Regler ziehen oder Pfeiltasten (Umschalt = grosse Schritte)</figcaption>
</figure>"""


def page(title, body):
    return f"""<!doctype html>
<html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark">
<title>{html.escape(title)}</title>
<style>{CSS}</style></head>
<body><main><h1>{html.escape(title)}</h1>
{body}
</main><script>{JS}</script></body></html>
"""


def main():
    cfg = json.loads((Path(__file__).with_name("config.json")).read_text())
    out = ROOT / "out"
    figs = []
    for name, ic in cfg["images"].items():
        src = ic.get("source")
        (out / f"{name}_vergleich.html").write_text(page(f"Vorher / Nachher: {name}", figure(name, src)), encoding="utf-8")
        figs.append(figure(name, src, link=True))
        print("ok", name)
    (out / "index.html").write_text(page("Vorher / Nachher", "\n".join(figs)), encoding="utf-8")


if __name__ == "__main__":
    main()
