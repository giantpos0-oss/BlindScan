# Reference implementation of the app's OCR pipeline (numpy + onnxruntime only),
# written step-for-step the way the Kotlin version does it.
import numpy as np, onnxruntime as ort, math, sys, json, time
from PIL import Image
from scipy import ndimage
import os
# Folder holding ppocrv5/ and ppocrv6/ (pip download onnxocr==4.0.0, unzip the wheel, use onnxocr/models).
M=os.environ.get("MODELS", os.path.expanduser("~/onnxocr/models/"))
class Ocr:
    def __init__(s, ver="v5"):
        if ver=="v5": d=M+"ppocrv5/"; s.det=ort.InferenceSession(d+"det/det.onnx"); s.rec=ort.InferenceSession(d+"rec/rec.onnx"); dic=d+"ppocrv5_dict.txt"
        else: d=M+"ppocrv6/"; s.det=ort.InferenceSession(d+ver+"/det/det.onnx"); s.rec=ort.InferenceSession(d+ver+"/rec/rec.onnx"); dic=d+("ppocrv6_tiny_dict.txt" if ver=="tiny" else "ppocrv6_dict.txt")
        s.cls=ort.InferenceSession(M+"ppocrv5/cls/cls.onnx")
        s.keys=[l.rstrip("\r\n") for l in open(dic,encoding="utf-8")]
        s.keys=[""]+s.keys+[" "]
    def detect(s, img, limit=1600, thresh=0.3, box_thresh=0.6, unclip=1.5):
        W,H=img.size; r=min(1.0, limit/max(W,H))
        rw=max(32,int(round(W*r/32))*32); rh=max(32,int(round(H*r/32))*32)
        a=np.asarray(img.resize((rw,rh),Image.BILINEAR),dtype=np.float32)[:,:,::-1]/255.0  # BGR
        a=(a-np.array([0.485,0.456,0.406],np.float32))/np.array([0.229,0.224,0.225],np.float32)
        prob=s.det.run(None,{"x":a.transpose(2,0,1)[None]})[0][0,0]
        lab,n=ndimage.label(prob>thresh, structure=np.ones((3,3)))
        boxes=[]; sx=W/rw; sy=H/rh
        objs=ndimage.find_objects(lab)
        for k,sl in enumerate(objs):
            ys,xs=np.nonzero(lab[sl]==k+1)
            if len(xs)<6: continue
            ys=ys+sl[0].start; xs=xs+sl[1].start
            score=prob[ys,xs].mean()
            if score<box_thresh: continue
            rect=min_area_rect(xs.astype(float),ys.astype(float))
            cx,cy,w,h,ang=rect
            if min(w,h)<3: continue
            d=(w*h)*unclip/(2*(w+h)); w+=2*d; h+=2*d
            if min(w,h)<5: continue
            boxes.append((cx*sx,cy*sy,w*sx,h*sy,ang,score))
        return boxes,prob
    def crop(s,img,b):
        cx,cy,w,h,ang,_=b
        u=np.array([math.cos(ang),math.sin(ang)]); v=np.array([-math.sin(ang),math.cos(ang)])
        if h>w: u,v=v,-u; w,h=h,w
        if u[0]<-1e-3 or (abs(u[0])<=1e-3 and u[1]<0): u=-u
        v=np.array([-u[1],u[0]])  # 90deg ccw from u in image coords => points "down" when u points right
        o=np.array([cx,cy])-u*w/2-v*h/2
        W=int(round(w)); Hh=int(round(h))
        # PIL AFFINE: maps output (x,y)->input (a x + b y + c, d x + e y + f)
        return img.transform((max(W,1),max(Hh,1)),Image.AFFINE,(u[0],v[0],o[0],u[1],v[1],o[1]),resample=Image.BILINEAR)
    def norm(s,im,W):
        h=48; w=im.size[0]; hh=im.size[1]; rw=min(W,max(1,int(math.ceil(h*w/hh))))
        a=np.asarray(im.resize((rw,h),Image.BILINEAR),dtype=np.float32)[:,:,::-1]/255.0
        a=(a-0.5)/0.5; out=np.zeros((3,h,W),np.float32); out[:,:,:rw]=a.transpose(2,0,1); return out
    def is_flipped(s,im):
        p=s.cls.run(None,{"x":s.norm(im,192)[None]})[0][0]; return p[1]>0.9
    def recognize(s,im):
        W=max(320,int(math.ceil(48*im.size[0]/im.size[1])))
        p=s.rec.run(None,{"x":s.norm(im,W)[None]})[0][0]
        idx=p.argmax(1); prv=0; out=[]; sc=[]
        for t,i in enumerate(idx):
            if i!=0 and i!=prv: out.append(s.keys[i] if i<len(s.keys) else ""); sc.append(p[t,i])
            prv=i
        return "".join(out).strip(), float(np.mean(sc)) if sc else 0.0
    def orient(s,img,limit):
        """Whole-page orientation: sideways if most text boxes are tall; upside down by majority vote."""
        boxes,_=s.detect(img,limit)
        if not boxes: return img,boxes
        tall=sum(1 for b in boxes if b[3]>b[2]*1.5 and max(b[2],b[3])>3*min(b[2],b[3]) or (b[2]>b[3]*1.5 and abs(math.sin(b[4]))>0.7))
        wide=len(boxes)-tall
        if tall>wide:
            img=img.transpose(Image.ROTATE_270); boxes,_=s.detect(img,limit)
        big=sorted(boxes,key=lambda b:-max(b[2],b[3]))[:9]
        votes=sum(1 if s.is_flipped(s.crop(img,b)) else -1 for b in big)
        if votes>0:
            img=img.transpose(Image.ROTATE_180); boxes,_=s.detect(img,limit)
        return img,boxes
    def read(s,img,limit=1600,use_cls=True):
        img,boxes=s.orient(img,limit); items=[]
        # adaptive resolution: small text (short side < 16 px at detection scale) -> detect again at higher detail
        if boxes:
            r=min(1.0,limit/max(img.size)); hs=sorted(min(b[2],b[3])*r for b in boxes)
            if hs[len(hs)//2]<16 and max(img.size)>limit:
                b2,_=s.detect(img,min(max(img.size),int(limit*1.6)))
                if len(b2)>=len(boxes): boxes=b2
        for b in boxes:
            c=s.crop(img,b)
            t,sc=s.recognize(c)
            if sc<0.8:  # low confidence: retry with upscale + deblur sharpening, keep the better read
                from PIL import ImageFilter
                c2=c.resize((max(1,c.size[0]*48//max(1,c.size[1])),48),Image.LANCZOS).filter(ImageFilter.UnsharpMask(4,150,0))
                t2,sc2=s.recognize(c2)
                if sc2>sc: t,sc=t2,sc2
            if t and sc>=0.5: items.append((b,t))
        return order(items)
def order(items):
    # group into lines by vertical overlap, sort left->right
    items=sorted(items,key=lambda it:it[0][1])
    lines=[]
    for b,t in items:
        cy,h=b[1],min(b[2],b[3])
        for L in lines:
            if abs(L["cy"]-cy)<0.5*min(L["h"],h): L["it"].append((b,t)); break
        else: lines.append({"cy":cy,"h":h,"it":[(b,t)]})
    return [" ".join(t for b,t in sorted(L["it"],key=lambda it:it[0][0])) for L in sorted(lines,key=lambda L:L["cy"])]
def hull(xs,ys):
    pts=sorted(set(zip(xs.tolist(),ys.tolist())))
    if len(pts)<3: return pts
    def cr(o,a,b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    lo=[];up=[]
    for p in pts:
        while len(lo)>=2 and cr(lo[-2],lo[-1],p)<=0: lo.pop()
        lo.append(p)
    for p in reversed(pts):
        while len(up)>=2 and cr(up[-2],up[-1],p)<=0: up.pop()
        up.append(p)
    return lo[:-1]+up[:-1]
def min_area_rect(xs,ys):
    # use row extremes (pixel corners) -> hull -> rotating calipers
    rows={}
    for x,y in zip(xs,ys):
        a=rows.get(y)
        rows[y]=(min(a[0],x),max(a[1],x)) if a else (x,x)
    px=[];py=[]
    for y,(a,b) in rows.items():
        px+= [a,b+1,a,b+1]; py+=[y,y,y+1,y+1]
    H=hull(np.array(px),np.array(py))
    if len(H)<3: return (np.mean(px),np.mean(py),1,1,0)
    best=None
    for i in range(len(H)):
        p,q=H[i],H[(i+1)%len(H)]; ex,ey=q[0]-p[0],q[1]-p[1]; L=math.hypot(ex,ey)
        if L==0: continue
        ux,uy=ex/L,ey/L
        us=[x*ux+y*uy for x,y in H]; vs=[-x*uy+y*ux for x,y in H]
        a=(max(us)-min(us))*(max(vs)-min(vs))
        if best is None or a<best[0]:
            mu=(max(us)+min(us))/2; mv=(max(vs)+min(vs))/2
            best=(a,mu*ux-mv*uy,mu*uy+mv*ux,max(us)-min(us),max(vs)-min(vs),math.atan2(uy,ux))
    return best[1:]
def lev(a,b):
    p=list(range(len(b)+1))
    for i,ca in enumerate(a,1):
        c=[i]
        for j,cb in enumerate(b,1): c.append(min(p[j]+1,c[j-1]+1,p[j-1]+(ca!=cb)))
        p=c
    return p[-1]
if __name__=="__main__":
    ver=sys.argv[1]; limit=int(sys.argv[2]); gt=json.load(open(sys.argv[3] if len(sys.argv)>3 else "gt.json")); o=Ocr(ver); tot=0;err=0; T=0
    for f,lines in gt.items():
        t0=time.time(); out=o.read(Image.open(f).convert("RGB"),limit); T+=time.time()-t0
        nz=lambda x:" ".join(x.split())
        lines=[nz(x) for x in lines]; out=[nz(x) for x in out]
        g="\n".join(lines); r="\n".join(out); e=lev(g,r); tot+=len(g); err+=e
        bad=[x for x in out if x not in lines]; miss=[x[:30] for x in lines if x not in out]
        print(f"{f:40s} CER {100*e/len(g):5.1f}%", "" if e==0 else "| got: "+" / ".join(bad)[:160]+(" | lines:%d/%d"%(len(out),len(lines))))
    print(f"== {ver} limit {limit}: overall CER {100*err/tot:.2f}%  avg {T/len(gt):.2f}s/page")
