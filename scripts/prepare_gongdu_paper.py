from pathlib import Path
from PIL import Image, ImageOps
import hashlib,json
import argparse
parser=argparse.ArgumentParser(description='Retone the user-supplied Gongdu paper into matching light/dark assets.')
parser.add_argument('source', type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]; audit=root/'.codex/gongdu-paper-20261107'
audit.mkdir(parents=True, exist_ok=True)
im=Image.open(args.source).convert('RGB')
gray=ImageOps.grayscale(im)
hist=gray.histogram(); total=sum(hist); s=0
for i,n in enumerate(hist):
    s+=n
    if s>=total*.99: white=i; break
out=root/'app/src/main/res/drawable-nodpi'; out.mkdir(exist_ok=True)
for name,base,ink in [('gongdu_room_paper',(245,238,233),(145,130,113)),('gongdu_room_paper_dark',(47,43,39),(103,91,76))]:
    channels=[]
    for b,k in zip(base,ink):
        lut=[round(b+(k-b)*min(.55,max(0,(white-v)/160*.55))) for v in range(256)]
        channels.append(gray.point(lut))
    toned=Image.merge('RGB',channels)
    toned.thumbnail((1080,1920),Image.Resampling.LANCZOS)
    toned.save(out/(name+'.webp'),quality=94,method=6)
    toned.save(audit/(name+'.png'))
info={'sourceSha256':hashlib.sha256((args.source).read_bytes()).hexdigest(),'sourceSize':im.size,'whitePoint':white,'method':'grayscale; tonal density capped 0.55; paper #F5EEE9; dark #2F2B27; no screen overlay','outputs':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.glob('gongdu_room_paper*.webp')}}
(audit/'background-source.json').write_text(json.dumps(info,indent=2),encoding='utf-8')
print(json.dumps(info))
