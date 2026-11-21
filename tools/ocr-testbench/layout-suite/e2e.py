import sys, json, math, random
sys.path.insert(0, "/home/claude/layout"); sys.path.insert(0, "/home/claude/scan/test")
import layouts
from PIL import Image, ImageDraw, ImageFont
from mine import Ocr
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
rng = random.Random(4242)
o = Ocr("small")
out = open("e2e.tsv", "w", encoding="utf-8"); gt = {}
for kind, fn in layouts.LAYOUTS.items():
    for k in range(3):
        segs, g = fn(rng)
        tilt = rng.choice([0, rng.uniform(-6, 6), rng.uniform(-15, 15)])
        W, H = 1700, max(2200, int(max(s["y"] for s in segs) + 200))
        img = Image.new("RGB", (W, H), "white"); d = ImageDraw.Draw(img)
        for s in segs:
            f = s["f"] * (2.4 if s.get("cap") else 1.0)
            font = ImageFont.truetype(FONT, int(round(f)))
            d.text((s["x"] + 40, s["y"]), s["text"], fill=(20, 20, 20), font=font)
        if tilt: img = img.rotate(tilt, resample=Image.BICUBIC, fillcolor="white")
        pid = f"{kind}-{k}"
        img2, boxes = o.orient(img, 1600)
        for b in boxes:
            t, sc = o.recognize(o.crop(img2, b))
            if t and sc >= 0.5:
                out.write(f"{pid}\t{t}\t{b[0]:.2f}\t{b[1]:.2f}\t{b[2]:.2f}\t{b[3]:.2f}\t{b[4]:.5f}\n")
        gt[pid] = dict(kind=kind, tilt=tilt, curve=0, gt=g)
        if k == 0: img.save(f"{pid}.jpg", quality=85)
        print(pid, len(boxes), flush=True)
json.dump(gt, open("e2e_gt.json", "w"))
