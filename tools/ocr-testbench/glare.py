from evalh import *
import numpy as np, math
o=Ocr("tiny")
def glare(f):
    im=Image.open(f).convert("RGB"); boxes,_=o.detect(im,640)
    W=480; sc=W/im.size[0]; g=np.asarray(im.convert("L").resize((W,int(im.size[1]*sc))),dtype=np.float32); H=g.shape[0]
    cell=16; gh,gw=H//cell,W//cell
    text=np.zeros((gh,gw),bool)
    for b in boxes:
        cx,cy,w,h,a,_=b; c,s_=abs(math.cos(a)),abs(math.sin(a))
        ex=(c*w/2+s_*h/2)*sc; ey=(s_*w/2+c*h/2)*sc
        x0=int(max(0,(cx*sc-ex)//cell)); x1=int(min(gw-1,(cx*sc+ex)//cell)); y0=int(max(0,(cy*sc-ey)//cell)); y1=int(min(gh-1,(cy*sc+ey)//cell))
        text[y0:y1+1,x0:x1+1]=True
    if text.sum()<6: return None
    mean=np.zeros((gh,gw)); con=np.zeros((gh,gw))
    for y in range(gh):
        for x in range(gw):
            p=g[y*cell:(y+1)*cell,x*cell:(x+1)*cell]; mean[y,x]=p.mean(); con[y,x]=np.percentile(p,95)-np.percentile(p,5)
    tm=np.median(mean[text]); tc=np.median(con[text])
    glare=text&(mean>tm+15)&(con<0.6*tc)
    # need a clump: a glare cell with at least one glare neighbour
    from scipy import ndimage
    lab,n=ndimage.label(glare); big=max(ndimage.sum(glare,lab,range(1,n+1))) if n else 0
    return int(big), int(text.sum())
for f in ["will2.jpg","will1.jpg","t00_serif_40_clean.jpg","t13_carlito_28_dim-grad-noise.jpg","t04_bolditalic_42_grad.jpg","t06_cond_30_rot.jpg","hard/h_caveat_17_blur.jpg","hard/h_vt323_22.jpg","tiny/x1_serif_12_clean.jpg"]:
    print(f"{f:36s} glare clump / text cells: {glare(f)}")
