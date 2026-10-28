## 도메인 이벤트 (outbox_events.event_type) → 알림

| 이벤트 | 발생 지점 | 수신자 | 채널 | 필수 알림 |
|---|---|---|---|:-:|
| `JOB_PUBLISHED` | 공고 게시 | 저장검색 일치자(P2), 긴급이면 지역 구독자 | 푸시 | ✕(광고성 시간 제한) |
| `APPLICATION_SUBMITTED` | 지원 | 업체 담당자 | 인앱·푸시 | ✕ |
| `APPLICATION_CONFIRMED` | 합격 | 출연자 | 인앱·푸시·(이메일) | ○ |
| `APPLICATION_STANDBY` | 예비합격 | 출연자 | 인앱·푸시 | ○ |
| `APPLICATION_REJECTED` | 불합격 | 출연자 | 인앱 | ✕ |
| `APPLICATION_AUTO_REJECTED` | 마감 자동 불합격 | 출연자 | 인앱(묶음) | ✕ |
| `WAITLIST_PROMOTED` | 예비→합격 | 출연자 | 인앱·푸시 | ○ |
| `APPLICATION_CANCELED_BY_ACTOR` | 합격 후 취소 | 업체 담당자·반장 | 인앱·푸시 | ○ |
| `APPLICATION_CANCELED_BY_COMPANY` | 업체 취소 | 출연자 | 인앱·푸시 | ○ |
| `SCHEDULE_CHANGED` | 일자 취소/연기 | 해당 지원자·합격자 | 인앱·푸시·이메일 | ○ |
| `DEPARTURE_CHECK_REQUEST` | 전날 18시 배치 | 합격자 | 푸시·인앱 | ○ |
| `DEPARTURE_PENDING_ALERT` | 전날 22시 배치 | 담당 반장·업체 | 푸시 | ○ |
| `REMINDER_DAY_OF` | 집합 2시간 전 | 합격자 | 푸시 | ○ |
| `BROADCAST_SENT` | 단체 메시지 | 대상 합격자 | 인앱·푸시(+선택 채널) | ○ |
| `COMPANY_APPROVED` / `COMPANY_REJECTED` | 관리자 처리 | 업체 OWNER | 인앱·이메일 | ○ |
| `GUARDIAN_CONSENT_VERIFIED` / `GUARDIAN_CONSENT_REJECTED` | 운영자 검수 | 출연자 | 인앱·푸시 | ○ |
| `INQUIRY_ANSWERED` | 문의 답변 | 작성자 | 인앱·이메일 | ✕ |

'필수 알림'은 사용자의 수신 설정과 무관하게 발송한다(인앱은 항상).
