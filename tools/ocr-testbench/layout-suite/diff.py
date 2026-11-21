import json, sys, collections, difflib
gt = json.load(open("suite_gt.json")); pred = collections.defaultdict(list)
for l in open("pred.tsv", encoding="utf-8"):
    pid, i, t = l.rstrip("\n").split("\t", 2); pred[pid].append(t)
for pid in sys.argv[1:]:
    g = [p if isinstance(p, str) else p[1] for p in gt[pid]["gt"]]
    print(f"== {pid} (tilt {gt[pid]['tilt']:.0f}, curve {gt[pid]['curve']:.1f})")
    for op in difflib.SequenceMatcher(None, g, pred[pid], autojunk=False).get_opcodes():
        tag, i1, i2, j1, j2 = op
        if tag == "equal": continue
        for p in g[i1:i2]: print("   - expected:", p[:120])
        for p in pred[pid][j1:j2]: print("   + got:     ", p[:120])
