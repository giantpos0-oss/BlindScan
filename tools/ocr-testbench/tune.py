import pickle, sys, json, math, difflib
sys.argv=sys.argv
import correct
from mine import order, lev
data,kidx=pickle.load(open("/tmp/cache.pkl","rb"))
def run(verbose=False):
    res={}; changes=[]
    for f,(g,lines,items) in data.items():
        out=[]
        for b,chars,P in items:
            if not chars: continue
            conf=sum(c[2] for c in chars)/len(chars)
            log=[]; t=correct.correct_line(chars,P,kidx,log) if CORR else "".join(c[0] for c in chars)
            if conf>=0.5 and t.strip(): out.append((b,t.strip()))
            if verbose and log:
                truth=" ".join(" ".join(lines).split())
                for a,bb in log:
                    tag="GOOD" if bb.lower() in truth.lower().split() or bb.lower() in truth.lower() else "?"
                    changes.append(f"{tag:4s} {a} -> {bb}")
        o=order(out); nz=lambda x:" ".join(x.split()).lower()
        G="\n".join(map(nz,lines)); R="\n".join(map(nz,o))
        e,t=res.get(g,(0,0)); res[g]=(e+lev(G,R),t+len(G))
    return {k:round(100*e/t,2) for k,(e,t) in res.items()}, changes
CORR=False; print("no correction:", run()[0])
CORR=True
for lam in []:
  for mar in [0.5,1.5,3.0]:
    correct.LAMBDA=lam; correct.MARGIN=mar
    print(f"lambda {lam} margin {mar}:", run()[0])
print("---")
correct.LAMBDA=0.5; correct.MARGIN=1.0
for drop in [2,4,8,15]:
    for mf in [1e5,2e4]:
        correct.CTC_DROP=drop; correct.MIN_FREQ=mf; print(f"ctc_drop {drop} min_freq {mf:.0e}:", run()[0])
correct.CTC_DROP=8; correct.MIN_FREQ=1e5
r,ch=run(True); print("\n".join(ch))
