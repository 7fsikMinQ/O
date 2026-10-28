#!/usr/bin/env python3
"""docs/mockups/*.html 생성기 — 출연자 앱 20화면 + 업체·관리자 웹 9화면(라이트/다크). 실행: python3 tools/build_mockups.py
스타일 원칙(사용자 확정): 파스텔(라벤더·민트·피치), 쉬운 말, 이모지, 본문 16px·버튼 52px, 겹침·잘림 없음. 점검: tools/check_mockups.js"""
import os
import re
D = os.path.join(os.path.dirname(__file__), '..', 'docs', 'mockups')
TABS = '<div class="tabbar"><span class="on">🏠<br>홈</span><span>🔎<br>일 찾기</span><span>📅<br>내 일정</span><span>💬<br>메시지</span><span>🙂<br>내 정보</span></div>'
NOTICE = '<div class="card cap">⚠️ 시간은 모두 <b>예상</b>이에요. 확정이 아니고, 촬영 상황에 따라 <b>바뀌거나 늦게 끝날 수 있어요.</b></div>'


def phone(title, sub, body, cta=None, tab=False, back=True):
    c = f'<div class="cta">{cta}</div>' if cta else ''
    b = '<button class="back">← 뒤로</button>' if (back and not tab) else ''
    return (f'<section class="phone" aria-label="{title}">{b}<div class="top">{title}</div><div class="sub">{sub}</div>'
            f'<div class="body">{body}</div>{c}{TABS if tab else ""}</section>')


def btn(t, kind=''):
    return f'<button class="btn {kind}">{t}</button>'


def field(k, v):
    return f'<div class="field"><span>{k}</span><span>{v}</span></div>'


def chk(t, on=True):
    return f'<div class="check"><i class="{"" if on else "off"}">{"✓" if on else ""}</i><span>{t}</span></div>'


def chips(items, kind=''):
    return '<div class="wrap">' + ''.join(f'<span class="chip {kind}">{x}</span>' for x in items) + '</div>'


def job(t, pay, tags, place, when):
    return (f'<div class="card"><div class="h">{t}</div><div class="cap">📍 {place}</div><div><b>{when}</b></div>'
            f'<div class="pay">{pay}</div>{chips(tags)}</div>')


def steps(n, total=3):
    return '<div class="steps">' + ''.join(f'<i class="{"on" if i < n else ""}"></i>' for i in range(total)) + '</div>'


P = []
# 1~3 시작
P.append(phone('👋 시작하기', '휴대폰 번호만 있으면 돼요',
    field('휴대폰 번호', '010-1234-5678') + '<div class="card cap">문자로 숫자 6자리를 보내 드려요. 비밀번호는 필요 없어요.</div>',
    btn('문자 숫자 받기') + btn('약관·개인정보 처리방침 보기', 'ghost'), back=False))
P.append(phone('🔢 숫자 6자리 입력', '010-1234-5678 로 보냈어요',
    '<div class="otp"><b>3</b><b>8</b><b>1</b><b>9</b><b>·</b><b>·</b></div><div class="row"><span class="cap">남은 시간 02:41</span><span class="cap">다시 받기는 60초 뒤</span></div>'
    '<div class="alert">문자가 안 오면 번호가 맞는지 확인해 보세요. 그래도 안 되면 전화로 도와 드려요 ☎️</div>',
    btn('확인') + btn('번호 다시 입력', 'ghost')))
P.append(phone('📝 가입하기', '이름을 적고 약관에 동의해 주세요',
    field('이름', '김보출') + '<div class="card">' + chk('전체 동의') + chk('(필수) 서비스 이용약관') + chk('(필수) 개인정보 수집·이용') + chk('(필수) 지원한 업체에 번호 제공') + chk('(선택) 소식 받기', False) + '</div>',
    btn('가입하기 ✅')))
# 4 홈
P.append(phone('🏠 일할 곳 찾기', '12월 20일 · 서울·경기',
    job('사극 A현장 (가제)', '시급 10,320원 (최저시급)', ['3명 뽑아요', '🌙 밤 촬영', '🏕 숙박'], '경기 양주', '아침 6시쯤 모여요 (±2시간)')
    + job('현대극 B (가제)', '시급 12,000원', ['10명 뽑아요'], '서울 마포', '낮 1시쯤 모여요 (±2시간)') + NOTICE, tab=True))
# 5 공고 상세
P.append(phone('🎬 공고 보기', '사극 A현장 (가제) · 보출A 🔒',
    '<div class="card"><div class="h">아침 6시쯤 모여요 (±2시간)</div><div class="cap">끝나는 때: 저녁 6시쯤 (±4시간)</div><div class="cap">📍 경기 양주 · 자세한 장소는 뽑힌 뒤에 알려 드려요</div></div>'
    '<div class="card"><div class="h">궁녀 · 3명</div>' + chips(['여자', '1990~2000년생', '키 155~170', '머리 길게 가능']) + '<div class="pay">시급 10,320원 (최저시급)</div><div class="cap">식대는 비고를 확인해 주세요</div></div>'
    + '<div class="alert ok">✔ 내 조건에 맞아요</div>' + NOTICE, btn('지원하기 ✍️') ))
# 6~9 지원
P.append(phone('✍️ 지원하기', '1단계 / 3단계 · 내 정보 확인',
    steps(1) + field('이름', '김보출') + field('휴대폰', '010-****-1234') + field('키 · 몸무게', '172cm · 64kg')
    + '<div class="alert">⚠️ 같은 날 다른 일과 시간이 가까워요. 그래도 지원할 수 있어요.</div>',
    btn('다음: 내 사진 찍기 📷') + btn('나중에 이어서 하기', 'ghost')))
P.append(phone('📷 현재 사진', '2단계 / 3단계 · 지금 모습으로 찍어 주세요',
    steps(2) + '<div class="photo">🙂</div><div class="card cap">밝은 곳에서 얼굴과 상체가 잘 보이게 찍어 주세요. 사진은 지원한 업체만 볼 수 있어요.</div>',
    btn('사진 찍기 📷') + btn('앨범에서 고르기', 'ghost')))
P.append(phone('🖋 서명하기', '3단계 / 3단계 · 마지막이에요',
    steps(3) + '<div class="sig">손가락으로 이름을 써 주세요</div><div class="card cap">서명과 지원서는 증빙으로 보관돼요(기본 5년).</div>',
    btn('지원 완료하기 ✅') + btn('다시 쓰기', 'ghost')))
P.append(phone('🎉 지원했어요', '업체가 보고 뽑으면 알려 드려요',
    '<div class="card"><div class="h">사극 A현장 (가제)</div><div class="cap">내 지원서는 <b>내 일정</b>에서 볼 수 있어요</div></div><div class="card cap">뽑히지 않으면 마감 뒤에 알려 드려요. 지원은 마감 전까지 취소할 수 있어요.</div>',
    btn('내 일정 보기') + btn('다른 일 더 찾기', 'ghost')))
# 10~12 합격
P.append(phone('🎉 뽑혔어요!', '12시간 안에 “참여할게요”를 눌러 주세요',
    '<div class="card"><div class="h">사극 A현장 (가제)</div><div class="cap">12월 20일 · 양주 ○○주차장</div><div><b>아침 6시쯤 모여요 (±2시간)</b></div><div><span class="chip warn">⏰ 11시간 남았어요</span></div></div>'
    '<div class="card"><div class="h">가는 길 · 연락</div>' + btn('📍 지도 열기 (네이버·카카오)', 'line') + btn('📞 반장님께 전화', 'line') + btn('☎️ 업체에 전화', 'line') + '</div>' + NOTICE,
    btn('✅ 참여할게요') + btn('📄 합격서 보기', 'ghost')))
P.append(phone('⚠️ 시간이 가까워요', '두 일을 모두 하려면 알려 주세요',
    '<div class="card"><div class="h">사극 A현장 (가제)</div><div class="cap">12월 20일 아침 6시쯤</div></div><div class="card"><div class="h">현대극 B (가제)</div><div class="cap">12월 20일 낮 1시쯤</div></div>'
    '<div class="alert">두 일 사이가 2시간보다 짧아요. 촬영이 길어지면 두 번째 일에 늦을 수 있어요. 두 업체에도 알려 드려요.</div>',
    btn('그래도 둘 다 할게요') + btn('다시 생각할게요', 'ghost')))
P.append(phone('📄 합격서', '2026년 12월 18일 뽑힘',
    '<div class="card"><div class="cap">업체</div><div class="h">보출A</div><div class="row"><span class="cap">현장</span><b>사극 A현장 (가제)</b></div><div class="row"><span class="cap">역할</span><b>궁녀</b></div>'
    '<div class="row"><span class="cap">모이는 때</span><b>12/20 아침 6시쯤 (±2시간)</b></div><div class="row"><span class="cap">끝나는 때</span><b>저녁 6시쯤 (±4시간)</b></div></div>'
    '<div class="card cap">⚠️ 위 시간은 모두 예상이에요. 확정이 아니며 변동될 수 있고 <b>추가로 길어질 수 있어요.</b> 보수는 공고를 확인해 주세요. 근로계약서는 현장에서 따로 써요.</div>',
    btn('PDF로 저장하기 ⬇️')))
# 13 QR, 14 메시지
P.append(phone('📲 출근 확인 QR', '현장 직원에게 보여 주세요',
    '<div class="card" style="text-align:center"><div class="qr" role="img" aria-label="출근 확인 QR"></div><span class="chip ok">🔄 30초마다 바뀌어요</span><div class="cap">직원이 출근 확인을 열어야 보여요</div></div>'
    '<div class="card cap">☎️ 도움이 필요하면 <b>고객센터에 전화</b>해 주세요</div>'))
P.append(phone('💬 보출A', '12월 20일 사극 A현장',
    '<div class="bubble"><b>집합 장소가 바뀌었어요.</b><br>양주 ○○주차장 → △△주차장이에요.<div class="cap">읽음 · 18:03</div></div><div class="bubble me">네, 확인했어요!</div>'
    '<div class="field"><span>답장</span><span>메시지를 입력해 주세요</span></div>',
    btn('보내기') + '<div class="row"><button class="btn ghost small">🚫 이 업체 차단</button><button class="btn ghost small">🚩 신고</button></div>'))
# 15~19 내 정보
P.append(phone('🙂 내 정보', '김보출님', '<div class="menu">' + ''.join(field(k, '›') for k in ['내 프로필·사진', '주민등록번호', '정산 계좌', '알림 설정', '차단한 업체', '약관·개인정보 처리방침', '고객센터 전화', '로그아웃']) + '</div>'
    '<div class="field" style="color:var(--bad-t)"><span style="color:var(--bad-t)">탈퇴하기</span><span>›</span></div>', tab=True))
P.append(phone('🧍 내 프로필', '업체가 지원자를 고를 때 보는 정보예요',
    field('생년월일', '1990.01.01') + field('성별', '남') + field('키 · 몸무게', '175cm · 68kg') + field('옷 · 신발', '상의 100 · 하의 32 · 270') + field('머리', '짧음') + field('문신', '없음')
    + field('사는 곳', '서울 강남구') + '<div class="cap">생년월일·성별은 주민등록번호를 등록한 뒤에는 바꿀 수 없어요.</div>', btn('저장하기')))
P.append(phone('🪪 주민등록번호', '급여 신고에 필요해요. 첫 지원 전에 한 번만 등록해요',
    '<div class="alert ok">🔒 암호화해서 보관하고, 업체 화면에는 가려서 보여 드려요</div>' + field('주민등록번호', '900101-1●●●●●●') + '<div class="card">' + chk('(필수) 고유식별정보 처리에 동의해요') + '</div>'
    '<div class="card cap">등록된 번호: <b>900101-1******</b><br>다시 보여 드리지 않아요. 바꾸려면 새로 입력해 주세요.</div>', btn('등록하기')))
P.append(phone('🏦 정산 계좌', '일한 뒤 업체가 이 계좌로 보내요',
    field('은행', 'KB국민은행') + field('계좌번호', '****6789') + field('예금주', '김보출') + '<div class="alert ok">✔ 예금주가 내 이름과 같아요</div>'
    '<div class="card cap">예금주가 내 이름과 다르면 알려 드려요. 업체가 본인 계좌로 직접 보내요.</div>', btn('저장하기')))
P.append(phone('📲 홈 화면에 추가', '아이폰은 이렇게 하면 알림을 받을 수 있어요',
    '<div class="card"><div class="h">1. 아래 공유 버튼 누르기 ⬆️</div></div><div class="card"><div class="h">2. “홈 화면에 추가” 고르기 ➕</div></div><div class="card"><div class="h">3. 홈 화면의 앱으로 다시 열기</div></div>'
    '<div class="card cap">홈 화면에 추가해야 알림이 와요. 건너뛰면 문자·메일로 알려 드려요.</div>', btn('알겠어요') + btn('나중에', 'ghost')))
P.append(phone('⬆️ 업데이트가 필요해요', '새 버전으로 바꿔야 계속 쓸 수 있어요',
    '<div class="card"><div class="h">보조출연 헬프 새 버전</div><div class="cap">더 안전하고 빨라졌어요</div></div>', btn('스토어에서 업데이트'), back=False))

# ── 웹
def web(title, body, nav='로그인'):
    return f'<main><section class="web" aria-label="{title}"><div class="wtitle">{title}</div><div class="sect">{body}</div></section></main>'


def lbl(k, v):
    return f'<div class="lbl">{k}<div class="input">{v}</div></div>'


def table(heads, rows):
    return '<div class="scroll"><table><thead><tr>' + ''.join(f'<th>{h}</th>' for h in heads) + '</tr></thead><tbody>' + ''.join('<tr>' + ''.join(f'<td>{c}</td>' for c in r) + '</tr>' for r in rows) + '</tbody></table></div>'


STEPPER = '<div class="stepper"><span class="chip ok">✔ 1 가입</span><span class="chip ok">✔ 2 이메일 확인</span><span class="chip">3 업체 등록</span><span class="chip">4 관리자 승인</span></div>'
W = []
W.append(web('🔐 업체 로그인', '<div class="formgrid">' + lbl('이메일', 'staff@bochul-a.example') + lbl('비밀번호', '●●●●●●●●●●') + '</div>'
    '<div class="alert bad sect">비밀번호를 5번 잘못 입력해서 이 접속 위치(203.0.113.***)에서의 로그인을 15분 동안 막았어요. 다른 곳에서는 평소처럼 로그인할 수 있어요.</div>'
    '<div class="wrap sect"><button class="btn small">로그인</button><button class="btn small ghost">비밀번호를 잊었어요</button></div>'))
W.append(web('📧 이메일을 확인해 주세요', STEPPER + '<div class="card"><div class="h">확인 메일을 보냈어요</div><div class="cap">staff@bochul-a.example 로 보낸 메일의 주소를 24시간 안에 눌러 주세요.</div></div>'
    '<div class="card cap sect">메일이 안 보이면 스팸함을 확인하거나 아래 버튼으로 다시 받아 보세요. (1분에 1번, 하루 5번까지)</div>'
    '<div class="wrap sect"><button class="btn small">확인 메일 다시 받기</button><button class="btn small ghost">로그아웃</button></div>'))
W.append(web('🔑 새 비밀번호로 바꿔 주세요', '<div class="alert">임시 비밀번호로 로그인했어요. 새 비밀번호로 바꾸기 전에는 다른 화면을 쓸 수 없어요.</div><div class="formgrid sect">'
    + lbl('새 비밀번호', '●●●●●●●●●●●●') + lbl('새 비밀번호 다시 입력', '●●●●●●●●●●●●') + '</div><div class="cap sect">10자 이상, 영문과 숫자를 함께 써 주세요. 바꾸면 모든 기기에서 로그아웃돼요.</div>'
    '<div class="wrap sect"><button class="btn small">비밀번호 바꾸기</button></div>'))
W.append(web('🏢 업체 등록', STEPPER.replace('<span class="chip">3 업체 등록</span>', '<span class="chip warn">➜ 3 업체 등록</span>') + '<div class="formgrid">' + lbl('상호', '보출A') + lbl('사업자등록번호', '220-81-62517') + lbl('대표자', '김대표') + lbl('전화', '02-123-4567')
    + lbl('주소', '서울 마포구 ○○로 1') + lbl('소개', '사극·현대극 보조출연 전문') + '</div>'
    '<div class="pdf sect">📎 사업자등록증 올리기<br>PDF · JPG · PNG, 3개까지</div><div class="wrap sect"><button class="btn small">등록 신청</button></div>'))
W.append(web('⏳ 승인을 기다리고 있어요', '<div class="stepper"><span class="chip ok">✔ 1 가입</span><span class="chip ok">✔ 2 이메일 확인</span><span class="chip ok">✔ 3 업체 등록</span><span class="chip warn">⏳ 4 관리자 승인</span></div>'
    '<div class="card"><div class="h">보출A · 검토 중</div><div class="cap">승인되면 메일로 알려 드려요. 승인 전에는 공고를 올릴 수 없어요.</div></div>'
    '<div class="card sect"><div class="h">그동안 할 수 있어요</div><div class="cap">직원 초대, 업체 소개 고치기</div></div>'))
W.append(web('👥 직원 관리', '<div class="wrap">' + '<span class="chip">스케줄러 담당 3/4명</span><span class="chip ok">대표 1명</span></div>'
    + '<div class="sect">' + table(['이름', '이메일', '직책', '상태', ''], [
        ['김대표', 'owner@bochul-a.example', '대표', '<span class="chip ok">활동 중</span>', '—'],
        ['이팀장', 'lead@bochul-a.example', '팀장', '<span class="chip ok">활동 중</span>', '<button class="btn small line">바꾸기</button>'],
        ['박반장', 'field@bochul-a.example', '현장반장', '<span class="chip">초대함</span>', '<button class="btn small line">취소</button>']]) + '</div>'
    '<div class="formgrid sect">' + lbl('초대할 이메일', 'new@bochul-a.example') + lbl('직책', '스태프') + '</div><div class="cap sect">이메일 확인을 마친 가입자만 초대할 수 있어요.</div>'
    '<div class="wrap sect"><button class="btn small">초대하기</button></div>'))
W.append(web('📋 12월 20일 사극 A현장 · 인원 정보표', '<div class="wrap"><span class="chip ok">참여 확정 8/10명</span></div><div class="sect">' + table(['이름', '역할', '휴대폰', '주민번호', '계좌', '상태', '⭐'], [
    ['김보출', '궁녀', '010-1234-5678', '900101-1******', '국민 ****1234', '<span class="chip ok">✅ 참여 확정</span>', '<button class="btn star" aria-label="별표 표시">⭐</button>'],
    ['이엑스', '군중', '010-2222-3333', '880505-1******', '—', '<span class="chip">📨 뽑았어요(답 기다림)</span>', '—'],
    ['박지원', '궁녀', '010-9999-0000', '950707-2******', '신한 ****5678', '<span class="chip warn">⚠️ 다른 일과 겹침</span>', '—']]) + '</div>'
    '<div class="row sect"><span class="cap">주민번호 전체 보기는 이유를 적어야 하고 기록이 남아요.</span><button class="btn small">엑셀 받기 (비밀번호 있음)</button></div>'))
W.append(web('📢 공지 읽음 현황 · “집합 장소 안내”', '<div class="wrap"><span class="chip warn">안 읽음 2명</span></div><div class="sect">' + table(['이름', '받은 시각', '읽은 시각', '다시 알림', '⭐'], [
    ['김보출', '12/19 18:00', '12/19 18:03', '—', '<button class="btn star" aria-label="별표 표시">⭐</button>'],
    ['이엑스', '12/19 18:00', '<span class="chip warn">아직 안 읽음</span>', '1회', '—'],
    ['박지원', '12/19 18:00', '<span class="chip warn">아직 안 읽음</span>', '0회', '—']]) + '</div>'
    '<div class="row sect"><span class="cap">다시 알림은 한 공지당 최대 5번이에요. 문자 알림은 동의한 분께만 가요.</span><button class="btn small">안 읽은 2명에게 다시 알리기</button></div>'))
W.append(web('🛡 업체 승인 (관리자)', '<div class="wrap"><span class="chip warn">대기 3건</span></div><div class="split sect"><div class="card"><div class="h">보출A</div>'
    + field('사업자번호', '220-81-62517') + field('대표자', '김대표') + field('신청한 사람', '김대표 (이메일 확인 완료)') + field('신청 시각', '12/18 09:12')
    + '</div><div class="pdf">📄 사업자등록증 (5분 동안만 열려요)<br>열람 기록이 남아요</div></div>'
    '<div class="formgrid sect">' + lbl('반려 사유 (반려할 때 필수)', '등록증이 흐려서 읽을 수 없어요') + '</div>'
    '<div class="wrap sect"><button class="btn small">승인</button><button class="btn small danger">반려</button></div>'))


EMOJI = re.compile(r'(?<=\S) ((?:[\u2190-\u21ff\u2300-\u23ff\u2600-\u27bf\u2b00-\u2bff\U0001F000-\U0001FAFF]\uFE0F?)+)(?=<|$)')


def glue(html):
    """문장 끝의 이모지가 혼자 다음 줄로 떨어지지 않게 앞 공백을 줄바꿈 금지 공백으로 바꾼다."""
    return EMOJI.sub(r'&nbsp;\1', html)


def page(theme, title, body, cls=''):
    return (f'<!doctype html><html lang="ko" data-theme="{theme}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            f'<title>{title}</title><link rel="stylesheet" href="tokens.css"><script>if(/device=1/.test(location.search))document.documentElement.classList.add("device")</script></head><body>{body}</body></html>')


for th, sfx in (('light', ''), ('dark', '_dark')):
    open(f'{D}/app{sfx}.html', 'w', encoding='utf-8').write(page(th, '보조출연 헬프 앱 목업', glue(f'<main class="grid">{"".join(P)}</main>')))
    open(f'{D}/web{sfx}.html', 'w', encoding='utf-8').write(page(th, '보조출연 헬프 웹 목업', glue(''.join(W))))
# 옛 파일 정리(웹 한 파일로 통합)
for old in ('web_notice.html', 'web_notice_dark.html', 'shot_web_notice.png', 'shot_web_notice_dark.png'):
    try:
        os.remove(os.path.join(D, old))
    except FileNotFoundError:
        pass
print(f'앱 {len(P)}화면 · 웹 {len(W)}화면')
