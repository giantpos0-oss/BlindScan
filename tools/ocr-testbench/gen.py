import random, json, numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
random.seed(7); np.random.seed(7)
F="/usr/share/fonts/"
import subprocess
def font(name):
    p=subprocess.run(["fc-match","-f","%{file}",name],capture_output=True,text=True).stdout
    return p
FONTS={k:font(v) for k,v in {"serif":"DejaVu Serif","thin":"Inter:style=Thin","xlight":"DejaVu Sans:style=ExtraLight","italic":"FreeSerif:style=Italic","bolditalic":"Inter:style=Black Italic","mono":"FreeMono","cond":"DejaVu Sans Condensed","basker":"Caladea:style=Bold Italic","porson":"DejaVu Sans Mono:style=Bold Oblique","carlito":"Carlito"}.items()}
TEXT=["Take two tablets by mouth every 6 hours as needed for pain.",
"Do not exceed 8 tablets in 24 hours. Keep out of reach of children.",
"Your account balance of $1,248.37 is due on December 15, 2026.",
"Questions? Call 1-800-555-0199, Monday through Friday, 8am to 6pm.",
"Ingredients: water, sugar, citric acid, natural flavors, sodium benzoate.",
"The quick brown fox jumps over the lazy dog near the riverbank.",
"Please return the enclosed form within 30 days to avoid late fees.",
"Expiration date: 03/2027   Lot number: A47-11902-B",
"Warning: contains milk, eggs, and tree nuts (almonds, pecans).",
"Meeting moved to Thursday at 2:30 p.m. in conference room B.",
"Store at room temperature between 68 and 77 degrees Fahrenheit.",
"Visit www.example.org/help or email support@example.org today."]
def page(fontkey, size, n=8, W=2400, H=1800):
    img=Image.new("L",(W,H),245); d=ImageDraw.Draw(img); f=ImageFont.truetype(FONTS[fontkey],size)
    lines=random.sample(TEXT,n); y=120
    for t in lines:
        d.text((150,y),t,font=f,fill=20); y+=int(size*1.9)
        if y>H-120: break
    return img, lines[: (y-120)//int(size*1.9)] if False else lines, f
def degrade(img, kind):
    a=np.asarray(img).astype(np.float32)
    H,W=a.shape
    if "dim" in kind: a=a*0.35+10
    if "lowcon" in kind: a=128+(a-128)*0.35
    if "grad" in kind:
        g=np.linspace(0.3,1.1,W)[None,:]*np.linspace(1.0,0.6,H)[:,None]; a=a*g
    if "noise" in kind: a=a+np.random.normal(0,12,a.shape)
    img=Image.fromarray(np.clip(a,0,255).astype(np.uint8))
    if "blur" in kind: img=img.filter(ImageFilter.GaussianBlur(2.2))
    if "rot" in kind: img=img.rotate(4,expand=True,fillcolor=90)
    if "persp" in kind:
        import cv2
        src=np.float32([(0,0),(W,0),(W,H),(0,H)]); dst=np.float32([(160,90),(W-60,10),(W-10,H-20),(40,H-160)])
        Mx=cv2.getPerspectiveTransform(src,dst); img=Image.fromarray(cv2.warpPerspective(np.asarray(img),Mx,(W,H),borderValue=90))
    if "flip" in kind: img=img.rotate(180)
    if "side" in kind: img=img.rotate(90,expand=True)
    return img.convert("RGB")
cases=[("serif",40,""),("thin",40,""),("xlight",34,"blur"),("italic",38,"dim noise"),("bolditalic",42,"grad"),
("mono",36,"lowcon"),("cond",30,"rot"),("basker",40,"persp"),("porson",40,""),("carlito",22,""),("serif",18,""),("italic",20,"noise"),
("thin",24,"blur"),("carlito",28,"dim grad noise"),("cond",26,"persp blur"),("serif",36,"flip"),("carlito",30,"side"),("italic",26,"side noise")]
gt={}
for i,(fk,sz,k) in enumerate(cases):
    random.seed(i)
    img,lines,f=page(fk,sz)
    # keep only lines that fit
    fit=[];y=120
    for t in lines:
        if y+sz>1800-60: break
        if f.getlength(t)+150>2400: pass
        fit.append(t); y+=int(sz*1.9)
    name=f"t{i:02d}_{fk}_{sz}_{k.replace(' ','-') or 'clean'}.jpg"
    degrade(img,k).save(name,quality=85); gt[name]=fit
json.dump(gt,open("gt.json","w"),indent=1)
print(len(gt))
