# -*- coding: utf-8 -*-
from lib import *
S = []
def add(id, title, phase, kind, who, purpose, points, perms, html):
    S.append(dict(id=id, title=title, phase=phase, kind=kind, who=who, purpose=purpose, points=points, perms=perms, html=html))

# ---- 직원 웹 (1단계) ----
add('SW01','로그인','1','web','직원 전체','이메일과 비밀번호로 들어갑니다. 관리자는 2차 비밀번호 칸이 추가로 나옵니다.',
 ['5회 틀리면 해당 IP만 15분 잠금, 계정 주인에게 알림 메일','승인 전·정지 계정은 로그인 후 안내 화면만 보임','대표·이사·관리자는 운영 전 인증 앱(TOTP) 적용'],'공개',
 bare(card(f'<div style="width:320px;display:grid;gap:14px"><div><div class="brand" style="padding:0 0 4px;font-size:18px">OIO 캐스팅</div><div class="cap">직원 전용 업무 시스템</div></div>{field("이메일","staff@oio.example")}{field("비밀번호","••••••••••")}{field("2차 비밀번호 (관리자 전용)","••••••••",ph=False)}<span class="btn lg">로그인</span><div class="row" style="justify-content:space-between"><span class="btn text" style="padding:0">비밀번호를 잊었어요</span><span class="btn text" style="padding:0">가입 신청</span></div></div>',28), minh=470))
add('SW02','직원 가입 신청','1','web','신규 직원','이름·이메일·연락처·희망 직급을 적어 가입을 신청합니다. 이메일 확인 후 승인 대기가 됩니다.',
 ['이메일 확인 링크를 눌러야 신청이 접수됨','스팸 방지: 같은 IP 시간당 5건 제한, 미확인 신청 24시간 뒤 삭제','희망 직급은 참고용이며 부여는 승인자가 결정'],'공개',
 bare(card(f'<div style="width:340px;display:grid;gap:12px"><h3>직원 가입 신청</h3>{field("이름","박스케줄")}{field("이메일","park@oio.example",hint="확인 메일을 보내드려요.")}{field("연락처","010-0000-0000")}{select("희망 직급","스케줄러")}{textarea("메모 (선택)","11월부터 합류합니다. 담당 대표님 확인 부탁드립니다.",60)}{alert("신청 후 이메일 확인과 관리자 승인이 필요해요.","info")}<span class="btn lg">신청하기</span></div>',24), minh=540))
add('SW03','승인 대기 안내','1','web','승인 전 직원','신청 상태를 보여 주고, 승인 전에는 업무 화면이 열리지 않습니다.',
 ['진행 상태 3단계 표시(신청, 이메일 확인, 관리자 승인)','승인되면 메일로 알림, 반려 시 사유 표시','이 상태에서 업무 API는 모두 403'],'승인 대기',
 bare(card(f'<div style="width:360px;display:grid;gap:14px"><h3>승인을 기다리고 있어요</h3><div class="kv"><div class="k">신청일</div><div class="v tnum">11월 18일 14:20</div><div class="k">희망 직급</div><div class="v">스케줄러</div><div class="k">상태</div><div class="v">{badge("승인 대기","warn")}</div></div><div class="row" style="gap:6px">{badge("신청 완료","ok")}{badge("이메일 확인","ok")}{badge("관리자 승인","gray")}</div>{alert("승인되면 이메일로 알려 드려요. 급한 경우 대표에게 문의해 주세요.","info")}<span class="btn line lg">로그아웃</span></div>',24), minh=420))
add('SW04','대시보드','1','web','직원 전체(권한별 카드)','오늘 할 일을 한눈에 봅니다. 권한이 있는 카드만 보입니다.',
 ['오늘 촬영, 팀장 미배정 일정, 승인 대기 직원','다가오는 일정 5건','권한이 없는 카드는 아예 표시하지 않음'],'SCHEDULE_READ 등',
 desk('대시보드','대시보드',tabs(['오늘','이번 주'],0),
  f'<div class="grid g4" style="margin-bottom:12px">{stat("2","오늘 촬영")}{stat("5","팀장 미배정 일정")}{stat("3","승인 대기 직원")}{stat("12","이번 주 필요 인원 합계")}</div>'
  f'<div class="grid" style="grid-template-columns:1.4fr 1fr"><div class="card" style="padding:14px 16px"><h3 style="margin-bottom:8px;font-size:15px">다가오는 일정</h3>{table(["일시","일정","상태"],[["11/19 09:00","사극 가제 3회차",badge("확정","ok")],["11/19 13:00","현대극 카페 장면",badge("팀장 필요","warn")],["11/20 06:00","사극 가제 4회차",badge("모집 중","pri")],["11/20 10:00","광고 촬영",badge("초안")],["11/22 09:00","사극 가제 5회차",badge("모집 중","pri")]],["","",""])}</div>'
  f'<div class="card" style="padding:14px 16px"><h3 style="margin-bottom:8px;font-size:15px">처리할 일</h3><div style="display:grid;gap:8px">{prow("직원 승인 대기","3명","" ,None)}{prow("팀장이 없는 일정","5건","")}{prow("변경 알림 미확인","2건","")}</div></div></div>'))
add('SW05','촬영 일정 목록','1','web','일정 열람 권한 이상','날짜별로 촬영 일정을 보고 필터합니다. 등록 버튼은 권한자에게만 보입니다.',
 ['필터: 작품, 상태, 팀장 미배정, 지난 일정','행마다 필요 인원, 팀장 현황, 상태 라벨','행을 누르면 일정 상세'],'SCHEDULE_READ',
 desk('촬영 일정','촬영 일정',tabs(['월','주','목록'],2)+btn('일정 등록','', 'plus'),
  chips(['작품 전체 ▾','상태 전체 ▾','팀장 미배정만','지난 일정 포함'],-1)+'<div class="day">11월 19일 수요일</div>'
  +'<div class="it first"><span class="tnum">09:00–18:00</span><div><div class="t">사극 가제 3회차</div><div class="pl">강남 세트장</div></div><span class="tnum">필요 30</span><span class="tnum">팀장 1/2</span>'+badge('확정','ok')+'</div>'
  +'<div class="it last"><span class="tnum">13:00–20:00</span><div><div class="t">현대극 카페 장면</div><div class="pl">성수동 스튜디오</div></div><span class="tnum">필요 8</span><span class="tnum">팀장 0/1</span>'+badge('팀장 필요','warn')+'</div>'
  +'<div class="day">11월 20일 목요일</div>'
  +'<div class="it first"><span class="tnum">06:00–22:00</span><div><div class="t">사극 가제 4회차 (야외)</div><div class="pl">양평 오픈 세트</div></div><span class="tnum">필요 60</span><span class="tnum">팀장 2/3</span>'+badge('모집 중','pri')+'</div>'
  +'<div class="it last"><span class="tnum">10:00–14:00</span><div><div class="t">광고 촬영 이미지단역</div><div class="pl">서울숲</div></div><span class="tnum">필요 6</span><span class="tnum">팀장 1/1</span>'+badge('초안')+'</div>'))
add('SW06','촬영 일정 월 보기','1','web','일정 열람 권한 이상','달력으로 한 달 일정을 봅니다.',
 ['일정 막대는 상태 라벨과 함께 표시(색만으로 구분하지 않음)','날짜를 누르면 그날 목록','지난 달·다음 달 이동'],'SCHEDULE_READ',
 desk('촬영 일정','촬영 일정',tabs(['월','주','목록'],0)+btn('일정 등록','','plus'),
  '<div class="row" style="margin-bottom:10px;gap:14px"><b style="font-size:16px">2026년 11월</b><span class="cap">이전 · 다음</span></div>'
  +month_grid({19:[('a','사극 3회차'),('b','카페 장면')],20:[('a','사극 4회차'),('c','광고(초안)')],22:[('a','사극 5회차')],25:[('a','광고 촬영')],27:[('b','현대극 2회차')],28:[('a','사극 6회차')]}),minh=500))
add('SW07','일정 상세','1','web','일정 열람 권한 이상','한 일정의 모든 정보를 봅니다. 권한에 따라 수정·팀장 등록·삭제 버튼이 달라집니다.',
 ['날짜·장소·작품·필요 인원(유형별)·팀장 현황·변경 이력','변경 이력에 누가 언제 무엇을 바꿨는지 기록','삭제는 관리자·윗 직급만 보임'],'SCHEDULE_READ',
 desk('사극 가제 3회차','촬영 일정',btn('팀장 등록','line')+btn('수정','line','edit')+btn('공개','', None),
  '<div class="grid" style="grid-template-columns:1.2fr 1fr">'
  +card(kv([('일시','<span class="tnum">11월 19일 수 09:00–18:00 (예상)</span>'),('장소','강남 세트장'),('작품','사극 가제'),('상태',badge('확정','ok')+' '+badge('공개','pri')),('메모','의상 지참, 주차 불가')]),16)
  +card(f'<h3 style="font-size:15px;margin-bottom:8px">필요 인원</h3>{table(["유형","요건","필요","확정"],[["보조출연","성별 무관","20","18"],["단역","대사 2줄","6","6"],["이미지단역","한복 소화","4","3"]],["","","r","r"])}',14)
  +'</div><div class="grid g2" style="margin-top:12px">'
  +card(f'<h3 style="font-size:15px;margin-bottom:8px">팀장 1/2명</h3><div style="display:grid;gap:8px">{prow("김현장","현장 팀장 · 11/17 등록",badge("등록됨","ok"))}{prow("미배정","한 명 더 필요",btn("등록","", None))}</div>',14)
  +card(f'<h3 style="font-size:15px;margin-bottom:8px">변경 이력</h3><div class="cap" style="display:grid;gap:6px;line-height:18px"><div>11/18 14:20 스케줄러 박스케줄: 집합 시간 08:30 → 09:00</div><div>11/17 10:05 대표: 단역 필요 인원 4 → 6 (인원 조정)</div><div>11/15 09:12 스케줄러 박스케줄: 일정 등록</div></div>',14)
  +'</div>',minh=520))
add('SW08','일정 등록·수정','1','web','일정 등록 권한','촬영 일정과 날짜별 필요 인원을 입력합니다. 저장하면 공개 상태에 따라 바로 반영됩니다.',
 ['필수 항목은 라벨 뒤 "(필수)"로 표시','필요 인원은 유형·요건·인원을 줄 단위로 추가','신청자가 있는 일정의 날짜·시간·장소를 바꾸면 영향 인원 수를 보여 주고 사유를 받음'],'SCHEDULE_WRITE, HEADCOUNT_WRITE',
 desk('일정 등록','촬영 일정','','<div class="grid g2">'
  +card(f'<div style="display:grid;gap:12px">{select("작품 (필수)","사극 가제")}{field("촬영일 (필수)","2026-11-22 (토)")}<div class="grid g2">{field("집합 시각 (필수)","09:00")}{field("종료 예상 (필수)","18:00")}</div>{field("장소 (필수)","강남 세트장")}{textarea("메모","의상 지참, 주차 불가",54)}</div>',16)
  +card(f'<div style="display:grid;gap:10px"><h3 style="font-size:15px">필요 인원</h3>{table(["유형","요건","인원"],[["보조출연","성별 무관","20"],["단역","대사 2줄","6"],["이미지단역","한복 소화","4"]],["","","r"])}<span class="btn line sm" style="width:fit-content">{ic("plus",14)}줄 추가</span><div class="field"><label>공개 상태</label><div class="row" style="gap:14px"><span class="radio"><i></i>초안(비공개)</span><span class="radio"><i class="on"></i>공개</span></div></div></div>',16)
  +'</div><div class="row" style="justify-content:flex-end;margin-top:14px">'+btn('취소','line')+btn('저장해요')+'</div>',minh=520))
add('SW09','팀장 등록','1','web','팀장 등록 권한','일정에 팀장을 등록합니다. 본인 또는 권한자가 다른 직원을 등록하며, 등록하면 바로 확정됩니다.',
 ['사유는 선택지 + 기타 직접 입력','본인 등록은 팀장 이상, 타인 등록은 실장 이상','삭제는 등록한 사람보다 윗 직급만'],'LEADER_SELF, LEADER_ASSIGN',
 modal_over(f'<h3>팀장 등록</h3><div class="cap" style="margin:2px 0 14px">사극 가제 3회차 · 11월 19일 09:00</div>{radios("등록할 사람",["나 (김현장)","다른 직원 (실장 이상)"],0)}<div style="height:8px"></div>{radios("사유 (선택)",["담당 현장 경험이 있어요","인원 부족을 채워요","요청을 받았어요","기타"],1)}<div class="row" style="justify-content:flex-end;margin-top:14px">{btn("취소","line")}{btn("등록해요")}</div>',minh=470))
add('SW10','작품 관리','1','web','일정 등록 권한','작품(드라마·영화·광고) 목록을 관리하고, 작품별 일정을 모아 봅니다.',
 ['작품 등록·수정·종료 처리','작품별 일정 수와 누적 확정 인원','가제와 실제 제목을 구분해 관리(공개 화면엔 가제)'],'SCHEDULE_WRITE',
 desk('작품','작품',btn('작품 등록','','plus'),chips(['진행 중','종료'],0)+table(["가제(공개)","실제 제목(내부)","유형","일정 수","누적 확정","상태"],[["사극 가제","—","드라마","12","214",badge("진행 중","pri")],["현대극 카페","—","드라마","5","58",badge("진행 중","pri")],["광고 촬영 A","—","광고","2","9",badge("진행 중","pri")],["영화 가제 B","—","영화","8","120",badge("종료")]],["","","","r","r",""])))
add('SW11','직원 관리 — 승인 대기','1','web','승인 권한자','가입 신청을 승인·반려하고 직급을 정합니다. 자신과 같거나 높은 직급은 부여할 수 없습니다.',
 ['승인 시 직급 선택 필수, 반려는 사유 선택지 + 기타','승인·반려는 메일로 자동 알림','모든 처리는 감사 로그에 기록'],'STAFF_APPROVE, STAFF_POSITION_ASSIGN',
 desk('직원 관리','직원·권한',tabs(['활성 18','승인 대기 3','정지 1'],1),
  table(["이름","이메일","신청일","희망 직급","직급 선택",""],[["박스케줄","park@oio.example","11/18","스케줄러",f'<div class="input sel" style="min-height:30px;width:140px"><span>스케줄러</span>{ic("down",14)}</div>',btn("반려","line sm")+" "+btn("승인","sm")],["이현장","lee@oio.example","11/18","현장 팀장",f'<div class="input sel" style="min-height:30px;width:140px"><span>현장 팀장</span>{ic("down",14)}</div>',btn("반려","line sm")+" "+btn("승인","sm")],["최캐스팅","choi@oio.example","11/19","캐스팅 매니저",f'<div class="input sel ph" style="min-height:30px;width:140px"><span>직급 선택</span>{ic("down",14)}</div>',btn("반려","line sm")+" "+btn("승인","off sm")]],["","","","","","r"])
  +'<div class="cap" style="margin-top:10px">자신과 같거나 높은 서열의 직급은 부여할 수 없어요. 승인하면 메일로 알려요.</div>'))
add('SW12','직원 상세·직급 변경','1','web','승인 권한자','한 직원의 직급·기능 묶음·최근 활동을 보고 변경·정지합니다.',
 ['직급 변경, 기능 묶음(스케줄러 등) 추가·제거','정지·정지 해제(사유 필수)','최근 로그인과 변경 이력'],'STAFF_POSITION_ASSIGN, STAFF_SUSPEND',
 desk('이현장','직원·권한',btn('정지','danger')+btn('저장'),'<div class="grid" style="grid-template-columns:1fr 1.2fr">'
  +card(f'<div class="row" style="gap:12px;margin-bottom:12px">{avatar("이",44)}<div><b style="font-size:16px">이현장</b><div class="cap">lee@oio.example · 010-0000-0000</div></div></div>{kv([("상태",badge("활성","ok")),("가입일","2026-10-02"),("최근 로그인","오늘 08:41")])}',16)
  +card(f'<div style="display:grid;gap:12px">{select("직급","현장 팀장")}<div class="field"><label>기능 묶음</label>{checks([("스케줄러",False),("캐스팅 추천",True)])}</div>{textarea("변경 사유","현장 경력 반영",54)}</div>',16)
  +'</div>'))
add('SW13','직급 관리','1','web','직급 관리 권한(관리자)','직급을 추가·수정하고 서열을 정합니다. 서열이 큰 수일수록 상위입니다.',
 ['직급 추가·이름·서열·설명·비활성','사용 중인 직급은 삭제 불가(비활성만)','시스템 기본 직급(관리자·대표)은 이름 보호'],'POSITION_MANAGE',
 desk('직급 관리','직원·권한',btn('직급 추가','','plus'),table(["직급","서열","소속 인원","설명","상태",""],[["대표","90","1","최고 책임자",badge("사용","ok"),btn("수정","line sm")],["이사","80","2","승인·일정·선발",badge("사용","ok"),btn("수정","line sm")],["현장 실장","60","3","팀장 배정",badge("사용","ok"),btn("수정","line sm")],["캐스팅 매니저","55","2","인재 DB",badge("사용","ok"),btn("수정","line sm")],["현장 팀장","40","9","현장 운영",badge("사용","ok"),btn("수정","line sm")],["직원","10","1","열람",badge("사용","ok"),btn("수정","line sm")]],["","r","r","","","r"])+'<div class="cap" style="margin-top:10px">직급은 화면에서 바로 추가하고 고칠 수 있어요. 서열 변경은 감사 로그에 남아요.</div>'))
add('SW14','권한 관리','1','web','권한 관리 권한(관리자)','직급별로 허용할 기능을 체크하고 저장합니다. 저장하면 바로 반영됩니다.',
 ['그룹별 체크박스, 민감 권한은 주황색 경고 표시','저장 전 "변경 3개" 미리보기, 변경 사유 입력','저장 후 해당 직급 직원에게 즉시 적용'],'PERMISSION_MANAGE',
 desk('직급·권한','직원·권한',btn('직급 추가','line')+btn('변경 3개 저장'),'<div class="grid" style="grid-template-columns:200px 1fr;align-items:start">'
  +'<div class="card" style="padding:6px">'+''.join(f'<div class="nav{" on" if n=="현장 실장" else ""}" style="justify-content:space-between"><span>{n}</span><span class="cap tnum">{r}</span></div>' for n,r in [("대표",90),("이사",80),("현장 실장",60),("캐스팅 매니저",55),("현장 팀장",40),("직원",10)])+'</div>'
  +card('<div style="display:grid;gap:0">'+''.join(f'<div style="display:grid;grid-template-columns:90px 1fr;padding:11px 0;border-bottom:1px solid var(--line)"><b>{g}</b>{checks(c)}</div>' for g,c in [("일정",[("열람",True),("등록·수정",False),("공개·마감",False),("삭제",False)]),("팀장",[("본인 등록",True),("타인 등록",True),("수정",True),("삭제 (낮은 서열만)",True)]),("선발",[("신청자 열람",True),("합격 통보",False),("추천",False)]),("직원 관리",[("가입 승인",False),("직급 부여",False),("정지",False)]),("민감",[("주민번호 전체 열람",False,True),("영상·이력서 열람",False,True)])])+'<div class="cap" style="padding-top:10px">저장하면 해당 직급 직원에게 바로 반영돼요. 변경 사유를 입력해야 해요.</div></div>',8,'padding:6px 16px')+'</div>',minh=500))
add('SW15','권한 시험 도구','1','web','관리자','"이 직원이 이 기능을 쓸 수 있나?"를 사람과 기능으로 조회합니다. 설정이 의도대로인지 확인합니다.',
 ['직원 선택 + 기능 선택 → 허용/차단과 이유 표시','이유 예: 직급 권한, 기능 묶음, 개별 예외, 서열 제한','권한 변경 전후 비교'],'PERMISSION_MANAGE',
 desk('권한 시험 도구','직원·권한','','<div class="grid g2">'+card(f'<div style="display:grid;gap:12px">{select("직원","이현장 (현장 팀장)")}{select("기능","팀장 등록 삭제")}<span class="btn">확인</span></div>',16)+card(f'<h3 style="font-size:15px;margin-bottom:10px">결과</h3>{alert("차단 — 팀장 직급에는 팀장 등록 삭제 권한이 없어요.","bad")}<div class="cap" style="margin-top:10px;line-height:18px">허용하려면 직급·권한 화면에서 "팀장 삭제"를 현장 팀장에게 부여하세요. 단, 자신보다 낮은 서열의 등록만 삭제할 수 있어요.</div>',16)+'</div>'))
add('SW16','감사 로그','1','web','감사 열람 권한','누가 무엇을 언제 바꿨는지, 어떤 요청이 거절됐는지 확인합니다.',
 ['검색·기간·사용자·종류 필터','권한 거절 기록도 표시(어떤 기능에 권한이 없었는지)','내보내기는 암호화 파일만'],'AUDIT_READ',
 desk('감사 로그','감사 로그',btn('내보내기','line'),chips(['최근 7일 ▾','사용자 전체 ▾','종류 전체 ▾'],-1)+table(["시각","사용자","행동","대상","결과"],[["11/19 09:12","박스케줄","일정 수정","사극 가제 3회차",badge("성공","ok")],["11/19 08:55","이현장","팀장 삭제 시도","사극 가제 3회차",badge("권한 없음","bad")],["11/18 17:30","대표","직원 승인","박스케줄",badge("성공","ok")],["11/18 15:02","대표","권한 변경","현장 실장 +3",badge("성공","ok")],["11/18 10:40","관리자","직급 추가","캐스팅 매니저",badge("성공","ok")]],["","","","",""])))
add('SW17','휴지통','1','web','삭제·복구 권한','삭제된 항목을 확인하고 복구합니다. 삭제는 관리자·윗 직급만 할 수 있습니다.',
 ['항목 종류별 목록, 삭제한 사람·시각·사유','복구는 관리자와 상위 직급만','보존 기간이 지나면 자동으로 완전 삭제'],'RECORD_RESTORE',
 desk('휴지통','휴지통','',chips(['전체','일정','작품','직원'],0)+table(["항목","종류","삭제자","삭제 시각","사유",""],[["광고 촬영 A 2회차","일정","대표","11/17 16:20","촬영 취소",btn("복구","line sm")],["테스트 작품","작품","관리자","11/12 11:00","입력 실수",btn("복구","line sm")],["김임시","직원","이사","11/10 09:45","중복 신청",btn("복구","line sm")]],["","","","","","r"])))
add('SW18','사유 선택지 관리','1','web','관리자','신청·취소·팀장·삭제 때 보이는 사유 선택지를 화면에서 고칩니다.',
 ['상황별 목록(신청, 취소, 팀장 등록·취소, 일정 변경)','"기타(직접 입력)"는 항상 마지막, 필수 여부 설정','순서 변경·비활성'],'SETTINGS_WRITE',
 desk('사유 선택지','설정',btn('항목 추가','','plus'),'<div class="grid" style="grid-template-columns:200px 1fr">'+'<div class="card" style="padding:6px">'+''.join(f'<div class="nav{" on" if n=="출연자 취소" else ""}">{n}</div>' for n in ["출연자 신청","출연자 취소","팀장 등록","팀장 취소·삭제","일정 변경·삭제","가입 반려·정지"])+'</div>'+card(f'<div style="display:flex;gap:8px;margin-bottom:10px">{badge("필수 입력","pri")}<span class="cap">기타 선택 시 직접 입력 필수</span></div>'+table(["순서","선택지","상태"],[["1","몸이 아파요",badge("사용","ok")],["2","가족 사정(경조사)",badge("사용","ok")],["3","다른 촬영과 겹쳐요",badge("사용","ok")],["4","교통·거리 문제",badge("사용","ok")],["5","개인 사정",badge("사용","ok")],["6","기타 (직접 입력)",badge("고정")]],["c","",""]),12)+'</div>'))
add('SW19','내 정보·보안','1','web','직원 전체','비밀번호 변경과 2차 인증(인증 앱) 설정을 합니다.',
 ['비밀번호 변경 시 다른 기기 로그아웃','대표·이사·관리자는 인증 앱 등록 필수(운영)','최근 로그인 기록'],'본인',
 desk('내 정보','설정','', '<div class="grid g2">'+card(f'<div style="display:grid;gap:12px"><h3 style="font-size:15px">계정</h3>{kv([("이름","박스케줄"),("직급","스케줄러"),("이메일","park@oio.example")])}{field("현재 비밀번호","••••••••")}{field("새 비밀번호","••••••••••",hint="10자 이상, 영문과 숫자를 섞어 주세요.")}<span class="btn" style="width:fit-content">변경</span></div>',16)+card(f'<div style="display:grid;gap:12px"><h3 style="font-size:15px">2차 인증</h3>{alert("인증 앱을 등록하면 로그인할 때 6자리 코드를 한 번 더 입력해요.","info")}<div class="row" style="gap:16px">{qr(110)}<div class="cap" style="line-height:18px">앱으로 QR을 스캔한 뒤<br>표시된 6자리 코드를 입력하세요.</div></div>{field("6자리 코드","")}<span class="btn" style="width:fit-content">등록</span></div>',16)+'</div>'))
add('SW20','권한 없음·오류 안내','1','web','모든 사용자','권한이 없거나 문제가 생겼을 때 이유와 다음 행동을 알려 줍니다.',
 ['원인과 해결 방법을 한 문장으로','요청 가능한 대표·관리자 안내','403·404·점검·네트워크 오류 공통 형식'],'공통',
 bare(f'<div style="width:380px;text-align:left;display:grid;gap:12px"><b style="font-size:20px;line-height:28px">이 기능을 쓸 권한이 없어요</b><div class="sub" style="color:var(--t2)">직원 승인과 직급 부여는 이사 이상만 할 수 있어요. 필요하면 대표에게 권한을 요청해 주세요.</div><div class="row">{btn("대시보드로")}{btn("권한 요청 메일","line")}</div><div class="cap">오류 코드 403 · 11월 19일 09:30</div></div>',minh=340))
