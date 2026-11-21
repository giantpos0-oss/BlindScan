from evalh import *
import sys
class Dual(Ocr):
    mode="none"
    def read(s,img,limit=1600,use_cls=True):
        img,boxes=s.orient(img,limit); items=[]
        for b in boxes:
            c=s.crop(img,b); t,sc=s.recognize(c)
            if s.mode!="none":
                cx,cy,w,h,ang,score=b
                if s.mode=="pad": b2=(cx,cy,w+0.3*h,h*1.15,ang,score)
                elif s.mode=="tight": b2=(cx,cy,w,h*0.9,ang,score)
                c2=s.crop(img,b2); t2,sc2=s.recognize(c2)
                if sc2>sc: t,sc=t2,sc2
            if t and sc>=0.5: items.append((b,t))
        return order(items)
for mode in ["none","pad","tight"]:
    o=Dual("small"); o.rec=ort.InferenceSession("slim/rec_small.onnx"); o.keys=[""]+[l.rstrip("\r\n") for l in open("slim/dict_small.txt",encoding="utf-8")]+[" "]
    o.mode=mode; r=[]
    for g in ["gt.json","gt_hard.json","gt_tiny.json"]:
        c,t=score(o,g); r.append(f"{g[3:-5] or 'main'} {c:.2f}% ({t:.2f}s)")
    print(f"{mode:6s}", " | ".join(r), flush=True)
