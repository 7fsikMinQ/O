# CLAUDE.md — 보조출연 매칭 플랫폼 (프로젝트 기억 · 작업 지침)

> 이 파일은 새 세션이 시작될 때마다 자동으로 읽힌다. **여기 적힌 결정이 `docs/DESIGN.md` 초안과 충돌하면 이 파일과 `docs/REVIEW_COMPARISON.md`가 우선**한다.
> 사용자는 한국어로 소통한다. 답변·문서·주석 설명은 한국어, 코드 식별자는 영어.
> 마지막 갱신: 2026-10-28 (설계 v0.2 확정, 코드 구현 전 단계)

## 1. 프로젝트 한 줄 정의
영화·드라마 **보조출연 당일/단기 인력 매칭 플랫폼(웹+앱)**. 사람인/잡코리아처럼 공고를 **달력**으로 보고 상세 조회·조건 확인·**첨부파일 포함 지원**을 하되, 도메인은 “당일 인력시장”(노쇼·현장 출석·일당 정산이 핵심).

## 2. 용어 (코드·DB·UI 공통)
- **보출 업체(company)**: 제작사 계약을 받아 보조출연자를 섭외하는 사업자. 내부에 대표·캐스팅팀장·**현장반장**이 여럿.
- **출연자(actor)**: 보조출연 희망 개인.
- **작품(production)**: 드라마/영화/광고/예능. 공용 마스터. 비공개 현장은 작품명·정확한 장소를 **확정자에게만** 공개.
- **공고(job_posting) → 촬영 일자(job_schedule) → 모집 역할(job_position)** 3단. 한 촬영에 역할이 여러 개(클럽 20대 30명 / 정장 형사 5명 / 행인 10명 — 의상·집결지·페이 각각 다름). **달력의 최소 단위 = 촬영 일자.**
- **지원(application) → 확정/예비(대기) → 출석(attendance) → 정산기록(settlement)**.
- 노쇼 = 확정 후 무단 불참. 출발확인 = 전날 “참석 확인” 응답.

## 3. 확정된 기술 결정 (변경하려면 근거를 문서에 남길 것)
| 영역 | 결정 |
|---|---|
| 구조 | **모듈러 모놀리스** 1개 Spring Boot. MSA·K8s ✕ |
| 언어/프레임워크 | Java 25 LTS(불가 시 21) + Spring Boot 최신 안정 GA(버전·지원정책은 착수 시 공식 확인), 가상 스레드, Spring Security + JJWT, Spring Data JPA + QueryDSL(동적 검색), Flyway |
| DB | **PostgreSQL 17/18 (≥16 호환)**. 부분 유니크·JSONB+GIN·pg_trgm·SKIP LOCKED·TIMESTAMPTZ 적극 활용 |
| 비동기 | **트랜잭션 아웃박스(`outbox_events`) + 워커**. **Kafka / RabbitMQ / Hadoop / Elasticsearch 사용 안 함** (도입 조건: 하루 수십만 건 알림, 서비스 분리 등) |
| 캐시 | MVP 없음. 필요 시(OTP, rate limit) **Valkey**(Redis 아님: 라이선스) |
| 파일 | **Cloudflare R2**(S3 호환, AWS SDK v2) + 서명 URL 직접 업로드, 클라이언트 WebP 압축(가로 ≤1200px, ≤300KB). 비공개 파일은 서명 GET URL만 |
| 업체/관리자 웹 | React 19 + TS + **Vite** SPA, TanStack Query/Table, Zustand, Tailwind + shadcn/ui, FullCalendar **MIT 코어만**(프리미엄 플러그인 금지). Cloudflare Pages 정적 배포. 반장용 화면은 **모바일 우선** |
| 출연자 앱 | **Flutter** 최신 stable, Riverpod, Dio, GoRouter, table_calendar, firebase_messaging, flutter_image_compress. (푸시 신뢰도 때문에 앱이 본체. 웹은 보조) |
| API | REST `/api/v1`, springdoc OpenAPI → TS/Dart 클라이언트 자동 생성, RFC 7807 에러, 멱등성 키(지원/확정) |
| 알림 | FCM 무료 → 미응답자만 카카오 알림톡(폴백, 건당 소액) → 이메일. 광고성 알림 21~08시 금지 |
| 인프라 | **한국 리전** VPS 1대 + Docker Compose + Nginx/Caddy, Cloudflare(DNS/CDN/WAF/Turnstile). Oracle Free는 개발용만. Hetzner/Contabo는 한국 리전 없음(지연·국외이전 고려) |
| 백업 | `pg_dump` 일 1회 → R2 암호화 보관, 복구 리허설. 이후 WAL 아카이빙 |
| 관측/CI | Actuator+Micrometer, Sentry, UptimeRobot, GitHub Actions |
| 라이선스 | 상업 이용 가능한 MIT/Apache/BSD만. 의존성 추가 시 라이선스 확인 |

## 4. DB 설계 규칙 (`db/schema.sql` v0.2 = 단일 진실 원천, 47개 테이블)
1. **인증(users) / 권한(roles·permissions·user_roles) / 프로필(actor_profiles, companies) 분리.** 한 사람이 여러 역할 가능. `users.role` 단일 컬럼 방식 ✕. 업체는 조직이고 직원은 `company_members(OWNER/MANAGER/FIELD_LEADER/STAFF)`, 한 사람이 여러 업체 소속 가능.
2. **파일**: `files`(메타) + `file_attachments(entity_type, entity_id, usage)` 다형성. 새 첨부는 CHECK 값만 확장. 연결 쓰기는 **단일 서비스(AttachmentService)** 로만. `public_url` 저장 금지(키+CDN 도메인 조합). 사업자등록증·의상/지원 첨부는 `is_private=TRUE`.
3. **게시판**: `boards / board_permissions(게시판×역할) / posts / comments`. 공고와 완전 분리. 블랙리스트 공개 게시판 ✕ (비공개 차단 `company_actor_blocks` + 신고 `reports`).
4. 상태값은 `VARCHAR + CHECK` (DB enum ✕). 시간은 `TIMESTAMPTZ`(촬영일·집합시간만 `DATE/TIME` KST 업무값). PK는 `BIGINT IDENTITY`, 외부 노출 파일은 `public_id UUID`.
5. **정합성은 DB가 최후 방어**: 정원 `CHECK(confirmed_count<=headcount)`, 중복 지원 `uq_applications_active`(활성 상태만 → 취소 후 재지원 허용), 같은 날 이중 확정 `uq_applications_confirmed_day`. 선착순 확정은 `UPDATE … SET confirmed_count=confirmed_count+1 WHERE … AND confirmed_count<headcount` 원자적 처리 + `@Version`.
6. 선발 상태(`applications.status`)와 참석확인(`departure_confirmed_at`)과 출결(`attendances.result`)은 **직교 축으로 분리** — 한 status 컬럼에 섞지 말 것.
7. 확정 시 `condition_snapshot`(일당·집결·의상)을 JSONB로 고정. 비정규화/캐시 컬럼(`applied_count`, `shoot_date`, `reliability_score` 등)은 **갱신 책임 코드 위치를 주석으로 명시**.
8. 마이그레이션은 Flyway, 단계 적용: **V1 MVP(~30테이블) → V2 오픈 후 → V3 법무 확인 후**(`docs/REVIEW_COMPARISON.md` §5). 스키마 변경 시 `db/schema.sql`도 함께 갱신하고 PostgreSQL에서 실행 검증.

## 5. 도메인 규칙·정책 (구현 시 반드시 지킬 것)
- 역할 조건: 성별, **출생연도 범위**, 키 범위, 최소 성실도, 의상 사진 필수 여부, 예비 정원, 역할별 집합시간/장소 override(NULL이면 일자 값).
- 확정 시 같은 날 **미확정 지원 자동 취소(`CANCELED_BY_SYSTEM`)** + 알림. 정책 `app_settings.allow_multi_confirm_same_day`(기본 false).
- **노쇼 방지 파이프라인**: 전날 18시(설정) 확정자 전원에 “참석 확인” 푸시 → `departure_confirmed_at` → 22시 마감 후 미응답자 목록을 반장에게 → 폴백 알림톡/전화. 당일 반장이 출석/노쇼 일괄 체크, 노쇼 시 `reliability_events` 차감 + `apply_blocked_until`.
- 성실도는 **원장(reliability_events)이 원천, 점수는 캐시**. 업체에는 점수와 함께 출석 N/노쇼 N 객관 지표를 노출. 소명·복구 경로 제공.
- 취소/마감/우천 연기는 **일자 단위**(`job_schedules.status`)로 처리하고 하위 지원자에게 일괄 알림.
- 정산은 **기록·엑셀 생성까지만**(실제 송금·에스크로 범위 밖). 항목은 `settlement_items`(기본/연장/야간/식대/교통/보너스/공제).
- 출연자는 휴대폰 필수·유일(앱 레벨, 재가입 우회 방지). 인증 수단은 무료/저가부터.
- 업체는 사업자등록증 업로드 → **관리자 승인 후에만 공고 가능**.

## 6. 개인정보·보안 금지/필수
- ⛔ 주민등록번호·신분증 사진 **기본 수집 안 함**. 원천징수용 식별번호는 `actor_tax_identities`에 **기능 OFF**로만 존재(법무 검토 후 활성, 앱 레벨 AES-256-GCM + 키 DB 밖 + `key_version` 회전 + 확정 건 한정 노출 + 다운로드 감사 + 보관기한 파기).
- 계좌는 `actor_payout_accounts` 암호화 분리 저장, 정산 엑셀 다운로드는 OWNER/MANAGER만 + `audit_logs`.
- 비밀번호 Argon2/BCrypt, refresh 토큰은 **해시만 저장**·회전, 로그에 개인정보 마스킹, 업로드 확장자/MIME/크기 검증, CORS/CSRF, rate limit(Turnstile).
- 약관 버전·동의 이력 저장, 탈퇴 시 soft delete → 보관기간 후 파기 배치, 개인정보 국외이전(FCM, R2 등) 처리방침 고지.
- 법적 확인 필요 항목(구현 전 전문가): 직업안정법(소개/광고·수수료), 연소자(만15세 미만·18세 미만 야간), 일용근로 신고(업체 책임 명시), 광고성 정보 야간 전송, 위치정보(GPS 출석 시).

## 7. 기능 우선순위 (상세 ID는 `docs/DESIGN.md` §3)
- **P1 MVP**: 이메일/카카오 로그인, 약관, 출연자 프로필(+사진), 업체 등록·승인, 공고 작성(일자·역할 다중, 복사, 긴급, 비공개 현장), **달력/목록/필터/검색**, 내 조건 맞는 공고만 보기, 북마크, 지원(첨부)·취소, 업체 지원자 관리(카드/표 뷰, 확정·예비·거절), 중복확정 방지, 인앱+푸시+이메일 알림 및 수신설정, 전날 참석확인, 반장 모바일 출석 체크, 공지/FAQ/1:1문의/신고, 관리자(업체 승인·회원·공고·신고·게시판·감사로그).
- **P2**: 예비 자동 승격, QR/GPS 출석, 퇴근 일괄 입력, 정산 기록·**엑셀(주민번호 제외)**, 상호 평가, 성실도 원장·패널티, 저장검색 알림, 직원 권한, 통계, Valkey.
- **P3**: 인재검색/제안, 지도, 알림톡 연동 확대, 전자 근로조건 동의, 채팅, 반장 인력풀, 팀/그룹, (선택) 결제 연동.

## 8. 코드 컨벤션
- 패키지: `{base}.{module}.{api|application|domain|infra}`; 모듈 = auth, user, company, production, job, application, attendance, settlement, file, board, notification, admin, audit. 모듈 간 쓰기는 소유 모듈 서비스/도메인 이벤트로만.
- 컨트롤러 → 서비스 → 레포지토리. 엔티티를 API에 노출하지 않고 DTO 사용. N+1 방지(fetch join/배치), 목록은 **커서 페이지네이션**.
- 시간대: 서버 UTC 저장, 표시 `Asia/Seoul`. 금액은 원 단위 정수.
- 테스트: 동시성(정원·이중확정), 상태 전이, 권한(타 업체 공고 접근 불가)은 통합 테스트(Testcontainers PostgreSQL) 필수.
- 커밋 메시지 한국어/영어 무관, 변경 이유 중심. 브랜치 정책은 세션 지시를 따른다. PR은 요청 시에만.

## 9. 다음 작업 순서 (새 세션이 이어서 할 일)
1. `docs/DESIGN.md` §13의 **미결 질문 8개** 중 사용자 답변 확인(수수료 모델, 같은 날 복수 확정, 미성년자 등) → 필요 시 스키마 반영.
2. 리포 구조 생성: `backend/`(Spring Boot, Gradle), `web/`(Vite), `app/`(Flutter), `db/migration/`(Flyway V1~), `infra/`(Compose, Nginx).
3. Flyway **V1** = `db/schema.sql`의 MVP 테이블 분할. 시드(roles, permissions, position_categories, boards, app_settings, 지역 마스터).
4. 백엔드 수직 슬라이스: auth → company 승인 → job(포스팅·일자·역할) → 달력/검색 쿼리 → application(원자적 확정) → outbox/알림 → attendance.
5. 업체 웹(공고 작성 마법사·지원자 그리드·반장 출석) → Flutter 출연자 앱(달력·지원·푸시·참석확인).
6. 스테이징 배포(한국 리전 VPS + R2 + Cloudflare), 백업·모니터링 구성.

## 10. 참고 문서
- `docs/DESIGN.md` — 전체 정의서·기능 목록·권한 매트릭스·흐름·API·화면 (일부 기술 스택은 구버전: 이 파일/REVIEW_COMPARISON 우선)
- `docs/REVIEW_COMPARISON.md` — 사용자 검토안 vs 기존안 비교와 최종 결정, MVP 테이블 세트, 기술 스택 표
- `db/schema.sql` — PostgreSQL DDL v0.2 (검증 완료)
- ⚠️ 무료 한도·요금·버전·법령은 변동 → **착수 시 공식 출처 재확인** 후 반영.
