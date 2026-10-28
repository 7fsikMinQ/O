## 0. 설계 원칙 · 명명 규칙
- 테이블: 복수형 snake_case, PK는 `id BIGINT IDENTITY`, 외부 노출용은 `public_id UUID`(파일).
- 상태값: `VARCHAR + CHECK`. 시간: `TIMESTAMPTZ`. 달력용 날짜(`start_date`, `end_date`)는 KST 기준 **생성 컬럼**.
- **삭제**: 업무 테이블은 hard delete 금지(FK 는 기본 RESTRICT). 순수 하위 상세만 CASCADE.
- **캐시 컬럼**(`applied_count`, `selected_count`, `confirmed_count`, 프로필 횟수)은 원천과 `PGM-J10` 배치로 대조.
- **민감 컬럼**(`account_number_enc`, `rrn_enc`)은 앱 계층 AES-256-GCM(`key_version`). 주민번호는 `rrn_hmac`(HMAC, 서버 비밀 pepper)로 **동일인 중복 가입 탐지**.
- **지원서 = 증빙**: 정보 스냅샷(JSONB)·전자서명 메타·무결성 해시·주민번호 버전 참조(`tax_identity_id`)를 한 행에 묶고, 서명 이미지·현재 사진은 `file_attachments`.
- **일정**: 한 번의 연속 근무 구간(`start_at`~`end_at`, 1시간~21일(3주)). 자정을 넘기는 심야와 숙박(2박3일·5박6일)도 하나의 일정. 출결은 날짜별(`attendances` 지원×근무일).
- **DB가 지키는 업무 규칙**:
  - 일정 길이 1시간~21일(3주) (`ck_schedule_window`, `ck_position_window`)
  - 정원: 합격 통보 중 + 확정 ≤ 정원 (`ck_position_capacity`)
  - 활성 중복 지원 금지·취소 후 재지원 허용 (`uq_applications_active`)
  - **같은 사람의 확정 구간 겹침 금지**, 단 출연자가 경고를 확인하고 진행한 행(`conflict_override`)만 예외 (`ex_applications_no_overlap`)
  - 합격 통보/확정에는 예약 구간 필수 (`ck_selected_has_range`)
  - 공고당 패널티 1건 (`uq_penalty_per_posting`), 동일 주민번호 활성 1명 (`uq_tax_identity_active_hmac`)
  - 최저시급 선택 시 기준 연도 필수 (`ck_wage_mode`)
  - 출결 지원×날짜 유일, 열린 출퇴근 세션 1개 (`uq_attendance_session_open`)

