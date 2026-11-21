import sys
from evalh import *
import correct
class OcrC(Ocr):
    use_corr=True
    def recognize(s,im):
        W=max(320,int(math.ceil(48*im.size[0]/im.size[1])))
        p=s.rec.run(None,{"x":s.norm(im,W)[None]})[0][0]
        idx=p.argmax(1); prv=0; chars=[]
        for t,i in enumerate(idx):
            if i!=0 and i!=prv: chars.append((s.keys[i] if i<len(s.keys) else "",t,float(p[t,i])))
            prv=i
        # trim leading/trailing spaces like .strip()
        while chars and chars[0][0]==" ": chars.pop(0)
        while chars and chars[-1][0]==" ": chars.pop()
        conf=float(np.mean([c[2] for c in chars])) if chars else 0.0
        text="".join(c[0] for c in chars)
        if s.use_corr and chars:
            if not hasattr(s,"kidx"): s.kidx={k:i for i,k in enumerate(s.keys) if k}
            text=correct.correct_line(chars,p,s.kidx)
        return text,conf
if __name__=="__main__":
    print("sum of probs per frame (should be ~1):")
    o=OcrC("small")
    for lam,mar in [(0.5,1.0)]:
        correct.LAMBDA=lam; correct.MARGIN=mar
        r={g:score(o,g)[0] for g in ["gt_hard.json","gt.json","gt_tiny.json"]}
        print(f"lambda {lam} margin {mar}: hard {r['gt_hard.json']:.2f}%  main {r['gt.json']:.2f}%  tiny {r['gt_tiny.json']:.2f}%")
