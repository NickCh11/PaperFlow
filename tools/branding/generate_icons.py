"""Generate the PaperFlow vector/adaptive and legacy launcher artwork.

Developer-only requirements: Pillow and aggdraw. No bitmap dependency is added to the app.
"""
from pathlib import Path
import re
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "build/icon-tools"))
import aggdraw
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
BRANDING = ROOT / "branding"
OUT = ROOT / "artifacts/branding"

# Coordinates use Android's 108dp adaptive layer. The visible mask covers the
# central 72dp; the document stays within the central safe area.
BACK = "M43 32 L69 32 C74 32 78 36 78 41 L78 74 C78 79 75 82 70 82 L44 82 C40 82 37 79 37 75 L37 39 C37 35 39 32 43 32 Z"
PAPER = "M38 25 L58 25 C60 25 61 26 63 28 L70 35 C72 37 73 39 73 42 L73 66 C73 69 71 71 68 72 C60 75 56 79 47 79 L38 79 C33 79 30 76 30 71 L30 33 C30 28 33 25 38 25 Z"
FOLD = "M59 26 L59 35 C59 39 61 41 65 41 L73 41 C72 38 71 37 69 35 L63 29 C62 28 60 26 59 26 Z"
LINE1 = "M41 46 L54 46 C55.2 46 56 46.8 56 48 C56 49.2 55.2 50 54 50 L41 50 C39.8 50 39 49.2 39 48 C39 46.8 39.8 46 41 46 Z"
LINE2 = "M41 55 L62 55 C63.2 55 64 55.8 64 57 C64 58.2 63.2 59 62 59 L41 59 C39.8 59 39 58.2 39 57 C39 55.8 39.8 55 41 55 Z"
FLOW = "M40 67 C45 67 47 65 51 64 C55 62.5 59 63 63 63 C64.2 63 65 63.8 65 65 C65 66.2 64.2 67 63 67 C58 67 56 66.5 52 68 C48 69.5 45 71 40 71 C38.8 71 38 70.2 38 69 C38 67.8 38.8 67 40 67 Z"
MONO_BACK = "M76 40 C77.2 40 78 41 78 43 L78 74 C78 79 75 82 70 82 L44 82 C42 82 41 81 41 79 L69 79 C73 79 75 77 75 73 L75 43 C75 41 75.2 40 76 40 Z"
MONO_FOLD = "M62 31 L69 38 L65 38 C63 38 62 37 62 35 Z"
MONO = PAPER + " " + MONO_FOLD + " " + LINE1 + " " + LINE2 + " " + FLOW
COLORS = [(BACK, "#E8A478"), (PAPER, "#FFF9F0"), (FOLD, "#ECC8A8"),
          (LINE1, "#984321"), (LINE2, "#984321"), (FLOW, "#984321")]


def vector(paths, even_odd=False):
    body = []
    for path, color in paths:
        fill = ' android:fillType="evenOdd"' if even_odd else ""
        body.append(f'    <path android:fillColor="{color}"{fill} android:pathData="{path}" />')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n' +
            "\n".join(body) + '\n</vector>\n')


def svg(monochrome=False):
    if monochrome:
        body = f'<path fill="#000" d="{MONO_BACK}"/><path fill="#000" fill-rule="evenodd" d="{MONO}"/>'
    else:
        body = ('<defs><linearGradient id="brand" x1="0" y1="0" x2="1" y2="1">'
                '<stop stop-color="#C06B44"/><stop offset="1" stop-color="#80371F"/>'
                '</linearGradient></defs><path fill="url(#brand)" d="M0 0H108V108H0Z"/>' +
                ''.join(f'<path fill="{color}" d="{path}"/>' for path,color in COLORS))
    return '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">' + body + '</svg>\n'


def draw_path(image, data, color, scale):
    tokens = re.findall(r"[MLCZ]|-?\d+(?:\.\d+)?", data)
    path = aggdraw.Path(); index = 0
    while index < len(tokens):
        command = tokens[index]; index += 1
        count = {"M":2,"L":2,"C":6,"Z":0}[command]
        values = [float(value)*scale for value in tokens[index:index+count]];index += count
        if command == "M":path.moveto(*values)
        elif command == "L":path.lineto(*values)
        elif command == "C":path.curveto(*values)
        else:path.close()
    context=aggdraw.Draw(image);context.path(path,None,aggdraw.Brush(color));context.flush()


def artwork():
    size=864;scale=size/108
    image=Image.new("RGB",(size,size));pixels=image.load()
    start=(192,107,68);end=(128,55,31)
    for y in range(size):
        for x in range(size):
            t=(x+y)/(2*(size-1));pixels[x,y]=tuple(round(a+(b-a)*t) for a,b in zip(start,end))
    for path,color in COLORS:draw_path(image,path,color,scale)
    return image.crop((144,144,720,720)).convert("RGBA")


def masked(image,size,round_icon=False):
    image=image.resize((size,size),Image.Resampling.LANCZOS)
    large=size*4;mask=Image.new("L",(large,large));draw=ImageDraw.Draw(mask)
    if round_icon:draw.ellipse((0,0,large-1,large-1),fill=255)
    else:draw.rounded_rectangle((0,0,large-1,large-1),radius=large*.23,fill=255)
    image.putalpha(mask.resize((size,size),Image.Resampling.LANCZOS));return image


def main():
    BRANDING.mkdir(exist_ok=True);OUT.mkdir(parents=True,exist_ok=True)
    (RES/"drawable/ic_launcher_foreground.xml").write_text(vector(COLORS),encoding="utf-8",newline="\n")
    (RES/"drawable/ic_launcher_monochrome.xml").write_text(vector([(MONO_BACK,"#FFFFFF"),(MONO,"#FFFFFF")],True),encoding="utf-8",newline="\n")
    (RES/"drawable/ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        '    <gradient android:angle="315" android:startColor="#C06B44" android:endColor="#80371F" />\n</shape>\n',encoding="utf-8",newline="\n")
    for name in ("ic_launcher.xml","ic_launcher_round.xml"):
        (RES/"mipmap-anydpi-v26"/name).write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n',encoding="utf-8",newline="\n")
    (BRANDING/"paperflow-icon.svg").write_text(svg(),encoding="utf-8",newline="\n")
    (BRANDING/"paperflow-icon-monochrome.svg").write_text(svg(True),encoding="utf-8",newline="\n")
    image=artwork();image.convert("RGB").resize((512,512),Image.Resampling.LANCZOS).save(BRANDING/"paperflow-play-store.png")
    for density,size in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
        for round_icon in (False,True):
            name="ic_launcher_round.webp" if round_icon else "ic_launcher.webp"
            masked(image,size,round_icon).save(RES/f"mipmap-{density}"/name,lossless=True)
    preview=Image.new("RGB",(1120,640),"#FAF7F2")
    preview.paste(masked(image,420),(60,60),masked(image,420))
    preview.paste(masked(image,260,True),(650,70),masked(image,260,True))
    for index,size in enumerate((96,64,48)):
        icon=masked(image,size);preview.paste(icon,(625+index*145,400),icon)
    draw=ImageDraw.Draw(preview)
    try:font=ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf",40)
    except OSError:font=ImageFont.load_default()
    draw.text((60,520),"PaperFlow",font=font,fill="#30251F")
    preview.save(OUT/"paperflow-icon-preview.png")
    mask=Image.new("RGB",(864,864),"#000000")
    for path in (MONO_BACK,PAPER):draw_path(mask,path,"#FFFFFF",8)
    for path in (MONO_FOLD,LINE1,LINE2,FLOW):draw_path(mask,path,"#000000",8)
    mask=mask.crop((144,144,720,720)).convert("L").resize((240,240),Image.Resampling.LANCZOS)
    themed=Image.new("RGB",(580,300),"#FAF7F2")
    for index,(background,foreground) in enumerate((("#E7D8C9","#51382C"),("#38271F","#F0BB96"))):
        tile=Image.new("RGB",(240,240),background)
        tile.paste(Image.new("RGB",tile.size,foreground),(0,0),mask)
        edge=Image.new("L",tile.size);ImageDraw.Draw(edge).ellipse((0,0,239,239),fill=255)
        themed.paste(tile,(30+index*280,30),edge)
    themed.save(OUT/"paperflow-themed-preview.png")
    print("Generated adaptive color/monochrome, 10 density icons, SVG sources and Play Store PNG.")


if __name__=="__main__":main()
