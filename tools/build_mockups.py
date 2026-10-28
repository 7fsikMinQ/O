#!/usr/bin/env python3
"""docs/mockups/*.html 생성기 (쉬운 말 UI 시안). 실행: python3 tools/build_mockups.py"""
import os
D=os.path.join(os.path.dirname(__file__),'..','docs','mockups')
def phone(title,sub,body,cta=None,tab=True):
    c=f'<div class="cta">{cta}</div>' if cta else ''
    t='<div class="tabbar"><span class="on">🏠<br>홈</span><span>🔎<br>일 찾기</span><span>📅<br>내 일정</span><span>💬<br>메시지</span><span>🙂<br>내 정보</span></div>' if tab else ''
    back='' if tab else '<button class="back">← 뒤로</button>'
    return f'<section class="phone" aria-label="{title}">{back}<div class="top">{title}</div><div class="sub">{sub}</div><div class="body">{body}</div>{c}{t}</section>'
NOTICE='<div class="card cap">⚠️ 시간은 모두 <b>예상</b>이에요. 확정이 아니고, 촬영 상황에 따라 <b>바뀌거나 늦게 끝날 수 있어요.</b></div>'
def job(t,pay,chips,place,when):
    return f'<div class="card"><div class="h">{t}</div><div class="cap" style="margin-top:4px">📍 {place}</div><div style="margin-top:8px"><b>{when}</b></div><div class="pay" style="margin-top:8px">{pay}</div><div style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap">{"".join(f"<span class=chip>{x}</span>" for x in chips)}</div></div>'
P=[]
P.append(phone('🏠 일할 곳 찾기','12월 20일 · 서울·경기',
 job('사극 A현장 (가제)','시급 10,320원 (최저시급)',['3명 뽑아요','🌙 밤 촬영','🏕 숙박'],'경기 양주','아침 6시쯤 모여요 (±2시간)')+
 job('현대극 B (가제)','시급 12,000원',['10명 뽑아요'],'서울 마포','낮 1시쯤 모여요 (±2시간)')+NOTICE))
P.append(phone('✍️ 지원하기','1단계 / 3단계 · 내 정보 확인',
 '<div class="steps" style="padding:0"><i class="on"></i><i></i><i></i></div><div class="field"><span>이름</span><b>김보출</b></div><div class="field"><span>휴대폰</span><b>010-****-1234</b></div><div class="field"><span>키 · 몸무게</span><b>172cm · 64kg</b></div><div class="alert">⚠️ 같은 날 다른 일과 시간이 가까워요. 그래도 지원할 수 있어요.</div>',
 '<button class="btn">다음: 내 사진 찍기 📷</button><button class="btn ghost">나중에 이어서 하기</button>',False))
P.append(phone('🖋 서명하기','3단계 / 3단계 · 마지막이에요','<div class="steps" style="padding:0"><i class="on"></i><i class="on"></i><i class="on"></i></div><div class="sig">손가락으로 이름을 써 주세요</div><div class="card cap">서명과 지원서는 증빙으로 보관돼요(기본 5년).</div>',
 '<button class="btn">지원 완료하기 ✅</button><button class="btn ghost">다시 쓰기</button>',False))
P.append(phone('🎉 뽑혔어요!','12시간 안에 “참여할게요”를 눌러 주세요',
 '<div class="card"><div class="h">사극 A현장 (가제)</div><div class="cap">12월 20일 · 양주 ○○주차장</div><div style="margin-top:8px"><b>아침 6시쯤 모여요 (±2시간)</b></div><span class="chip warn" style="margin-top:8px">⏰ 11시간 남았어요</span></div>'+'<div class="card"><div class="h">가는 길 · 연락</div><button class="btn line">📍 지도 열기 (네이버·카카오)</button><button class="btn line">📞 반장님께 전화</button><button class="btn line">☎️ 업체에 전화</button></div>'+NOTICE,
 '<button class="btn">✅ 참여할게요</button><button class="btn ghost">📄 합격서 보기</button>',False))
P.append(phone('📄 합격서','2026년 12월 18일 합격','<div class="card"><div class="cap">업체</div><div class="h">보출A</div><hr style="border:0;border-top:1px solid var(--line);margin:12px 0"><div class="row"><span class="cap">현장</span><b>사극 A현장 (가제)</b></div><div class="row"><span class="cap">역할</span><b>궁녀</b></div><div class="row"><span class="cap">모이는 때</span><b>12/20 아침 6시쯤 (±2시간)</b></div><div class="row"><span class="cap">끝나는 때</span><b>저녁 6시쯤 (±4시간)</b></div></div><div class="card cap">⚠️ 위 시간은 모두 예상이에요. 확정이 아니며 변동될 수 있고 <b>추가로 길어질 수 있어요.</b> 보수는 공고를 확인해 주세요. 근로계약서는 현장에서 따로 써요.</div>',
 '<button class="btn">PDF로 저장하기 ⬇️</button>',False))
P.append(phone('📲 출근 확인 QR','현장 직원에게 보여 주세요','<div class="card" style="text-align:center"><div class="qr" role="img" aria-label="출근 확인 QR"></div><span class="chip ok">🔄 30초마다 바뀌어요</span><div class="cap" style="margin-top:8px">직원이 출근 확인을 열어야 보여요</div></div><div class="card cap">☎️ 도움이 필요하면 <b>고객센터에 전화</b>해 주세요</div>',None))
P.insert(0,phone('👋 시작하기','휴대폰 번호만 있으면 돼요',
 '<div class="field"><span>휴대폰 번호</span><b>010-1234-5678</b></div><div class="card cap">문자로 숫자 6자리를 보내 드려요. 비밀번호는 필요 없어요.</div><div class="field"><span>문자로 온 숫자</span><b>● ● ● ● ● ●</b></div><div class="cap" style="text-align:center">문자가 안 오나요? <b>다시 받기</b> · ☎️ 전화 문의</div>',
 '<button class="btn">문자 숫자 받기</button><button class="btn ghost">약관·개인정보 처리방침 보기</button>',False))
P.insert(1,phone('🙂 내 정보','김보출님','<div class="field"><span>내 프로필·사진</span><span>›</span></div><div class="field"><span>주민등록번호 등록</span><span class="chip warn">첫 지원 전 필요</span></div><div class="field"><span>알림 설정</span><span>›</span></div><div class="field"><span>차단한 업체</span><span>›</span></div><div class="field"><span>약관·개인정보 처리방침</span><span>›</span></div><div class="field"><span>고객센터 전화</span><span>›</span></div><div class="field"><span>로그아웃</span><span>›</span></div><div class="field" style="color:var(--bad-t)"><span>탈퇴하기</span><span>›</span></div>'))
def page(theme,title,body): return f'<!doctype html><html lang="ko" data-theme="{theme}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{title}</title><link rel="stylesheet" href="tokens.css"></head><body>{body}</body></html>'
for th,sfx in(('light',''),('dark','_dark')):
    open(f'{D}/app{sfx}.html','w',encoding='utf-8').write(page(th,'보조출연 헬프 앱 목업',f'<main class="grid">{"".join(P)}</main>'))
web='''<main><section class="web" aria-label="인원 정보표"><div class="row" style="margin-bottom:12px"><span class="top" style="padding:0">12월 20일 사극 A현장 · 인원 정보표</span><span class="chip ok">참여 확정 8/10명</span></div>
<table><thead><tr><th>이름</th><th>역할</th><th>휴대폰</th><th>주민번호</th><th>계좌</th><th>상태</th></tr></thead><tbody>
<tr><td>김보출</td><td>궁녀</td><td>010-1234-5678</td><td>900101-*******</td><td>국민 ****1234</td><td><span class="chip ok">✅ 참여 확정</span></td></tr>
<tr><td>이엑스</td><td>군중</td><td>010-2222-3333</td><td>880505-*******</td><td>—</td><td><span class="chip">📨 뽑았어요(답 기다림)</span></td></tr>
<tr><td>박지원</td><td>궁녀</td><td>010-9999-0000</td><td>950707-*******</td><td>신한 ****5678</td><td><span class="chip warn">⚠️ 다른 일과 겹침</span></td></tr></tbody></table>
<div class="row" style="margin-top:16px"><span class="cap">주민번호 전체 보기는 이유를 적어야 하고 기록이 남아요.</span><button class="btn" style="width:auto;padding:0 24px">엑셀 받기 (비밀번호 있음)</button></div></section></main>'''
for th,sfx in(('light',''),('dark','_dark')): open(f'{D}/web{sfx}.html','w',encoding='utf-8').write(page(th,'업체 웹 목업',web))

notice='''<main><section class="web" aria-label="공지 읽음 현황"><div class="row" style="margin-bottom:12px"><span class="top" style="padding:0">공지 읽음 현황 · "집합 장소 안내"</span><span class="chip warn">안 읽음 2명</span></div>
<table><thead><tr><th>이름</th><th>받은 시각</th><th>읽은 시각</th><th>다시 알림</th><th>단골</th></tr></thead><tbody>
<tr><td>김보출</td><td>12/19 18:00</td><td>12/19 18:03</td><td>—</td><td><button class="btn" style="width:auto;height:48px;padding:0 16px;font-size:16px">⭐ 단골 저장</button></td></tr>
<tr><td>이엑스</td><td>12/19 18:00</td><td><span class="chip warn">아직 안 읽음</span></td><td>1회</td><td>—</td></tr>
<tr><td>박지원</td><td>12/19 18:00</td><td><span class="chip warn">아직 안 읽음</span></td><td>0회</td><td>—</td></tr></tbody></table>
<div class="row" style="margin-top:16px"><span class="cap">다시 알림은 한 공지당 최대 5번이에요. 문자 알림은 동의한 분께만 가요.</span><button class="btn" style="width:auto;padding:0 24px">안 읽은 2명에게 다시 알리기</button></div></section></main>'''
for th,sfx in(('light',''),('dark','_dark')): open(f'{D}/web_notice{sfx}.html','w',encoding='utf-8').write(page(th,'공지 읽음 목업',notice))
