import sys, numpy as np
from PIL import Image, ImageFilter, ImageOps
from scipy import ndimage as ndi
def prep(im, maxside=1600):
    g=ImageOps.grayscale(im); k=maxside/max(g.size); g=g.resize((int(g.width*k),int(g.height*k)),Image.LANCZOS)
    a=np.asarray(g).astype(float); bg=np.asarray(g.filter(ImageFilter.GaussianBlur(25))).astype(float)+1
    f=np.clip(a/bg*235,0,255); return f, k
def inkmask(f):
    m=f<170
    lab,n=ndi.label(m); sl=ndi.find_objects(lab)
    keep=np.zeros(n+1,bool)
    for i,s in enumerate(sl,1):
        h=s[0].stop-s[0].start; w=s[1].stop-s[1].start
        keep[i]= h<=60 and w<=60          # dots and glyph pieces only; paper edges and shadows are long
    return keep[lab]
def skew(mask):
    ys,xs=np.nonzero(mask); best=(0,-1)
    for a in np.arange(-6,6.01,0.25):
        t=np.tan(np.radians(a)); y2=np.round(ys - xs*t).astype(int); y2-=y2.min()
        h=np.bincount(y2).astype(float); s=(h**2).sum()
        if s>best[1]: best=(a,s)
    return best[0]
def rows(mask):
    prof=mask.sum(1).astype(float); sm=np.convolve(prof,np.ones(5)/5,'same')
    thr=max(3, np.percentile(sm[sm>0],60)*0.25) if (sm>0).any() else 3
    on=sm>thr; out=[]; i=0
    while i<len(on):
        if on[i]:
            j=i
            while j<len(on) and on[j]: j+=1
            out.append((i,j)); i=j
        else: i+=1
    return [r for r in out if r[1]-r[0]>=8]
def analyse(p):
    im=Image.open(p).convert('RGB'); f,k=prep(im); m=inkmask(f); a=skew(m)
    mr=np.asarray(Image.fromarray((m*255).astype('uint8')).rotate(-a, resample=Image.BILINEAR))>100
    return im,f,k,a,mr,rows(mr)
if __name__=='__main__':
    for p in sys.argv[1:]:
        im,f,k,a,mr,rs=analyse(p)
        print(p.split('/')[-1], 'skew',a,'rows',len(rs),[b-a_ for a_,b in rs])
