## 0. 설계 원칙 · 명명 규칙
- 테이블: 복수형 snake_case, PK는 `id BIGINT IDENTITY`, 외부 노출용은 `public_id UUID`(파일).
- 상태값: `VARCHAR + CHECK`(DB enum 미사용). 시간: `TIMESTAMPTZ`, 촬영일·집합시간만 `DATE`/`TIME`(KST 업무값).
- **삭제**: 업무 테이블은 hard delete 금지(FK 는 기본 RESTRICT). 사용자·공고·게시글 등은 `deleted_at` soft delete, 순수 하위 상세(`settlement_items`, `plan_features` 등)만 `ON DELETE CASCADE`.
- **캐시 컬럼**(`applied_count`, `confirmed_count`, `standby_count`, `reliability_score`, `*_count`)은 원천(applications/원장)과 `PGM-J10` 배치로 대조한다.
- **민감 컬럼**(`account_number_enc`, `rrn_enc`)은 앱 계층 AES-256-GCM, `key_version` 으로 회전. 응답 시 마스킹은 `F-OP13` 정책 클래스가 담당.
- **첨부**: 모든 파일은 `files`(메타) + `file_attachments`(엔티티·용도 연결). 연결 쓰기는 `AttachmentService` 단일 경로, 정합성은 `PGM-J13` 점검.
- **DB가 지키는 업무 규칙**(애플리케이션 버그가 있어도 깨지지 않게):
  - 정원 초과 금지: `job_positions` CHECK
  - 활성 중복 지원 금지·취소 후 재지원 허용: `uq_applications_active`
  - **같은 사람의 시간 겹침 확정 금지**: `applications` `ex_applications_no_overlap` (EXCLUDE, `btree_gist`)
  - 확정에는 시간범위 필수: `ck_confirmed_has_range`
  - 업체 단일 활성 구독: `uq_company_subscription_active`

