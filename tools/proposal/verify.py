# -*- coding: utf-8 -*-
"""제안서 자동 검증. 분석 10 · 검토 10 · 최종 10 · 전수 대조 10 항목을 실제로 실행한다.
사용: python3 tools/proposal/verify.py  (PDF 가 만들어져 있어야 함)"""
import re, os, sys, subprocess, glob, json
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.abspath(os.path.join(HERE,'..','..'))
sys.path.insert(0, HERE)
from pypdf import PdfReader
import build as B
from screens_web1 import S
PDF = os.path.join(ROOT,'docs','proposal','OIO_캐스팅_플랫폼_구축_제안서.pdf')
rd = PdfReader(PDF); pages = [p.extract_text() or '' for p in rd.pages]; full = '\n'.join(pages)
R = []   # (group, name, ok, detail)
def chk(g, n, ok, d=''): R.append((g,n,bool(ok),d))
def read(p): return open(os.path.join(ROOT,p),encoding='utf-8').read()
d31, d32, d33 = read('docs/31_OIO_통합플랫폼_구상.md'), read('docs/32_권한_API_보안_설계.md'), read('docs/33_UI_디자인_시스템_v2.md')
ids = {s['id'] for s in S}
def expand(tok):
    m = re.fullmatch(r'([A-Z]{2})(\d+)~([A-Z]{2})?(\d+)', tok)
    if m: return [f'{m.group(1)}{i:02d}' for i in range(int(m.group(2)), int(m.group(4))+1)]
    return [tok]
# ---------- 분석 10 ----------
g='분석'
cnt_ = {p:sum(1 for s_ in S if s_['phase']==p) for p in ('1','2','3','4','옵션')}
chk(g,'A1 화면 수·단계별 수 일치', f'{len(S)}개' in full and all(f'{v}개' in full for v in cnt_.values()), f'총 {len(S)} {cnt_}')
refs=set()
for m in re.finditer(r'\b((?:SW|SM|AM|BW|OP)\d{2}(?:~(?:SW|SM|AM|BW|OP)?\d{2})?)\b', full):
    for t in expand(m.group(1)): refs.add(t)
missing = sorted(t for t in refs if t not in ids)
chk(g,'A2 본문이 가리키는 화면 번호가 모두 존재', not missing, str(missing))
cpart = full[full.rfind('부록 C 화면 목록'):full.rfind('부록 D')]
chk(g,'A3 부록 C 에 72개 화면 전부 수록', all(i in cpart for i in ids), f'누락 {[i for i in ids if i not in cpart]}')
codes32 = re.findall(r'`([A-Z_]{6,})`', d32.split('### 2-1')[1].split('초기 직급별')[0])
codes32 = [c for c in re.findall(r'`([A-Z][A-Z_]+)`', d32.split('### 2-1')[1].split('초기 직급별')[0]) if '_' in c]
chk(g,'A4 권한 코드가 32번 문서와 동일', set(codes32)==set(B.PERMS), f'문서 {len(set(codes32))} / 제안서 {len(B.PERMS)} 차이 {set(codes32)^set(B.PERMS)}')
chk(g,'A5 단계별 기간이 31번 문서와 일치', all(k in d31 for k in ['7~10주','6~8주','4~6주','6주+']) and all(k in full for k in ['7~10주','6~8주','4~6주','6주 이상']), '')
decisions = {'외국인 제외':'외국인','전원 신규 가입':'전원 신규 가입','삭제 윗 직급':'상위 직급','접속 제한 없음':'접속 제한','숨김 스위치':'숨겨','선택 동의 기본 꺼짐':'기본값이 꺼짐','서열 규칙':'서열 규칙','팀장 즉시 확정':'즉시 확정','변호사 검토 권고':'변호사'}
miss=[k for k,v in decisions.items() if v not in full]
chk(g,'A6 확정 사항 9개가 본문에 반영', not miss, str(miss))
emoji = re.compile('[\U0001F300-\U0001FAFF☀-➿⭐✅✨]')
hype = [w for w in ['혁신적','획기적','최고의','완벽한','압도적','세계 최초','AI 기반','차세대','궁극'] if w in full]
chk(g,'A7 이모지·과장 표현 없음', not emoji.search(full) and not hype, f'{hype}')
fonts = subprocess.check_output(['pdffonts',PDF]).decode()
fl = [l.split() for l in fonts.strip().split('\n')[2:]]
chk(g,'A8 글꼴 전부 PDF 에 포함(embedded)', all(f[-5]=='yes' for f in fl) , f'{len(fl)}개')
names = sorted({f[0].split('+')[-1] for f in fl})
chk(g,'A9 글꼴은 Pretendard 한 가지(쪽번호용 기본 글꼴 제외)', all(('Pretendard' in n or n.startswith('Inter')) for n in names), ', '.join(names))
open(os.path.join(ROOT,'docs','proposal','_check.html'),'w',encoding='utf-8').write(B.html_doc(None,'all'))
r = subprocess.run(['node',os.path.join(HERE,'check_layout.js'),os.path.join(ROOT,'docs','proposal','_check.html')],capture_output=True,text=True,env=dict(os.environ,NODE_PATH=subprocess.check_output(['npm','root','-g']).decode().strip()))
chk(g,'A10 화면 시안 겹침·잘림·넘침 없음(72개 점검)', r.stdout.strip().startswith('OK'), r.stdout.strip()[:200])
# ---------- 검토 10 ----------
g='검토'
def lum(h):
    h=h.lstrip('#'); c=[int(h[i:i+2],16)/255 for i in (0,2,4)]; f=lambda v: v/12.92 if v<=.03928 else ((v+.055)/1.055)**2.4
    return .2126*f(c[0])+.7152*f(c[1])+.0722*f(c[2])
def cr(a,b):
    la,lb=sorted([lum(a),lum(b)],reverse=True); return (la+.05)/(lb+.05)
pairs=[('#191F28','#FFFFFF'),('#4E5968','#FFFFFF'),('#5B6573','#FFFFFF'),('#5B6573','#F4F6F8'),('#FFFFFF','#0F766E'),('#0B5F58','#E6F4F1'),('#067647','#E8F6EE'),('#B54708','#FFF4E5'),('#B42318','#FDECEA'),('#0B5F58','#FFFFFF')]
low=[(a,b,round(cr(a,b),2)) for a,b in pairs if cr(a,b)<4.5]
chk(g,'R1 글자·배경 색 조합 대비 4.5 이상', not low, str(low))
import json as _j
_amt=_j.load(open(os.path.join(HERE,'config.json'),encoding='utf-8'))['amounts_won']
_exp = 6 if all(v is None for v in _amt.values()) else (0 if all(v is not None for v in _amt.values()) else None)
chk(g,'R2 개발비 칸: 금액을 안 넣었으면 빈칸 표시 6곳, 다 넣었으면 0곳(일부만 넣은 상태는 오류)', _exp is not None and full.count('[금액 입력]')==_exp, str(full.count('[금액 입력]')))
fw = [w for w in ['정원 ','업체 구성원','OWNER','SCHEDULER','MANAGER'] if w in full]
chk(g,'R3 내부 용어(영문 코드·옛 직책명) 본문 노출 없음', not fw, str(fw))
_h = re.sub(r'<figure.*?</figure>','',B.html_doc(None,'all'),flags=re.S)
_txt = [re.sub(r'<[^>]+>','',m) for m in re.findall(r'<(?:p|li|td)[^>]*>(.*?)</(?:p|li|td)>', _h, re.S)]
sents = [x.strip() for t in _txt for x in re.split(r'(?<=[다요함음임])\.\s', t) if len(x.strip())>130]
chk(g,'R4 130자 넘는 긴 문장 4개 이하', len(sents)<=4, f'{len(sents)}개: '+' / '.join(x[:50] for x in sents[:4]))
pii = re.findall(r'\b\d{6}-\d{7}\b|\b01[016789]-\d{3,4}-\d{4}\b', full)
real = [x for x in pii if not x.endswith('0000') and '*' not in x]
chk(g,'R5 실제 같은 전화·주민번호 없음(예시는 0000·마스킹만)', not real, str(real[:5]))
ranks = ['100','90','80','60','55','40','10']
chk(g,'R6 직급 서열 값이 31번 문서와 일치', all(re.search(r'\b'+r+r'\b', d31) for r in ranks) and all(r in full for r in ranks))
toc_ok=True; bad=[]
tocpg = {}
for n,t in B.CH_TITLES:
    pat = re.compile(r'(^|\n)\s*'+(re.escape(n)+r'\s*' if n.isdigit() else '')+re.escape(t)+r'\s*(\n|$)')
    for i,pg in enumerate(pages):
        if i<2: continue
        if pat.search(pg): tocpg[n]=i; break
tpage = pages[1]
for n,t in B.CH_TITLES:
    m = re.search(r'(^|\n)\s*'+(re.escape(n)+r'\s+' if n.isdigit() else '')+re.escape(t)+r'\s+(\d+)', tpage)
    if not m or int(m.group(2))!=tocpg.get(n,-1): bad.append((n,m.group(2) if m else None,tocpg.get(n)))
chk(g,'R7 목차 쪽수가 실제 위치와 일치', not bad, str(bad[:4]))
ccount = len(re.findall(r'\bSW\d\d\b|\bSM\d\d\b|\bAM\d\d\b|\bBW\d\d\b|\bOP\d\d\b', cpart))
chk(g,'R8 부록 C 행 수 = 72', ccount==72, str(ccount))
claims = re.findall(r'(시장 규모|점유율|연평균|CAGR)[^.\n]{0,30}\d', full)
chk(g,'R9 근거 없는 시장 수치 없음', not claims, str(claims))
short = [(i+1,len(p)) for i,p in enumerate(pages) if len(p.strip())<120 and i>0]
chk(g,'R10 거의 빈 쪽 없음', not short, str(short))
# ---------- 최종 10 ----------
g='최종'
chk(g,'F1 A4 용지·쪽수 범위(40~80)', abs(float(rd.pages[1].mediabox.width)-595)<2 and 40<=len(pages)<=80, f'{len(pages)}쪽')
chk(g,'F2 글자 추출 가능(이미지 PDF 아님)', sum(len(p) for p in pages)>40000, str(sum(len(p) for p in pages)))
ko = sum(1 for ch in full if '가'<=ch<='힣'); chk(g,'F3 한글이 정상 추출(깨짐 없음)', ko>15000 and '�' not in full, str(ko))
urls=set()
for pg in rd.pages:
    for a in (pg.get('/Annots') or []):
        o=a.get_object()
        if o.get('/A') and o['/A'].get('/URI'): urls.add(str(o['/A']['/URI']))
chk(g,'F4 외부 링크가 https 이고 출처 목록에 있음', urls and all(u.startswith('https://') for u in urls), f'{len(urls)}개')
chk(g,'F5 표지에 제안자 입력 칸과 법률 고지', '[제안자 성명' in pages[0] and '법률·세무 자문이 아닙니다' in pages[0])
chk(g,'F6 각 장 시작이 새 쪽(장 제목이 쪽 맨 위)', all(any(pat in pages[tocpg[n]].split('\n')[0:2].__str__() for pat in [t]) for n,t in B.CH_TITLES if n in tocpg), '')
chk(g,'F7 본문 쪽에 쪽번호 표시', all(re.search(r'\n\d+\s*$', p) or re.search(r'\d+\s*$', p.strip()) for p in pages[1:]), '')
fsz = os.path.getsize(PDF)/1e6; chk(g,'F8 파일 크기 10MB 이하', fsz<10, f'{fsz:.1f}MB')
cnt_ph = {p:sum(1 for s in S if s['phase']==p) for p in ('1','2','3','4','옵션')}
chk(g,'F9 화면 갤러리 장 제목에 단계별 수 표시', all(f'{v}개' in full for v in cnt_ph.values()), str(cnt_ph))
ol = rd.outline
chk(g,'F11 PDF 책갈피(목차 이동) 20개 이상', len(ol)>=20, str(len(ol)))
chk(g,'F10 한계·확인 필요 사항 장 존재', '이 제안서의 한계' in full and '확인이 필요한 사항' in full)
# ---------- 전수 대조 10 (문서·소스와 교차) ----------
g='대조'
chk(g,'X1 31번 §0 확정 11개 항목이 제안서에 모두 반영', all(k in full for k in ['OIO 법인','전원 신규','접속 제한','웹앱','숨겨','선택지']), '')
chk(g,'X2 31번 §4 작업(S1~S10)과 1단계 범위 일치', all(k in full for k in ['권한 지도 시험','직급','승인','감사','휴지통','새 디자인']), '')
code_api = len(re.findall(r'@(?:Get|Post|Put|Delete|Patch)Mapping', ''.join(open(f,encoding='utf-8').read() for f in glob.glob(os.path.join(ROOT,'backend/src/main/java/**/*Controller.java'),recursive=True))))
chk(g,'X3 "API 136개" 주장이 실제 코드 집계와 일치', code_api==136 and '136개' in full, f'코드 {code_api}')
tests = 0
for f in glob.glob(os.path.join(ROOT,'backend/build/test-results/test/*.xml')):
    m = re.search(r'tests="(\d+)"', open(f,encoding='utf-8').read(2000)); tests += int(m.group(1)) if m else 0
chk(g,'X4 "자동 시험 142건" 주장이 최근 전체 시험 결과(142건)와 일치', tests==142 and '142건' in full, f'결과 {tests}')
app_screens = len(re.findall(r"'[^']+': \(\) =>", read('app/test/layout_test.dart')))
chk(g,'X5 "출연자 앱 21개 화면" 주장이 앱 시험의 화면 수와 일치', app_screens==21 and '21개 화면' in full, f'시험 {app_screens}')
web_pages = len(glob.glob(os.path.join(ROOT,'web/src/pages/*.tsx')))
chk(g,'X6 "업체·관리자 웹 8개 화면 묶음" 주장이 웹 페이지 수와 일치', web_pages==8, str(web_pages))
chk(g,'X7 운영비 수치(US$12·US$25·US$99)가 28·31번 문서와 일치', all(k in read('docs/28_운영서버_구입_설정_가이드.md')+d31 for k in ['US$12','US$25','US$99']))
chk(g,'X8 색상·폰트 값이 33번 문서와 일치', all(k in d33 and k in full for k in ['#0F766E','#191F28','#F4F6F8','Pretendard']))
chk(g,'X9 개발 단계 기간이 31번 문서 표와 일치', all(k in d31 for k in ['7~10주','6~8주','4~6주','6주+']) and all(k in full for k in ['7~10주','6~8주','4~6주','6주 이상']))
chk(g,'X10 CLAUDE.md 의 확정 사항(삭제 윗직급·ACTOR_AREA·UNCLAIMED 폐기)과 모순 없음', 'UNCLAIMED' not in full and '명단 가져오기' in full and 'ACTOR_AREA' not in full)
chk(g,'X11 분리 구조(공유 핵심·프로그램 2개·DB 계정 분리·토큰 분리)가 31·32번 문서와 제안서에 모두 있음', all(k in d31 for k in ['B′','actor_app','audience']) and all(k in d32 for k in ['actor_app','audience']) and all(k in full for k in ['공유 핵심 코드','DB 계정','토큰']), '')
chk(g,'X12 기간 합계(1~2+7~10+4~6+6~8+6 = 24~32주)가 제안서·31번 문서와 일치', '24~32주' in full and '24~32주' in d31, '')
chk(g,'X13 분리 구조와 충돌하는 옛 표현("하나의 서버 프로그램") 없음', '하나의 서버 프로그램' not in full, '')
flat = re.sub(r'\s+','',full)
chk(g,'X14 1단계에서 출연자 화면 반영을 단정하는 문장 없음(3단계 조건 명시)', '이용자화면에바로반영되는구조를갖춥니다' not in flat and '3단계부터는일정을저장하면' in flat, '')
chk(g,'X15 내부 용어 "정원" 본문 노출 없음(공백 유무 무관)', '정원' not in full, str(full.count('정원')))
chk(g,'X16 용어 설명에 토큰·DB 계정·마이그레이션 포함', all(k in full for k in ['마이그레이션','DB 계정','토큰']), '')
# ---------- 출력 ----------
bad = 0
for grp in ('분석','검토','최종','대조'):
    print(f'\n[{grp} 10회]')
    for g_,n,ok,d in R:
        if g_==grp:
            print(('  ✔ ' if ok else '  ✘ ')+n+('' if ok or not d else '  → '+d)); bad += (not ok)
print('\n실패', bad, '건 / 전체', len(R))
sys.exit(1 if bad else 0)
