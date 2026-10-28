## 도메인 이벤트 (outbox_events.event_type) → 알림

| 이벤트 | 발생 지점 | 수신자 | 채널 | 필수 알림 |
|---|---|---|---|:-:|
| `JOB_PUBLISHED` | 공고 게시 | 저장검색 일치자(P2) | 푸시 | ✕ |
| `APPLICATION_SUBMITTED` | 지원 | 업체 담당자 | 인앱·푸시 | ✕ |
| `APPLICATION_SELECTED` | 합격 통보 | 출연자 | 인앱·푸시 | ○ (확정 응답 기한 포함) |
| `APPLICATION_CONFIRMED` | 출연자 확정 | 업체 담당자·반장 | 인앱·푸시 | ○ |
| `APPLICATION_REJECTED` / `AUTO_REJECTED` | 불합격/마감 | 출연자 | 인앱 | ✕ |
| `SELECTION_EXPIRED` | 응답 기한 경과 | 출연자·업체 | 인앱·푸시 | ○ |
| `APPLICATION_CANCELED_BY_ACTOR` | 확정 후 취소 | 업체 담당자·**스케줄러 전원** | 인앱·푸시 | ○ |
| `APPLICATION_CANCELED_BY_COMPANY` | 업체 취소 | 출연자 | 인앱·푸시 | ○ |
| `CONFLICT_DETECTED` | 겹침을 알고 둘 다 확정 | **양쪽 업체**·출연자 | 인앱·푸시·메시지 | ○ |
| `CONFLICT_REMINDER` | 미확인 충돌 (1시간 간격) | 출연자 | 푸시 | ○ (확인 시 중지) |
| `SCHEDULE_CANCELED` | 일정 취소 | 해당 지원자·합격자 | 인앱·푸시·이메일 | ○ |
| `SCHEDULE_POSTPONED` | 일정 연기 | 이동된 지원자 | 인앱·푸시·이메일 | ○ (확정 요청) |
| `DEPARTURE_CHECK_REQUEST` | 집합 2시간 전 | 확정자 | 푸시·인앱 | ○ |
| `DEPARTURE_PENDING_ALERT` | 집합 30분 전 | 담당 반장·업체 | 푸시 | ○ |
| `REMINDER_DAY_OF` | 집합 전 리마인더 | 확정자 | 푸시 | ○ |
| `ATTENDANCE_SESSION_OPENED` | 출근/퇴근 열기 | 확정자 | 푸시(QR 화면 안내) | ○ |
| `CANCEL_REVIEW_REQUESTED` | 취소 판정 필요 | 스케줄러 | 인앱·푸시 | ○ |
| `PENALTY_APPLIED` | 패널티 부과 | 출연자 | 인앱 | ○ |
| `MESSAGE_RECEIVED` | 업체 메시지/답장 | 상대방 | 인앱·푸시 | ✕ |
| `COMPANY_APPROVED` / `COMPANY_REJECTED` | 관리자 처리 | 업체 OWNER | 인앱·이메일 | ○ |
| `INQUIRY_ANSWERED` | 문의 답변 | 작성자 | 인앱·이메일 | ✕ |

'필수 알림'은 수신 설정과 무관하게 발송(인앱은 항상). iPhone 웹 사용자는 푸시가 없을 수 있어 앱 내 배너와 폴백 채널(P3)을 함께 설계한다.
