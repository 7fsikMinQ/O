## 부록. 핵심 API 상세 예시

### A. 달력 집계 — `GET /jobs/calendar?month=2026-11&regionIds=11,41`
```json
{
  "month": "2026-11",
  "days": [
    {"date": "2026-11-10", "postings": 4, "positions": 9, "hasUrgent": true},
    {"date": "2026-11-11", "postings": 3, "positions": 7, "hasUrgent": false}
  ]
}
```
다일 일정(2박3일 등)은 걸친 모든 날짜에 집계된다(`start_date ≤ 날짜 ≤ end_date`). `OPEN` 공고·일정·역할만 포함.

### B. 지원 폼 사전정보 — `GET /positions/{id}/apply-form`
```json
{
  "eligible": false,
  "reasons": ["WEIGHT_OUT_OF_RANGE", "PHOTO_REQUIRED"],
  "prefill": {"name": "홍길동", "phone": "010-1234-5678", "gender": "MALE", "birthYear": 1990, "heightCm": 178, "weightKg": 72,
              "sizes": {"top": "100", "bottom": "32", "shoe": 270}, "homeAddress": "서울 강남구 …",
              "departureLocation": "사당역 1번 출구", "payout": {"bankName": "국민은행", "accountMasked": "123-****-**89", "holder": "홍길동"},
              "rrnRegistered": true},
  "required": {"minPhotos": 1, "signature": true, "signatureTerms": {"version": "1", "text": "…"}},
  "warnings": [{"code": "OVERLAPS_MY_APPLICATION", "applicationId": 9812, "range": "2026-11-10T06:00~12:00+09:00"}]
}
```
`warnings` 는 경고일 뿐 지원은 가능하다. `reasons` 가 비어 있어야 `eligible=true`.

### C. 지원 — `POST /positions/{id}/applications`  (헤더 `Idempotency-Key`)
```json
{
  "departureLocation": "사당역 1번 출구",
  "payout": {"useDefault": false, "bankCode": "004", "accountNumber": "12345678901", "holder": "홍길동"},
  "message": "검정 정장 보유",
  "photoFileIds": ["b1f6c0de-0000-0000-0000-000000000001"],
  "signatureFileId": "b1f6c0de-0000-0000-0000-000000000002",
  "signedTermsVersion": "1"
}
```
응답 `201` `{"applicationId": 9901, "status": "APPLIED"}`. 서버는 프로필을 **스냅샷**으로 저장하고, 서명 해시(`snapshot_hash`)·IP·기기·약관 버전을 기록한다. 오류 `422 ELIGIBILITY_FAILED` + 사유 코드 목록, `403 RRN_REQUIRED`.

### D. 합격 통보·불합격 일괄 — `POST /company/applications/decide`
```json
{"applicationIds": [9901, 9902, 9903], "decision": "SELECT"}
```
`207`
```json
{"succeeded": [9901, 9902],
 "failed": [{"id": 9903, "code": "FULL", "message": "정원이 모두 찼습니다"}],
 "warnings": [{"id": 9902, "code": "OVERLAPS_OTHER", "message": "같은 시간대 다른 합격 건이 있어요"}]}
```
`REJECT` 는 사유 없이 처리. `SELECT` 는 출연자의 확정을 기다리는 상태이며 정원 중 `selected_count` 가 증가한다.

### E. 출연자 확정 — `POST /applications/{id}/confirm`
충돌이 없으면 `200 {"status":"CONFIRMED"}`. 충돌이 있으면:
```json
{"status": 409, "code": "TIME_CONFLICT",
 "conflicts": [{"applicationId": 9812, "title": "현대극 B(가제)", "range": "2026-11-10T09:00~14:00+09:00"}]}
```
앱이 경고 시트를 보여 주고, 사용자가 "그래도 둘 다 확정"을 고르면 다시 호출한다.
```json
{"proceedDespiteConflict": true}
```
→ `200 {"status":"CONFIRMED","conflictId":15}` 양쪽 업체에 알림/메시지, 출연자에게 확인(ack) 요구 알림(1시간 간격).

### F. 인원 정보표 — `GET /company/schedules/{id}/roster`
```json
{
  "scheduleId": 301, "startAt": "2026-11-10T06:00:00+09:00",
  "items": [{
    "no": 1, "applicationId": 9901, "status": "CONFIRMED", "position": "정장 형사",
    "name": "홍길동", "phone": "010-1234-5678", "gender": "MALE", "birthYear": 1990,
    "heightCm": 178, "weightKg": 72, "sizes": "100/32/270",
    "homeAddress": "서울 강남구 …", "departureLocation": "사당역 1번 출구",
    "bank": {"name": "국민은행", "account": "12345678901", "holder": "홍길동"},
    "rrn": "900101-1******",
    "departureConfirmedAt": "2026-11-10T04:10:00Z", "attendance": "PENDING",
    "pay": {"gross": 82560, "status": "PENDING"}
  }]
}
```
`bank.account` 는 OWNER/MANAGER 에게만 전체, 그 외에는 `12*******01`. 주민번호는 **항상 마스킹**, 전체는 암호화 엑셀(`roster.xlsx?includeRrn=true`) 또는 단건 열람 API(사유·감사). 호출은 `audit_logs` 에 남는다.

### G. 메시지 — `POST /company/broadcasts`
```json
{"scheduleId": 301, "targetFilter": "CLOSED_AVAILABLE",
 "title": "추가 모집 문의", "body": "11/10 06시 촬영 가능하신가요? 가능하시면 답장 주세요.", "channels": ["INAPP","PUSH"]}
```
`202` `{"broadcastId": 77, "recipientCount": 12}`. 수신자마다 스레드가 생기고 출연자는 **답장만** 가능(`POST /me/messages/threads/{id}/reply`). `targetFilter` 는 `ALL_SELECTED | ALL_CONFIRMED | DEPARTURE_UNCONFIRMED | CLOSED_AVAILABLE | CUSTOM(applicationIds)`.

### H. QR 출퇴근
1) 반장: `POST /company/schedules/301/attendance-sessions` `{"kind":"CHECKIN","workDate":"2026-11-10"}` → `201 {"sessionId":55}`
2) 출연자 앱: `GET /me/applications/9901/qr` → `{"token":"…","expiresIn":30}` (세션이 닫혀 있으면 `409 NO_OPEN_SESSION`)
3) 반장 앱: `POST /company/attendance/scan` `{"sessionId":55,"token":"…"}` → `{"applicationId":9901,"name":"홍길동","result":"ATTENDED"}`
4) 반장: `POST /company/attendance-sessions/55/close`

### I. 취소 패널티 투표 — `POST /company/cancellation-reviews/{applicationId}/vote`
```json
{"vote": "PENALTY"}
```
SCHEDULER 만 호출 가능. 한 명이라도 `PENALTY` 면 해당 공고에 대해 패널티 1건이 반영된다(중복 투표는 무시).
