import pickle
from evalc import *
o=OcrC("small"); o.use_corr=False
kidx={k:i for i,k in enumerate(o.keys) if k}
cols=sorted({i for k,i in kidx.items() if len(k)==1 and ord(k)<128} | {0, len(o.keys)-1})
colmap={c:j for j,c in enumerate(cols)}
data={}
for g in ["gt_hard.json","gt.json","gt_tiny.json"]:
    gt=json.load(open(g))
    for f,lines in gt.items():
        img2,boxes=o.orient(Image.open(f).convert("RGB"),1600); items=[]
        for b in boxes:
            c=o.crop(img2,b); W=max(320,int(math.ceil(48*c.size[0]/c.size[1])))
            p=o.rec.run(None,{"x":o.norm(c,W)[None]})[0][0]
            idx=p.argmax(1); prv=0; chars=[]
            for t,i in enumerate(idx):
                if i!=0 and i!=prv: chars.append((o.keys[i] if i<len(o.keys) else "",t,float(p[t,i])))
                prv=i
            while chars and chars[0][0]==" ": chars.pop(0)
            while chars and chars[-1][0]==" ": chars.pop()
            items.append((b,chars,p[:,cols].astype(np.float32)))
        data[f]=(g,lines,items)
pickle.dump((data,{k:colmap[i] for k,i in kidx.items() if i in colmap}),open("/tmp/cache.pkl","wb"))
print("cached",len(data),"pages")
