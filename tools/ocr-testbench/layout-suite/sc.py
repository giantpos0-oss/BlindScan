import json, collections, difflib, re
gt = json.load(open("e2e_gt.json")); pred = collections.defaultdict(list)
for l in open("pred.tsv", encoding="utf-8"):
    pid, i, t = l.rstrip("\n").split("\t", 2); pred[pid].append(t)
norm = lambda s: re.sub(r"[^\w']+", " ", s.lower()).split()
tot = collections.defaultdict(lambda: [0, 0, 0, 0])
for pid, g in gt.items():
    paras = [p if isinstance(p, str) else p[1] for p in g["gt"]]
    labels = [p[1] for p in g["gt"] if not isinstance(p, str)]
    gw = []; gb = set()
    for p in paras: gw += norm(p); gb.add(len(gw))
    pw = []; pb = set()
    for p in pred[pid]: pw += norm(p); pb.add(len(pw))
    sm = difflib.SequenceMatcher(None, gw, pw, autojunk=False)
    match = sum(b.size for b in sm.get_matching_blocks())
    # map boundaries through matching blocks
    m = {}
    for a, b, n in sm.get_matching_blocks():
        for k in range(n + 1): m[a + k] = b + k
    hit = sum(1 for x in gb if x in m and m[x] in pb)
    t = tot[g["kind"]]; t[0] += match; t[1] += len(gw); t[2] += hit; t[3] += len(gb)
    if match / max(1, len(gw)) < 0.9: print("low", pid, round(match / max(1, len(gw)), 3))
print(f"{'layout':14}{'words in order':>16}{'para breaks':>13}")
A = [0, 0, 0, 0]
for k, t in tot.items():
    print(f"{k:14}{100*t[0]/t[1]:15.1f}%{100*t[2]/t[3]:12.1f}%"); A = [a + b for a, b in zip(A, t)]
print(f"{'ALL':14}{100*A[0]/A[1]:15.1f}%{100*A[2]/A[3]:12.1f}%")
