# CLAUDE.md — 보조출연 매칭 플랫폼 (프로젝트 기억 · 작업 지침 · v1.0)

> 새 세션이 시작될 때마다 자동으로 읽힌다. **설계 단일 진실 원천**: `db/schema.sql`(DB), `tools/spec/*.py`(요구·기능·화면·API·프로그램 명세) → `docs/` 생성. 충돌하면 이 파일 → `docs/98_v1.0_비교분석.md` → 나머지 순으로 우선.
> 사용자는 한국어로 소통한다. 답변·문서·설명은 한국어, 코드 식별자는 영어. 사용자는 보출 업체를 직접 운영한다(운영자=업체 겸업).
> 마지막 갱신: 2026-10-28 · 상태: **설계 v1.0 완료, 코드 구현 전**

## 1. 한 줄 정의
영화·드라마 **보조출연 당일/단기 인력 매칭 플랫폼(웹+앱)**. 업체가 날짜·역할별 공고를 올리면 출연자가 **달력/검색으로 찾아 첨부와 함께 지원**, 업체가 합격 통보(**통보 없이 마감하면 자동 불합격**), 합격 후 **번호 상호 공개·전체 합격자 메시지·인원표/인원 정보표(계좌·이름·번호·위치)** 관리. **현재 수수료 0원·전 기능 무료, 중개만.** 추후 구독 등 수익화는 구조만 선반영.

## 2. 사용자 확정 정책 (변경 금지, 변경 시 사용자 확인)
1. 수수료 0원·전 기능 무료(`subscription_enforcement=false`), 추후 구독 확장.
2. **시간이 안 겹치면 같은 날 복수 지원·복수 확정 가능**, 확정은 업체가 한다. 겹치는 확정은 DB가 거부(EXCLUDE).
3. 업체는 지원자의 **역대 합격 목록**을 본다(본인 동의 전제). **타 공고 지원 목록**은 추후 구독 기능(지금은 `free_open_application_history=true`).
4. **미성년자는 법정대리인 동의서(검수 완료)가 있을 때만** 지원 가능.
5. 통보 없이 마감 → `APPLIED/SHORTLISTED` 는 `AUTO_REJECTED`. **예비합격(`WAITLISTED`)은 유지**(집합 시각 후 `EXPIRED`).
6. 합격 후 업체↔출연자 번호 상호 공개, 업체는 합격자 전체에 알림/메시지, 공고별 인원표·인원 정보표 관리.
7. 운영자와 자사 업체 계정은 분리하고 자사도 동일 규칙·감사(이해충돌 방지).

## 3. 산출물 지도 (`docs/` — 12종 + 보조)
`00_README`(색인·변경이력) · `01 요구사항정의서` · `02 프로세스정의서` · `03 기능정의서`(+권한/마스킹/상태전이/확정순서 부록) · `04 메뉴구성도` · `05 프로그램정의서` · `06 요구사항추적매트릭스` · `07 ERD·테이블정의서` · `08 API명세서` · `09 화면설계서` · `10 시스템아키텍처` · `11 운영유지보수지침서` · `12 개발환경구축가이드` · `13 보안·개인정보·법무` · `14 수익화·확장설계` · `15 UI/UX 디자인시스템` · `98 비교분석` · `99 검토기록` · `archive/`(폐기된 초안)
- **01,03~09 는 자동 생성물** — 직접 수정 금지. `tools/spec/*.py` 또는 `tools/spec/*.md`(손글씨 조각) 또는 `db/schema.sql` 을 고치고 `python3 tools/build_docs.py`(필요한 환경변수는 12번 문서 §4) 로 재생성. 생성기는 요구↔기능↔화면↔API↔프로그램 연결 누락·중복·우선순위 불일치·문서 속 ID/식별자 오류를 검사해 실패 시 exit 1.
- 02,10~15,98,99 는 손글씨. 변경하면 `99_검토기록` 과 `00_README` 변경 이력을 갱신.
- 검증 도구: `tools/test_schema.sql`(제약 시험), `tools/concurrency_test.sh`(pgbench 동시성), `tools/check_mermaid.mjs`(다이어그램 파싱).

## 4. 확정 기술 결정
| 영역 | 결정 |
|---|---|
| 구조 | **모듈러 모놀리스** 1개 Spring Boot. MSA·K8s ✕ |
| 백엔드 | Java 25 LTS(불가 시 21) + Spring Boot **최신 안정 GA(착수 시 지원 정책 확인, 버전 번호 박제 금지)**, Security+JJWT, Data JPA+QueryDSL(동적 검색), Flyway, ShedLock, Apache POI |
| DB | **PostgreSQL 17/18 (≥16)**: 부분 유니크·**EXCLUDE(btree_gist)**·JSONB+GIN·pg_trgm·SKIP LOCKED |
| 비동기 | 트랜잭션 **아웃박스 + 워커**. Kafka/RabbitMQ/Hadoop/Elasticsearch ✕ (도입 조건은 10번 §3) |
| 캐시 | MVP 없음, 필요 시 **Valkey**(Redis ✕ 라이선스) |
| 파일 | **Cloudflare R2** + 서명 URL 직접 업로드, 클라이언트 WebP 압축. 비공개 기본, `public_url` 저장 금지 |
| 출연자+현장 앱 | **Flutter** 최신 stable, Riverpod·GoRouter·Dio·table_calendar·firebase_messaging. 같은 앱의 **현장(반장) 모드**(출석·미응답자·단체메시지·명단) |
| 업체/관리자 웹 | React 19+TS+**Vite** SPA, TanStack Query/Table, Zustand, Tailwind+**shadcn/ui**(MUI Core 대체 가능, MUI X Pro/Premium 금지), FullCalendar MIT 코어만. Cloudflare Pages |
| 디자인 | 출연자 앱 **토스·당근 감성**(브랜드 자산 복제 금지), 토큰/컴포넌트는 15번 문서(명도 대비 검증 완료) |
| 인프라 | **한국 리전** VPS 1대 + Docker Compose + Cloudflare. Oracle Free 는 개발/스테이징만. 월 2~4만원(스토어 연회비 별도) |
| 알림 | FCM → (미응답자 폴백) 카카오 알림톡 → 이메일. 광고성 21~08시 금지 |
| 수익화 | `subscription_plans`/`plan_features`/`company_subscriptions`/`usage_counters` 선반영, 게이트는 `EntitlementService` 한 곳, 결제 DDL 초안은 `db/future/` |

## 5. DB 규칙 (`db/schema.sql` v1.0: 테이블 53, 뷰 2)
1. 인증(users) / 권한(roles·permissions·user_roles) / 프로필(actor_profiles, companies) 분리. **`users.role` 단일 컬럼 ✕.** 업체 직원은 `company_members(OWNER/MANAGER/FIELD_LEADER/STAFF)`, 한 사람이 여러 업체 소속 가능.
2. 파일: `files` + 다형성 `file_attachments(entity_type, entity_id, usage)`. 연결 쓰기는 **AttachmentService 단일 경로**. 용도 추가는 CHECK 확장.
3. 게시판: `boards/board_permissions/posts/comments`, 공고와 완전 분리. 공개 블랙리스트 ✕(비공개 차단 + 신고).
4. 상태값은 VARCHAR+CHECK. 시간은 TIMESTAMPTZ(촬영일·집합시간만 DATE/TIME KST). 업무 테이블 hard delete 금지(FK RESTRICT), 순수 하위 상세만 CASCADE.
5. **DB가 최후 방어**: 정원 CHECK, 활성 중복 지원 부분 유니크(취소 후 재지원 허용), **같은 사람 시간 겹침 확정 EXCLUDE**, CONFIRMED 는 시간범위 필수.
6. 선발 상태(`applications.status`) / 참석확인(`departure_confirmed_at`) / 출결(`attendances.result`)은 **직교 축** — 섞지 말 것.
7. 확정 시 `condition_snapshot`, `confirmed_time_range` 기록. 지원서의 이름·번호·출발지·계좌는 **지원 시점 스냅샷**(인원 정보표 원천), 계좌는 AES-256-GCM + 정산 후 90일 파기(J07).
8. 뷰 `v_schedule_headcount_summary`, `v_schedule_confirmed_roster` 는 **백엔드 전용**(마스킹·복호화는 `F-OP13` 정책 클래스, 직접 SQL/BI 노출 금지).
9. 스키마를 바꾸면: `db/schema.sql` + Flyway 마이그레이션 + 문서 재생성 + `tools/test_schema.sql` 보강을 한 PR 로, PostgreSQL 에서 실행 검증.

## 6. 도메인 핵심 규칙 (상세: 03 부록, 02 프로세스)
- 지원 가능 판정 순서·사유 코드: 03 부록 D (실패 사유를 모아 반환). 확정 처리 순서: 부록 E (정원 원자 증가 → 시간범위 → EXCLUDE → 이력/아웃박스/감사). 일괄 처리는 207 부분 성공.
- 마스킹: 합격 전 전화 `010-****-1234`·계좌 비공개·출발지 시/구, 합격 후 공개(계좌 전체는 OWNER/MANAGER). 업체 직책 권한은 03 부록 A.
- 미성년 임계값은 설정: `min_actor_age=15`, `minor_age_limit=19`(동의서), `night_restrict_age=18`(철야 차단). 동의서는 `guardian_consents`(검수·유효기간).
- 노쇼: 전날 18시 참석확인 푸시 → 22시 미응답자 반장 알림 → 당일 출석 → 노쇼 시 `reliability_events` 차감·`apply_blocked_until`. 성실도 원장이 원천, 점수는 캐시(J10 대조).
- 일자 취소/연기는 일자 단위 일괄 알림. v1 은 연기 시 합격자 자동 이전 없음(P3).
- 정산은 **기록·엑셀까지**(주민번호 제외). 송금·에스크로 ✕.

## 7. 개인정보·보안 금지/필수
- ⛔ 주민등록번호·신분증 사진 비수집(`actor_tax_identities` 는 기능 OFF). ⛔ 로그에 전화·계좌·토큰. ⛔ 민감 열람/엑셀 다운로드 감사로그 누락.
- 필수: 비밀번호 Argon2/BCrypt, refresh 해시·회전·재사용 감지, 모든 `/{id}` IDOR 검사(타 업체 접근 시 404/403 테스트), 업로드 검증·재인코딩, 약관 버전·제3자 제공 동의 분리, 국외이전 고지(FCM·R2), 관리자 2단계 인증.
- 법무는 **전문가 확인 전 단정 금지**: 직업정보제공/직업소개 신고, 연소자(근로기준법), 일용근로 신고(업체 책임), 광고성 정보 야간 제한, 위치정보(13번 §5).

## 8. 코드 컨벤션
- 패키지 `{base}.{module}.{api|application|domain|infra}`; 모듈은 05번 문서의 PGM-B*. 모듈 간 쓰기는 소유 모듈 서비스/도메인 이벤트로만. 엔티티 직접 반환 ✕(DTO), N+1 방지, 커서 페이지네이션, 서버 UTC·표시 KST, 금액 원 정수.
- 프런트는 OpenAPI 생성 클라이언트만 사용(수기 DTO ✕). 앱 UI 는 `core/ui` 컴포넌트 조립(색/크기 하드코딩 ✕).
- 테스트 필수: 정원 동시성, 시간 겹침 409, 자동불합격(예비 유지), 취소 후 재지원, 타 업체 접근 차단, 아웃박스 원자성(Testcontainers PostgreSQL).
- 커밋은 변경 이유 중심. 브랜치/푸시/PR 은 세션 지시를 따른다(PR 은 요청 시에만).

## 9. 외부 사실은 반드시 재확인 (이 설계는 2026-06 지식 기준)
무료 한도·요금(R2, Resend/메일, 휴대폰 인증, 알림톡, VPS)·최신 버전(Java/Boot/Flutter/PG)·법령 조문은 착수 시 공식 출처로 확인 후 문서를 갱신한다. 미결정 항목: 휴대폰 인증 수단, 메일 발송사, VPS 사업자, Boot 메이저 버전, 법무 12항목(10번 §9, 13번 §5).

## 10. 다음 작업 순서
1. (사용자) 미결정 항목 확정 → 필요 시 `tools/spec`·스키마 갱신 후 재생성.
2. 저장소 뼈대: `backend/`(Gradle), `web/`(Vite), `app/`(Flutter), `db/migration/`(Flyway V1~V3 = schema.sql 단계 분할), `infra/`(개발 compose는 있음, 배포 compose·nginx 추가), CI(테스트·라이선스 스캔·문서 검증).
3. 백엔드 수직 슬라이스: auth → company 승인 → job(공고·일자·역할) → 달력/검색 → application(원자 확정·EXCLUDE→409) → outbox/알림 → attendance → 단체 메시지/인원 정보표.
4. 업체 웹(공고 마법사·지원자 그리드·정보표) → Flutter 앱(홈 달력·지원·푸시·참석확인·현장 모드). 디자인 토큰 먼저(15번).
5. 스테이징(한국 리전 VPS+R2+Cloudflare), 백업·모니터링·복구 리허설(11번), 법무 확인 후 오픈.
