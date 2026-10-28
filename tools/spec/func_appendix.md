## 부록 A. 업체 내부 직책별 권한 (company_members.member_role)

| 권한 | OWNER 대표 | MANAGER 팀장 | SCHEDULER 스케줄러 | FIELD_LEADER 현장반장 | STAFF |
|---|:-:|:-:|:-:|:-:|:-:|
| 공고 작성·수정·복사 | ○ | ○ | ○ | 담당 일정만 수정 | ○(작성) |
| 공고 마감·취소·연기 | ○ | ○ | ○ | 담당 일정 마감만 | ✕ |
| 지원자 열람(휴대폰 포함)·합격 통보/불합격 | ○ | ○ | ○ | 담당 일정만 | 열람만 |
| 지원자 역대 이력 열람 | ○ | ○ | ○ | ○ | ○ |
| 타 공고 지원목록 열람 | 플랜/스위치 | 플랜/스위치 | 플랜/스위치 | 플랜/스위치 | ✕ |
| 마감 후 추가 합격·개별 메시지 | ○ | ○ | ○ | 담당 일정 | ✕ |
| 인원표 열람 | ○ | ○ | ○ | 담당 일정 | 열람 |
| 인원 정보표(집주소·집합지) | ○ | ○ | ○ | 담당 일정 | ✕ |
| **계좌 전체·주민번호 열람/엑셀** | ○ | ○ | ✕ | ✕ | ✕ |
| 단체 메시지 | ○ | ○ | ○ | 담당 일정 | ✕ |
| 출근/퇴근 세션 열기·스캔·수기 보정 | ○ | ○ | ○ | 담당 일정 | ✕ |
| **취소 패널티 판정 투표** | ✕ | ✕ | **○ (1~4명)** | ✕ | ✕ |
| 정산 기록·은행/원천징수 엑셀 | ○ | ○ | ✕ | ✕ | ✕ |
| 직원 초대·직책 변경 | ○ | ✕ | ✕ | ✕ | ✕ |
| 차단 목록 관리 | ○ | ○ | ✕ | ✕ | ✕ |

플랫폼 권한(`permissions`)은 **역할 단위 상한**이고 위 표는 **업체 내부 직책별 추가 제한**이다. 둘 다 통과해야 허용. 주민번호 단건 열람(`API-OP26`)과 엑셀(주민번호 포함)은 사유 입력·감사로그 대상.

## 부록 B. 데이터 공개·마스킹 규칙 (F-OP15 단일 정책 클래스)

| 데이터 | 비로그인 | 출연자 본인 | 업체: 지원(APPLIED) | 업체: 합격 통보 이후(SELECTED/CONFIRMED) |
|---|---|---|---|---|
| 이름·프로필 사진·현재 사진·신체 스펙 | ✕ | ○ | ○ | ○ |
| **휴대폰 번호** | ✕ | ○ | **○ (지원 단계부터)** | ○ |
| 집주소 | ✕ | ○ | 시/구 단위 | 전체 (OWNER/MANAGER/SCHEDULER/담당 반장) |
| 집합지(탑승 희망지) | ✕ | ○ | ○ | ○ |
| 계좌번호 | ✕ | ○(마스킹 조회) | 비공개 | OWNER/MANAGER 전체, 그 외 비공개 |
| 주민등록번호 | ✕ | 마스킹 | 비공개 | 화면은 `900101-1******` 고정, 전체는 `API-OP26`/암호화 엑셀 (OWNER/MANAGER, 사유·감사) |
| 역대 합격/출석 이력·노쇼/패널티 **횟수** | ✕ | ○ | ○(동의한 경우) | ○ |
| 타 공고 지원 현황 | ✕ | ○ | 플랜/스위치 시, 타 업체 제목 마스킹 | 동일 |
| 비공개 현장 실제 작품명·상세 장소 | ✕ | 합격 후 ○ | — | ○ |
| 업체/반장 연락처 | 업체 대표번호만 | 합격 통보 이후 ○ | — | ○ |
| 전자서명 이미지·지원서 사본 | ✕ | ○ | 담당 업체 ○(감사) | ○ |

## 부록 C. 지원 상태 전이 (예비합격·선착순 없음)

| 현재 | → 다음 | 주체 | 조건/부수효과 |
|---|---|---|---|
| (없음) | APPLIED | 출연자 | 자격 통과, 현재 사진·서명 포함, 정보표 스냅샷 |
| APPLIED | SELECTED | 업체 | **합격 통보**. `selected_count+1`(정원 한도), 예약 구간 기록, 응답 기한 설정, 겹침이면 업체에 경고 배지 |
| APPLIED | REJECTED | 업체 | 불합격(사유 없음) |
| APPLIED | AUTO_REJECTED | 시스템 | 마감(업체 마감·지원 마감·집합 시각 경과)까지 미통보 |
| AUTO_REJECTED·REJECTED | SELECTED | 업체 | **마감 후 추가 합격**: 정원 여유 + ±1일 가용 확인(권장) 후 재선발 |
| SELECTED | CONFIRMED | 출연자 | **'확정'** 클릭. `selected−1, confirmed+1`. 겹침이 있으면 경고 시트→명시적 진행 시 `conflict_override`, 충돌 추적·양쪽 업체 알림·반복 경고 |
| SELECTED | CANCELED_BY_ACTOR | 출연자 | 거절/취소(패널티 없음), `selected−1` |
| SELECTED | EXPIRED | 시스템 | 응답 기한 경과, `selected−1`, 업체 알림 |
| SELECTED·CONFIRMED | CANCELED_BY_COMPANY | 업체 | 정원 해제, 출연자 알림, `penalty_exempt` |
| CONFIRMED | CANCELED_BY_ACTOR | 출연자 | 사유 필수 → **스케줄러 판정 대기**(`cancellation_reviews`) + 업체 알림 |
| SELECTED·CONFIRMED | CANCELED_BY_SYSTEM | 시스템 | 일정 취소·계정 정지, **일정 연기 시 원본 지원** |
| (연기) | SELECTED(신규 지원 행) | 시스템 | 새 일정에 `moved_from_application_id` 로 생성, 출연자 확정 대기 |
| CONFIRMED | (출결) | 반장/QR | 출결은 `attendances` 에 날짜별로 별도 기록 |

## 부록 D. 지원 가능 조건 판정 순서 (실패 사유 모두 반환)

1. 로그인·ACTOR 역할·프로필 완성 → `PROFILE_INCOMPLETE`; 주민번호 미등록(`require_rrn_before_apply`) → `RRN_REQUIRED`
2. 업체 차단 목록 → `BLOCKED_BY_COMPANY`
3. 공고/일정/역할 OPEN·지원 마감 전 → `CLOSED`
4. 연령: 만 나이 < `minor_age_limit`(19) 이고 `minor_apply_enabled=false` → `MINOR_NOT_ALLOWED`; 만 나이 < `min_actor_age` → `UNDER_MIN_AGE`
5. **필수 요건**: 성별 `GENDER_MISMATCH` / 출생연도 `BIRTH_YEAR_OUT_OF_RANGE` / 키 `HEIGHT_OUT_OF_RANGE` / 몸무게 `WEIGHT_OUT_OF_RANGE` / 머리 길이 `HAIR_LENGTH_MISMATCH` / 문신(없는 사람만) `TATTOO_NOT_ALLOWED`
6. 현재 사진 0장 → `PHOTO_REQUIRED`; 서명 없음 → `SIGNATURE_REQUIRED`
7. 활성 중복 지원 → `ALREADY_APPLIED`
8. (경고만, 차단 아님) 같은 시간대 다른 지원/합격 → `OVERLAPS_MY_APPLICATION` — **겹쳐도 지원은 가능**

## 부록 E. 합격 통보(SELECT) 처리 순서

1. 권한(플랫폼 권한 + 직책 + 담당 일정) → 2. 지원 상태 검증(APPLIED, 또는 마감 후 재선발 대상) → 3. 차단 목록 → 4. **정원 원자 증가** `UPDATE job_positions SET selected_count=selected_count+1 WHERE id=? AND selected_count+confirmed_count<headcount` (0행이면 `FULL`) → 5. 근무 구간 확정(역할 override ?? 일정) → 예약 구간 `[시작−버퍼, 종료+버퍼)` (`버퍼 = conflict_gap_minutes/2`) → 6. `applications` 갱신(상태·`reserved_range`·`selected_at`·`response_due_at`·`condition_snapshot`) → 7. 같은 사람의 다른 SELECTED/CONFIRMED 와 겹치면 **응답에 경고 포함**(차단 아님) → 8. 이력·감사·아웃박스(합격 알림) → 커밋. 일괄 처리는 건별 SAVEPOINT 로 부분 성공(207).
응답 기한 = `min(selected_at + selected_response_hours, 집합 3시간 전)`.

## 부록 F. 출연자 확정(CONFIRM)과 충돌 처리

1. 상태가 SELECTED 이고 기한 내인지 확인 → 2. `confirmed` 로 전환하려 할 때 **겹치는 CONFIRMED 가 있으면** DB `EXCLUDE` 가 거부 → API 는 `409 TIME_CONFLICT`(충돌 목록 포함) 반환 → 3. 앱이 **경고 시트**("다른 확정과 시간이 겹쳐요")를 보여주고 [취소하고 다른 곳 선택] / [그래도 둘 다 확정] 중 선택 → 4. 후자면 `proceedDespiteConflict=true` 로 재호출 → `conflict_override=true` 로 확정, `schedule_conflicts` 생성, **양쪽 업체**에 알림+메시지, 출연자에게 `requires_ack` 알림 → 5. `ConflictReminder`(PGM-J16)가 확인(ack)·해소(한쪽 취소)까지 `conflict_reminder_interval_minutes`(기본 60분)마다 재알림 → 6. 한쪽을 취소하면 `resolution=CANCELED_A|B`, 확인만 누르면 `ACK_ONLY`(경고 중지, 업체 화면의 충돌 배지는 유지).
> **구현 주의(설계 한계)**: DB `EXCLUDE` 는 `conflict_override=false` 인 확정끼리만 막는다. 이미 override 로 확정된 행과의 겹침은 DB가 못 막으므로, **확정 전 애플리케이션이 해당 출연자의 모든 SELECTED/CONFIRMED(override 포함) 예약 구간과의 겹침을 직접 조회**해 409 를 내야 한다(`tools/test_schema.sql` T11d 가 이 쿼리를 검증). DB 제약은 동시 요청 경쟁의 최후 방어선이다.
> "겹침"의 기준은 예약 구간(양끝 버퍼 포함)이 겹치는 경우다. 간격이 `conflict_gap_minutes` 미만인 이동 불가 일정도 겹침으로 본다.

## 부록 G. 마감 후 추가 합격 (예비 제도 없음)

- 마감(CLOSED) 후에도 업체는 **이전 공고 → 지원자 보기**에서 `AUTO_REJECTED`/`REJECTED` 지원자를 열람할 수 있다.
- 후보 산출(`API-SL08`): 해당 일정의 ±`reserve_candidate_window_days`(1일) 범위에 **SELECTED/CONFIRMED 가 없는** 지원자만 "가용".
- 업체가 가용 지원자에게 메시지/알림(`CLOSED_AVAILABLE` 대상)을 보내고, 응답(답장)을 확인한 뒤 합격 통보(SELECT)한다. 정원에 여유가 없으면 `FULL`.

## 부록 H. 취소 패널티 판정

1. 출연자가 **CONFIRMED 이후 취소** → `cancellation_reviews`(PENDING) 생성, 해당 업체의 **SCHEDULER 전원**에게 알림.
2. 각 스케줄러가 `PENALTY` 또는 `NO_PENALTY` 투표. **한 명이라도 PENALTY** 이면 `actor_penalty_events(kind=CANCEL_PENALTY)` 1건 생성(공고당 1건 유일 인덱스, 중복 투표 무관), 리뷰는 PENALIZED.
3. 전원이 NO_PENALTY 이거나 `cancel_review_timeout_hours` 경과 시 CLEARED.
4. 연기·업체 사정 취소(`penalty_exempt`)는 판정 없이 면제. 노쇼는 별도(출결 `NO_SHOW` → 공고당 1건, 횟수만 증가).

## 부록 I. QR 출퇴근 (대리 출석 방지)

1. 팀장/담당 반장이 앱에서 **[출근 열기]** (`attendance_sessions` CHECKIN, 날짜별) → 2. 출연자 앱 [출근 QR] 화면이 활성화되고 **본인 전용 토큰**이 `qr_rotate_seconds`(30초)마다 갱신(`HMAC(서버키, 세션 nonce | applicationId | 시간 단계)`) → 3. 반장 앱이 QR 을 스캔 → 서버가 세션·토큰·시간 단계·지원 상태(CONFIRMED)를 검증 후 `attendances` 기록(`method=QR`) → 4. [출근 닫기]. 퇴근도 동일(CHECKOUT 세션).
- 세션이 닫혀 있으면 출연자 앱에 QR 이 나타나지 않는다. 스크린샷은 30초 후 무효. 대리로 나온 사람은 **지원자 본인의 휴대폰·로그인**이 없으면 QR 을 만들 수 없다.
- 숙박 등 다일 일정은 **날짜별 세션**. 수기 보정은 `method=MANUAL` + 감사로그.

## 부록 J. 보수·근무시간 계산 규칙

- 시급 = 역할의 `hourly_wage_krw`(최저시급 선택 시 해당 연도 `minimum_wages` 값을 공고 작성 시 스냅샷).
- 근무시간 ≤ `fixed_pay_threshold_hours`(9시간) → **시급 × `min_pay_hours`(8시간)** 고정. 초과 → **시급 × 근무시간**.
- 식대(아침/점심/저녁)·교통·연장·야간 가산 등은 **비고(`pay_note`)에 자유 기재**, 정산 시 `settlement_items` 에 수기 입력. (연장·야간 가산 의무 여부는 노무 확인)
- 다일(숙박) 일정은 **근무일별로** 위 규칙을 적용해 합산한다(숙박 일당 규정은 비고에 기재, 확인 필요).
- 세금: `tax_type`(BUSINESS_3_3 / DAILY_WAGE / NONE) 선택 → 세액 계산 규칙은 **세무사 확인 후 확정**(13번 §5).

## 부록 K. 일정 연기 처리

1. 업체가 [연기] → 새 일정(시작/종료) 입력 → 시스템이 새 `job_schedules`(`postponed_from_schedule_id`) 와 역할 복제(`cloned_from_position_id`) 생성, 원 일정은 `POSTPONED`.
2. 원 일정의 SELECTED/CONFIRMED 지원은 `CANCELED_BY_SYSTEM`(`penalty_exempt`)으로 닫고, 새 일정에 **SELECTED 상태의 새 지원 행**(`moved_from_application_id`)을 만든다. **새 일정의 인원은 아직 채워지지 않는다.**
3. 출연자에게 "일정이 연기됐어요" 알림 → 출연자가 **[확정]** 하면 CONFIRMED(정원 충족), **[취소]** 하면 패널티 없이 종료.
4. 응답 기한 경과 시 `EXPIRED`, 업체는 마감 후 추가 합격 절차로 보충.
