## 부록 A. 업체 내부 직책별 권한 (company_members.member_role)

| 권한 | OWNER 대표 | MANAGER 캐스팅팀장 | FIELD_LEADER 현장반장 | STAFF |
|---|:-:|:-:|:-:|:-:|
| 공고 작성·수정·복사 | ○ | ○ | 담당 일자만 수정 | ○(작성) |
| 공고 마감·취소 | ○ | ○ | 담당 일자 마감만 | ✕ |
| 지원자 열람·합격/불합격 처리 | ○ | ○ | 담당 일자만 | 열람만 |
| 지원자 역대 이력 열람 | ○ | ○ | ○ | ○ |
| 타 공고 지원목록 열람 | 플랜/스위치 | 플랜/스위치 | 플랜/스위치 | ✕ |
| 인원표 열람 | ○ | ○ | 담당 일자 | 열람 |
| 인원 정보표(이름·번호·위치) | ○ | ○ | 담당 일자 | ✕ |
| **계좌번호 전체 열람·인원정보표 엑셀** | ○ | ○ | ✕ (마스킹) | ✕ |
| 단체 메시지 | ○ | ○ | 담당 일자 | ✕ |
| 출석·퇴근·노쇼 기록 | ○ | ○ | 담당 일자 | ✕ |
| 정산 기록·엑셀 | ○ | ○ | ✕ | ✕ |
| 직원 초대·직책 변경 | ○ | ✕ | ✕ | ✕ |
| 차단 목록 관리 | ○ | ○ | ✕ | ✕ |

플랫폼 권한(`permissions`)은 **역할 단위의 상한**이고, 위 표는 **업체 내부 직책에 따른 추가 제한**이다. 둘 다 통과해야 API가 허용된다.

## 부록 B. 데이터 공개·마스킹 규칙 (OP13 단일 정책 클래스가 구현)

| 데이터 | 비로그인 | 출연자 본인 | 업체: 합격 전(APPLIED/SHORTLISTED/WAITLISTED) | 업체: 합격(CONFIRMED) | 비고 |
|---|---|---|---|---|---|
| 이름 | ✕ | ○ | ○ | ○ | 이력 판단에 필요 |
| 전화번호 | ✕ | ○ | `010-****-1234` | **전체** | 합격 시 출연자 화면에도 업체/반장 번호 공개 |
| 출발·탑승 위치 | ✕ | ○ | 시/구 단위 | **전체** | |
| 계좌번호 | ✕ | ○(마스킹 조회) | **비공개** | OWNER/MANAGER 전체, 그 외 앞2·뒤2 마스킹 | `sensitive_purged_at` 이후 NULL |
| 신체 스펙·사진 | ✕ | ○ | ○ | ○ | `is_searchable` 무관(지원자이므로) |
| 역대 합격/출석 이력 | ✕ | ○ | ○(동의한 경우) | ○ | `history_disclosure_consented_at` |
| 타 공고 지원 현황 | ✕ | ○ | 플랜/스위치 시, 타 업체 제목 마스킹 | 동일 | |
| 비공개 현장 작품명·상세 장소 | ✕ | 합격 후 ○ | — | ○ | `conceal_details` |
| 업체/반장 연락처 | 업체 프로필 대표번호만 | 합격 후 `contact_phone`·반장번호 | — | ○ | |

## 부록 C. 지원 상태 전이

| 현재 | → 다음 | 주체 | 조건/부수효과 |
|---|---|---|---|
| (없음) | APPLIED | 출연자 | 요건 통과. 정보표 스냅샷 저장 |
| (없음) | CONFIRMED | 시스템 | 선착순 역할: 정원 내 즉시 확정 |
| APPLIED | SHORTLISTED | 업체 | 보류/1차 통과 |
| APPLIED·SHORTLISTED | CONFIRMED | 업체 | 정원 원자 증가 + 시간범위 기록(겹치면 409). 합격 통보 |
| APPLIED·SHORTLISTED | WAITLISTED | 업체 | **예비합격**(standby 정원 내). 통보됨 |
| WAITLISTED | CONFIRMED | 업체/시스템 | 결원 시 승격(자동은 P2) |
| APPLIED·SHORTLISTED·WAITLISTED | REJECTED | 업체 | 수동 불합격(사유 선택) |
| APPLIED·SHORTLISTED | AUTO_REJECTED | 시스템 | 역할/일자/공고 마감 또는 지원마감·집합시각 도래 |
| WAITLISTED | EXPIRED | 시스템 | 집합시각 경과 시 만료(결원 없음) |
| APPLIED·SHORTLISTED·WAITLISTED | CANCELED_BY_ACTOR | 출연자 | 자유 취소 |
| CONFIRMED | CANCELED_BY_ACTOR | 출연자 | 사유 필수, 성실도 이벤트(늦은 취소 기준 적용), 정원 감소, 업체 알림, 예비 승격 후보 |
| CONFIRMED | CANCELED_BY_COMPANY | 업체 | 사유 필수, 출연자 알림 |
| 활성 전체 | CANCELED_BY_SYSTEM | 시스템 | 일자/공고 취소, 계정 정지 등 |
| CONFIRMED | (출결) | 반장 | 출석 결과는 `attendances.result` 에 별도 기록(상태와 분리) |

> 같은 날 다른 지원이 있어도 **자동 취소하지 않는다**(사용자 정책: 시간이 안 겹치면 복수 확정 허용). 이미 확정된 건과 시간이 겹치는 다른 지원은 업체 화면에 *충돌 배지*가 표시되고 확정 시도는 409 `TIME_CONFLICT` 로 거부된다.

## 부록 D. 지원 가능 조건 판정 순서 (실패 사유를 모두 모아 반환)

1. 로그인·ACTOR 역할·프로필 완성 → `PROFILE_INCOMPLETE`
2. 지원 제한 기간(`apply_blocked_until`) → `APPLY_BLOCKED`
3. 업체 차단 목록 → `BLOCKED_BY_COMPANY`
4. 공고/일자/역할 상태 OPEN, 지원 마감 전 → `CLOSED`
5. 성별 → `GENDER_MISMATCH` / 출생연도 → `BIRTH_YEAR_OUT_OF_RANGE` / 키 → `HEIGHT_OUT_OF_RANGE` / 최소 성실도 → `RELIABILITY_TOO_LOW`
6. 연령: 만 나이 < `min_actor_age` → `UNDER_MIN_AGE`; 만 나이 < `minor_age_limit` 이면 역할 `allow_minor` 여부 → `MINOR_NOT_ALLOWED`, 철야·야간 일정(만 `night_restrict_age` 미만) → `MINOR_NIGHT_BLOCKED`, 검증된 유효 동의서 없음 → `GUARDIAN_CONSENT_REQUIRED`
7. 필수 첨부(의상 사진/문서) → `ATTACHMENT_REQUIRED`
8. 활성 중복 지원 → `ALREADY_APPLIED`
9. 선착순이면 정원 → `FULL`, 확정 시간 겹침 → `TIME_CONFLICT`

## 부록 E. 확정 처리 순서 (SL05 / AP02 공통 서비스)

1. 권한(플랫폼 권한 + 업체 직책 + 담당 일자) → 2. 지원 상태 검증 → 3. 차단 목록 → 4. **정원 원자 증가** `UPDATE job_positions SET confirmed_count=confirmed_count+1 WHERE id=? AND confirmed_count<headcount` (0행이면 `FULL`) → 5. 시간범위 계산 `[시작−버퍼, 종료+버퍼)` (시작=역할 call_time ?? 일자 call_time, 종료=역할 end_time ?? 일자 end_time ?? 시작+기본촬영시간, 철야면 익일) → 6. `applications` 갱신(상태·`confirmed_time_range`·`condition_snapshot`·결정자/시각) — `EXCLUDE` 위반 시 트랜잭션 롤백 후 `TIME_CONFLICT` → 7. 상태 이력·감사로그·아웃박스(합격 알림) 기록 → 8. 커밋.
일괄 처리는 건별 SAVEPOINT 로 부분 성공(HTTP 207)을 허용한다.

## 부록 F. 마감 시 자동 불합격 규칙

- 트리거: ① 업체의 공고/일자/역할 마감 ② 지원 마감시각(`apply_deadline_at`) 경과 ③ 집합시각 경과(5분 배치 `PGM-J05`).
- 대상: `APPLIED`, `SHORTLISTED` → `AUTO_REJECTED`(사유 '모집 마감에 따른 자동 불합격'). **`WAITLISTED`(예비합격)는 업체가 명시적으로 통보한 상태이므로 유지**하고 집합시각 경과 시 `EXPIRED`.
- 각 지원자에게 '마감되어 선발되지 않았습니다' 알림(묶음 발송)을 보내고 `application_status_histories` 에 기록한다.
