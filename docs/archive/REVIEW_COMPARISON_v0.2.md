> ⚠️ **보관용(폐기된 초안)** — 최신 설계는 `docs/00_README.md` 와 루트 `CLAUDE.md` 를 따른다.

# 사용자 검토안 vs 기존 설계안 — 비교 분석과 최종 결정 (v0.2)

> 기준일 2026-10-28. 무료 한도·요금·버전은 **착수 전 공식 문서로 재확인** (이 문서 작성자의 지식은 2026-06까지).
> 결론 요약: **사용자 검토안의 도메인/상용화 지적은 대부분 수용**하고, **권한·첨부·게시판·동시성 같은 구조 설계는 기존안을 유지**하되, 양쪽의 약점을 서로 보정한 것이 최종안이다. `db/schema.sql` v0.2에 반영 완료(47개 테이블, PostgreSQL에서 실행·제약 동작 검증).

---

## 1. 사용자 지적 5가지 — 판정

| # | 사용자 지적 | 판정 | 정리 |
|---|---|---|---|
| 1 | S3 → Cloudflare R2 (egress 0원) | ✅ **수용** | 기존안도 R2/GCS 병기였으나 **R2 단일 확정**. 업체가 사진을 대량 조회하는 패턴이라 egress 무료가 결정적. 단, 신분증·사업자등록증 같은 비공개 파일은 CDN 공개가 아니라 **서명 URL** (§3-6) |
| 2 | Oracle Free 상용화 리스크 → 저가 VPS | ✅ **수용 + 보완** | 맞다. 다만 **Hetzner·Contabo는 한국 리전이 없다**(유럽/미국/싱가포르). 한국 사용자는 지연(수십~수백 ms)과 **개인정보 국외이전 고지** 이슈. → 한국 리전(AWS Lightsail 서울, Vultr 서울, GCP 서울 등) 우선 검토. 가격 차이는 월 수천~1만 원대 수준이라 가성비 손해가 작음 |
| 3 | 공고 1건에 역할 N개 (posting ↔ roles) | ✅ **이미 반영돼 있었음 + 개선** | 기존안은 `posting → schedule(일자) → position(역할)` 3단으로 이미 분리(다일자 지원). 사용자안은 `posting(1일) → roles`. **기존안이 상위호환**이지만 사용자가 짚은 “역할마다 의상·집결지·페이가 다르다”는 점이 **역할 단위 집결 override가 없던 기존 약점**이었음 → `job_positions`에 `call_time/meeting_place` override, 출생연도/키 범위, 예비 정원, 연장·식대 필드 추가 |
| 4 | 업체 1:1 한계 → agencies / members | ✅ **이미 반영 + 개선** | 기존 `companies/company_members` 있음. 사용자안의 `user_id UNIQUE`(1명=1업체)는 **프리랜서 반장이 여러 업체에서 일하는 현실과 충돌** → 기존안(복합 PK) 유지. 사용자안의 **FIELD_LEADER(현장반장), 팀명** 수용 |
| 5 | 노쇼·출퇴근·연장/야간·예비합격 누락 | ✅ **가장 가치 있는 지적, 전면 수용** | 기존안에는 대기자/출석/정산 기록은 있었으나 **전날 출발확인, 미응답자 색출, 성실도 점수, 연장·식대 구조화, 조건 스냅샷**이 없었음 → 모두 추가 (§3) |

---

## 2. 항목별 상세 비교 (더 확실한 쪽 선택)

| 영역 | 사용자안 | 기존안 | **최종 채택** | 이유 |
|---|---|---|---|---|
| 사용자/권한 | `users.role` 단일 컬럼 | `roles/permissions/user_roles` 다대다 | **기존안** | 한 사람이 출연자+반장일 수 있음, 관리자 세분화(OPERATOR 등), 권한 추가 시 코드 수정 불필요. 사용자안은 가장 단순하지만 사용자가 요구한 “확장 시 이슈 없는 초기설계”에 불리 |
| 휴대폰 | `phone UNIQUE NOT NULL` | 선택, 인증은 P3 | **절충** | 노쇼 패널티는 **재가입 우회**가 가능하면 무력 → 출연자는 휴대폰 필수·유일(앱 레벨), 인증은 무료/저가 수단부터(Firebase Phone Auth 무료 구간 또는 카카오 동의항목, 한도 확인). DB 컬럼은 NULL 허용(관리자·소셜 단계 가입 대응) |
| FCM 토큰 | `users.fcm_token` 1개 | `device_tokens` 다중 | **기존안** | 폰 교체/태블릿/웹 동시 사용, 로그아웃 시 토큰 폐기 |
| 출연자 신체정보 | 전용 컬럼 + `specialty_tags JSONB` | EAV `actor_attributes` | **사용자안** | 매칭 필터에 쓰는 값은 **타입 있는 컬럼 + 인덱스**가 빠르고 단순, 개방형 특기는 JSONB+GIN. EAV는 제거 |
| 나이 조건 | 출생연도 min/max | min_age/max_age | **사용자안** | 현장 관행(“85~05년생”), 해가 바뀌어도 공고 불변 |
| 계좌/주민번호 | `performer_profiles`에 암호화 저장 | 주민번호 수집 금지 | **분리 + 기본 OFF** | ⚠️ 아래 §4 참고. 프로필에서 분리한 별도 테이블(`actor_payout_accounts`, `actor_tax_identities`), 앱 암호화+키 회전, **주민번호 수집 기능은 법무 검토 전 비활성**. 정산 엑셀 MVP는 이름·계좌·금액 |
| 지원/출결/정산 | `applications` 한 테이블에 합침 | `applications / attendances / settlements` 분리 | **기존안 + 편의 뷰** | 합치면 조회는 편하나 컬럼이 계속 비대해지고(연장·식대·분쟁), 출결 수정 이력 관리가 어렵다. 분리 유지, 목록용 DB VIEW로 보완. `settlement_items` 추가로 연장/야간/식대 구조화 |
| 중복 지원 | `UNIQUE(job_posting_id, performer_id)` | 부분 유니크(활성 상태만) | **기존안** | 사용자안은 **취소 후 재지원이 영구 불가**. 기존안은 취소 후 재지원 허용 (실제 테스트 통과) |
| 이중 확정 | 확정 시 같은 날 다른 지원 자동 취소(기능) | DB 제약으로 차단만 | **둘 다** | DB 부분 유니크(최후 방어) + 확정 시 같은 날 **미확정 지원 자동 `CANCELED_BY_SYSTEM`** + 알림 (정책 `allow_multi_confirm_same_day`로 완화 가능) |
| 상태머신 | `DEPARTURE_CHECKED`, `NO_SHOW`를 status에 혼합 | status와 출결 분리 | **분리** | 상태가 직교 축 혼합되면 조합 폭발. status=선발 단계, `departure_confirmed_at`=참석확인, `attendances.result`=출결 |
| 첨부파일 | `file_groups` + `files`, 도메인 테이블에 `file_group_id` FK | `files` + 다형성 `file_attachments` | **기존안(다형성) 유지 + 사용자안 장점 흡수** | §3-6 상세 |
| 게시판 | 단일 `boards`(글 겸용) | `boards/board_permissions/posts/comments` | **기존안** | 사용자안은 게시판=글이라 댓글·권한·다중 게시판 불가 |
| 블랙리스트 신문고 | 게시판 활용 | `company_actor_blocks`(비공개) + 신고 | **기존안** | 공개 게시판에서 특정인 비방은 명예훼손/개인정보 리스크. 비공개 차단 + 신고·소명 절차 |
| 웹 프론트 | React 19 + Vite SPA | Next.js PWA | **Vite SPA (업체/관리자)** | 업체·관리자 포털은 로그인 전용이라 SEO 불필요 → 정적 배포(Cloudflare Pages 무료)로 서버 불필요. 기존 Next.js 권고 **철회** |
| 출연자 클라이언트 | Flutter | PWA→Flutter | **Flutter 앱 우선** | “전날 출발확인 푸시”가 핵심 가치인데 iOS 웹푸시는 홈화면 설치 의존 → 신뢰도 낮음. 앱이 본체. 단 MVP 일정상 보조로 **React 모바일 웹**에 조회·지원 기능을 같은 코드로 최소 제공 가능 |
| 캐시/큐 | Valkey + Redis Stream | 불필요, 선택 | **MVP 제외, P2에 Valkey** | 정원 동시성은 **DB 원자적 UPDATE로 충분**(Redis 불필요). OTP·rate limit이 필요해질 때 추가. 도입 시 **Valkey**(BSD) 선택은 옳음 — Redis 7.4+ 라이선스 변경 이력 |
| Kafka/RabbitMQ/Hadoop | 제외 | 제외 | **제외(일치)** | 아웃박스 + `SKIP LOCKED` 워커 |
| 테이블 수 | 11개 | 44개 | **47개 중 MVP 약 30개 먼저** | 기존안이 과설계 소지 → §5의 MVP 최소 세트로 Flyway 단계 적용 |
| 알림톡 | FCM 우선 + 미응답자에게만 알림톡 | 알림톡 P3 | **사용자안** | 비용 효율적인 폴백. 알림톡은 정보성 템플릿 심사 필요, 발송대행사 계약 |
| 정산 엑셀 | Apache POI 원클릭 | P2 기록만 | **사용자안(P2)** | 판매 포인트. 주민번호 제외 버전부터 |
| 현장 반장 모바일 | 반응형/전용 탭 | 일괄 출석 체크 | **사용자안** | “노트북 못 켠다” 현실 반영. 반장 화면은 모바일 우선 설계 |

---

## 3. 최종안에 새로 반영된 것 (스키마 v0.2 기준)

1. **전날 참석 확인 파이프라인**: 매일 18시(설정값) `applications(CONFIRMED)`에 푸시 → `departure_confirmed_at` 기록 → 22시 마감 후 미응답자 목록(부분 인덱스 `ix_applications_departure`)을 반장 화면에 → 필요 시 알림톡/전화.
2. **성실도 점수 원장** `reliability_events` + `actor_profiles.reliability_score / apply_blocked_until`: 노쇼 시 차감·지원 제한. 점수는 **캐시, 원장이 원천** → 소명/이의제기 시 복구 가능. 업체에게는 불투명한 점수만이 아니라 **출석 N회/노쇼 N회** 같은 객관 지표 병행 노출(명예훼손 리스크 완화).
3. **확정 시점 조건 스냅샷** `applications.condition_snapshot`: 일당·집결·의상 조건을 확정 순간 고정 → 이후 공고 수정이 있어도 분쟁 근거 유지.
4. **역할 단위 조건 확장**: 출생연도/키 범위, 예비 정원(`standby_headcount`), 최소 성실도, 연장·식대, 의상 사진 필수.
5. **정산 항목화** `settlement_items`(기본/연장/야간/식대/교통/보너스/공제). 엑셀 생성의 원천.
6. **파일 보강**: 이미지 크기·리사이즈 변형본 키(`variants`), `public_url` 비저장(키+CDN 도메인 조합), 의상 사진 용도 추가.
7. **업체 내부 직책**: `OWNER / MANAGER(캐스팅팀장) / FIELD_LEADER(현장반장) / STAFF` + 팀명.
8. **앱 설정 테이블 시드**: 참석확인 시각, 패널티 점수·제한일수, 광고 알림 금지 시간 등 운영값을 코드가 아닌 데이터로.

### 3-6. 첨부파일: 왜 다형성을 유지하는가 (정직한 트레이드오프)
| | file_groups(사용자안) | 다형성 `file_attachments`(기존안) |
|---|---|---|
| FK 무결성 | ✅ 있음 | ❌ `entity_id`에 FK 없음 → 앱 단일 진입점 + 주기적 정합성 점검 배치로 보완 |
| 새 기능에 첨부 추가 | 대상 테이블에 컬럼 추가 마이그레이션 | ✅ 마이그레이션 없음 (CHECK 값만 확장) |
| 엔티티의 모든 파일 역조회 | 불가능(어느 테이블이 가리키는지 모름) | ✅ 인덱스 하나 |
| 고아 파일 정리 | 모든 도메인 테이블 스캔 필요 | ✅ `files.status`+연결 유무 |
| 용도/순서 | `files.file_category/sort_order` | `usage/sort_order` |
**결정**: 사용자가 가장 강조한 “추가 기능이 생겨도 이슈 없게”에는 다형성이 유리. 두 방식 모두 실무에서 쓰이며, FK 무결성이 최우선이 되는 시점이 오면 `file_group` 방식으로 이관 가능(데이터 변환 쉬움). 사용자안에서 **비공개 파일 문제**(모두 `public_url` 저장)는 반드시 고쳐야 할 점: 사업자등록증·의상/신분 관련 사진은 `is_private=TRUE`, 서명 URL만.

---

## 4. 사용자안의 위험 요소 (반드시 짚을 것)

1. **주민등록번호 저장**: 개인정보보호법상 법령 근거 없이는 처리 불가이고, 근거가 있어도(원천징수 신고) **플랫폼이 중앙 보관하면 유출 시 사고 규모가 치명적**. 정산 엑셀에 주민번호까지 넣어 업체 직원이 내려받는 구조는 2차 유출 경로. → 기본 OFF, 법무 검토 후 켜더라도 ① 별도 테이블 ② 앱 암호화(AES-256-GCM, 키는 DB 밖) ③ 확정 건에 한해 업체 노출 ④ 다운로드는 OWNER/MANAGER만 + `audit_logs` ⑤ 보관기한 후 파기. 원천징수 신고 책임은 **업체**임을 약관에 명시하고, 업체가 자체 수집하는 방식(업체 측 서류)도 옵션.
2. **신분증 사진(`ID_CARD`) 수집**: 목적 불명확하면 과수집. 지원 단계에선 받지 않는다.
3. **`public_url` 일괄 공개 저장**: 위 3-6 참조.
4. **`users.phone UNIQUE NOT NULL` + 소셜 로그인**: 소셜 가입 후 단계적 입력(프로필 완성도) 흐름과 충돌 가능 → 앱 레벨 필수, DB는 NULL 허용.
5. **`password_hash NOT NULL`**: 소셜 전용 계정 불가 → NULL 허용.
6. **`applications` 올인원**: 출결/정산 수정 이력이 한 행에 덮어써짐.
7. **`UNIQUE(job_posting_id, performer_id)`**: 취소 후 재지원·다른 역할 재지원 불가.
8. **TIMESTAMP(타임존 없음)**: 서버/DB 타임존 변경 시 오작동 → `TIMESTAMPTZ`.
9. **기존안 자기 비판**: 44→47개 테이블은 MVP엔 과함, EAV는 불필요했음(제거), Next.js 권고는 부적절했음(철회), 역할별 집결 override 누락(보완).

---

## 5. MVP 최소 테이블 세트 (Flyway V1 → V2 단계 적용 권장)

- **V1 (MVP 핵심, ~30)**: users, user_auth_providers, roles, permissions, role_permissions, user_roles, refresh_tokens, terms, user_consents, regions, position_categories, productions, files, file_attachments, actor_profiles, companies, company_members, company_verifications, job_postings, job_schedules, job_positions, applications, application_status_histories, attendances, bookmarks, boards, board_permissions, posts, comments, notifications, notification_deliveries, device_tokens, notification_settings, outbox_events, reports, audit_logs, app_settings
- **V2 (오픈 후)**: settlements, settlement_items, reliability_events, reviews, company_actor_blocks, saved_searches, notification_templates, login_histories
- **V3 (법무 확인 후)**: actor_payout_accounts, actor_tax_identities

*(스키마 파일은 전체를 담고 있고, 실제 마이그레이션 파일은 위 단계로 쪼개 적용)*

---

## 6. 2026 기준 최종 기술 스택

| 영역 | 최종 | 비고 |
|---|---|---|
| 백엔드 | **Java 25 LTS**(없으면 21) + **Spring Boot 최신 안정 GA** | 2025-09 Java 25 LTS 출시 → 2026 신규 프로젝트는 25가 기본 후보. Spring Boot는 **3.x OSS 지원 종료 시점·4.x 안정 여부를 공식 지원 정책 페이지에서 확인** 후 선택. 가상 스레드 활성화 |
| 데이터 접근 | Spring Data JPA + **QueryDSL**(동적 검색) | QueryDSL 본가 유지보수가 느림 → 포크(OpenFeign) 사용 여부 확인. 대안 jOOQ OSS(PostgreSQL 무료) |
| 보안 | Spring Security + JJWT, 쿠키(웹)/Bearer(앱) | |
| DB | **PostgreSQL 17 또는 18**(≥16 호환) | 부분 유니크, JSONB+GIN, pg_trgm, SKIP LOCKED, TIMESTAMPTZ |
| 마이그레이션 | Flyway | |
| 큐/비동기 | **아웃박스 + 워커**(Spring `@Scheduled`, ShedLock) / Spring Modulith 이벤트(선택) | Kafka/RabbitMQ/Hadoop ✕ |
| 캐시 | (P2) **Valkey** + Spring Data Redis | |
| 파일 | **Cloudflare R2** + AWS SDK v2(S3 호환), 업로드는 서명 URL 직접, 클라이언트에서 WebP 압축 | |
| 업체/관리자 웹 | **React 19 + TS + Vite**, TanStack Query/Table, Zustand, Tailwind + shadcn/ui, FullCalendar(MIT 코어만) | Cloudflare Pages 정적 배포 |
| 출연자 앱 | **Flutter 최신 stable** + Riverpod, Dio, GoRouter, table_calendar, firebase_messaging, flutter_image_compress | |
| API 계약 | springdoc OpenAPI → TS/Dart 클라이언트 자동 생성 | 양쪽 타입 불일치 방지 |
| 푸시/알림 | **FCM** 무료 + (폴백) 카카오 알림톡(건당 소액, 발송대행사) + 이메일(Resend/Brevo/SES 무료·저가 구간) | |
| 인프라 | **한국 리전 VPS 1대 + Docker Compose + Nginx(or Caddy)**, Cloudflare(DNS/CDN/WAF/Turnstile 무료), Let's Encrypt | 성장 시 DB 분리/매니지드 |
| 백업 | `pg_dump`(일 1회) → R2 암호화 보관 + 복구 리허설, 이후 WAL 아카이빙(pgBackRest/WAL-G) | **필수** |
| 관측 | Actuator+Micrometer, Sentry 무료, UptimeRobot/Better Stack 무료, Grafana Cloud 무료 | |
| CI/CD | GitHub Actions → 이미지 빌드 → SSH/Compose 배포 | |
| 라이선스 | 위 라이브러리 모두 MIT/Apache/BSD 계열(상업 사용 가능). FullCalendar **프리미엄 플러그인 사용 금지**. Redis 대신 Valkey | 의존성 라이선스 스캔(예: license-maven-plugin) CI 포함 |

---

## 7. 최종 결론

- 사용자 검토는 **“현장 도메인(노쇼·역할별 조건·반장·정산)”과 “상용 운영 비용(R2, VPS)”** 면에서 기존안보다 **더 정확**했고 대부분 수용했다.
- 기존안은 **권한 모델, 첨부/게시판 분리, 상태·출결·정산 분리, 동시성/제약 설계** 면에서 확장성이 더 안전해 유지했다.
- 사용자안의 가장 큰 위험은 **주민번호·계좌 중앙 저장과 공개 URL 파일**, **단일 role 컬럼**, **취소 후 재지원 불가 제약**이며, 이를 고친 형태로 합쳤다.
- 최종 설계는 `db/schema.sql` v0.2, 프로젝트 기억용 지침은 루트 `CLAUDE.md`.
