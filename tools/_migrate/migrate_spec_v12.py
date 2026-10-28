#!/usr/bin/env python3
"""v1.1 → v1.2 명세 변환 (1회성): 사용자 확정(H1~H8) + 외부 사실 검증 결과 반영."""
import os, sys, importlib
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPEC = os.path.join(ROOT, "tools", "spec"); sys.path.insert(0, SPEC)
R = [list(x) for x in importlib.import_module("reqs").REQS]
S = [list(x) for x in importlib.import_module("screens").SCREENS]
A = [list(x) for x in importlib.import_module("apis").APIS]
P = [list(x) for x in importlib.import_module("programs").PROGRAMS]
Fn = [list(x) for x in importlib.import_module("funcs").FUNCS]

def idx(lst, i): return [k for k, x in enumerate(lst) if x[0] == i][0]
def setf(lst, i, field, val): lst[idx(lst, i)][field] = val
def add_after(lst, after, item): lst.insert(idx(lst, after) + 1, list(item))

# ── 요구사항 (R: id, 그룹, 제목, 설명, 우선, 출처, 인수)
setf(R,"REQ-J01",3,"한 공고에 일정(최소 1시간~최대 3주=21일 연속 구간, 자정 넘김 심야·2박3일·5박6일 숙박 포함) 여러 개, 일정마다 역할 여러 개를 등록한다.")
setf(R,"REQ-J01",6,"1시간 미만/21일 초과 거부, 자정 넘김·숙박 일정 저장, 역할 5개 일괄 등록")
setf(R,"REQ-J10",3,"보수는 시급제. '최저시급' 선택 시 **근무 시작일이 속한 연도**의 최저시급 자동 입력(2026=10,320원, 2027=10,700원). 근무 9시간 이내는 최저시급×8시간 고정, 초과 시 시급×근무시간. 연도 경계를 넘는 일정은 날짜별 연도 시급 적용. 식대(아침·점심·저녁) 등은 비고에 자유 기재.")
add_after(R,"REQ-J10",["REQ-J11","공고","법정 최저시급 미만 입력 차단","'직접 입력' 시급도 근무 시작일 연도의 최저시급 이상이어야 한다(최저임금법 준수). 미만이면 저장·게시 거부.","P1","L","최저시급 미만 입력 시 422 BELOW_MIN_WAGE"])
setf(R,"REQ-A07",2,"휴대폰은 출연자 필수·중복 불가. 인증은 **문자 OTP(국내 문자 대행, 단문 건당 약 18원 수준, 확인 필요)** 를 기본으로 하고, 카카오 전화번호 동의항목은 비즈앱·비즈니스 정보 심사·개인정보 항목 검수를 통과하면 병행한다.")
setf(R,"REQ-P07",3,"원천징수·지급명세서용으로 **내 정보에서 등록**하고 첫 지원 전 필수. 개인정보보호법 제24조의2 제3항에 따라 **회원가입 단계에서는 요구하지 않는다.** 암호화·버전 이력·중복 가입 탐지 해시. 법무 확인 후 유지.")
setf(R,"REQ-PC07",3,"해당 업체 구성원이면 누구나(STAFF 포함) 출근·퇴근 세션을 열고 스캔·수기 보정할 수 있다. 세션이 열려야 출연자 앱에 본인 전용 QR 이 나타나고(수초마다 회전) 반장 앱이 스캔해 기록한다(대리 출석 방지). 수기 보정은 감사 대상.")
setf(R,"REQ-PC15",3,"지원서(서명·사진·스냅샷)·출결·정산·메시지 이력을 보존기간 동안 보관하고 분쟁 시 보류한다. 근로 서류는 근로기준법 제42조 3년(근로계약서=근로관계 종료일부터, 임금대장=마지막 기재일부터) 이상, 기본 5년.")
setf(R,"REQ-C01",3,"사업자등록증을 올려 업체를 등록하고 관리자 승인 후에만 공고를 올릴 수 있다. (직업정보제공 사업자의 구인자 신원 확인 의무에 대응: 구인자 불명 광고 게재 금지)")
setf(R,"REQ-N02",3,"안드로이드/웹 푸시. **iPhone 웹은 홈 화면에 추가한 PWA 에서만 웹푸시가 가능**(iOS 16.4+, 사용자 동작 후 권한 요청, 도중 중단 사례 보고)하므로 푸시가 불가능한 사용자를 식별하고 REQ-N08 폴백을 적용한다.")
add_after(R,"REQ-N07",["REQ-N08","알림","중요(CRITICAL) 알림 폴백","합격 통보·일정 취소/연기·충돌 경고·참석 확인 등 중요 알림은 푸시 성공/열람이 없으면 일정 시간(기본 10분) 후 이메일 → (옵트인 시) 문자/알림톡으로 재전달하고 앱 내 배너로 표시한다.","P1","U","푸시 불가 기기 사용자에게 중요 알림이 다른 채널로 도달"])
setf(R,"REQ-NFR05",3,"초기 월 운영비 목표 3만원 내외(스토어 연회비 제외): Lightsail 서울 2GB(월 US$12 확인), 도메인, 문자 OTP 가입당 약 18원, 나머지 무료 티어. 과금 경보.")
setf(R,"REQ-NFR06",3,"서비스명 **보조출연 헬프**(로고 공란). 토스·당근 감성(파스텔 톤, 큰 타이포·여백·바텀시트·카드·이모지 버튼), 라이트/다크. 웹은 shadcn 기반.")
setf(R,"REQ-NFR12",3,"Android 앱(Flutter) 우선, iPhone 은 같은 Flutter 코드의 웹(PWA). **Flutter Wasm 빌드는 iOS 브라우저에서 동작하지 않으므로 기본(CanvasKit) 빌드를 사용**하고, 오픈 전 iPhone 실기기 검증 게이트(로드 시간·입력·카메라·서명·QR 표시)를 통과해야 한다. 업체/관리자는 React 웹.")
add_after(R,"REQ-NFR12",["REQ-NFR13","비기능","iPhone 홈 화면 추가 안내·푸시 권한 유도","iPhone Safari 접속 시 홈 화면 추가 방법을 안내하고, 설치 후 사용자 동작(버튼)으로 알림 권한을 요청한다. 푸시 상태 점검 화면 제공.","P1","U","설치/권한 상태가 서버에 보고되고 미설치자는 안내를 받음"])
setf(R,"REQ-MON01",3,"요금제/기능(plan_features)으로 유료 기능을 켜고 끌 수 있는 구조를 선반영. 구독은 업체만, **월 정액(원 단위, 금액 추후)**: 지원자 추가 정보 확인, 인재 검색, 긴급 공고·상단 노출.")

# ── 화면
add_after(S,"SCR-AU07",["SCR-AU08","APP","인증/설치안내","홈 화면에 추가 안내 (iPhone)","P1","iPhone Safari 에서 접속 시 [공유 → 홈 화면에 추가] 단계별 안내, 설치 후 알림 권한 요청 버튼"])
add_after(S,"SCR-H13",["SCR-H34","APP","MY/설정","푸시 상태 점검","P1","푸시 가능 여부(설치/권한/토큰), 해결 방법, 중요 알림 문자 수신 옵트인"])
# ── API
setf(A,"API-ME12",5,"{platform,token,isPwaInstalled,permission:GRANTED|DENIED|DEFAULT,userAgent} (등록·갱신)")
add_after(A,"API-ME13",["API-ME21","GET","/me/push-status","푸시 가능 여부 진단","USER","→ {canPush, reasons[NOT_INSTALLED,PERMISSION_DENIED,NO_TOKEN,UNSUPPORTED], fallbackChannels[]}","device_tokens,notification_settings",""])
setf(A,"API-MS05",5,"?date=2027-01-05 → {year,hourlyKrw} (근무 시작일 기준 연도)")
setf(A,"API-JB01",7,"422 일정 1시간~21일 위반 · 최저시급 연도 미등록 · BELOW_MIN_WAGE(직접 입력 시급이 법정 최저 미만)")
# ── 프로그램
P.append(["PGM-J20","배치","CriticalNotificationFallback","notification","CRITICAL 알림이 N분 내 푸시 성공/열람이 없으면 폴백 채널(이메일→옵트인 시 문자/알림톡)로 재전달","2분 주기"])
# ── 기능 (id, 이름, 설명, reqs[], who, pri, scr[], api[], pgm[])
setf(Fn,"F-JB09",2,"MIN_WAGE 선택 시 근무 시작일 연도의 최저시급 조회·스냅샷(연도 경계는 날짜별), 미등록 연도 안내")
setf(Fn,"F-OP06",4,"반장·업체 구성원 전원·출연자")
add_after(Fn,"F-JB09",["F-JB11","법정 최저시급 검증","직접 입력 시급이 근무 시작일 연도 최저시급 미만이면 저장·게시 거부","REQ-J11".split(),"업체","P1",["SCR-C03"],["API-JB01","API-MS05"],["PGM-B05","PGM-B14"]])
add_after(Fn,"F-NT07",["F-NT08","중요 알림 폴백","CRITICAL 알림 미도달 시 이메일→옵트인 문자/알림톡 재전달, 앱 내 배너","REQ-N08 REQ-N02".split(),"시스템","P1",[],[],["PGM-J20","PGM-B12"]])
add_after(Fn,"F-NT08",["F-NT09","iPhone 설치 안내·푸시 상태 점검","홈 화면 추가 안내, 알림 권한 요청, 푸시 상태 진단·보고","REQ-NFR13 REQ-N02".split(),"출연자","P1",["SCR-AU08","SCR-H34"],["API-ME12","API-ME21"],["PGM-M01","PGM-M05"]])
setf(Fn,"F-PR04",2,"회원가입 이후 '내 정보'에서 등록(가입 필수 아님, 법 제24조의2 ③). 동의→입력→형식·생년월일·성별 일치 검증→암호화+HMAC 해시 저장, 버전 이력, 중복 가입 탐지")

def dump(name, var, rows, header):
    with open(os.path.join(SPEC, name), "w", encoding="utf-8") as f:
        f.write(header + "\n" + var + " = [\n")
        for r in rows: f.write("  " + repr(tuple(r)) + ",\n")
        f.write("]\n")
dump("reqs.py","REQS",R,"# 요구사항: (ID, 그룹, 제목, 설명, 우선순위, 출처, 인수기준)  출처: U=사용자 요구 R=검토 반영 L=법·보안 X=확장 대비  (v1.2)")
dump("screens.py","SCREENS",S,"# 화면: (ID, 플랫폼, 메뉴 경로, 화면명, 우선순위, 설명)  APP=출연자 앱(Flutter, 웹/PWA 동일 코드) FLD=현장 모드 WEB=업체 웹 ADM=관리자 웹  (v1.2)")
dump("apis.py","APIS",A,"# API: (ID, METHOD, PATH(/api/v1), 이름, 권한, 요청/응답 요약, 테이블, 주요 오류)  (v1.2)")
dump("programs.py","PROGRAMS",P,"# 프로그램: (ID, 구분, 이름, 위치/패키지, 책임, 실행/주기)  (v1.2)")
dump("funcs.py","FUNCS",[tuple(x) for x in Fn],"# 기능: (ID, 이름, 설명, 요구사항[], 수행자, 우선순위, 화면[], API[], 프로그램[])  (v1.2)")
print("REQ",len(R),"FUNC",len(Fn),"SCR",len(S),"API",len(A),"PGM",len(P))
