## 부록. 핵심 API 상세 예시

### A. 달력 집계 — `GET /jobs/calendar?month=2026-11&regionIds=11,41`
```json
{
  "month": "2026-11",
  "days": [
    {"date": "2026-11-10", "postings": 4, "openSlots": 37, "hasUrgent": true},
    {"date": "2026-11-11", "postings": 2, "openSlots": 12, "hasUrgent": false}
  ]
}
```
집계는 `OPEN` 공고·`OPEN` 일자·`OPEN` 역할만 포함하고 `headcount - confirmed_count` 합을 `openSlots` 로 한다. 응답은 30~60초 캐시 가능(개인화 없음).

### B. 지원 폼 사전정보 — `GET /positions/{id}/apply-form`
```json
{
  "eligible": false,
  "reasons": ["GUARDIAN_CONSENT_REQUIRED"],
  "prefill": {"name": "홍길동", "phone": "010-1234-5678", "departureLocation": "서울 사당역 인근",
              "bankName": "국민은행", "accountMasked": "123-****-**89", "accountHolder": "홍길동"},
  "required": {"costumePhoto": true, "document": false, "guardianConsent": true},
  "warnings": [{"code": "OVERLAPS_MY_CONFIRMED", "applicationId": 9812, "range": "2026-11-10T06:00~12:00+09:00"}]
}
```

### C. 지원 — `POST /positions/{id}/applications`
요청 (헤더 `Idempotency-Key`)
```json
{
  "name": "홍길동", "phone": "010-1234-5678",
  "departureLocation": "사당역 1번 출구 셔틀 탑승 희망",
  "bankCode": "004", "accountNumber": "12345678901", "accountHolder": "홍길동",
  "message": "검정 정장 보유", "costumePhotoFileIds": ["b1f6..."]
}
```
응답 `201` `{"applicationId": 9901, "status": "APPLIED"}` — 선착순 역할이면 `"status":"CONFIRMED"`.
오류 `422 ELIGIBILITY_FAILED` `{"errors":[{"code":"ATTACHMENT_REQUIRED"},{"code":"GUARDIAN_CONSENT_REQUIRED"}]}`, `409 ALREADY_APPLIED`.
> `accountNumber` 는 TLS 로 전송되어 서버가 즉시 암호화 저장한다. 기본 계좌를 쓰면 생략 가능(`useDefaultPayout:true`).

### D. 일괄 합격/예비/불합격 — `POST /company/applications/decide`
```json
{"applicationIds": [9901, 9902, 9903], "decision": "CONFIRM"}
```
`207`
```json
{"succeeded": [9901, 9902], "failed": [{"id": 9903, "code": "TIME_CONFLICT",
  "message": "같은 날 06:00~12:00 다른 확정 건과 시간이 겹칩니다"}]}
```

### E. 인원 정보표 — `GET /company/schedules/{id}/roster`
```json
{
  "scheduleId": 301, "shootDate": "2026-11-10",
  "items": [{
    "no": 1, "applicationId": 9901, "position": "정장 형사",
    "name": "홍길동", "phone": "010-1234-5678", "departureLocation": "사당역 1번 출구",
    "gender": "MALE", "birthYear": 1990, "heightCm": 178, "weightKg": 72, "topSize": "100", "bottomSize": "32", "shoeSize": 270,
    "bank": {"name": "국민은행", "account": "12345678901", "holder": "홍길동"},
    "departureConfirmedAt": "2026-11-09T09:12:00Z",
    "attendance": "PENDING", "checkinAt": null, "overtimeMinutes": 0,
    "pay": {"gross": 120000, "tax": 0, "net": 120000, "status": "PENDING"}
  }]
}
```
`bank.account` 는 OWNER/MANAGER 에게만 전체, 그 외 직책은 `12*******01` 형태. 호출은 `audit_logs`(`ROSTER_VIEW`)에 기록된다.

### F. 합격자 단체 메시지 — `POST /company/broadcasts`
```json
{"scheduleId": 301, "positionId": null, "targetFilter": "ALL_CONFIRMED",
 "title": "집합 장소 변경 안내", "body": "사당역 1번 출구 → 2번 출구로 변경되었습니다.", "channels": ["INAPP","PUSH"]}
```
`202` `{"broadcastId": 77, "recipientCount": 38}` — 발송은 아웃박스 워커가 비동기 처리. 조회: `GET /company/broadcasts?scheduleId=301` → `{id,title,recipientCount,readCount,createdAt}`.

### G. 출석 일괄 기록 — `PUT /company/schedules/{id}/attendances`
```json
{"items": [{"applicationId": 9901, "result": "ATTENDED", "checkinAt": "2026-11-10T06:55:00+09:00"},
           {"applicationId": 9902, "result": "NO_SHOW", "note": "연락 두절"}]}
```
`NO_SHOW` 는 성실도 원장에 `-noshow_penalty_points` 를 기록하고 `apply_blocked_until` 을 설정한다(`PGM-B08`).
