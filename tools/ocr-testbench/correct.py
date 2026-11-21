# Word correction for low-confidence OCR words, using the recognizer's own CTC probabilities.
import math, re, numpy as np
WORDS={}; BYLEN={}
for line in open(__import__("os").path.join(__import__("os").path.dirname(__file__), "../../app/src/main/assets/words_en.txt")):
    w,c=line.split(); WORDS[w]=int(c); BYLEN.setdefault(len(w),[]).append(w)
TOTAL=sum(WORDS.values())
def logfreq(w): c=WORDS.get(w.lower()); return math.log(c/TOTAL) if c else None
def lev_bounded(a,b,k):
    if abs(len(a)-len(b))>k: return k+1
    prev=list(range(len(b)+1))
    for i,ca in enumerate(a,1):
        cur=[i]+[0]*len(b); best=cur[0]
        for j,cb in enumerate(b,1):
            cur[j]=min(prev[j]+1,cur[j-1]+1,prev[j-1]+(ca!=cb)); best=min(best,cur[j])
        if best>k: return k+1
        prev=cur
    return prev[-1]
def candidates(w):
    k=1 if len(w)<=4 else 2
    out=[]
    for L in range(len(w)-k,len(w)+k+1):
        for c in BYLEN.get(L,[]):
            if lev_bounded(w,c,k)<=k: out.append(c)
    return out
def ctc_logp(P,seq,t0,t1):
    """log P(seq | frames t0..t1) under CTC, blank=0. P = per-frame probabilities."""
    lp=np.log(np.maximum(P[t0:t1+1],1e-12)); T=lp.shape[0]
    ext=[0]
    for s in seq: ext+= [s,0]
    S=len(ext); a=np.full(S,-1e30); a[0]=lp[0,0]
    if S>1: a[1]=lp[0,ext[1]]
    for t in range(1,T):
        n=np.full(S,-1e30)
        for s in range(S):
            m=a[s]
            if s>0: m=np.logaddexp(m,a[s-1])
            if s>1 and ext[s]!=0 and ext[s]!=ext[s-2]: m=np.logaddexp(m,a[s-2])
            n[s]=m+lp[t,ext[s]]
        a=n
    return np.logaddexp(a[-1],a[-2]) if S>1 else a[-1]
def apply_case(src,w):
    if src.isupper() and len(src)>1: return w.upper()
    if src[:1].isupper(): return w[:1].upper()+w[1:]
    return w
LAMBDA=0.5; MARGIN=1.0; KNOWN_MARGIN=4.0; MIN_LEN=3; MIN_FREQ=100000; CTC_DROP=4.0
def is_word(w): c=WORDS.get(w.lower()); return c is not None and c>=MIN_FREQ
def correct_line(chars, P, keys_index, log=None):
    """chars: list of (char, frame, prob) from greedy decode. Returns corrected text."""
    toks=[]; cur=[]
    for c in chars:
        if c[0]==" ":
            if cur: toks.append(cur); cur=[]
            toks.append([c])
        else: cur.append(c)
    if cur: toks.append(cur)
    out=[]; T=P.shape[0]
    for tok in toks:
        text="".join(c[0] for c in tok)
        if text==" ": out.append(" "); continue
        alnum=[ch for ch in text if ch.isalnum()]
        digits=sum(ch.isdigit() for ch in alnum)
        if digits:
            if digits*2>=len(alnum):   # number-like: letter o between/next to digits -> zero; l between digits -> one
                text=re.sub(r"(?<=\d)[oO]|[oO](?=\d)", "0", text)
                text=re.sub(r"(?<=\d)[oO]|[oO](?=\d)", "0", text)
                text=re.sub(r"(?<=\d)l(?=\d)","1",text)
            out.append(text); continue
        m=re.match(r"^([^A-Za-z]*)([A-Za-z][A-Za-z']*[A-Za-z]|[A-Za-z])([^A-Za-z]*)$",text)
        if not m: out.append(text); continue
        pre,core,post=m.groups()
        if len(core)<MIN_LEN: out.append(text); continue
        probs=[c[2] for c in tok]; low=min(probs); avg=sum(probs)/len(probs)
        known=is_word(core)
        if (known and low>=0.3) or (not known and low>=0.9 and avg>=0.95):
            out.append(text); continue
        i0=chars.index(tok[0]); i1=chars.index(tok[-1])
        t0=chars[i0-1][1]+1 if i0>0 else 0
        t1=chars[i1+1][1]-1 if i1+1<len(chars) else T-1
        def ctc(word):
            idx=[keys_index.get(ch) for ch in pre+word+post]
            return -1e30 if None in idx else ctc_logp(P,idx,t0,t1)
        def lm(word):
            lf=logfreq(word) if is_word(word) else None
            return lf if lf is not None else math.log(1/TOTAL)-5
        c0=ctc(core); base=c0+LAMBDA*lm(core)
        best,bs=core,base
        k=1 if len(core)<=4 else 2
        for c in candidates(core.lower()):
            if not is_word(c) or len(c)<MIN_LEN: continue
            if (len(core)<=3 and len(c)!=len(core)) or abs(len(c)-len(core))>1: continue
            w=apply_case(core,c); cc=ctc(w)
            if cc<c0-CTC_DROP: continue           # must agree with what the recognizer saw
            s=cc+LAMBDA*lm(w)
            if s>bs: best,bs=w,s
        need=KNOWN_MARGIN if known else MARGIN
        if best!=core and bs>base+need:
            if log is not None: log.append((core,best))
            out.append(pre+best+post)
        else: out.append(text)
    return "".join(out)
