# -*- coding: utf-8 -*-
"""제안서 PDF 생성(한 번에 렌더링, 합치기 없음). 목차 쪽수는 1차 결과에서 추출해 2차에 넣고, 책갈피는 pikepdf 로 추가한다."""
import subprocess, json, re, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.join(HERE,'..','..','docs','proposal'))
FINAL = os.path.join(OUT,'OIO_Casting_Platform_Proposal.pdf')
env = dict(os.environ, NODE_PATH=subprocess.check_output(['npm','root','-g']).decode().strip())
sys.path.insert(0, HERE)
import build as B
from pypdf import PdfReader
import pikepdf
def render(toc=None, tag=''):
    h = os.path.join(OUT, f'_doc{tag}.html'); pdf = os.path.join(OUT, f'_doc{tag}.pdf')
    open(h,'w',encoding='utf-8').write(B.html_doc(toc, 'all'))
    subprocess.check_call(['node', os.path.join(HERE,'render.js'), h, pdf], env=env)
    return pdf, h
def find_pages(pdf):
    txt = [p.extract_text() or '' for p in PdfReader(pdf).pages]; res = {}
    for n,t in B.CH_TITLES:
        pat = re.compile(r'(^|\n)\s*'+(re.escape(str(n))+r'\s*' if n.isdigit() else '')+re.escape(t)+r'\s*(\n|$)')
        for i,pg in enumerate(txt):
            if i<2: continue            # 표지·목차 제외
            if pat.search(pg): res[n]=i+1; break   # 쪽번호 = 실제 쪽(표지=1)
    return res
p1,_ = render(None,'1'); toc = find_pages(p1)
p2,h2 = render(toc,'2'); t2 = find_pages(p2)
if t2 != toc: p2,h2 = render(t2,'3'); toc = t2
pdf = pikepdf.open(p2)
with pdf.open_outline() as ol:
    ol.root.append(pikepdf.OutlineItem('표지', 0)); ol.root.append(pikepdf.OutlineItem('목차', 1))
    for n,t in B.CH_TITLES:
        if n in toc: ol.root.append(pikepdf.OutlineItem((f'{n}. {t}' if n.isdigit() else t), toc[n]-1))
with pdf.open_metadata() as m:
    m['dc:title'] = 'OIO 캐스팅 플랫폼 구축 제안서'; m['dc:description'] = '개발 제안서 v1.0'
pdf.docinfo['/Title'] = 'OIO 캐스팅 플랫폼 구축 제안서'
pdf.save(FINAL, linearize=True)
os.replace(h2, os.path.join(OUT,'proposal.html'))
print('pages', len(PdfReader(FINAL).pages), 'toc', toc)
for f in os.listdir(OUT):
    if f.startswith('_'): os.remove(os.path.join(OUT,f))
