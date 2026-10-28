-- =====================================================================
-- 보조출연 매칭 플랫폼 - PostgreSQL 16 초기 스키마 (v0.1)
-- 원칙
--  1) 인증(users) / 권한(roles) / 프로필(actor, company) 분리
--  2) 파일(files) 은 메타데이터 1곳, 연결은 file_attachments (다형성) 로 분리
--  3) 게시판(boards/posts/comments) 은 공고(job_*) 와 완전히 분리
--  4) 상태값은 VARCHAR + CHECK (enum 변경 마이그레이션 부담 회피)
--  5) 공통 컬럼: id(BIGINT IDENTITY), created_at, updated_at, (필요 시) deleted_at
--  6) 시간은 모두 TIMESTAMPTZ, 촬영일/집합시간은 DATE/TIME (KST 기준 업무 데이터)
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------
-- 0. 공통: updated_at 자동 갱신 트리거
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- 1. 계정 / 인증 / 권한
-- ---------------------------------------------------------------------
CREATE TABLE users (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email              CITEXT       NOT NULL,
  password_hash      VARCHAR(100),                        -- 소셜 전용 계정이면 NULL
  name               VARCHAR(50)  NOT NULL,
  nickname           VARCHAR(50),
  phone              VARCHAR(20),
  status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','WITHDRAWN')),
  email_verified_at  TIMESTAMPTZ,
  phone_verified_at  TIMESTAMPTZ,
  last_login_at      TIMESTAMPTZ,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
  deleted_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_users_email ON users (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_phone ON users (phone) WHERE deleted_at IS NULL AND phone IS NOT NULL;

CREATE TABLE user_auth_providers (            -- 소셜 로그인 (카카오/구글/네이버/애플)
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           BIGINT      NOT NULL REFERENCES users(id),
  provider          VARCHAR(20) NOT NULL CHECK (provider IN ('KAKAO','GOOGLE','NAVER','APPLE')),
  provider_user_id  VARCHAR(100) NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_user_id)
);

CREATE TABLE roles (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(30) NOT NULL UNIQUE,        -- SUPER_ADMIN, ADMIN, OPERATOR, COMPANY, ACTOR
  name        VARCHAR(50) NOT NULL,
  description VARCHAR(200)
);
CREATE TABLE permissions (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(60) NOT NULL UNIQUE,        -- 예: JOB_POST_CREATE, COMPANY_APPROVE
  name        VARCHAR(100) NOT NULL
);
CREATE TABLE role_permissions (
  role_id       BIGINT NOT NULL REFERENCES roles(id),
  permission_id BIGINT NOT NULL REFERENCES permissions(id),
  PRIMARY KEY (role_id, permission_id)
);
CREATE TABLE user_roles (                       -- 한 사람이 ACTOR + COMPANY 동시 보유 가능
  user_id    BIGINT NOT NULL REFERENCES users(id),
  role_id    BIGINT NOT NULL REFERENCES roles(id),
  granted_by BIGINT REFERENCES users(id),
  granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     BIGINT      NOT NULL REFERENCES users(id),
  token_hash  VARCHAR(100) NOT NULL UNIQUE,       -- 원문 저장 금지, 해시만
  device_id   VARCHAR(100),
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE login_histories (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     BIGINT REFERENCES users(id),
  login_id    VARCHAR(100),                       -- 실패 시 입력값
  success     BOOLEAN     NOT NULL,
  ip_address  INET,
  user_agent  VARCHAR(300),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_histories_user ON login_histories (user_id, created_at DESC);

CREATE TABLE terms (                            -- 약관 버전 관리
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  type         VARCHAR(30) NOT NULL CHECK (type IN ('SERVICE','PRIVACY','MARKETING','LOCATION','YOUTH')),
  version      VARCHAR(20) NOT NULL,
  content      TEXT        NOT NULL,
  is_required  BOOLEAN     NOT NULL,
  effective_at TIMESTAMPTZ NOT NULL,
  UNIQUE (type, version)
);
CREATE TABLE user_consents (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id),
  term_id     BIGINT NOT NULL REFERENCES terms(id),
  agreed      BOOLEAN NOT NULL,
  agreed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, term_id)
);

-- ---------------------------------------------------------------------
-- 2. 마스터 데이터
-- ---------------------------------------------------------------------
CREATE TABLE regions (                          -- 시/도 → 시/군/구 (2단계)
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parent_id BIGINT REFERENCES regions(id),
  code      VARCHAR(10) NOT NULL UNIQUE,
  name      VARCHAR(50) NOT NULL,
  level     SMALLINT    NOT NULL CHECK (level IN (1,2))
);

CREATE TABLE position_categories (              -- 일반/교복/군인/경찰/환자/외국인/차량지참 …
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code       VARCHAR(30) NOT NULL UNIQUE,
  name       VARCHAR(50) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_active  BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE productions (                      -- 작품(드라마/영화/광고…) 공용 마스터
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title          VARCHAR(200) NOT NULL,
  type           VARCHAR(20)  NOT NULL DEFAULT 'DRAMA'
                 CHECK (type IN ('DRAMA','MOVIE','AD','MV','OTT','ETC')),
  broadcaster    VARCHAR(100),                    -- 방송사/OTT/배급사
  producer_name  VARCHAR(100),                    -- 제작사
  is_confidential BOOLEAN NOT NULL DEFAULT FALSE, -- 작품명 비공개 현장 대응
  status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','HIDDEN','MERGED')),
  merged_into_id BIGINT REFERENCES productions(id),
  created_by     BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_productions_title_trgm ON productions USING gin (title gin_trgm_ops);

-- ---------------------------------------------------------------------
-- 3. 파일 (메타데이터 1곳 + 다형성 연결)
-- ---------------------------------------------------------------------
CREATE TABLE files (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id         UUID        NOT NULL DEFAULT gen_random_uuid() UNIQUE,   -- 외부 노출용
  uploader_user_id  BIGINT REFERENCES users(id),
  storage_provider  VARCHAR(20) NOT NULL DEFAULT 'GCS' CHECK (storage_provider IN ('GCS','S3','R2','LOCAL')),
  bucket            VARCHAR(100) NOT NULL,
  object_key        VARCHAR(500) NOT NULL,
  original_name    VARCHAR(255) NOT NULL,
  content_type      VARCHAR(100) NOT NULL,
  size_bytes        BIGINT      NOT NULL CHECK (size_bytes >= 0),
  checksum_sha256   VARCHAR(64),
  status            VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','ACTIVE','DELETED')),         -- PENDING: 업로드 URL 발급만 된 상태
  is_private        BOOLEAN     NOT NULL DEFAULT TRUE,                       -- TRUE → 서명 URL로만 접근
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at        TIMESTAMPTZ,
  UNIQUE (bucket, object_key)
);
CREATE INDEX ix_files_pending ON files (created_at) WHERE status = 'PENDING';   -- 고아 파일 청소 배치

CREATE TABLE file_attachments (                 -- “어떤 엔티티의 어떤 용도 파일인가”
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  file_id      BIGINT      NOT NULL REFERENCES files(id),
  entity_type  VARCHAR(30) NOT NULL
               CHECK (entity_type IN ('ACTOR_PROFILE','COMPANY','COMPANY_VERIFICATION','JOB_POSTING',
                                      'APPLICATION','POST','COMMENT','REPORT','PRODUCTION')),
  entity_id    BIGINT      NOT NULL,
  usage        VARCHAR(30) NOT NULL
               CHECK (usage IN ('PROFILE_PHOTO','FULL_BODY_PHOTO','RESUME','BIZ_LICENSE','LOGO',
                                'JOB_IMAGE','APPLY_DOC','ATTACHMENT','EVIDENCE')),
  sort_order   INT         NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (file_id, entity_type, entity_id, usage)
);
CREATE INDEX ix_file_attachments_entity ON file_attachments (entity_type, entity_id, usage, sort_order);

-- ---------------------------------------------------------------------
-- 4. 프로필 (출연자 / 업체)
-- ---------------------------------------------------------------------
CREATE TABLE actor_profiles (                   -- 1:1 users, ACTOR 역할 사용자만
  user_id        BIGINT PRIMARY KEY REFERENCES users(id),
  birth_date     DATE        NOT NULL,            -- 연령/미성년 판정용 (주민번호 저장 금지)
  gender         VARCHAR(10) NOT NULL CHECK (gender IN ('MALE','FEMALE','OTHER')),
  height_cm      SMALLINT CHECK (height_cm BETWEEN 50 AND 250),
  weight_kg      SMALLINT CHECK (weight_kg BETWEEN 20 AND 300),
  region_id      BIGINT REFERENCES regions(id),
  introduction   VARCHAR(1000),
  has_vehicle    BOOLEAN     NOT NULL DEFAULT FALSE,
  experience_note VARCHAR(1000),
  is_searchable  BOOLEAN     NOT NULL DEFAULT TRUE,  -- 업체의 인재 검색 노출 동의
  -- 집계 캐시 (원천은 attendances / reviews, 배치/이벤트로 갱신)
  attended_count INT         NOT NULL DEFAULT 0,
  noshow_count   INT         NOT NULL DEFAULT 0,
  rating_avg     NUMERIC(3,2),
  rating_count   INT         NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE actor_attributes (                 -- 확장형(EAV): 사이즈, 특기, 어학, 보유의상, 문신 등
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id   BIGINT      NOT NULL REFERENCES actor_profiles(user_id),
  attr_type VARCHAR(30) NOT NULL,               -- SIZE, SKILL, LANGUAGE, COSTUME, LOOK ...
  attr_key  VARCHAR(50) NOT NULL,
  attr_value VARCHAR(200),
  UNIQUE (user_id, attr_type, attr_key)
);

CREATE TABLE companies (                        -- 보출 업체
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name            VARCHAR(100) NOT NULL,
  biz_reg_no      VARCHAR(12)  NOT NULL,        -- 사업자등록번호
  representative  VARCHAR(50)  NOT NULL,
  phone           VARCHAR(20),
  address         VARCHAR(200),
  region_id       BIGINT REFERENCES regions(id),
  introduction    VARCHAR(2000),
  status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','APPROVED','REJECTED','SUSPENDED')),
  approved_by     BIGINT REFERENCES users(id),
  approved_at     TIMESTAMPTZ,
  rating_avg      NUMERIC(3,2),
  rating_count    INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_companies_biz ON companies (biz_reg_no) WHERE deleted_at IS NULL;

CREATE TABLE company_members (                  -- 업체 내부 직원/담당자 (다대다)
  company_id  BIGINT NOT NULL REFERENCES companies(id),
  user_id     BIGINT NOT NULL REFERENCES users(id),
  member_role VARCHAR(20) NOT NULL DEFAULT 'STAFF' CHECK (member_role IN ('OWNER','MANAGER','STAFF')),
  status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('INVITED','ACTIVE','REMOVED')),
  joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (company_id, user_id)
);
CREATE INDEX ix_company_members_user ON company_members (user_id);

CREATE TABLE company_verifications (            -- 업체 승인 심사 이력 (사업자등록증은 file_attachments)
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id   BIGINT NOT NULL REFERENCES companies(id),
  status       VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED'
               CHECK (status IN ('SUBMITTED','APPROVED','REJECTED')),
  reject_reason VARCHAR(500),
  reviewed_by  BIGINT REFERENCES users(id),
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_at  TIMESTAMPTZ
);

-- ---------------------------------------------------------------------
-- 5. 공고 (핵심 도메인)  posting → schedule(일자) → position(역할/모집)
-- ---------------------------------------------------------------------
CREATE TABLE job_postings (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id       BIGINT NOT NULL REFERENCES companies(id),
  production_id    BIGINT REFERENCES productions(id),
  display_title    VARCHAR(200),                -- 작품명 비공개 시 노출용 별칭 ("사극 A현장")
  title            VARCHAR(200) NOT NULL,
  description      TEXT,
  status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT','OPEN','CLOSED','CANCELED','COMPLETED')),
  is_urgent        BOOLEAN NOT NULL DEFAULT FALSE,   -- 당일/긴급 충원
  conceal_details  BOOLEAN NOT NULL DEFAULT FALSE,   -- TRUE → 확정자에게만 상세 장소/작품명 공개
  contact_phone    VARCHAR(20),
  apply_deadline_at TIMESTAMPTZ,
  first_shoot_date DATE,                              -- 일정 중 최소일 (목록/정렬용 비정규화)
  last_shoot_date  DATE,
  view_count       INT NOT NULL DEFAULT 0,
  published_at     TIMESTAMPTZ,
  created_by       BIGINT NOT NULL REFERENCES users(id),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at       TIMESTAMPTZ
);
CREATE INDEX ix_job_postings_company ON job_postings (company_id, status);
CREATE INDEX ix_job_postings_open_date ON job_postings (first_shoot_date) WHERE status = 'OPEN' AND deleted_at IS NULL;
CREATE INDEX ix_job_postings_title_trgm ON job_postings USING gin (title gin_trgm_ops);

CREATE TABLE job_schedules (                    -- 촬영 “일자” 단위. 달력의 최소 단위
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  posting_id       BIGINT NOT NULL REFERENCES job_postings(id),
  shoot_date       DATE NOT NULL,
  call_time        TIME NOT NULL,                 -- 집합 시간
  end_time         TIME,                          -- 예상 종료 (익일 종료는 is_overnight)
  is_overnight     BOOLEAN NOT NULL DEFAULT FALSE,
  region_id        BIGINT REFERENCES regions(id),
  meeting_place    VARCHAR(200),                  -- 집합 장소명
  meeting_address  VARCHAR(300),
  latitude         NUMERIC(9,6),
  longitude        NUMERIC(9,6),
  note             VARCHAR(1000),
  status           VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                   CHECK (status IN ('OPEN','CLOSED','CANCELED','POSTPONED')),   -- 우천/일정 변경
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_job_schedules_date ON job_schedules (shoot_date, status);
CREATE INDEX ix_job_schedules_posting ON job_schedules (posting_id);

CREATE TABLE job_positions (                    -- 일자 내 모집 단위(역할)
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  schedule_id      BIGINT NOT NULL REFERENCES job_schedules(id),
  posting_id       BIGINT NOT NULL REFERENCES job_postings(id),   -- 조회 편의 비정규화
  category_id      BIGINT REFERENCES position_categories(id),
  title            VARCHAR(100) NOT NULL,         -- "교복 학생", "일반 행인(남)"
  headcount        INT  NOT NULL CHECK (headcount > 0),
  gender_req       VARCHAR(10) NOT NULL DEFAULT 'ANY' CHECK (gender_req IN ('ANY','MALE','FEMALE')),
  min_age          SMALLINT,
  max_age          SMALLINT,
  pay_type         VARCHAR(10) NOT NULL DEFAULT 'DAILY' CHECK (pay_type IN ('DAILY','HOURLY','NEGOTIABLE')),
  pay_amount       INT CHECK (pay_amount >= 0),
  meal_provided    BOOLEAN NOT NULL DEFAULT FALSE,
  transport_provided BOOLEAN NOT NULL DEFAULT FALSE,
  dress_code       VARCHAR(300),                  -- 지참 의상
  requirements     VARCHAR(1000),
  apply_mode       VARCHAR(20) NOT NULL DEFAULT 'APPROVAL' CHECK (apply_mode IN ('APPROVAL','FIRST_COME')),
  requires_document BOOLEAN NOT NULL DEFAULT FALSE, -- 첨부 필수 여부
  applied_count    INT NOT NULL DEFAULT 0,        -- 캐시 (애플리케이션 트랜잭션에서 갱신)
  confirmed_count  INT NOT NULL DEFAULT 0,
  status           VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','FULL','CLOSED','CANCELED')),
  version          BIGINT NOT NULL DEFAULT 0,     -- JPA @Version (낙관적 락)
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (confirmed_count <= headcount),
  CHECK (min_age IS NULL OR max_age IS NULL OR min_age <= max_age)
);
CREATE INDEX ix_job_positions_schedule ON job_positions (schedule_id);
CREATE INDEX ix_job_positions_posting ON job_positions (posting_id);

-- ---------------------------------------------------------------------
-- 6. 지원 / 출석 / 정산 / 평가
-- ---------------------------------------------------------------------
CREATE TABLE applications (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  position_id    BIGINT NOT NULL REFERENCES job_positions(id),
  schedule_id    BIGINT NOT NULL REFERENCES job_schedules(id),   -- 비정규화(충돌검사)
  posting_id     BIGINT NOT NULL REFERENCES job_postings(id),
  shoot_date     DATE   NOT NULL,                                -- 비정규화(같은 날 중복확정 방지)
  actor_user_id  BIGINT NOT NULL REFERENCES users(id),
  status         VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
                 CHECK (status IN ('APPLIED','SHORTLISTED','WAITLISTED','CONFIRMED','REJECTED',
                                   'CANCELED_BY_ACTOR','CANCELED_BY_COMPANY','EXPIRED')),
  message        VARCHAR(1000),
  decided_by     BIGINT REFERENCES users(id),
  decided_at     TIMESTAMPTZ,
  decision_reason VARCHAR(500),
  canceled_at    TIMESTAMPTZ,
  applied_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 같은 포지션에 활성 지원은 1건만 (취소 후 재지원 허용)
CREATE UNIQUE INDEX uq_applications_active ON applications (position_id, actor_user_id)
  WHERE status IN ('APPLIED','SHORTLISTED','WAITLISTED','CONFIRMED');
-- 같은 날 확정은 1건만 (이중 확정 방지) — 시간대 겹침 정책 도입 시 애플리케이션 로직으로 확장
CREATE UNIQUE INDEX uq_applications_confirmed_day ON applications (actor_user_id, shoot_date)
  WHERE status = 'CONFIRMED';
CREATE INDEX ix_applications_actor ON applications (actor_user_id, shoot_date DESC);
CREATE INDEX ix_applications_position ON applications (position_id, status);
CREATE INDEX ix_applications_posting ON applications (posting_id, status);

CREATE TABLE application_status_histories (     -- 상태 변경 감사
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id  BIGINT NOT NULL REFERENCES applications(id),
  from_status     VARCHAR(20),
  to_status       VARCHAR(20) NOT NULL,
  changed_by      BIGINT REFERENCES users(id),
  reason          VARCHAR(500),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_app_hist_app ON application_status_histories (application_id, created_at);

CREATE TABLE attendances (                      -- 출석 (확정 지원 1건당 1행)
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id  BIGINT NOT NULL UNIQUE REFERENCES applications(id),
  result          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                  CHECK (result IN ('PENDING','ATTENDED','LATE','EARLY_LEAVE','NO_SHOW','EXCUSED')),
  checkin_at      TIMESTAMPTZ,
  checkout_at     TIMESTAMPTZ,
  method          VARCHAR(20) CHECK (method IN ('QR','GPS','MANUAL')),
  latitude        NUMERIC(9,6),
  longitude       NUMERIC(9,6),
  recorded_by     BIGINT REFERENCES users(id),
  note            VARCHAR(500),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE settlements (                      -- 정산 “기록” (실제 송금은 범위 밖, 추후 확장)
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id  BIGINT NOT NULL UNIQUE REFERENCES applications(id),
  gross_amount    INT NOT NULL CHECK (gross_amount >= 0),
  tax_amount      INT NOT NULL DEFAULT 0,
  net_amount      INT NOT NULL CHECK (net_amount >= 0),
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PAID','DISPUTED')),
  due_date        DATE,
  paid_at         TIMESTAMPTZ,
  memo            VARCHAR(500),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reviews (                          -- 상호 평가 (출석 완료 건에 한해, 양쪽 작성 후 공개 = 블라인드)
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id   BIGINT NOT NULL REFERENCES applications(id),
  reviewer_user_id BIGINT NOT NULL REFERENCES users(id),
  target_type      VARCHAR(10) NOT NULL CHECK (target_type IN ('ACTOR','COMPANY')),
  target_id        BIGINT NOT NULL,              -- ACTOR → users.id, COMPANY → companies.id
  score            SMALLINT NOT NULL CHECK (score BETWEEN 1 AND 5),
  comment          VARCHAR(1000),
  is_visible       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (application_id, reviewer_user_id)
);

CREATE TABLE company_actor_blocks (             -- 업체 → 출연자 블랙리스트 (반대 방향 차단이 필요하면 actor_company_blocks 를 별도 추가)
  company_id     BIGINT NOT NULL REFERENCES companies(id),
  actor_user_id  BIGINT NOT NULL REFERENCES users(id),
  reason         VARCHAR(300),
  created_by     BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (company_id, actor_user_id)
);

CREATE TABLE bookmarks (                        -- 관심 공고
  user_id     BIGINT NOT NULL REFERENCES users(id),
  posting_id  BIGINT NOT NULL REFERENCES job_postings(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, posting_id)
);

CREATE TABLE saved_searches (                   -- 저장된 검색조건 + 신규 공고 알림
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id),
  name        VARCHAR(50) NOT NULL,
  conditions  JSONB NOT NULL,                   -- {regionIds, categoryIds, gender, minPay, keywords...}
  notify      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 7. 게시판 (공고와 분리)
-- ---------------------------------------------------------------------
CREATE TABLE boards (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code             VARCHAR(30) NOT NULL UNIQUE,   -- NOTICE, FAQ, FREE, QNA, REVIEW, ...
  name             VARCHAR(50) NOT NULL,
  type             VARCHAR(20) NOT NULL CHECK (type IN ('NOTICE','FAQ','GENERAL','QNA','INQUIRY')),
  allow_comment    BOOLEAN NOT NULL DEFAULT TRUE,
  allow_attachment BOOLEAN NOT NULL DEFAULT TRUE,
  allow_secret     BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order       INT NOT NULL DEFAULT 0,
  is_active        BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE board_permissions (                -- 게시판 × 역할 권한 → 새 역할 추가해도 스키마 변경 없음
  board_id     BIGINT NOT NULL REFERENCES boards(id),
  role_id      BIGINT NOT NULL REFERENCES roles(id),
  can_read     BOOLEAN NOT NULL DEFAULT TRUE,
  can_write    BOOLEAN NOT NULL DEFAULT FALSE,
  can_comment  BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (board_id, role_id)
);
CREATE TABLE posts (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  board_id       BIGINT NOT NULL REFERENCES boards(id),
  author_id      BIGINT NOT NULL REFERENCES users(id),
  title          VARCHAR(200) NOT NULL,
  content        TEXT NOT NULL,
  is_pinned      BOOLEAN NOT NULL DEFAULT FALSE,
  is_secret      BOOLEAN NOT NULL DEFAULT FALSE,
  status         VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('DRAFT','PUBLISHED','HIDDEN','DELETED')),
  view_count     INT NOT NULL DEFAULT 0,
  comment_count  INT NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at     TIMESTAMPTZ
);
CREATE INDEX ix_posts_board ON posts (board_id, is_pinned DESC, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_posts_title_trgm ON posts USING gin (title gin_trgm_ops);
CREATE TABLE comments (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  post_id     BIGINT NOT NULL REFERENCES posts(id),
  parent_id   BIGINT REFERENCES comments(id),      -- 1단계 대댓글
  author_id   BIGINT NOT NULL REFERENCES users(id),
  content     VARCHAR(2000) NOT NULL,
  status      VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED','HIDDEN','DELETED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at  TIMESTAMPTZ
);
CREATE INDEX ix_comments_post ON comments (post_id, created_at);

-- ---------------------------------------------------------------------
-- 8. 알림
-- ---------------------------------------------------------------------
CREATE TABLE notification_templates (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(50) NOT NULL,              -- APPLICATION_CONFIRMED, SCHEDULE_CANCELED, NEW_MATCHING_JOB…
  channel     VARCHAR(10) NOT NULL CHECK (channel IN ('INAPP','PUSH','EMAIL','SMS','KAKAO')),
  title_tpl   VARCHAR(200) NOT NULL,
  body_tpl    VARCHAR(1000) NOT NULL,
  is_active   BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (code, channel)
);
CREATE TABLE notifications (                    -- 인앱 알림함 (모든 채널의 원본)
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id),
  type        VARCHAR(50) NOT NULL,
  title       VARCHAR(200) NOT NULL,
  body        VARCHAR(1000),
  link_path   VARCHAR(300),                      -- 앱/웹 딥링크
  payload     JSONB,
  read_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE TABLE notification_deliveries (          -- 채널별 발송 결과/재시도
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  notification_id  BIGINT NOT NULL REFERENCES notifications(id),
  channel          VARCHAR(10) NOT NULL CHECK (channel IN ('PUSH','EMAIL','SMS','KAKAO')),
  status           VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
  attempt_count    SMALLINT NOT NULL DEFAULT 0,
  provider_msg_id  VARCHAR(100),
  error_message    VARCHAR(500),
  sent_at          TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_deliveries_pending ON notification_deliveries (created_at) WHERE status = 'PENDING';
CREATE TABLE notification_settings (            -- 사용자별 수신 설정
  user_id     BIGINT NOT NULL REFERENCES users(id),
  type        VARCHAR(50) NOT NULL,
  channel     VARCHAR(10) NOT NULL,
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (user_id, type, channel)
);
CREATE TABLE device_tokens (                    -- FCM / Web Push
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES users(id),
  platform     VARCHAR(10) NOT NULL CHECK (platform IN ('ANDROID','IOS','WEB')),
  token        VARCHAR(500) NOT NULL UNIQUE,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at   TIMESTAMPTZ
);
CREATE INDEX ix_device_tokens_user ON device_tokens (user_id) WHERE revoked_at IS NULL;

-- 트랜잭션 아웃박스: 지원/확정 등 도메인 트랜잭션과 알림 생성을 원자적으로 연결
CREATE TABLE outbox_events (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  aggregate_type VARCHAR(30) NOT NULL,
  aggregate_id  BIGINT NOT NULL,
  event_type    VARCHAR(50) NOT NULL,
  payload       JSONB NOT NULL,
  status        VARCHAR(10) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','DONE','FAILED')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at  TIMESTAMPTZ
);
CREATE INDEX ix_outbox_new ON outbox_events (id) WHERE status = 'NEW';

-- ---------------------------------------------------------------------
-- 9. 운영 / 감사 / 신고
-- ---------------------------------------------------------------------
CREATE TABLE reports (                          -- 신고 (게시글/댓글/공고/사용자/업체)
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reporter_id   BIGINT NOT NULL REFERENCES users(id),
  target_type   VARCHAR(20) NOT NULL CHECK (target_type IN ('POST','COMMENT','JOB_POSTING','USER','COMPANY')),
  target_id     BIGINT NOT NULL,
  reason_code   VARCHAR(30) NOT NULL,
  detail        VARCHAR(1000),
  status        VARCHAR(20) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','IN_REVIEW','RESOLVED','DISMISSED')),
  handled_by    BIGINT REFERENCES users(id),
  handled_at    TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE audit_logs (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id BIGINT REFERENCES users(id),
  action        VARCHAR(50) NOT NULL,            -- COMPANY_APPROVED, APPLICATION_CONFIRMED, USER_SUSPENDED…
  entity_type   VARCHAR(30) NOT NULL,
  entity_id     BIGINT,
  before_data   JSONB,
  after_data    JSONB,
  ip_address    INET,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_logs (entity_type, entity_id, created_at DESC);
CREATE TABLE app_settings (
  key         VARCHAR(80) PRIMARY KEY,
  value       VARCHAR(1000) NOT NULL,
  description VARCHAR(200),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 10. updated_at 트리거 일괄 부착
-- ---------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
  FOR t IN SELECT table_name FROM information_schema.columns
           WHERE table_schema = 'public' AND column_name = 'updated_at'
             AND table_name NOT IN (SELECT table_name FROM information_schema.views WHERE table_schema='public')
  LOOP
    EXECUTE format('CREATE TRIGGER trg_%s_updated BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION set_updated_at()', t, t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 11. 시드 데이터
-- ---------------------------------------------------------------------
INSERT INTO roles (code, name, description) VALUES
  ('SUPER_ADMIN','최고관리자','전체 권한'),
  ('ADMIN','관리자','회원/업체 승인, 게시판, 신고 처리'),
  ('OPERATOR','운영자(CS)','문의 응대, 게시글 관리'),
  ('COMPANY','보출 업체','공고 등록/지원자 관리'),
  ('ACTOR','출연자','공고 검색/지원');

INSERT INTO position_categories (code, name, sort_order) VALUES
  ('GENERAL','일반',1),('STUDENT','교복/학생',2),('MILITARY','군인',3),('POLICE','경찰/제복',4),
  ('PATIENT','환자/병원',5),('FOREIGNER','외국인',6),('VEHICLE','차량 지참',7),('SPECIAL','특수/분장',8);

INSERT INTO boards (code, name, type, allow_secret, sort_order) VALUES
  ('NOTICE','공지사항','NOTICE',FALSE,1),
  ('FAQ','자주 묻는 질문','FAQ',FALSE,2),
  ('FREE','자유게시판','GENERAL',FALSE,3),
  ('INQUIRY','1:1 문의','INQUIRY',TRUE,4);
