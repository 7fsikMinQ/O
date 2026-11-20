# -*- coding: utf-8 -*-
"""제안서 PDF 생성: 표지·본문을 따로 렌더링하고 합친다. 목차 쪽수는 1차 렌더 결과에서 추출해 2차에 넣는다."""
import subprocess, json, re, os, sys
from pypdf import PdfReader, PdfWriter
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(os.path.join(HERE,'..','..','docs','proposal'))
env = dict(os.environ, NODE_PATH=subprocess.check_output(['npm','root','-g']).decode().strip())
def run(*a): subprocess.check_call(list(a), env=env)
sys.path.insert(0, HERE)
import build as B
def render(part, toc=None, tag=''):
    h = os.path.join(OUT, f'_{part}{tag}.html'); pdf = os.path.join(OUT, f'_{part}{tag}.pdf')
    open(h,'w',encoding='utf-8').write(B.html_doc(toc, part))
    run('node', os.path.join(HERE,'render.js'), h, pdf, part)
    return pdf
def pages_of(pdf):
    return [pg.extract_text() or '' for pg in PdfReader(pdf).pages]
def find_pages(pdf):
    txt = pages_of(pdf); res = {}
    for n,t in B.CH_TITLES:
        title = t
        pat = re.compile(r'(^|\n)\s*'+re.escape(str(n))+r'\s*'+re.escape(title)+r'\s*(\n|$)') if n.isdigit() else re.compile(r'(^|\n)\s*'+re.escape(title)+r'\s*(\n|$)')
        for i,pg in enumerate(txt):
            if i==0: continue
            if pat.search(pg): res[n]=i+1; break
    return res
cover = render('cover')
b1 = render('body', None, '1')
toc = find_pages(b1)
b2 = render('body', toc, '2')
toc2 = find_pages(b2)
if toc2 != toc:
    b2 = render('body', toc2, '3'); toc = toc2
w = PdfWriter()
for f in (cover, b2):
    for pg in PdfReader(f).pages: w.add_page(pg)
toc_pg = toc
def _bm():
    parent=None
    w.add_outline_item('표지',0)
    w.add_outline_item('목차',1)
    for n,t in B.CH_TITLES:
        if n in toc_pg:
            lab = (f'{n}. {t}' if n.isdigit() else t)
            w.add_outline_item(lab, toc_pg[n])   # 본문 쪽번호 = 물리 쪽 번호(표지 1쪽 뒤) 보정
_bm()
w.add_metadata({'/Title':'OIO 캐스팅 플랫폼 구축 제안서','/Author':'제안자','/Subject':'개발 제안서 v1.0'})
final = os.path.join(OUT,'OIO_캐스팅_플랫폼_구축_제안서.pdf')
with open(final,'wb') as fh: w.write(fh)
print('pages', len(PdfReader(final).pages), 'toc', toc)
for f in os.listdir(OUT):
    if f.startswith('_'): os.remove(os.path.join(OUT,f))
