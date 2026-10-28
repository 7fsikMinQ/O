#!/usr/bin/env python3
"""v1.0 명세 → v1.1 명세 변환 (1회성). 사용자 답변 50문항을 반영해 요구/기능/화면/API/프로그램 명세 파일을 다시 쓴다."""
import os, sys, pprint
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPEC = os.path.join(ROOT, "tools", "spec")
sys.path.insert(0, SPEC)
import importlib
reqs = importlib.import_module("reqs").REQS
scr = importlib.import_module("screens").SCREENS
api = importlib.import_module("apis").APIS
pgm = importlib.import_module("programs").PROGRAMS

def by_id(lst): return {x[0]: i for i, x in enumerate(lst)}
def remove(lst, ids):
    return [x for x in lst if x[0] not in ids]
def replace(lst, new):
    idx = by_id(lst)
    for t in new:
        if t[0] in idx: lst[idx[t[0]]] = t
        else: lst.append(t)
    return lst
def insert_after(lst, after_id, items):
    idx = by_id(lst)[after_id] + 1
    for k, t in enumerate(items): lst.insert(idx + k, t)
    return lst

# ───────────────────────── 요구사항 ─────────────────────────
R = remove(reqs, {"REQ-J08","REQ-SEL07","REQ-SEL08","REQ-PC14","REQ-AP05","REQ-A08","REQ-A06x"})
R = replace(R, [
("REQ-A02","계정","소셜 로그인","카카오(우선)·구글로 가입/로그인한다. 애플 로그인은 iOS 앱 출시 시 추가(현재 iPhone 은 모바일 웹).","P1","U","소셜 최초 로그인 시 유형 선택·추가정보 입력으로 이어진다"),
("REQ-A07","계정","출연자 휴대폰 필수·유일 및 인증","휴대폰 번호는 출연자 필수·중복 불가. 인증은 카카오 전화번호 동의항목(무료) 또는 국내 문자 OTP(건당 소액) 중 가장 저렴·간단한 방식.","P1","U","동일 번호 가입 거부, 인증 완료 전 지원 불가"),
("REQ-P01","출연자 프로필","기본·신체 프로필","성별·생년월일·키·몸무게·사이즈·집주소·머리 길이·문신 유무·운전 여부·거주 지역을 관리한다.","P1","U","필수 항목 입력 시 프로필 완성도 100%"),
("REQ-P03","출연자 프로필","기본 계좌·집합지 저장","프로필에 기본 계좌(은행·번호·예금주, 암호화)와 기본 집합지를 저장. 지원 시 '내 정보에서 불러오기' 또는 다른 계좌 직접 입력.","P1","U","불러오기/직접 입력 둘 다 가능, 지원서에는 선택한 값이 스냅샷으로 저장"),
("REQ-P05","출연자 프로필","내 활동 이력","확정·출석·노쇼·취소 패널티 횟수를 본인이 확인한다(점수제 없음, 업체에는 횟수만 노출).","P1","U","원장과 캐시 횟수가 일치"),
("REQ-C02","업체","업체 직원·직책 관리","대표가 직원을 초대하고 OWNER(대표)/MANAGER(팀장)/SCHEDULER(스케줄러 담당 1~4명)/FIELD_LEADER(현장반장)/STAFF 직책을 부여한다. 한 사람이 여러 업체 소속 가능.","P1","U","직책별 권한 매트릭스에 따라 API 접근 제어"),
("REQ-J01","공고","공고 작성(일정·역할 다중)","한 공고에 일정(최소 1시간~최대 7일 연속 구간, 자정 넘김 심야·2박3일·5박6일 숙박 포함) 여러 개, 일정마다 역할 여러 개를 등록한다.","P1","U","1시간 미만/7일 초과 거부, 자정 넘김·숙박 일정 저장, 역할 5개 일괄 등록"),
("REQ-J03","공고","긴급 공고","당일/익일 긴급 모집을 표시한다(상단 고정 노출은 추후 유료 후보, 현재 무료).","P1","R","긴급 표시 공고가 목록 상단"),
("REQ-J04","공고","비공개 현장(기본값)","공고는 대부분 가제로 올라온다. 기본값으로 가제·지역·시간·역할만 공개하고 실제 작품명·상세 장소는 합격자에게 공개.","P1","U","비합격자 응답에 가제·대략 지역·시간·역할만 포함"),
("REQ-J06","공고","일정 취소·연기","일정 취소 시 영향 받는 지원자에게 알림. 연기 시 합격자를 새 일정으로 자동 이동하고 새 공고 안내를 보내며, 출연자가 '확정'을 눌러야 인원이 채워진다. 출연자가 취소를 누르면 연기 건은 패널티 없이 취소.","P1","U","이동된 지원은 SELECTED 상태, 확정 전에는 정원 미충족, 취소 시 penalty_exempt"),
("REQ-J07","공고","역할별 필수 요건","성별·출생연도 범위·키 범위·몸무게(체형) 범위·머리 길이·문신 유무를 필수 조건으로 건다. 지원 시 현재 사진 첨부는 항상 필수.","P1","U","요건 불충족 시 지원 버튼 비활성+사유 표시"),
("REQ-J10","공고","시급·최저시급·비고","보수는 시급제. '최저시급' 선택 시 해당 연도 최저시급 자동 입력. 근무 9시간 이내는 최저시급×8시간 고정, 초과 시 시급×근무시간. 식대(아침·점심·저녁) 등은 비고에 자유 기재.","P1","U","연도별 최저시급 테이블에서 자동 채움, 정산 계산 규칙과 일치"),
("REQ-S01","탐색","달력 조회","월/주 달력에 날짜별 공고 수를 보이고(다일 일정은 걸친 모든 날짜) 날짜를 누르면 해당일 공고를 본다.","P1","U","월 이동 시 300ms 이내 응답(p95)"),
("REQ-S02","탐색","목록·필터·검색","날짜·지역·역할 유형·성별·나이·시급·긴급 필터와 키워드 검색.","P1","U","필터 조합 결과가 조건과 일치"),
("REQ-AP01","지원","지원(정보표 자동 채움)","이름·휴대폰·성별·출생연도·키·몸무게·사이즈·집주소·집합지·계좌·주민등록번호가 프로필에서 채워지고 공고별로 수정해 지원한다. 지원 시점 값은 스냅샷으로 저장.","P1","U","주민번호는 복제하지 않고 당시 버전을 참조"),
("REQ-AP04","지원","미성년자 동의서 첨부","(현재 미성년 지원 불가) 허용 시 법정대리인 동의서가 검증된 경우에만 지원.","P3","U","설정 minor_apply_enabled=true 에서만 동작"),
("REQ-SEL04","선발","합격 통보·불합격 일괄 처리","체크박스 다중 선택으로 합격 통보(SELECTED) 또는 불합격(REJECTED). 불합격 사유 입력 없음.","P1","U","정원 초과 시 일부 실패 응답과 사유"),
("REQ-SEL05","선발","마감 시 자동 불합격","통보 없이 마감되면 APPLIED 를 AUTO_REJECTED 로 전환한다(예비합격 제도 없음).","P1","U","마감 트랜잭션 내 일괄 전환+안내 알림"),
("REQ-SEL06","선발","시간 겹침 경고(합격 통보)","합격 통보 시 같은 사람의 다른 합격/확정과 시간이 겹치면 업체 화면에 경고 배지. 최종 확정 단계의 충돌은 SEL10 이 처리.","P1","U","겹침 배지 표시"),
("REQ-PC01","합격 이후","공개 범위 단계화","휴대폰 번호는 지원 단계부터 업체에 공개. 집주소·계좌는 합격 통보 이후 권한자만, 주민번호는 화면 마스킹·권한자 내보내기에서만 전체.","P1","U","APPLIED 응답에 계좌·주소 상세·주민번호 없음"),
("REQ-PC03","합격 이후","단체·개별 메시지(답장 가능)","합격자 전체·역할별·참석 미확인자·마감 후 가용 지원자·선택 대상에게 알림/메시지 발송. 출연자는 답장만 가능(먼저 보낼 수 없음).","P1","U","발송 수·읽음 수 확인, 출연자 선발송 API 없음"),
("REQ-PC05","합격 이후","인원 정보표 + 엑셀","이름·휴대폰·성별·출생연도·키·몸무게·사이즈·집주소·계좌·집합지·주민번호를 표로 관리하고 엑셀로 내려받는다. 주민번호 포함 파일은 암호화.","P1","U","다운로드는 권한자만, 감사로그, 암호화 파일"),
("REQ-PC06","합격 이후","참석 확인","집합 2시간 전부터 30분 전까지 참석 확인. 미응답자는 마감 시점에 담당 반장에게 알림.","P1","U","창 밖에서는 응답 거부"),
("REQ-PC07","합격 이후","QR 출퇴근","팀장/담당 반장이 출근·퇴근 세션을 열어야 출연자 앱에 본인 전용 QR 이 나타나고(수초마다 회전), 반장 앱이 스캔해 기록한다(대리 출석 방지). 수기 보정은 감사 대상.","P1","U","세션이 닫혀 있으면 QR 미표시, 타인 QR 재사용 불가"),
("REQ-PC09","합격 이후","노쇼 횟수·취소 패널티 판정","노쇼는 횟수만 업체에 노출. 확정 후 출연자가 취소하면 업체 스케줄러 담당(1~4명)에게 알림 → 패널티 부과/미부과 투표, 한 명이라도 부과하면 공고당 1건만 반영.","P1","U","4명이 모두 부과해도 공고당 1건, 점수제 없음"),
("REQ-PC10","합격 이후","정산 기록·은행 대량이체·원천징수 엑셀","기본·연장·식대 등 항목을 기록하고 은행별 대량이체 양식과 원천징수 자료 엑셀을 생성한다(세금 유형은 세무 확인 후 확정).","P2","U","항목 합계 = 지급액, 양식은 실제 은행 양식 확보 후 추가"),
("REQ-PC11","합격 이후","상호 평가","출석 완료 건에 한해 양방향 평가(블라인드).","P3","R","양쪽 작성 후 공개"),
("REQ-N02","알림","푸시(FCM)·iPhone 폴백","안드로이드/웹 푸시. iPhone 웹은 홈 화면 추가 시에만 웹푸시가 가능하므로 중요 알림은 폴백 채널(P3 알림톡)·앱 내 배너로 보완.","P1","R","기기 다중 토큰, 푸시 불가 사용자 식별"),
("REQ-M09","관리자","동의서 검수","(현재 미성년 지원 불가) 허용 시 동의서 서류 검수.","P3","L","승인/반려와 사유"),
("REQ-NFR03","비기능","성능","출연자 약 1만 명·업체 10~50곳·일 공고 30~150건 규모에서 목록·달력 p95 300ms.","P1","R","부하 테스트 리포트"),
("REQ-NFR06","비기능","UI/UX 디자인","토스·당근 감성(파스텔 톤, 큰 타이포·여백·바텀시트·카드·이모지 버튼). 서비스명·로고는 공란(추후). 웹은 shadcn 기반.","P1","U","15번 디자인 시스템 준수"),
("REQ-NFR07","비기능","호환·접근성","Android 앱 우선, iPhone 은 모바일 웹(PWA)으로 지원하되 설계는 iOS 앱 출시를 대비. 글자 확대·명도 대비 준수.","P1","U",""),
])
R = insert_after(R, "REQ-A07", [
("REQ-A08","계정","미성년자 지원 차단","생년월일로 만 나이를 계산해 만 19세 미만은 현재 지원할 수 없다(설정으로 향후 허용).","P1","U","미성년 지원 시도 시 안내 후 차단"),
("REQ-A09","계정","미성년자 법정대리인 동의서 흐름(선반영)","동의서 제출·검수·유효기간 구조만 설계해 두고 현재는 비활성.","P3","U","설정으로 활성화 시 동작"),
])
R = insert_after(R, "REQ-P06", [("REQ-P07","출연자 프로필","주민등록번호 등록","원천징수·지급명세서용으로 첫 지원 전에 등록(필수). 암호화·버전 이력·중복 가입 탐지 해시. 법무 확인 후 유지.","P1","U","첫 지원 전 미등록 시 지원 불가, 화면에는 항상 마스킹")])
R = insert_after(R, "REQ-C05", [("REQ-C06","업체","스케줄러 담당 알림 수신","업체별 스케줄러 담당(1~4명)이 출연자 취소 알림을 받고 판정한다.","P1","U","SCHEDULER 직책에게만 판정 알림")])
R = insert_after(R, "REQ-J07", [("REQ-J08","공고","마감 후 지원자 재열람","공고가 마감되어도 업체는 '이전 공고 보기'에서 지원자를 열람하고 추가 합격을 진행할 수 있다.","P1","U","CLOSED 공고에서도 지원자 열람·SELECT 가능(정원 여유 시)")])
R = insert_after(R, "REQ-AP04", [
("REQ-AP05","지원","현재 사진 첨부(필수)","지원 때마다 현재 사진을 1장 이상 첨부해야 제출할 수 있다.","P1","U","미첨부 시 제출 불가"),
("REQ-AP06","지원","지원서 전자서명","지원서에 화면 서명을 받아 증빙으로 보관(서명 이미지·시각·IP·기기·약관 버전·무결성 해시).","P1","U","서명 없는 지원 불가, 해시로 변조 탐지"),
("REQ-AP07","지원","지원서 증빙 사본(PDF)","확정 시점에 지원서 내용·서명·사진을 묶은 PDF 사본을 보관/제공.","P2","U","본인·권한 업체만 열람"),
])
R = insert_after(R, "REQ-SEL06", [
("REQ-SEL09","선발","출연자 확정(수락)","합격 통보(SELECTED)를 받은 출연자가 '확정'을 눌러야 CONFIRMED 가 되어 인원이 채워진다. 기한 내 미응답 시 만료.","P1","U","확정 전에는 confirmed_count 미증가, 만료 시 정원 해제"),
("REQ-SEL10","선발","복수 확정 충돌 경고","시간이 겹치는 두 일정을 출연자가 알면서 둘 다 확정하면 양쪽 업체에 알림·메시지, 출연자에게는 '확인'을 누를 때까지 1시간마다 경고.","P1","U","확인 시 반복 중지, 충돌 현황 업체 화면 표시"),
("REQ-SEL11","선발","마감 후 추가 합격","이전 공고의 지원자 중 해당 일정 ±1일에 다른 합격이 없는 사람을 확인하고 메시지/알림 후 합격 통보한다.","P1","U","가용 판단 쿼리 결과가 후보 목록과 일치"),
])
R = insert_after(R, "REQ-PC14x" if False else "REQ-PC13", [("REQ-PC15","합격 이후","증빙 보관","지원서(서명·사진·스냅샷)·출결·정산·메시지 이력을 보존기간 동안 보관하고 분쟁 시 보류한다.","P1","U","보존기간 설정(기본 5년), 법적 보류 플래그")])
R = insert_after(R, "REQ-N06", [("REQ-N07","알림","확인 요구 반복 알림","확인(ack)이 필요한 알림은 사용자가 확인할 때까지 설정 주기(기본 1시간)로 재발송.","P1","U","확인 시 즉시 중지")])
R = insert_after(R, "REQ-M09", [("REQ-M10","관리자","연도별 최저시급 관리","매년 고시되는 최저시급을 등록·수정하고 미등록 연도 공고 작성 시 안내.","P1","U","공고 작성 화면이 해당 연도 값을 자동 사용")])
R = insert_after(R, "REQ-NFR10", [
("REQ-NFR11","비기능","다크 모드","앱·웹 모두 라이트/다크 테마(시스템 설정 연동).","P1","U","토큰 대비 4.5:1 이상"),
("REQ-NFR12","비기능","클라이언트 범위","Android 앱(Flutter) 우선, iPhone 은 같은 Flutter 코드의 웹(PWA)으로 제공. 업체/관리자는 React 웹.","P1","U","동일 API, 동일 디자인 토큰"),
])
R = [r for r in R if r[0] != "REQ-A06x"]

# ───────────────────────── 화면 ─────────────────────────
S = list(scr)
S = replace(S, [
("SCR-H05","APP","공고/지원","지원하기(정보표 확인·현재 사진·서명)","P1","프로필에서 자동 입력(이름·번호·성별·출생연도·키·몸무게·사이즈·집주소·집합지), 계좌 '불러오기/다른 계좌 입력', 현재 사진 필수, 서명 단계, 겹침 안내"),
("SCR-H09","APP","내 일정/합격상세","합격 상세·현장 안내","P1","상세 집결지, 반장 연락처, 업체 메시지, 비공개 정보, 참석 확인·출근 QR 진입"),
("SCR-H10","APP","내 일정/참석확인","참석 확인","P1","집합 2시간 전~30분 전 활성, 원터치 확인"),
("SCR-H18","APP","MY/미성년자","법정대리인 동의서 제출(비활성)","P3","설정으로 활성화 시 사용"),
("SCR-H19","APP","MY/활동","내 활동 이력","P1","확정·출석·노쇼·취소 패널티 횟수(점수 없음)"),
("SCR-H20","APP","MY/활동","평가 작성","P3","별점·한줄평"),
("SCR-F05","FLD","현장/퇴근","퇴근 세션·QR 스캔","P1","퇴근 세션 열기/닫기, 인원별 QR 스캔, 수기 보정"),
("SCR-M11","ADM","승인/동의서","미성년자 동의서 검수(비활성)","P3","설정으로 활성화 시 사용"),
("SCR-C03","WEB","공고/작성","공고 작성 마법사","P1","가제→일정(1시간~7일, 자정 넘김·숙박)→역할(필수 요건·시급·비고)→검토, 임시저장"),
("SCR-C08","WEB","지원자/상세","지원자 상세 패널","P1","프로필·현재 사진·전화 열람·역대 합격 탭·타 공고 지원 탭·겹침 배지"),
("SCR-C09","WEB","지원자/처리","일괄 합격 통보·불합격·마감","P1","확인 모달, 정원 검증 결과, 불합격 사유 없음"),
("SCR-C11","WEB","현장/인원정보표","인원 정보표","P1","이름·번호·성별·출생연도·키·몸무게·사이즈·집주소·계좌·집합지·주민번호(마스킹), 엑셀(암호화) 다운로드"),
])
S = remove(S, set())
S += [
("SCR-H27","APP","내 일정/합격통보","합격 통보 확정/거절","P1","'합격했어요' 카드, [확정하기]/[어렵겠어요], 응답 기한, 연기된 일정 안내 포함, 겹침 시 경고 시트"),
("SCR-H28","APP","내 일정/충돌경고","일정 충돌 경고","P1","'확인' 누를 때까지 1시간마다 알림, 어느 쪽을 취소할지 선택"),
("SCR-H29","APP","메시지","메시지함","P1","업체가 보낸 메시지 목록, 읽음 표시 (출연자가 먼저 시작 불가)"),
("SCR-H30","APP","메시지","대화/답장","P1","메시지 확인과 답장"),
("SCR-H31","APP","MY/프로필","주민등록번호 등록","P1","고유식별정보 동의, 입력(항상 마스킹), 안내 문구, 변경 이력"),
("SCR-H32","APP","공고/지원","전자서명","P1","서명 패드, 약관 버전 표시, 제출"),
("SCR-H33","APP","내 일정/출퇴근","출퇴근 QR","P1","세션이 열려 있을 때만 개인 QR 표시(수초 회전)"),
("SCR-F08","FLD","현장/출근","출근 세션·QR 스캔","P1","출근 세션 열기/닫기, 인원별 QR 스캔, 진행 현황"),
("SCR-F09","FLD","현장/취소판정","취소 패널티 판정(스케줄러)","P1","취소 알림 목록, 사유 확인, 부과/미부과 선택"),
("SCR-C23","WEB","현장/취소판정","취소 패널티 판정","P1","스케줄러 담당 대상 목록과 투표 현황"),
("SCR-C24","WEB","지원자/마감후","마감 후 추가 합격","P1","이전 공고 지원자 목록, ±1일 가용 판단, 선택 후 메시지/합격 통보"),
("SCR-C25","WEB","현장/메시지함","메시지함","P1","스레드 목록·답장 확인"),
("SCR-M15","ADM","마스터","연도별 최저시급 관리","P1","연도·시급·시행일 등록"),
("SCR-M16","ADM","마스터","엑셀 양식 관리","P2","인원표/은행 대량이체/원천징수 양식 컬럼 정의"),
]

# ───────────────────────── API ─────────────────────────
A = remove(api, {"API-SL07","API-OP12","API-OP13","API-OP10"})
A = replace(A, [
("API-ME03","PUT","/me/actor-profile","프로필 저장","ACTOR","{birthDate,gender,heightCm,weightKg,topSize,bottomSize,shoeSize,hairLength,hairStyle,hasTattoo,canDrive,hasVehicle,regionId,homePostalCode,homeAddress,homeAddressDetail,tags[],intro}","actor_profiles","422"),
("API-ME04","PUT","/me/actor-profile/payout","기본 계좌·집합지 저장","ACTOR","{bankCode,accountNumber,holder,defaultDepartureLocation} (서버 암호화)","actor_payout_accounts,actor_profiles","422"),
("API-ME07","GET","/me/activity","내 활동 이력(횟수)","ACTOR","→ {confirmed,attended,noshow,penalty}","actor_profiles,actor_penalty_events,applications",""),
("API-AP01","GET","/positions/{id}/apply-form","지원 폼 사전정보","ACTOR","→ 자동 입력값(이름·번호·성별·출생연도·키·몸무게·사이즈·집주소·집합지·계좌 마스킹), 주민번호 등록 여부, 자격 판정 사유, 최소 사진 수, 서명 약관, 겹침 경고","actor_profiles,actor_payout_accounts,actor_tax_identities,applications,terms",""),
("API-AP02","POST","/positions/{id}/applications","지원","ACTOR","{departureLocation,payout:{useDefault|bankCode,accountNumber,holder},message,photoFileIds[≥1],signatureFileId,signedTermsVersion} (Idempotency-Key) → 201 {applicationId,status:APPLIED}. 서버가 프로필 스냅샷·무결성 해시·IP/기기 기록","applications,actor_tax_identities,file_attachments,application_status_histories,outbox_events","403 차단/미성년/주민번호 미등록, 409 중복, 422 자격·사진·서명"),
("API-AP03","DELETE","/applications/{id}","지원/확정 취소","ACTOR","{reason?} (확정 후 취소는 reason 필수) → 204. 확정 후 취소 시 스케줄러 판정 대기 생성","applications,cancellation_reviews,outbox_events","409"),
("API-AP07","POST","/applications/{id}/departure-confirm","참석 확인","ACTOR","→ 204 (집합 2시간 전~30분 전에만)","applications","409 기간 외"),
("API-SL01","GET","/company/positions/{id}/applications","역할별 지원자 목록","CMP","?status&sort&view=card|grid → 휴대폰 공개, 집주소/계좌/주민번호는 합격 통보 이후 권한자만","applications,actor_profiles",""),
("API-SL05","POST","/company/applications/decide","일괄 합격 통보·불합격","CMP","{applicationIds[],decision:SELECT|REJECT} (사유 없음) → 207 {succeeded[],failed[{id,code}],warnings[{id,code:OVERLAPS_OTHER}]}. SELECT 는 정원(selected+confirmed<headcount) 원자 증가·예약 구간 기록·확정 기한 설정","applications,job_positions,application_status_histories,outbox_events,audit_logs","207 부분 성공, 409 FULL"),
("API-SL06","POST","/company/applications/{id}/cancel","합격 취소(업체)","CMP-M","{} → 출연자 알림, penalty_exempt","applications,job_positions,outbox_events",""),
("API-OP02","GET","/company/schedules/{id}/roster","인원 정보표","CMP-F","?position&q → 이름·번호·성별·출생연도·키·몸무게·사이즈·집주소·집합지·계좌(권한자)·주민번호(항상 마스킹)","v_schedule_confirmed_roster,audit_logs",""),
("API-OP03","GET","/company/schedules/{id}/roster.xlsx","인원 정보표 엑셀(암호화)","CMP-M","?template&includeRrn → 비밀번호로 보호된 xlsx (비밀번호는 별도 1회 표시), 감사로그","v_schedule_confirmed_roster,actor_tax_identities,export_templates,audit_logs,plan_features","403 권한/플랜"),
("API-OP04","POST","/company/broadcasts","단체 알림/메시지 발송","CMP-F","{scheduleId|positionId,targetFilter:ALL_SELECTED|ALL_CONFIRMED|DEPARTURE_UNCONFIRMED|CLOSED_AVAILABLE|CUSTOM,applicationIds?[],title,body,channels[]} → 202 {broadcastId,recipientCount}. 수신자별 스레드 생성","job_broadcasts,job_broadcast_recipients,message_threads,messages,notifications,outbox_events,plan_features,usage_counters","429 월 한도"),
("API-OP05","GET","/company/broadcasts","발송 내역","CMP-F","?postingId → 수신/읽음/답장 수","job_broadcasts,job_broadcast_recipients,messages",""),
("API-OP06","GET","/company/schedules/{id}/departure-pending","참석 미응답자 목록","CMP-F","→ 이름·전화","applications",""),
("API-OP07","PUT","/company/schedules/{id}/attendances","수기 출석/노쇼 보정","CMP-F","{items:[{applicationId,workDate,result,checkinAt?,checkoutAt?,note?}]} (멱등, 감사)","attendances,actor_penalty_events,audit_logs","403 담당 아님"),
("API-JB01","POST","/company/jobs","공고 생성(임시저장)","CMP","{title(가제),productionId?,concealDetails=true,isUrgent,contactPhone,applyDeadlineAt,schedules[{startAt,endAt,isLodging,regionId,shootingLocation,meetingPlace,meetingAddress,fieldLeaderUserId,positions[{title,headcount,genderReq,birthYearMin/Max,heightMin/Max,weightMin/Max,hairLengths[],tattooReq,wageMode:MIN_WAGE|CUSTOM,hourlyWageKrw?,payNote,dressCode,requirements,startAt?,endAt?,meetingPlace?}]}]} → 201","job_postings,job_schedules,job_positions,minimum_wages,productions","422 일정 1시간~7일 위반·최저시급 연도 미등록"),
("API-JB07","PATCH","/company/schedules/{id}/status","일정 마감/취소","CMP","{status:CLOSED|CANCELED,reason}","job_schedules,applications,outbox_events",""),
])
A += [
("API-ME14","PUT","/me/actor-profile/tax-identity","주민등록번호 등록·교체","ACTOR","{rrn,termVersion} (쓰기 전용, 서버 암호화+해시, 기존 버전 비활성) → {masked}","actor_tax_identities,user_consents,audit_logs","409 이미 등록된 번호(중복 가입), 422 형식/생년월일·성별 불일치"),
("API-ME15","GET","/me/actor-profile/tax-identity","주민번호 등록 상태","ACTOR","→ {registered, masked:'900101-1******'}","actor_tax_identities",""),
("API-ME16","GET","/me/messages/threads","내 메시지함","ACTOR","?cursor → 업체가 보낸 스레드 목록","message_threads,messages",""),
("API-ME17","GET","/me/messages/threads/{id}","대화 보기","ACTOR","→ 메시지 목록(읽음 처리)","message_threads,messages",""),
("API-ME18","POST","/me/messages/threads/{id}/reply","답장","ACTOR","{body} (업체가 시작한 스레드에만)","messages,notifications","403 스레드 없음"),
("API-ME19","GET","/me/conflicts","내 일정 충돌 목록","ACTOR","→ 미확인 충돌","schedule_conflicts,applications",""),
("API-ME20","POST","/me/conflicts/{id}/ack","충돌 경고 확인","ACTOR","{keepBoth|cancelApplicationId} → 재알림 중지","schedule_conflicts,applications,notifications",""),
("API-AP08","POST","/applications/{id}/confirm","출연자 확정(수락)","ACTOR","{proceedDespiteConflict?:bool} → 200 CONFIRMED. 겹침이 있으면 409 TIME_CONFLICT + 충돌 목록, 명시적으로 진행하면 conflict_override·충돌 추적·양쪽 업체 알림","applications,job_positions,schedule_conflicts,notifications,outbox_events","409 TIME_CONFLICT/EXPIRED/FULL"),
("API-AP09","GET","/me/applications/{id}/qr","출퇴근 개인 QR 토큰","ACTOR","→ {token,expiresIn} (열린 세션이 있을 때만, 수초마다 재발급)","attendance_sessions,applications","409 세션 없음"),
("API-AP10","GET","/applications/{id}/document","지원서 증빙 사본","ACTOR","→ PDF (본인) / 권한 업체","applications,file_attachments","403"),
("API-SL08","GET","/company/jobs/{id}/reserve-candidates","마감 후 추가 합격 후보","CMP","?scheduleId → 지원자 중 일정 ±1일에 다른 합격/확정이 없는 사람","applications,job_schedules,job_positions",""),
("API-OP14","POST","/company/messages","개별/선택 메시지 시작","CMP-F","{applicationIds[],title,body} → 스레드 생성","message_threads,messages,notifications,outbox_events",""),
("API-OP15","GET","/company/messages/threads","업체 메시지함","CMP-F","?postingId&unread","message_threads,messages",""),
("API-OP16","GET","/company/messages/threads/{id}","대화 보기","CMP-F","","message_threads,messages",""),
("API-OP17","POST","/company/messages/threads/{id}/reply","업체 답장","CMP-F","{body}","messages,notifications",""),
("API-OP18","GET","/company/cancellation-reviews","취소 패널티 판정 대기","CMP","→ SCHEDULER 에게만 대기 건 노출","cancellation_reviews,applications",""),
("API-OP19","POST","/company/cancellation-reviews/{applicationId}/vote","패널티 판정 투표","CMP","{vote:PENALTY|NO_PENALTY} (SCHEDULER 만). 한 명이라도 PENALTY → 공고당 1건 부과","cancellation_review_votes,cancellation_reviews,actor_penalty_events,actor_profiles,audit_logs","403 SCHEDULER 아님"),
("API-OP20","POST","/company/schedules/{id}/attendance-sessions","출근/퇴근 세션 열기","CMP-F","{kind:CHECKIN|CHECKOUT,workDate} → 출연자 앱에 QR 활성화","attendance_sessions","403 담당 아님, 409 이미 열림"),
("API-OP21","POST","/company/attendance-sessions/{id}/close","세션 닫기","CMP-F","","attendance_sessions",""),
("API-OP22","POST","/company/attendance/scan","QR 스캔 출퇴근 기록","CMP-F","{sessionId,token} → {applicationId,name,result} (멱등)","attendances,attendance_sessions,applications","409 만료/타 세션 토큰"),
("API-OP23","GET","/company/schedules/{id}/settlements/bank-transfer.xlsx","은행 대량이체 엑셀","CMP-M","?template → xlsx","settlements,settlement_items,export_templates,audit_logs",""),
("API-OP24","GET","/company/schedules/{id}/settlements/withholding.xlsx","원천징수 자료 엑셀(암호화)","CMP-M","→ 주민번호 포함, 비밀번호 보호","settlements,actor_tax_identities,export_templates,audit_logs","403 RRN_REVEAL 권한"),
("API-OP25","POST","/company/schedules/{id}/postpone","일정 연기(자동 이동)","CMP","{newStartAt,newEndAt,reason} → 새 일정 생성, 합격자는 SELECTED 상태로 이동(출연자가 확정해야 인원 충족), 안내 알림","job_schedules,job_positions,applications,application_status_histories,outbox_events",""),
("API-OP26","POST","/company/applications/{id}/reveal-rrn","주민번호 단건 열람","CMP-M","→ {rrn} (사유 입력, 감사로그, 합격 통보 이후만)","actor_tax_identities,audit_logs","403"),
("API-JB12","POST","/company/jobs/{id}/reopen","마감 해제(정원 여유 시)","CMP","","job_postings,job_positions",""),
("API-MS05","GET","/master/minimum-wage","연도별 최저시급","CMP","?year → {year,hourlyKrw}","minimum_wages",""),
("API-AD23","PUT","/admin/minimum-wages/{year}","최저시급 등록·수정","ADM","{hourlyKrw,effectiveFrom,sourceNote}","minimum_wages,audit_logs",""),
("API-AD24","PUT","/admin/export-templates/{code}","엑셀 양식 편집","ADM","{name,kind,bankCode,columns[]}","export_templates,audit_logs",""),
]
# 기존 API 중 의미가 바뀐 이름 정리
A = [a for a in A if a[0] not in ("API-AU12x",)]

# ───────────────────────── 프로그램 ─────────────────────────
P = list(pgm)
P = remove(P, {"PGM-J07"})
P = replace(P, [
("PGM-J03","배치","DepartureCheckRequester","attendance","집합 2시간 전이 된 확정자에게 참석 확인 푸시(departure_requested_at 기록)","5분 주기"),
("PGM-J04","배치","DepartureDeadlineNotifier","attendance","집합 30분 전 시점에 미응답자를 담당 반장/업체에 알림","5분 주기"),
("PGM-J05","배치","AutoRejectOnDeadline","application","지원 마감시각 경과·업체 마감·집합 시각 경과 시 미통보(APPLIED) 자동 불합격","5분 주기"),
])
P += [
("PGM-J16","배치","ConflictReminder","application","확인되지 않은 일정 충돌에 대해 출연자에게 1시간 간격(설정) 경고 재발송","10분 주기"),
("PGM-J17","배치","SelectionExpirer","application","합격 통보 후 응답 기한 경과 건을 EXPIRED 처리하고 정원 해제, 업체 알림","5분 주기"),
("PGM-J18","배치","CancelReviewTimeout","attendance","취소 패널티 판정 대기 제한 경과 시 미부과 처리","1시간 주기"),
("PGM-J19","배치","EvidenceRetentionPurger","application","보존기간(기본 5년) 경과·법적 보류 아님인 지원서/주민번호/정산 증빙 파기","매일 03:00"),
]
P = replace(P, [("PGM-B08","BE모듈","attendance","backend/.../attendance","참석확인·QR 출퇴근 세션·출석·노쇼 집계·취소 패널티 판정","상시")])

def dump(name, var, rows, header):
    path = os.path.join(SPEC, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(header + "\n" + var + " = [\n")
        for r in rows: f.write("  " + repr(tuple(r)) + ",\n")
        f.write("]\n")

dump("reqs.py", "REQS", R, "# 요구사항: (ID, 그룹, 제목, 설명, 우선순위, 출처, 인수기준)  출처: U=사용자 요구 R=검토 반영 L=법·보안 X=확장 대비\n# v1.1: 사용자 답변 50문항 반영")
dump("screens.py", "SCREENS", S, "# 화면: (ID, 플랫폼, 메뉴 경로, 화면명, 우선순위, 설명)  플랫폼: APP=출연자 앱(Flutter, 웹/PWA 동일 코드) FLD=현장(반장) 모드 WEB=업체 웹(React) ADM=관리자 웹")
dump("apis.py", "APIS", A, "# API: (ID, METHOD, PATH(/api/v1), 이름, 권한, 요청/응답 요약, 테이블, 주요 오류)\n# 권한: PUB USER ACTOR CMP(승인 업체 구성원) CMP-M(OWNER/MANAGER) CMP-F(OWNER/MANAGER/담당 FIELD_LEADER) OPR ADM")
dump("programs.py", "PROGRAMS", P, "# 프로그램: (ID, 구분, 이름, 위치/패키지, 책임, 실행/주기)")
print("REQ", len(R), "SCR", len(S), "API", len(A), "PGM", len(P))
