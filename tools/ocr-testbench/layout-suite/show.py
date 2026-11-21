import json, sys, collections
gt = json.load(open("suite_gt.json")); pred = collections.defaultdict(list)
for l in open("pred.tsv", encoding="utf-8"):
    pid, i, t = l.rstrip("\n").split("\t", 2); pred[pid].append(t)
for pid in sys.argv[1:]:
    g = gt[pid]; print(f"===== {pid} tilt {g['tilt']:.1f} curve {g['curve']:.2f}")
    print("  EXPECTED:"); [print("   ", (p if isinstance(p, str) else "[label] " + p[1])[:110]) for p in g["gt"]]
    print("  GOT:"); [print("   ", p[:110]) for p in pred[pid]]
