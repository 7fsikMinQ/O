# -*- coding: utf-8 -*-
from lib import *
from screens_web1 import add

STAFF_TABS = ([('cal','오늘'),('search','일정'),('qr','출결'),('user','내 정보')],0)
ACT_TABS = ([('home','공고'),('cal','내 일정'),('msg','메시지'),('user','내 정보')],0)

# ---- 직원 앱 (2단계) ----
add('SM01','오늘 일정','2','phone','직원(현장)','오늘 촬영과 내가 맡은 일을 한 화면에서 봅니다.',
 ['팀장 등록 여부와 필요 인원을 카드로 표시','도착 확인 인원 실시간 집계','출근 QR 스캔·팀장 전화로 바로 이동'],'SCHEDULE_READ',
 phone('오늘 일정',f'<div class="cap">11월 19일 수요일 · 2건</div>{prow("사극 가제 3회차","09:00 · 강남 세트장",badge("팀장 등록됨","ok"))}{prow("현대극 카페 장면","13:00 · 성수동 스튜디오",badge("팀장 필요","warn"))}'+card('<div class="cap">사극 가제 3회차</div><div class="tnum" style="font:700 26px/32px Pretendard">24 / 30명</div><div class="cap">확정 인원 · 도착 확인 18명</div>',14)+prow("출근 QR 스캔","세션이 열려 있어요","","qr")+prow("담당 팀장에게 전화","김현장 · 010-0000-0000","","phone"),btn('팀장 등록하기','lg'),STAFF_TABS,right=ic('bell',20)))
add('SM02','일정 상세','2','phone','직원(현장)','일정 정보와 팀장 현황을 봅니다. 팀장 등록·취소는 여기서 합니다.',
 ['날짜·장소·필요 인원·메모','지도 열기와 전화 버튼(링크만 사용)','팀장 취소 시 사유 입력'],'SCHEDULE_READ, LEADER_SELF',
 phone('일정 상세','' ,None,STAFF_TABS,back=True) .replace('<div class="pbody"></div>',f'<div class="pbody"><div><b style="font-size:18px;line-height:26px">사극 가제 3회차</b><div class="cap">11월 19일 수 · 09:00–18:00 (예상)</div></div>{kv([("장소","강남 세트장"),("필요 인원","30명"),("팀장","1 / 2명"),("메모","의상 지참")])}{prow("지도에서 열기","강남 세트장","","map")}{prow("김현장","현장 팀장 · 등록됨",badge("나"))}{btn("팀장 등록 취소","line lg")}</div>'))
add('SM03','팀장 등록·취소 시트','2','phone','직원(현장)','아래에서 올라오는 시트로 팀장 등록 사유를 고릅니다.',
 ['선택지 + 기타(직접 입력)','등록은 즉시 확정','취소는 사유가 필수'],'LEADER_SELF',
 phone('일정 상세',f'<div style="opacity:.35;display:grid;gap:10px">{prow("사극 가제 3회차","09:00 · 강남 세트장")}</div>',None,None,back=True).replace('</div></div>','</div><div style="position:absolute;left:0;right:0;bottom:0;background:var(--surface);border-top:1px solid var(--line);border-radius:20px 20px 0 0;padding:18px 16px 16px;box-shadow:0 -8px 24px rgba(0,0,0,.12)"><b style="font-size:17px">팀장 등록</b><div style="height:6px"></div>'+radios("사유 (선택)",["담당 현장 경험이 있어요","인원 부족을 채워요","요청을 받았어요","기타"],1)+'<div style="height:10px"></div>'+btn("등록해요","lg")+'</div></div>',1).replace('class="phone"','class="phone" data-rel="1"'))
add('SM04','QR 스캔','2','phone','직원(구성원 누구나)','출연자 앱의 회전 QR을 스캔해 도착을 확인합니다.',
 ['카메라 권한을 한 번만 요청','스캔 즉시 이름·유형 표시, 중복 스캔 방지','오프라인이면 임시 저장 후 전송'],'FIELD_ATTEND',
 phone('출근 스캔',f'<div style="flex:1;border-radius:14px;background:#2B3138;position:relative;display:flex;align-items:center;justify-content:center"><div style="width:190px;height:190px;border:3px solid #fff;border-radius:14px"></div></div><div class="cap" style="text-align:center">출연자 앱의 QR을 화면 안에 맞춰 주세요</div>{alert("김민준 · 보조출연 · 도착 확인됐어요.","ok")}',btn('목록으로 보기','line lg'),STAFF_TABS,back=True))
add('SM05','인원 체크','2','phone','직원(현장)','확정 인원의 도착·퇴근을 목록에서 확인하고 누락자를 보정합니다.',
 ['미도착자만 보기, 전화 바로 걸기','수동 보정은 사유 필수·기록 남김','퇴근 세션도 같은 방식'],'FIELD_ATTEND',
 phone('인원 체크',f'{tabs(["전체 30","도착 18","미도착 6"],2)}'+prow("최유나","이미지단역 · 신사역 2번 출구",btn("전화","line sm"))+prow("정하늘","보조출연 · 강남역 1번 출구",btn("전화","line sm"))+prow("송민아","보조출연 · 교통 지연",btn("전화","line sm"))+prow("한도윤","단역 · 연락 두절",btn("전화","line sm")),btn('수동으로 도착 처리','line lg'),STAFF_TABS,back=True))
add('SM06','알림','2','phone','직원','일정 변경·팀장 배정·승인 결과 알림을 모아 봅니다.',
 ['안 읽은 알림 표시','알림을 누르면 해당 일정으로 이동','푸시 알림은 앱 확장 단계에서 연결'],'본인',
 phone('알림',prow("일정 시간이 바뀌었어요","사극 가제 3회차 · 집합 09:00",badge("새","pri"))+prow("팀장 등록이 완료됐어요","11/19 사극 가제 3회차","")+prow("가입이 승인됐어요","직급: 현장 팀장","")+prow("11/17 공지","의상 지참 안내",""),None,STAFF_TABS,right=''))
add('SM07','내 정보','2','phone','직원','내 직급·권한 요약, 비밀번호, 인증 앱, 로그아웃.',
 ['직급과 사용 가능한 기능 요약','인증 앱(TOTP) 등록','오픈소스 라이선스 표시'],'본인',
 phone('내 정보',card(f'<div class="row" style="gap:12px">{avatar("김",44)}<div><b style="font-size:16px">김현장</b><div class="cap">현장 팀장 · kim@oio.example</div></div></div>',14)+prow("사용 가능한 기능","일정 열람, 팀장 등록, 출결","")+prow("비밀번호 변경","","")+prow("2차 인증","미등록",badge("설정","warn"))+prow("오픈소스 라이선스","",""),btn('로그아웃','line lg'),STAFF_TABS))

# ---- 출연자 앱 (3단계) ----
add('AM01','시작','3','phone','출연자','첫 화면에서 이용자로 시작하거나 직원으로 로그인합니다.',
 ['이용자: 이메일 입력으로 시작','직원 로그인은 별도 버튼','출연자 영역이 꺼져 있으면 이 선택지 자체가 숨겨짐(스위치)'],'공개',
 phone('OIO 캐스팅',f'<div style="height:50px"></div><b style="font-size:24px;line-height:32px">촬영 일정을<br>한곳에서 확인해요</b><div class="sub" style="color:var(--t2)">단역·보조출연 모집 공고를 보고 지원하세요.</div><div style="height:14px"></div>{field("이메일","name@example.com")}<div class="cap">입력한 주소로 6자리 인증번호를 보내드려요.</div>',btn('인증번호 받기','lg')+'<div style="height:8px"></div>'+btn('직원 로그인','line lg')))
add('AM02','인증번호 입력','3','phone','출연자','메일로 받은 6자리를 입력합니다. 비밀번호는 없습니다.',
 ['3분 유효, 5번 틀리면 잠시 막힘','같은 번호 재전송 1분 제한','인증하면 90일간 로그인 유지'],'공개',
 phone('인증번호',f'<div class="sub" style="color:var(--t2)">name@example.com 으로 보낸<br>6자리 숫자를 입력해 주세요.</div><div class="row" style="gap:8px;margin-top:8px">'+''.join(f'<div class="input tnum" style="width:44px;min-height:52px;justify-content:center;font-size:22px;font-weight:700;{"border:2px solid var(--primary)" if i==3 else ""}">{d}</div>' for i,d in enumerate("482 ".replace(" ","")+"   "))+'</div><div class="cap">02:41 남았어요 · 다시 보내기</div>',btn('확인','lg'),back=True))
add('AM03','이름·필수 동의','3','phone','출연자','이름을 적고 필수 약관 3가지에 동의합니다. 선택 동의는 다음 화면에서 따로 받습니다.',
 ['필수: 이용약관, 개인정보 수집·이용, 영상·이력서 보관','약관은 펼쳐서 전문 확인','주민번호는 가입이 아니라 첫 지원 전에 받음'],'공개',
 phone('가입하기',field('이름','김민준')+'<div style="height:6px"></div>'+prow("필수 약관 전체 동의","",switch(True))+prow("서비스 이용약관 (필수)","보기",switch(True))+prow("개인정보 수집·이용 (필수)","보기",switch(True))+prow("영상·이력서 보관 (필수)","보기",switch(True)),btn('다음','lg'),back=True))
add('AM04','자료 활용 동의 (선택)','3','phone','출연자','AI 학습·합성·숏폼 등 자료 활용 동의를 항목별로 받습니다. 모두 선택이며 거부해도 가입·지원에 불이익이 없습니다.',
 ['항목마다 한 줄 설명 + 자세히 보기, 기본값은 꺼짐','"나중에 설정에서 바꿀 수 있어요" 안내','동의 시각·버전·화면 증빙 저장'],'공개',
 phone('자료 활용 동의','<div class="sub" style="color:var(--t2);font-size:13px;line-height:19px">모두 선택 항목이에요. 동의하지 않아도 가입과 지원은 그대로 할 수 있어요.</div>'+prow("제3자 제공","제작사 등에 영상 열람을 허용해요",switch(False))+prow("AI 학습 이용","이름·연락처를 지운 자료를 사용해요",switch(False))+prow("얼굴·음성 합성 이용","합성·재현 콘텐츠 제작에 사용해요",switch(False))+prow("숏폼·이미지 콘텐츠","편집된 콘텐츠에 사용해요",switch(True))+btn('항목별 자세한 내용 보기','text',None,'align-self:flex-start'),btn('다음','lg'),back=True))
add('AM05','공고 목록 (홈)','3','phone','출연자','모집 중인 공고를 유형·날짜로 찾습니다.',
 ['유형 칩 필터, 달력 보기 전환','시급, 남은 인원, 마감 시각을 카드 상단에','시간은 모두 "예상" 문구 표시'],'공개',
 phone('공고','<div class="row" style="gap:8px">'+''.join(f'<span class="chip{" on" if i==0 else ""}">{t}</span>' for i,t in enumerate(["전체","보조출연","단역","이미지단역"]))+'</div>'+card(f'<div style="display:grid;gap:6px"><div class="row" style="justify-content:space-between">{badge("모집 중","pri")}<span class="cap">마감 11/18 18:00</span></div><b style="font-size:17px;line-height:24px">사극 가제 군중 역할</b><div style="color:var(--t2);font-size:13px">11월 22일 토 · 09:00 집합 (예상)</div><div class="row" style="justify-content:space-between"><b class="tnum" style="font-size:17px">시급 10,320원</b><span class="cap tnum">남은 12 / 30</span></div></div>',14)+card(f'<div style="display:grid;gap:6px"><div class="row" style="justify-content:space-between">{badge("마감 임박","warn")}<span class="cap">마감 11/20 12:00</span></div><b style="font-size:17px;line-height:24px">광고 촬영 이미지단역</b><div style="color:var(--t2);font-size:13px">11월 25일 화 · 10:00 집합 (예상)</div><div class="row" style="justify-content:space-between"><b class="tnum" style="font-size:17px">시급 12,000원</b><span class="cap tnum">남은 2 / 6</span></div></div>',14)+'<div class="cap" style="text-align:center">시간은 모두 예상이며 상황에 따라 바뀔 수 있어요.</div>',None,ACT_TABS,right=ic('search',20)))
add('AM06','공고 달력','3','phone','출연자','달력에서 날짜별 공고 수를 보고 그날 공고로 이동합니다.',
 ['날짜별 공고 수 표시','날짜를 누르면 목록','내가 확정된 날은 표시'],'공개',
 phone('공고 달력',f'<div class="row" style="justify-content:space-between"><b style="font-size:16px">2026년 11월</b><span class="cap">이전 · 다음</span></div>'+month_grid({19:[('a','2')],20:[('a','1')],22:[('a','3')],25:[('b','확정')],28:[('a','2')]})+prow("11월 22일 토","공고 3건","")+prow("11월 25일 화","확정 1건 · 공고 2건",""),None,ACT_TABS))
add('AM07','공고 상세','3','phone','출연자','공고의 역할·시급·집합 시간을 확인하고 지원합니다. 현장 상세는 합격 후에 공개됩니다.',
 ['역할별 요건(성별·나이·키 등) 충족 여부 표시','비공개 현장은 가제와 지역만 공개','시급제: 최저시급 이상, 9시간 이내는 8시간 기준'],'공개',
 phone('공고 상세',f'<div><b style="font-size:20px;line-height:28px">사극 가제 군중 역할</b><div class="cap">OIO 캐스팅 · 서울 강남</div></div>{kv([("일정","11월 22일 토 09:00 (예상)"),("시급","10,320원"),("예상 보수","82,560원 (8시간)"),("남은 인원","12 / 30")])}{alert("내 정보가 요건에 맞아요.","ok")}{alert("현장 주소와 연락처는 합격 후에 볼 수 있어요.","info")}<div class="cap">시간은 예상이며 변동·연장될 수 있어요.</div>',btn('지원하기','lg'),back=True))
add('AM08','지원 1단계 — 내 정보 확인','3','phone','출연자','지원서에 들어갈 내 정보를 확인합니다. 지원 시점의 정보가 증빙으로 보관됩니다.',
 ['프로필 자동 입력, 수정 가능','타고 갈 곳(탑승 희망지)과 한마디','주민번호는 첫 지원 전 등록'],'ACTOR',
 phone('지원하기 1/3',f'<div class="row" style="gap:6px">{badge("1 정보","pri")}{badge("2 사진")}{badge("3 서명")}</div>'+card(kv([("이름","김민준"),("키·몸무게","178cm · 70kg"),("생년월일","1998-03-**")]),14)+field('타고 갈 곳','강남역 1번 출구')+textarea('업체에 한마디 (선택)','사극 경험 있습니다.',56),btn('다음','lg'),back=True))
add('AM09','지원 2단계 — 현재 사진','3','phone','출연자','현재 모습 사진을 찍어 올립니다. 5MB를 넘으면 안내 팝업이 뜹니다.',
 ['카메라 촬영 또는 앨범 선택','JPG·PNG만, 5MB 이하, 사진 속 위치정보 제거','다시 찍기'],'ACTOR',
 phone('지원하기 2/3',f'<div class="row" style="gap:6px">{badge("1 정보","ok")}{badge("2 사진","pri")}{badge("3 서명")}</div><div style="display:flex;justify-content:center;margin-top:8px">{photo(220,270,"촬영한 사진")}</div><div class="cap" style="text-align:center">얼굴과 전신이 보이게 찍어 주세요.</div>',btn('다음','lg')+'<div style="height:8px"></div>'+btn('다시 찍기','line lg'),back=True))
add('AM10','지원 3단계 — 전자서명','3','phone','출연자','지원 내용과 증빙 보관에 동의하고 손으로 서명합니다.',
 ['서명 이미지는 지원서와 함께 보관','서명 시각·약관 버전·변조 확인값 저장','서명 지우고 다시 하기'],'ACTOR',
 phone('지원하기 3/3',f'<div class="row" style="gap:6px">{badge("1 정보","ok")}{badge("2 사진","ok")}{badge("3 서명","pri")}</div><div class="input" style="height:170px;margin-top:6px;border-style:dashed;justify-content:center;color:var(--t3)">여기에 서명해 주세요</div>'+prow("지원서와 서명을 보관하는 것에 동의해요","",switch(True)),btn('지원 완료','lg'),back=True))
add('AM11','지원 완료','3','phone','출연자','지원이 접수됐음을 알려 주고 다음 행동을 안내합니다.',
 ['업체가 보고 선발하며 통보를 받으면 확정해야 함','내 일정에서 진행 상태 확인','불합격 사유 안내 없음(정책)'],'ACTOR',
 phone('지원 완료','<div style="height:60px"></div><b style="font-size:22px;line-height:30px;text-align:center">지원이 접수됐어요</b><div class="sub" style="text-align:center;color:var(--t2)">선발되면 알림으로 알려 드려요.<br>마감 시 통보가 없으면 자동으로 종료돼요.</div>',btn('내 일정 보기','lg')+'<div style="height:8px"></div>'+btn('공고 더 보기','line lg')))
add('AM12','내 일정','3','phone','출연자','지원·합격·확정 상태를 한곳에서 봅니다.',
 ['상태 라벨: 신청, 합격 통보, 확정, 종료','합격 통보는 응답 기한 표시','겹치는 일정 경고 표시'],'ACTOR',
 phone('내 일정',tabs(["예정","지난"],0)+prow("사극 가제 군중 역할","11/22 09:00 · 합격 통보",badge("응답 필요","warn"))+prow("광고 촬영 이미지단역","11/25 10:00 · 확정",badge("확정","ok"))+prow("현대극 카페 손님","11/28 · 신청",badge("신청"))+prow("영화 가제 B","11/10 · 종료",badge("종료")),None,ACT_TABS))
add('AM13','합격 통보·확정','3','phone','출연자','합격 통보를 확인하고 "참여할게요"로 확정합니다. 기한이 지나면 자동 종료됩니다.',
 ['응답 기한 표시, 2단계 확정','다른 일정과 시간이 가까우면 경고와 확인 요구','못 하는 경우 사유 선택'],'ACTOR',
 phone('합격 통보',f'<div class="card" style="padding:16px;background:var(--warn-b);border-color:transparent"><b style="font-size:20px;line-height:28px">합격했어요</b><div style="color:var(--warn-t);font-size:13px;margin-top:4px">11/20 12:00까지 참여를 확정해 주세요.</div></div>'+kv([("작품","사극 가제"),("집합","11/22 09:00 (예상)"),("장소","합격 확정 후 공개")])+alert("11/22 14:00 광고 촬영과 간격이 짧아요. 확정하면 양쪽 업체에 알려요.","warn"),btn('참여할게요','lg')+'<div style="height:8px"></div>'+btn('이번엔 못 해요','line lg'),back=True))
add('AM14','합격서','3','phone','출연자','확정 후 집합 장소·연락처·복장 안내를 봅니다. 합격서에는 금액이 없습니다.',
 ['집합 시간은 ±2시간 예상 범위로 표시','지도 열기·전화 버튼(링크)','근로계약서는 현장에서 별도 작성 안내'],'ACTOR',
 phone('합격서',kv([("작품","사극 가제"),("집합","11/22 09:00 (예상 ±2시간)"),("종료","18:00 (예상 ±4시간)"),("장소","강남 세트장"),("주소","서울 강남구 OO로 396"),("복장","한복, 검정 신발"),("담당","김현장 010-0000-0000")])+prow("지도에서 열기","","","map")+prow("담당자에게 전화","","","phone")+'<div class="cap" style="line-height:17px">근로계약서는 현장에서 직접 작성해요. 시간은 촬영 상황에 따라 바뀌거나 연장될 수 있어요.</div>',None,None,back=True))
add('AM15','일정 겹침 경고','3','phone','출연자','시간이 겹치거나 가까운 일정을 모두 확정하면 알리고 확인을 받습니다. 확인할 때까지 1시간마다 다시 알립니다.',
 ['양쪽 업체에도 알림이 감','확인(ack) 전까지 1시간 간격 재알림','겹침 구간을 시각으로 표시'],'ACTOR',
 phone('일정 겹침',alert('11/22 09:00–18:00 사극 가제와 14:00 광고 촬영이 겹쳐요.','warn')+prow("사극 가제 군중 역할","09:00–18:00 (확정)",badge("확정","ok"))+prow("광고 촬영 이미지단역","14:00–18:00 (확정)",badge("확정","ok"))+'<div class="cap" style="line-height:17px">두 업체 모두에 알림이 갔어요. 확인하지 않으면 1시간마다 다시 알려요.</div>',btn('겹침을 확인했어요','lg'),back=True))
add('AM16','출근 QR','3','phone','출연자','출근 세션이 열리면 내 QR이 표시됩니다. 현장 담당자가 스캔합니다.',
 ['QR은 30초마다 바뀜(스크린샷 재사용 방지)','집합 2시간 전~30분 전에만 활성','세션이 닫혀 있으면 안내 문구'],'ACTOR',
 phone('출근 확인',f'<div style="display:flex;flex-direction:column;align-items:center;gap:12px;margin-top:20px">{qr(200)}<b class="tnum">00:22 남음</b><div class="cap" style="text-align:center;line-height:17px">현장 담당자에게 QR을 보여 주세요.<br>화면을 캡처해도 쓸 수 없어요.</div></div>',None,None,back=True))
add('AM17','취소 (사유 선택)','3','phone','출연자','확정 후 취소 시 사유를 고릅니다. 사유는 스케줄러의 판정 자료가 됩니다.',
 ['선택지 + 기타 직접 입력(필수)','취소 시점에 따라 패널티 가능성 안내','노쇼는 횟수만 표시, 점수 없음'],'ACTOR',
 phone('참여 취소',alert('취소하면 스케줄러가 사유를 확인해 노쇼 횟수 반영 여부를 판단해요.','warn')+radios('취소 사유 (필수)',["몸이 아파요","가족 사정(경조사)","다른 촬영과 겹쳐요","교통·거리 문제","개인 사정","기타"],0),btn('취소하기','danger lg'),back=True))
add('AM18','메시지함','3','phone','출연자','업체가 보낸 메시지를 보고 답장합니다. 출연자가 먼저 보낼 수는 없습니다.',
 ['업체→출연자 시작, 출연자는 답장만','안 읽은 메시지 표시','출연자끼리의 대화는 없음'],'ACTOR',
 phone('메시지',prow("OIO 캐스팅","집합 장소가 변경됐어요. 확인 부탁드립니다.",badge("새","pri"))+prow("김현장 팀장","내일 의상은 검정 신발로 부탁드려요.","")+prow("OIO 캐스팅","11/25 촬영 합격 안내",""),None,ACT_TABS))
add('AM19','내 프로필','3','phone','출연자','사진과 신체 정보, 활동 유형을 관리합니다.',
 ['유형 복수 선택(보조출연·단역·이미지단역)','프로필 사진·전신 사진 각 5장까지','주민번호는 등록 후 수정 불가(마스킹 표시)'],'ACTOR',
 phone('내 프로필',f'<div class="row" style="gap:14px">{photo(72,72,"")}<div><b style="font-size:17px">김민준</b><div class="cap">남 · 28세 · 178cm / 70kg</div></div></div>'+prow("활동 유형","보조출연, 이미지단역","")+prow("사진","프로필 3 · 전신 2","")+prow("이력서","경력 4건","")+prow("오디션 영상","1건","")+prow("정산 계좌·주민번호","등록됨",""),None,ACT_TABS))
add('AM20','이력서','3','phone','출연자','출연 경력을 줄 단위로 입력하고 이력서 파일(PDF)을 올립니다.',
 ['경력 항목: 작품·역할·연도·연출/제작사','이력서 파일은 5MB 이하 PDF','이 자료에 적용된 동의 상태가 아래에 표시됨'],'ACTOR',
 phone('이력서',card('<div style="display:grid;gap:8px;font-size:13px;line-height:18px"><div><b>2025</b> 사극 \'가제\' · 궁녀</div><div><b>2025</b> 광고 A · 카페 손님</div><div><b>2024</b> 웹드라마 B · 동료 역</div></div>',14)+btn('경력 추가','line',"plus")+prow("이력서 파일 올리기","PDF · 5MB 이하","","upload")+'<div class="cap">이 자료는 내부 캐스팅에만 사용돼요. (동의 설정 기준)</div>',btn('저장','lg'),back=True))
add('AM21','오디션 영상','3','phone','출연자','자기소개·연기 영상을 올립니다. 개수·길이·용량 한도가 있습니다.',
 ['1건 3분·200MB 이하, 최대 3개','업로드 전 미리보기·삭제','영상별로 허용된 용도가 표시됨(내부·제3자·AI 등)'],'ACTOR',
 phone('오디션 영상',video(326,184,'1:20','자유 연기')+f'<div class="cap">이 영상은 <b>내부 캐스팅에만</b> 사용돼요.</div>'+prow("자기소개 영상","0:45 · 업로드 완료",btn("삭제","line sm"))+prow("영상 올리기","3개까지 · 3분 이내","","upload"),btn('동의 설정 바꾸기','line lg'),back=True))
add('AM22','동의 관리','3','phone','출연자','동의한 항목을 언제든 끄고 켤 수 있습니다. 철회하면 이후 제공에서 제외됩니다.',
 ['항목별 현재 상태·동의일·버전','철회 시 영향 안내 후 처리','철회 이력 확인'],'ACTOR',
 phone('동의 관리','<div class="cap">2026-12-01 동의 · 언제든 바꿀 수 있어요.</div>'+prow("제3자 제공","동의 · v1.0",switch(True))+prow("AI 학습 이용","미동의",switch(False))+prow("얼굴·음성 합성 이용","미동의",switch(False))+prow("숏폼·이미지 콘텐츠","동의 · v1.0",switch(True))+alert("철회하면 이후 제공부터 제외돼요. 이미 제공된 자료는 계약에 따라 처리돼요.","info"),None,ACT_TABS))
add('AM23','주민번호·정산 계좌','3','phone','출연자','첫 지원 전에 주민번호를, 정산 계좌를 등록합니다. 화면에는 항상 마스킹되어 보입니다.',
 ['주민번호는 암호화 저장, 등록 후 화면에 전체 노출 안 됨','계좌는 지원 때 불러오기 가능','입력값은 로그·응답에 남지 않음'],'ACTOR',
 phone('주민번호·계좌',alert('주민번호는 지원서 증빙과 세무에만 쓰이며 암호화해서 보관해요.','info')+field('주민등록번호','980312-1******')+select('은행','KB국민은행')+field('계좌번호','123456-**-******')+field('예금주','김민준'),btn('저장','lg'),back=True))
add('AM24','설정','3','phone','출연자','알림, 비밀번호 대신 로그인 유지 기간, 계정 삭제를 설정합니다.',
 ['알림 종류별 끄고 켜기','계정 삭제는 앱과 웹에서 모두 가능','개인정보 처리방침·오픈소스 라이선스'],'ACTOR',
 phone('설정',prow("새 공고 알림","",switch(True))+prow("메시지 알림","",switch(True))+prow("마케팅 알림","",switch(False))+prow("개인정보 처리방침","","")+prow("계정 삭제","",""),None,ACT_TABS))

# ---- 구매자 (4단계) ----
add('BW01','구매자 로그인','4','web','구매자','승인된 구매자 담당자가 이메일과 비밀번호로 들어갑니다.',
 ['OIO가 승인한 구매자만 사용','2차 인증 적용','접속 기록 남김'],'공개',
 bare(card(f'<div style="width:320px;display:grid;gap:14px"><div class="brand" style="padding:0;font-size:18px">OIO 캐스팅 파트너</div>{field("이메일","partner@studio.example")}{field("비밀번호","••••••••••")}<span class="btn lg">로그인</span></div>',28),minh=380))
add('BW02','인재 검색','4','web','구매자','허용된 인재를 조건으로 검색합니다. 외부 제공에 동의한 사람만 보입니다.',
 ['연락처·실명은 보이지 않고 OIO가 연결','조건: 유형·나이·키·경력','영상 길이·동의 용도 표시'],'구매자 계약 범위',
 desk('인재 검색','인재 검색','',chips(['유형 ▾','성별 ▾','나이 ▾','키 ▾','영상 있음'],-1)+'<div class="grid g4">'+''.join(card(f'<div style="display:grid;gap:8px;justify-items:start">{photo(130,110,"사진")}<b>{n}</b><div class="cap">{d}</div><div>{b}</div></div>',12) for n,d,b in [("A-1042","30대 여 · 165cm",badge("단역")),("A-0877","20대 남 · 178cm",badge("보조출연")),("A-1190","20대 여 · 160cm",badge("이미지단역")),("A-0653","40대 남 · 175cm",badge("단역"))])+'</div>',nav=[('search','인재 검색'),('msg','제안'),('file','구매 내역')],who='스튜디오 A',role='구매자'))
add('BW03','인재 상세·영상','4','web','구매자','허용된 영상과 이력을 확인하고 제안을 보냅니다.',
 ['영상은 계약 기간 동안 열람만 가능(다운로드 제한)','열람 기록이 남고 출연자가 요약 확인','실명 대신 식별 코드'],'구매자 계약 범위',
 desk('A-1042','인재 검색',btn('제안 보내기'),'<div class="grid" style="grid-template-columns:1fr 1fr">'+card(video(380,214,'1:20','자유 연기'),12)+card(f'<h3 style="font-size:15px;margin-bottom:8px">프로필</h3>{kv([("유형","단역"),("나이대","30대"),("키","165cm"),("경력","4건"),("허용 용도","열람·제안")])}',14)+'</div>',nav=[('search','인재 검색'),('msg','제안'),('file','구매 내역')],who='스튜디오 A',role='구매자'))
add('BW04','제안 보내기','4','web','구매자','출연 제안을 OIO에 보냅니다. OIO가 출연자 의사를 확인한 뒤 연결합니다.',
 ['제안 내용·일정·조건','출연자 연락처는 OIO가 보호','출연자 동의 후에만 연결'],'구매자 계약 범위',
 modal_over(f'<h3>제안 보내기</h3><div class="cap" style="margin:2px 0 12px">대상: A-1042 (단역)</div><div style="display:grid;gap:10px">{field("작품명","드라마 가제")}{field("촬영 일정","2027-02 중 3일")}{textarea("제안 내용","대사 3줄 단역입니다. 조건은 별도 협의 희망합니다.",64)}</div><div class="row" style="justify-content:flex-end;margin-top:14px">{btn("취소","line")}{btn("보내기")}</div>',minh=440))
add('BW05','제안·구매 내역','4','web','구매자','보낸 제안의 진행 상태와 이용 중인 계약 정보를 봅니다.',
 ['제안 상태: 전달, 출연자 확인, 연결, 종료','계약 기간·허용 용도 표시','열람 기록 요약'],'구매자 계약 범위',
 desk('제안·구매 내역','구매 내역','',table(["일시","대상","내용","상태"],[["12/04","A-1042","대사 3줄 단역",badge("출연자 확인 중","warn")],["11/28","A-0877","보조출연 10일",badge("연결됨","ok")],["11/20","A-1190","광고 이미지",badge("종료")]],["","","",""])+'<div style="height:12px"></div>'+card(kv([("계약 기간","2026-12-01 ~ 2027-11-30"),("허용 용도","열람·제안"),("잔여 열람","무제한")]),14),nav=[('search','인재 검색'),('msg','제안'),('file','구매 내역')],who='스튜디오 A',role='구매자',minh=440))

# ---- 추가 개발 가능 기능 예시 (옵션) ----
add('OP01','정산 기록 (옵션)','옵션','web','대표·정산 담당','출연료 지급 기록을 관리합니다. 플랫폼이 돈을 받거나 송금하지 않고 기록과 파일 생성만 합니다.',
 ['일정별 근무 시간·금액 계산, 이체 파일 생성','원천징수 계산표(세무 확인 후)','기본 꺼짐, 법률 확인 후 켬'],'정산 권한(별도)',
 desk('정산 기록','설정',btn('이체 파일 만들기'),alert('이 기능은 법률 확인 후 켜는 옵션이에요. 플랫폼은 송금하지 않아요.','warn')+'<div style="height:10px"></div>'+table(["이름","일정","근무","금액","상태"],[["김민준","11/19 사극","9시간","82,560원",badge("계산됨")],["이서연","11/19 사극","9시간","82,560원",badge("계산됨")],["최유나","11/19 사극","10시간","103,200원",badge("확정","ok")]],["","","r","r",""]),minh=420))
add('OP02','통계 리포트 (옵션)','옵션','web','대표·이사','월별 촬영·인원·출석률·노쇼 추이를 봅니다.',
 ['월별 확정 인원·출석률','작품·유형별 비교','CSV·PDF 내보내기'],'리포트 권한(별도)',
 desk('통계 리포트','대시보드',btn('PDF','line'),'<div class="grid g3" style="margin-bottom:12px">'+stat("214","이번 달 확정 인원")+stat("96%","출석률")+stat("2.1%","노쇼 비율")+'</div>'+card(f'<h3 style="font-size:15px;margin-bottom:6px">월별 확정 인원 (예시)</h3>{bars([120,148,162,190,214],["7월","8월","9월","10월","11월"],560,160)}',16),minh=440))
add('OP03','콜시트 자동 생성 (옵션)','옵션','web','스케줄러','일정·팀장·집합 정보를 모아 출연자·스태프용 콜시트 PDF를 만듭니다.',
 ['일정 정보에서 자동 구성','출연자용은 필요한 정보만 노출','공유 링크·PDF'],'일정 권한 + 별도',
 desk('콜시트','촬영 일정',btn('PDF 만들기'),'<div class="grid g2">'+card(f'<h3 style="font-size:15px;margin-bottom:8px">콜시트 미리보기</h3>{kv([("작품","사극 가제"),("일시","11/19 09:00"),("장소","강남 세트장"),("팀장","김현장 010-0000-0000"),("복장","한복")])}',16)+card(f'<h3 style="font-size:15px;margin-bottom:8px">공개 범위</h3>{checks([("장소 주소",True),("팀장 연락처",True),("보수 안내",False)])}',16)+'</div>',minh=400))
add('OP04','구독·결제 (옵션)','옵션','web','대표','외부 구매자 구독과 결제 내역을 관리합니다. 카드 결제사 연동이 필요합니다.',
 ['요금제·구독 상태·결제 내역','결제사(PG) 연동은 별도 계약 필요','세금계산서 연동은 추가 개발'],'구매자 관리 + 결제',
 desk('구독·결제','자료 제공',btn('요금제 편집','line'),table(["구매자","요금제","시작","다음 결제","상태"],[["스튜디오 A","월 정액 기본","2026-12","2027-01-01",badge("정상","ok")],["제작사 B","월 정액 확장","2027-01","2027-02-01",badge("결제 대기","warn")]],["","","","",""])+'<div class="cap" style="margin-top:10px">금액은 OIO가 정합니다. 화면 예시에는 금액을 넣지 않았어요.</div>',minh=400))
