"""
Synthetic page layouts with known reading order, for testing BlindScan's ReadingOrder.

Each generator returns (segments, gt) where
  segments: list of dict(text, x, y, f)  -- one printed run of text: left x, top y, font size f (px)
  gt:       list of paragraphs (strings) in the correct reading order.
A segment becomes one detector box. Lines that the real detector would split (big gaps, table
cells, prices far from item names) are already separate segments.
"""
import random, math

WORDS = ("the of and to in is that for it with as was on be at by this had not are but from or have an they which "
 "one you were all we when there can more if no out so what up said about into than them only other new some could time "
 "these two may first then do any like my now over such our man me even most made after also did many before must through "
 "back years where much your way well down should because each just those people how too little state good very make world "
 "still own see men work long get here between both life being under never day same another know while last might us great "
 "old year off come since against go came right used take three himself few house use during without again place around "
 "however home small found thought went say part once general high upon school every does got united left number course war "
 "until always away something fact though water less public put think almost hand enough far took head yet government system "
 "better set told nothing night end why called didn't eyes find going look asked later knew point next program city business "
 "give group toward young days let room president side social given present several order national possible rather second face "
 "per among form important often things looked early white case john become large big need four within felt along children saw "
 "best church ever least power development light thing seemed family interest want members mind country area others done turned "
 "although open problem fine community students stadium music band season tickets service health county street library").split()
NAMES = ["Smith","Johnson","Garcia","Walsh","Nguyen","Patel","Lee","Brown","Davis","Martinez","Wilson","Moore"]
ITEMS = ["Milk","Bread","Eggs","Apples","Coffee","Chicken","Rice","Cheese","Butter","Bananas","Yogurt","Pasta","Soap","Tissues"]
DISHES = ["Grilled Salmon","Chicken Alfredo","Garden Salad","Beef Tacos","Veggie Burger","Tomato Soup","Fish and Chips","Pancakes"]
CW = 0.52  # average character width, in font sizes

def sentence(rng, n=None):
    n = n or rng.randint(6, 16)
    w = [rng.choice(WORDS) for _ in range(n)]
    if rng.random() < 0.3: w[rng.randrange(n)] = rng.choice(NAMES)
    if rng.random() < 0.2: w[rng.randrange(n)] = str(rng.randint(2, 2030))
    s = " ".join(w); return s[0].upper() + s[1:] + rng.choice([".", ".", ".", "?", "!"])
def paragraph(rng, k=None): return " ".join(sentence(rng) for _ in range(k or rng.randint(2, 5)))
def title(rng, n=None):
    return " ".join(w.capitalize() for w in (rng.choice(WORDS) for _ in range(n or rng.randint(2, 6))))
def width(text, f): return len(text) * CW * f

def wrap(text, max_w, f, first_indent=0.0):
    lines, cur = [], ""
    for word in text.split():
        trial = (cur + " " + word).strip()
        limit = max_w - (first_indent if not lines else 0)
        if cur and width(trial, f) > limit: lines.append(cur); cur = word
        else: cur = trial
    if cur: lines.append(cur)
    return lines

def flow(rng, paras, x, y, col_w, f, pitch, segs, indent=True, max_y=None):
    """Lay paragraphs into a column; returns new y. Paragraph gap is a blank-ish space."""
    for p in paras:
        ind = 2 * CW * f if indent else 0
        for i, line in enumerate(wrap(p, col_w, f, ind)):
            segs.append(dict(text=line, x=x + (ind if i == 0 else 0), y=y, f=f)); y += pitch
        y += pitch * (0.15 if indent else 0.7)
    return y

# ---------------------------------------------------------------- layouts
def single_column(rng):
    segs, gt = [], []; f = rng.randint(22, 32); pitch = f * rng.uniform(1.25, 1.5); x = 140; col = 1400; y = 120
    head = title(rng); hf = int(f * rng.uniform(1.8, 2.6)); segs.append(dict(text=head, x=x, y=y, f=hf)); gt.append(head); y += hf * 1.6
    if rng.random() < 0.5:
        by = "By " + rng.choice(NAMES).upper() + " " + rng.choice(NAMES).upper(); segs.append(dict(text=by, x=x, y=y, f=f)); gt.append(by); y += pitch * 1.6
    paras = [paragraph(rng) for _ in range(rng.randint(2, 4))]
    if rng.random() < 0.4:  # drop cap
        first = paras[0]; cap, rest = first[0], first[1:]
        lines = wrap(rest, col - 2.5 * f, f)
        segs.append(dict(text=cap, x=x, y=y, f=pitch * 2.9, cap=True))
        for i, l in enumerate(lines):
            segs.append(dict(text=l, x=x + (2.5 * f if i < 3 else 0), y=y, f=f)); y += pitch
        y += pitch * 0.15; gt.append(first); paras = paras[1:]
    y = flow(rng, paras, x, y, col, f, pitch, segs); gt += paras
    return segs, gt

def columns(rng, ncol):
    segs, gt = [], []; f = rng.randint(20, 28); pitch = f * rng.uniform(1.2, 1.4); x0 = 100; page_w = 1500
    gutter = f * rng.uniform(1.0, 3.0); col = (page_w - gutter * (ncol - 1)) / ncol; y0 = 110
    head = title(rng); hf = int(f * rng.uniform(2.0, 3.0))
    hl = wrap(head, page_w, hf)
    for l in hl: segs.append(dict(text=l, x=x0, y=y0, f=hf)); y0 += hf * 1.3
    gt.append(" ".join(hl))
    if rng.random() < 0.6:
        sub = sentence(rng, rng.randint(5, 9)).rstrip(".!?"); sf = int(f * 1.3)
        for l in wrap(sub, page_w * 0.8, sf): segs.append(dict(text=l, x=x0, y=y0, f=sf)); y0 += sf * 1.3
        gt.append(sub); y0 += pitch * 0.6
    # text flows down column 1, then column 2... paragraphs may continue across columns
    paras = [paragraph(rng) for _ in range(rng.randint(3, 3 + 2 * ncol))]
    lines = []  # (text, paragraph index, first line of paragraph?)
    for pi, p in enumerate(paras):
        for i, l in enumerate(wrap(p, col, f, 2 * CW * f)): lines.append((l, pi, i == 0))
    per_col = math.ceil(len(lines) / ncol)
    for c in range(ncol):
        y = y0; x = x0 + c * (col + gutter)
        for (l, pi, first) in lines[c * per_col:(c + 1) * per_col]:
            segs.append(dict(text=l, x=x + (2 * CW * f if first else 0), y=y, f=f)); y += pitch
    gt += paras
    if rng.random() < 0.7: pn = f"{rng.choice(['SAWDUST','THE DAILY NEWS','COMMUNITY'])} / {rng.choice(['FALL','SPRING'])} 2026 {rng.randint(2, 90)}"; segs.append(dict(text=pn, x=page_w - width(pn, f * 0.8), y=y0 + per_col * pitch + 60, f=f * 0.8)); gt.append(pn)
    return segs, gt

def table(rng):
    segs, gt = [], []; f = rng.randint(20, 26); pitch = f * rng.uniform(1.6, 2.0); x0 = 120; y = 120
    t = title(rng, 3); segs.append(dict(text=t, x=x0, y=y, f=int(f * 1.5))); gt.append(t); y += f * 3
    ncol = rng.randint(3, 5); colx = [x0]; widths = []
    kinds = [rng.choice(["name", "text", "num", "money", "date"]) for _ in range(ncol)]; kinds[0] = rng.choice(["name", "text", "date"])
    def cell(k):
        return {"name": lambda: rng.choice(NAMES) + " " + rng.choice(NAMES), "text": lambda: title(rng, rng.randint(1, 4)),
                "num": lambda: str(rng.randint(1, 999)), "money": lambda: f"${rng.randint(1, 9999)}.{rng.randint(10, 99)}",
                "date": lambda: f"{rng.randint(1, 12)}/{rng.randint(1, 28)}/2026"}[k]()
    rows = [[title(rng, 1) for _ in range(ncol)]] + [[cell(k) for k in kinds] for _ in range(rng.randint(4, 10))]
    for c in range(ncol): widths.append(max(width(r[c], f) for r in rows))
    gap = f * rng.uniform(1.2, 3.5)
    for c in range(1, ncol): colx.append(colx[-1] + widths[c - 1] + gap)
    for r in rows:
        for c, txt in enumerate(r):
            xx = colx[c] if kinds[c] not in ("num", "money") else colx[c] + widths[c] - width(txt, f)
            segs.append(dict(text=txt, x=xx, y=y, f=f))
        gt.append(" ".join(r)); y += pitch
    return segs, gt

def form(rng):
    segs, gt = [], []; f = rng.randint(22, 28); pitch = f * rng.uniform(1.8, 2.4); x0 = 140; y = 120
    t = title(rng, 3) + " Form"; segs.append(dict(text=t, x=x0, y=y, f=int(f * 1.6))); gt.append(t); y += f * 3.2
    labels = ["Name:", "Address:", "City:", "Phone:", "Date of birth:", "Account number:", "Email:", "Policy:", "Amount due:"]
    valx = x0 + f * rng.uniform(9, 14)
    for lab in rng.sample(labels, rng.randint(5, 9)):
        val = {"Name:": lambda: rng.choice(NAMES) + " " + rng.choice(NAMES), "Phone:": lambda: f"555-{rng.randint(100,999)}-{rng.randint(1000,9999)}",
               "Amount due:": lambda: f"${rng.randint(10,900)}.{rng.randint(10,99)}"}.get(lab, lambda: title(rng, rng.randint(1, 4)))()
        segs.append(dict(text=lab, x=x0, y=y, f=f)); segs.append(dict(text=val, x=valx, y=y, f=f)); gt.append(lab + " " + val); y += pitch
    return segs, gt

def receipt(rng):
    segs, gt = [], []; f = rng.randint(20, 26); pitch = f * rng.uniform(1.25, 1.5); w = f * rng.uniform(22, 30); x0 = 300; y = 120
    for line in [rng.choice(["CORNER MARKET", "FRESH FOODS", "CITY PHARMACY", "MAIN ST DELI"]), f"{rng.randint(10,999)} Main Street", f"Tel 555-{rng.randint(1000,9999)}"]:
        segs.append(dict(text=line, x=x0 + (w - width(line, f)) / 2, y=y, f=f)); gt.append(line); y += pitch
    y += pitch
    tot = 0
    for it in rng.sample(ITEMS, rng.randint(4, 10)):
        name = it + (" " + title(rng, 1) if rng.random() < 0.4 else ""); p = rng.randint(99, 1999) / 100; tot += p; price = f"{p:.2f}"
        segs.append(dict(text=name, x=x0, y=y, f=f)); segs.append(dict(text=price, x=x0 + w - width(price, f), y=y, f=f)); gt.append(name + " " + price); y += pitch
    y += pitch * 0.5
    for lab, val in [("SUBTOTAL", tot), ("TAX", tot * 0.08), ("TOTAL", tot * 1.08)]:
        v = f"{val:.2f}"; segs.append(dict(text=lab, x=x0, y=y, f=f)); segs.append(dict(text=v, x=x0 + w - width(v, f), y=y, f=f)); gt.append(lab + " " + v); y += pitch
    y += pitch; th = "Thank you for shopping with us!"; segs.append(dict(text=th, x=x0 + (w - width(th, f)) / 2, y=y, f=f)); gt.append(th)
    return segs, gt

def menu(rng):
    segs, gt = [], []; f = rng.randint(20, 26); pitch = f * 1.35; x0 = 150; w = f * rng.uniform(30, 40); y = 110
    t = rng.choice(["Lunch Menu", "Dinner", "Today's Specials"]); tf = int(f * 2); segs.append(dict(text=t, x=x0 + (w - width(t, tf)) / 2, y=y, f=tf)); gt.append(t); y += tf * 1.8
    for sec in rng.sample(["Starters", "Mains", "Desserts", "Drinks"], rng.randint(2, 3)):
        segs.append(dict(text=sec, x=x0, y=y, f=int(f * 1.3))); gt.append(sec); y += f * 2
        for d in rng.sample(DISHES, rng.randint(2, 4)):
            price = f"${rng.randint(5, 30)}"
            segs.append(dict(text=d, x=x0, y=y, f=f)); segs.append(dict(text=price, x=x0 + w - width(price, f), y=y, f=f)); gt.append(d + " " + price); y += pitch
            desc = sentence(rng, rng.randint(6, 11)).lower()
            dl = wrap(desc, w * 0.8, f * 0.85)
            for l in dl: segs.append(dict(text=l, x=x0, y=y, f=f * 0.85)); y += f * 0.85 * 1.3
            gt.append(" ".join(dl)); y += pitch * 0.5
        y += pitch
    return segs, gt

def letter(rng):
    segs, gt = [], []; f = rng.randint(22, 28); pitch = f * 1.35; x0 = 140; col = 1350; y = 110
    name = rng.choice(NAMES)
    sender = [f"{rng.choice(NAMES)} Company", f"{rng.randint(10, 999)} Oak Avenue", f"Springfield, IL {rng.randint(10000, 99999)}"]
    for l in sender: segs.append(dict(text=l, x=x0 + col - width(l, f) - 20, y=y, f=f)); gt.append(l); y += pitch
    y += pitch; d = f"{rng.choice(['March','June','October'])} {rng.randint(1, 28)}, 2026"; segs.append(dict(text=d, x=x0, y=y, f=f)); gt.append(d); y += pitch * 2
    for l in [f"Mr. {rng.choice(NAMES)} {name}", f"{rng.randint(10, 999)} Pine Street", f"Austin, TX {rng.randint(10000, 99999)}"]:
        segs.append(dict(text=l, x=x0, y=y, f=f)); gt.append(l); y += pitch
    y += pitch; sal = f"Dear Mr. {name},"; segs.append(dict(text=sal, x=x0, y=y, f=f)); gt.append(sal); y += pitch * 1.6
    paras = [paragraph(rng) for _ in range(rng.randint(2, 4))]
    y = flow(rng, paras, x0, y, col, f, pitch, segs, indent=False); gt += paras
    for l in ["Sincerely,", f"{rng.choice(NAMES)} {rng.choice(NAMES)}", "Customer Service"]:
        segs.append(dict(text=l, x=x0, y=y, f=f)); gt.append(l); y += pitch * (2.2 if l == "Sincerely," else 1)
    return segs, gt

def bullets(rng):
    segs, gt = [], []; f = rng.randint(22, 28); pitch = f * 1.35; x0 = 140; col = 1300; y = 120
    h = title(rng); segs.append(dict(text=h, x=x0, y=y, f=int(f * 1.6))); gt.append(h); y += f * 3
    intro = paragraph(rng, 1); y = flow(rng, [intro], x0, y, col, f, pitch, segs, indent=False); gt.append(intro)
    for i in range(rng.randint(3, 7)):
        item = sentence(rng, rng.randint(4, 18)); mark = rng.choice(["•", f"{i + 1}."])
        lines = wrap(item, col - 2 * f, f)
        segs.append(dict(text=mark + " " + lines[0], x=x0, y=y, f=f)); y += pitch
        for l in lines[1:]: segs.append(dict(text=l, x=x0 + 2 * f, y=y, f=f)); y += pitch
        gt.append(mark + " " + " ".join(lines)); y += pitch * 0.3
    return segs, gt

def med_label(rng):
    segs, gt = [], []; f = rng.randint(16, 22); pitch = f * 1.3; x0 = 300; w = f * 32; y = 150
    lines = [rng.choice(["CITY PHARMACY", "HEALTH MART"]), f"Rx {rng.randint(1000000, 9999999)}", f"{rng.choice(NAMES).upper()}, {rng.choice(['JOHN','MARIA','WILL'])}",
             f"Take {rng.choice(['one','two'])} tablet by mouth {rng.choice(['daily','twice daily','every 6 hours as needed'])}",
             f"{rng.choice(['Amoxicillin','Lisinopril','Metformin','Ibuprofen'])} {rng.choice(['250','500','10','20'])} mg", f"Qty: {rng.randint(10, 90)}   Refills: {rng.randint(0, 5)}",
             f"Dr. {rng.choice(NAMES)}", f"Exp: {rng.randint(1, 12)}/2027"]
    for l in lines:
        big = l.startswith("Take")
        ff = f * (1.2 if big else 1)
        segs.append(dict(text=l, x=x0, y=y, f=ff)); gt.append(l); y += ff * 1.35
    return segs, gt

def wrap_around_picture(rng):
    segs, gt = [], []; f = rng.randint(22, 28); pitch = f * 1.35; x0 = 120; col = 1400; y = 120
    h = title(rng); segs.append(dict(text=h, x=x0, y=y, f=int(f * 2))); gt.append(h); y += f * 3.5
    pic_top = y + pitch * rng.randint(2, 4); pic_h = pitch * rng.randint(5, 9); pic_w = col * rng.uniform(0.35, 0.5); right = rng.random() < 0.5
    paras = [paragraph(rng, rng.randint(3, 6)) for _ in range(rng.randint(2, 3))]
    for p in paras:
        words = p.split(); i = 0; first = True
        while i < len(words):
            narrow = pic_top <= y <= pic_top + pic_h
            cw = col - pic_w - f * 1.5 if narrow else col
            lx = x0 + (pic_w + f * 1.5 if (narrow and not right) else 0)
            line = ""
            while i < len(words) and width((line + " " + words[i]).strip(), f) <= cw: line = (line + " " + words[i]).strip(); i += 1
            if not line: line = words[i]; i += 1
            segs.append(dict(text=line, x=lx, y=y, f=f)); y += pitch
        y += pitch * 0.15
    gt += paras
    cap = sentence(rng, rng.randint(4, 8)); cx = x0 + (col - pic_w if right else 0)
    for l in wrap(cap, pic_w, f * 0.8): segs.append(dict(text=l, x=cx, y=pic_top + pic_h - f, f=f * 0.8, caption=True))
    gt.append(" ".join(wrap(cap, pic_w, f * 0.8)))
    return segs, gt

def sidebar(rng):
    segs, gt = [], []; f = rng.randint(20, 26); pitch = f * 1.35; x0 = 110
    main_w = 1000; gutter = f * rng.uniform(1.5, 3); side_x = x0 + main_w + gutter; side_w = 420; y = 120
    h = title(rng); segs.append(dict(text=h, x=x0, y=y, f=int(f * 2))); gt.append(h); y += f * 3.5
    paras = [paragraph(rng) for _ in range(rng.randint(3, 5))]
    flow(rng, paras, x0, y, main_w, f, pitch, segs); gt += paras
    sy = y + pitch * rng.randint(0, 6); sh = title(rng, 2)
    segs.append(dict(text=sh, x=side_x, y=sy, f=int(f * 1.2))); sy += f * 2
    sp = [paragraph(rng, rng.randint(1, 2)) for _ in range(rng.randint(1, 2))]
    flow(rng, sp, side_x, sy, side_w, f * 0.9, pitch * 0.9, segs, indent=False)
    gt += [sh] + sp
    return segs, gt

def labels_on_picture(rng):
    segs, gt = columns(rng, 1)
    extra = []
    bottom = max(s_["y"] for s_ in segs) + 200  # labels sit on a picture below the text, not on top of it
    for _ in range(rng.randint(3, 7)):
        lab = title(rng, rng.randint(1, 3)); extra.append(lab)
        segs.append(dict(text=lab, x=rng.uniform(100, 1200), y=rng.uniform(bottom, bottom + 600), f=rng.randint(18, 26), label=True))
    # labels come last, in any order: scored as a set
    return segs, gt + [("LABEL", l) for l in extra]

LAYOUTS = {"single": single_column, "two_col": lambda r: columns(r, 2), "three_col": lambda r: columns(r, 3), "table": table,
           "form": form, "receipt": receipt, "menu": menu, "letter": letter, "bullets": bullets, "med_label": med_label,
           "wrap_picture": wrap_around_picture, "sidebar": sidebar, "labels": labels_on_picture}

# ---------------------------------------------------------------- page -> detector-like boxes
def to_boxes(rng, segs, tilt_deg=0.0, curve=0.0, jitter=0.0):
    """Each segment -> one box like the detector's (padded, rotated). Optional tilt, page curvature, jitter."""
    boxes = []
    cx0, cy0 = 850, 1100; t = math.radians(tilt_deg)
    for s in segs:
        f = s["f"]
        if s.get("cap"):
            w = CW * f * 1.3 + 0.6 * f * 0.4; h = f * 0.95
        else:
            w = width(s["text"], f) + 0.8 * f; h = 1.45 * f
        cx = s["x"] + w / 2 - 0.4 * f; cy = s["y"] + h / 2
        # curvature: lines bend near the spine (left edge), like a thick book
        ang = 0.0
        if curve:
            dx = (cx - 100) / 1600; cy += curve * (1 - dx) ** 2 * 80; ang += -math.atan(curve * 2 * (1 - dx) * 80 / 1600)
        cx += rng.gauss(0, jitter * f); cy += rng.gauss(0, jitter * f * 0.5); ang += math.radians(rng.gauss(0, jitter * 2))
        # rotate the whole page about its centre
        x = cx0 + (cx - cx0) * math.cos(t) - (cy - cy0) * math.sin(t)
        y = cy0 + (cx - cx0) * math.sin(t) + (cy - cy0) * math.cos(t)
        a = ang + t
        if s.get("cap"):  # tall box: the detector reports it with the long side vertical
            boxes.append((s["text"], x, y, h, w, a + math.pi / 2))
        else:
            boxes.append((s["text"], x, y, w, h, a))
    return boxes

def make_suite(n_per=30, seed=1):
    rng = random.Random(seed); pages = []
    for kind, fn in LAYOUTS.items():
        for k in range(n_per):
            segs, gt = fn(rng)
            tilt = rng.choice([0, 0, rng.uniform(-8, 8), rng.uniform(-30, 30)])
            curve = rng.choice([0, 0, 0, rng.uniform(0.3, 1.0)]) if kind in ("single", "two_col", "three_col", "sidebar", "wrap_picture") else 0
            jit = rng.uniform(0, 0.08)
            boxes = to_boxes(rng, segs, tilt, curve, jit)
            rng.shuffle(boxes)
            pages.append(dict(id=f"{kind}-{k:02d}", kind=kind, tilt=tilt, curve=curve, boxes=boxes, gt=gt))
    return pages

if __name__ == "__main__":
    import json
    pages = make_suite()
    with open("suite.tsv", "w") as f:
        for p in pages:
            for (t, x, y, w, h, a) in p["boxes"]:
                f.write(f"{p['id']}\t{t}\t{x:.2f}\t{y:.2f}\t{w:.2f}\t{h:.2f}\t{a:.5f}\n")
    json.dump({p["id"]: dict(kind=p["kind"], tilt=p["tilt"], curve=p["curve"], gt=p["gt"]) for p in pages}, open("suite_gt.json", "w"))
    print(len(pages), "pages,", sum(len(p["boxes"]) for p in pages), "text boxes")
