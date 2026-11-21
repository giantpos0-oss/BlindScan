import json, sys, collections
gt = json.load(open("suite_gt.json"))
pred = collections.defaultdict(list)
for l in open(sys.argv[1] if len(sys.argv) > 1 else "pred.tsv", encoding="utf-8"):
    pid, i, t = l.rstrip("\n").split("\t", 2); pred[pid].append(t)

def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, y in enumerate(b, 1): cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y))
        prev = cur
    return prev[-1]

def bounds(paras):
    out, n = set(), 0
    for p in paras: out.add(n); n += len(p.split())
    return out

per = collections.defaultdict(lambda: [0, 0, 0, 0, 0, 0, 0])  # pages, perfect, word_err, words, b_tp, b_fp, b_fn
fails = collections.defaultdict(list)
for pid, g in gt.items():
    main = [p for p in g["gt"] if not isinstance(p, list)]
    labels = [p[1] for p in g["gt"] if isinstance(p, list)]
    pp = list(pred[pid])
    # picture labels: must appear as their own paragraphs after the main text (any order)
    lab_ok = True
    for lab in labels:
        if lab in pp: pp.remove(lab)
        else: lab_ok = False
    gw = " ".join(main).split(); pw = " ".join(pp).split()
    e = lev(gw, pw)
    gb, pb = bounds(main), bounds(pp) if e == 0 else set()
    tp = len(gb & pb) if e == 0 else 0
    k = per[g["kind"]]; k[0] += 1; k[2] += e; k[3] += len(gw)
    k[4] += tp; k[5] += (len(pb) - tp) if e == 0 else 0; k[6] += len(gb) - tp
    perfect = e == 0 and gb == pb and lab_ok
    k[1] += perfect
    if not perfect: fails[g["kind"]].append((pid, e, len(gb - pb) if e == 0 else None, len(pb - gb) if e == 0 else None, lab_ok, g["tilt"], g["curve"]))

tot = [0] * 7
print(f"{'layout':14s} {'pages':>5s} {'perfect':>8s} {'word order':>11s} {'para breaks':>12s}")
for kind, k in per.items():
    for i in range(7): tot[i] += k[i]
    acc = 100 * (1 - k[2] / max(1, k[3])); prec = k[4] / max(1, k[4] + k[5]); rec = k[4] / max(1, k[4] + k[6]); f1 = 100 * 2 * prec * rec / max(1e-9, prec + rec)
    print(f"{kind:14s} {k[0]:5d} {100 * k[1] / k[0]:7.0f}% {acc:10.2f}% {f1:11.1f}%")
acc = 100 * (1 - tot[2] / tot[3]); prec = tot[4] / max(1, tot[4] + tot[5]); rec = tot[4] / max(1, tot[4] + tot[6])
print(f"{'ALL':14s} {tot[0]:5d} {100 * tot[1] / tot[0]:7.0f}% {acc:10.2f}% {100 * 2 * prec * rec / (prec + rec):11.1f}%")
json.dump(fails, open("fails.json", "w"), indent=0)
