import random, json, numpy as np, glob
from PIL import Image, ImageDraw, ImageFont, ImageFilter
exec(open("gen.py").read().split("def page")[0].split("FONTS=")[0])  # imports + TEXT
TEXT=[l for l in open("gen.py").read().split("TEXT=[")[1].split("]")[0].split('",\n"')]
TEXT=[t.strip().strip('"') for t in TEXT]
fonts={n:glob.glob(__import__("os").path.expanduser(f"~/fonts/{n}/files/*-latin-400-normal.woff"))[0]  # get with: npm pack @fontsource/<name> for n in ["caveat","dancing-script","pacifico","special-elite","vt323","oswald","lobster","indie-flower","courier-prime","cinzel"]}
gt={}
for i,(n,fp) in enumerate(sorted(fonts.items())):
    for j,(sz,blur) in enumerate([(22,0.0),(17,1.1)]):
        random.seed(i*10+j); np.random.seed(i*10+j)
        f=ImageFont.truetype(fp,sz); W,H=2400,1800
        img=Image.new("L",(W,H),240); d=ImageDraw.Draw(img); y=100; lines=random.sample(TEXT,10)
        for t in lines: d.text((140,y),t,font=f,fill=25); y+=int(sz*2.0)
        a=np.asarray(img).astype(np.float32)+np.random.normal(0,6,(H,W))
        img=Image.fromarray(np.clip(a,0,255).astype(np.uint8))
        if blur: img=img.filter(ImageFilter.GaussianBlur(blur))
        name=f"hard/h_{n}_{sz}{'_blur' if blur else ''}.jpg"; img.convert("RGB").save(name,quality=82); gt[name]=lines
json.dump(gt,open("gt_hard.json","w"),indent=1); print(len(gt))
