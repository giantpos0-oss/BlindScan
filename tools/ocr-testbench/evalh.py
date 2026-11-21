import json, sys, time; exec(open("mine.py").read().split('if __name__')[0])
from PIL import Image
def score(o, gtfile="gt_hard.json", reader=None, verbose=False, limit=1600):
    gt=json.load(open(gtfile)); tot=err=0; T=0; per={}
    for f,lines in gt.items():
        img=Image.open(f).convert("RGB"); t=time.time()
        out=(reader or (lambda im:o.read(im,limit)))(img); T+=time.time()-t
        nz=lambda x:" ".join(x.split()).lower(); g="\n".join(map(nz,lines)); r="\n".join(map(nz,out))
        e=lev(g,r); tot+=len(g); err+=e; per[f]=100*e/len(g)
    if verbose:
        for f,v in per.items(): print(f"  {f:38s} {v:5.1f}%")
    return 100*err/tot, T/len(gt)
if __name__=="__main__":
    o=Ocr("small"); c,t=score(o,verbose=True); print(f"== baseline hard CER {c:.2f}%  {t:.2f}s/page")
