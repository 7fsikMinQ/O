-- =====================================================================
-- 보조출연 매칭 플랫폼 - PostgreSQL 16+ 스키마 (v1.3: 사용자 확정 + 외부 사실 검증 + 정산 숨김 기능 플래그 반영본)
-- 원칙
--  1) 인증(users) / 권한(roles) / 프로필(actor, company) 분리
--  2) 파일(files) 메타 1곳 + 연결은 file_attachments(다형성)
--  3) 게시판(boards/posts/comments) 은 공고(job_*) 와 완전 분리
--  4) 공고(job_postings) → 일정(job_schedules: 1시간~21일(3주) 연속 구간, 심야·숙박 포함) → 역할(job_positions) → 지원(applications)
--  5) 지원 흐름: APPLIED → (업체) SELECTED 합격 통보 → (출연자) CONFIRMED 확정. 예비합격·선착순 없음
--  6) 지원서는 증빙 문서: 정보 스냅샷 + 전자서명 + 현재 사진 + 주민번호 버전 참조
--  7) 업무 규칙은 DB 제약이 최후 방어 (정원, 활성 중복, 시간 겹침 EXCLUDE, 범위 CHECK)
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;     -- EXCLUDE 제약(시간 겹침) / 복합 gist 인덱스

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
  email              CITEXT,                              -- 출연자는 휴대폰 문자 인증 로그인이 기본이라 이메일 선택. 업체·관리자는 앱 규칙으로 필수
  password_hash      VARCHAR(100),                        -- 소셜 전용 계정이면 NULL
  name               VARCHAR(50)  NOT NULL,
  nickname           VARCHAR(50),
  phone              VARCHAR(20),                         -- 출연자는 앱 규칙으로 필수·유일
  status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','WITHDRAWN')),
  email_verified_at  TIMESTAMPTZ,
  phone_verified_at  TIMESTAMPTZ,
  last_login_at      TIMESTAMPTZ,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
  deleted_at         TIMESTAMPTZ,
  CONSTRAINT ck_users_contact CHECK (email IS NOT NULL OR phone IS NOT NULL)
);
CREATE UNIQUE INDEX uq_users_email ON users (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_phone ON users (phone) WHERE deleted_at IS NULL AND phone IS NOT NULL;

CREATE TABLE otp_challenges (                    -- 인증번호(문자 또는 이메일 채널, 가입·로그인·번호 변경). 코드·식별자는 해시만 저장
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  phone_hash   CHAR(64)    NOT NULL,                    -- HMAC(pepper, 정규화 번호)
  purpose      VARCHAR(20) NOT NULL CHECK (purpose IN ('SIGNUP','LOGIN','CHANGE_PHONE','DELETE_ACCOUNT')),
  code_hash    CHAR(64)    NOT NULL,
  attempts     INT         NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
  expires_at   TIMESTAMPTZ NOT NULL,
  consumed_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_otp_phone ON otp_challenges (phone_hash, created_at DESC);

-- 관리자 2차 비밀번호(사용자 결정 2026-10-28): 값은 DB 에 직접 심어 두고 앱·API 로는 바꿀 수 없다(UPDATE·DELETE 트리거 차단). 초기값은 BCrypt 해시.
-- 로그인 화면의 2차 칸은 관리자 계정일 때만 나타난다. 개발용 단순 장치이며 운영 오픈 전 TOTP 등 강화를 권장한다(13번).
CREATE TABLE admin_second_password (
  id          SMALLINT PRIMARY KEY CHECK (id = 1),
  secret_hash VARCHAR(100) NOT NULL
);
INSERT INTO admin_second_password (id, secret_hash) VALUES (1, '$2a$10$JPtEWVvwejJwSk6nLrC2Y.nKfvNkHNyS4yDw.Zkzg6LO3vnzkRy4a');
CREATE FUNCTION trg_admin_second_password_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'admin_second_password 는 수정·삭제할 수 없습니다'; END $$;
CREATE TRIGGER admin_second_password_immutable BEFORE UPDATE OR DELETE ON admin_second_password
  FOR EACH ROW EXECUTE FUNCTION trg_admin_second_password_immutable();

CREATE TABLE account_deletion_requests (        -- 앱 안·웹(스토어 요구) 어디서든 접수. 본인 확인 후 처리
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id       BIGINT REFERENCES users(id),
  contact_hash  CHAR(64),                              -- 로그인 없이 웹에서 요청한 경우 번호/이메일 해시
  channel       VARCHAR(10) NOT NULL CHECK (channel IN ('APP','WEB','SUPPORT')),
  status        VARCHAR(20) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','VERIFIED','DONE','REJECTED')),
  reject_reason VARCHAR(200),                          -- 진행 중 확정 건·법정 보존 등
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at  TIMESTAMPTZ,
  CHECK (user_id IS NOT NULL OR contact_hash IS NOT NULL)
);

CREATE TABLE user_auth_providers (            -- 소셜 로그인
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           BIGINT       NOT NULL REFERENCES users(id),
  provider          VARCHAR(20)  NOT NULL CHECK (provider IN ('KAKAO','GOOGLE','NAVER','APPLE')),
  provider_user_id  VARCHAR(100) NOT NULL,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_user_id)
);

CREATE TABLE roles (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(30) NOT NULL UNIQUE,
  name        VARCHAR(50) NOT NULL,
  description VARCHAR(200)
);
CREATE TABLE permissions (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(60) NOT NULL UNIQUE,
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
  token_hash  VARCHAR(100) NOT NULL UNIQUE,
  device_id   VARCHAR(100),
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE login_histories (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     BIGINT REFERENCES users(id),
  login_id    VARCHAR(254),                      -- 이메일 로그인: 실제 존재하는 계정의 시도만 기록(없는 이메일 시도는 기록하지 않음)
  success     BOOLEAN     NOT NULL,
  ip_address  INET,
  user_agent  VARCHAR(300),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_histories_user ON login_histories (user_id, created_at DESC);
CREATE INDEX ix_login_histories_lock ON login_histories (login_id, ip_address, created_at DESC);   -- (계정, IP) 단위 잠금 조회

CREATE TABLE email_verifications (               -- 이메일 소유 확인 링크(토큰은 해시만 저장, 24시간, 1회용)
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id    BIGINT      NOT NULL REFERENCES users(id),
  token_hash CHAR(64)    NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_email_verifications_user ON email_verifications (user_id, created_at DESC);

CREATE TABLE password_resets (                   -- 임시 비밀번호(기존 비밀번호는 그대로 유지, 임시 비밀번호로 로그인 후 새 비밀번호로 변경해야 완료). 해시만 저장, 30분, 1회용
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT      NOT NULL REFERENCES users(id),
  temp_hash    VARCHAR(100) NOT NULL,
  expires_at   TIMESTAMPTZ NOT NULL,
  used_at      TIMESTAMPTZ,
  requested_ip INET,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_password_resets_user ON password_resets (user_id, created_at DESC);

CREATE TABLE terms (                            -- 약관 버전 (고유식별정보·전자서명 동의 문구 포함)
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  type         VARCHAR(30) NOT NULL CHECK (type IN ('SERVICE','PRIVACY','MARKETING','LOCATION','YOUTH',
                                                    'THIRD_PARTY_PROVISION','SENSITIVE_ID','E_SIGNATURE')),
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
CREATE TABLE regions (                          -- 시/도(1) → 시/군/구(2). 전국 마스터 (시/군/구는 행정표준코드로 적재)
  id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parent_id BIGINT REFERENCES regions(id),
  code      VARCHAR(10) NOT NULL UNIQUE,
  name      VARCHAR(50) NOT NULL,
  level     SMALLINT    NOT NULL CHECK (level IN (1,2))
);

CREATE TABLE position_categories (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code       VARCHAR(30) NOT NULL UNIQUE,
  name       VARCHAR(50) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_active  BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE productions (                      -- 작품 마스터 (공고는 대부분 가제로 올라가므로 선택 사항)
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title          VARCHAR(200) NOT NULL,
  type           VARCHAR(20)  NOT NULL DEFAULT 'DRAMA'
                 CHECK (type IN ('DRAMA','MOVIE','AD','MV','OTT','VARIETY','ETC')),
  broadcaster    VARCHAR(100),
  producer_name  VARCHAR(100),
  is_confidential BOOLEAN NOT NULL DEFAULT TRUE,
  status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','HIDDEN','MERGED')),
  merged_into_id BIGINT REFERENCES productions(id),
  created_by     BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_productions_title_trgm ON productions USING gin (title gin_trgm_ops);

CREATE TABLE minimum_wages (                    -- 연도별 최저시급. 관리자가 매년 갱신 (공고 작성 시 '최저시급' 선택의 원천)
  year          SMALLINT PRIMARY KEY,
  hourly_krw    INT  NOT NULL CHECK (hourly_krw > 0),
  effective_from DATE NOT NULL,
  source_note   VARCHAR(200),
  created_by    BIGINT REFERENCES users(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 3. 파일 (메타 1곳 + 다형성 연결)
-- ---------------------------------------------------------------------
CREATE TABLE files (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id         UUID        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  uploader_user_id  BIGINT REFERENCES users(id),
  storage_provider  VARCHAR(20) NOT NULL DEFAULT 'R2' CHECK (storage_provider IN ('R2','GCS','S3','LOCAL')),
  bucket            VARCHAR(100) NOT NULL,
  object_key        VARCHAR(500) NOT NULL,
  original_name     VARCHAR(255) NOT NULL,
  content_type      VARCHAR(100) NOT NULL,
  size_bytes        BIGINT      NOT NULL CHECK (size_bytes >= 0),
  checksum_sha256   VARCHAR(64),
  width             INT,
  height            INT,
  variants          JSONB,                        -- {"thumb":"key","medium":"key"}
  status            VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','DELETED')),
  is_private        BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at        TIMESTAMPTZ,
  UNIQUE (bucket, object_key)
);
CREATE INDEX ix_files_pending ON files (created_at) WHERE status = 'PENDING';

CREATE TABLE file_attachments (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  file_id      BIGINT      NOT NULL REFERENCES files(id),
  entity_type  VARCHAR(30) NOT NULL
               CHECK (entity_type IN ('ACTOR_PROFILE','COMPANY','COMPANY_VERIFICATION','JOB_POSTING','APPLICATION',
                                      'GUARDIAN_CONSENT','JOB_BROADCAST','POST','COMMENT','REPORT','PRODUCTION')),
  entity_id    BIGINT      NOT NULL,
  usage        VARCHAR(30) NOT NULL
               CHECK (usage IN ('PROFILE_PHOTO','FULL_BODY_PHOTO','RESUME','PARENTAL_CONSENT','FAMILY_RELATION_PROOF',
                                'BIZ_LICENSE','LOGO','JOB_IMAGE','REF_IMAGE','APPLY_DOC','APPLY_PHOTO','COSTUME_PHOTO',
                                'SIGNATURE','APPLICATION_PDF','OFFER_LETTER','ATTACHMENT','EVIDENCE')),
  sort_order   INT         NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (file_id, entity_type, entity_id, usage)
);
CREATE INDEX ix_file_attachments_entity ON file_attachments (entity_type, entity_id, usage, sort_order);

-- ---------------------------------------------------------------------
-- 4. 프로필 (출연자 / 업체) · 구독 구조
-- ---------------------------------------------------------------------
CREATE TABLE actor_profiles (                   -- 1:1 users
  user_id        BIGINT PRIMARY KEY REFERENCES users(id),
  allow_star_alerts BOOLEAN NOT NULL DEFAULT TRUE,  -- ⭐로 저장한 업체의 새 공고 알림 받기(끌 수 있음)
  birth_date     DATE        NOT NULL,
  gender         VARCHAR(10) NOT NULL CHECK (gender IN ('MALE','FEMALE','OTHER')),
  height_cm      SMALLINT    NOT NULL CHECK (height_cm BETWEEN 50 AND 250),
  weight_kg      SMALLINT    NOT NULL CHECK (weight_kg BETWEEN 20 AND 300),
  top_size       VARCHAR(10),
  bottom_size    VARCHAR(10),
  shoe_size      SMALLINT,                        -- mm
  hair_length    VARCHAR(10) CHECK (hair_length IN ('SHAVED','SHORT','MEDIUM','LONG')),
  hair_style     VARCHAR(100),                    -- 색·염색 등 자유 기재
  has_tattoo     BOOLEAN     NOT NULL DEFAULT FALSE,
  can_drive      BOOLEAN     NOT NULL DEFAULT FALSE,
  has_vehicle    BOOLEAN     NOT NULL DEFAULT FALSE,
  region_id      BIGINT REFERENCES regions(id),   -- 거주 시/군/구 (필터·통계)
  home_postal_code   VARCHAR(10),
  home_address       VARCHAR(200),                -- 집 주소 (인원 정보표 필수 항목, 합격 전 업체에는 시/구 단위만)
  home_address_detail VARCHAR(200),
  default_departure_location VARCHAR(200),        -- 기본 집합지(탑승 희망지)
  introduction   VARCHAR(1000),
  experience_note VARCHAR(1000),
  tags           JSONB       NOT NULL DEFAULT '[]',
  is_searchable  BOOLEAN     NOT NULL DEFAULT TRUE,
  history_disclosure_consented_at TIMESTAMPTZ,    -- 역대 합격/지원 이력 업체 공개 동의
  -- 활동 횟수 캐시 (원천: attendances / actor_penalty_events). 점수제는 사용하지 않고 횟수만 업체에 노출
  confirmed_count INT        NOT NULL DEFAULT 0,
  attended_count INT         NOT NULL DEFAULT 0,
  noshow_count   INT         NOT NULL DEFAULT 0,
  penalty_count  INT         NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_actor_spec ON actor_profiles (gender, birth_date, height_cm, weight_kg);
CREATE INDEX ix_actor_tags ON actor_profiles USING gin (tags jsonb_path_ops);

-- 민감정보: 앱 계층 AES-256-GCM(키는 DB 밖), key_version 으로 회전
CREATE TABLE actor_payout_accounts (            -- 프로필 기본 계좌 (지원 시 불러오기 / 다른 계좌 직접 입력도 가능)
  user_id            BIGINT PRIMARY KEY REFERENCES actor_profiles(user_id),
  bank_code          VARCHAR(10)  NOT NULL,
  bank_name          VARCHAR(50)  NOT NULL,
  account_number_enc VARCHAR(300) NOT NULL,
  account_holder     VARCHAR(50)  NOT NULL,
  key_version        SMALLINT     NOT NULL DEFAULT 1,
  consented_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE actor_tax_identities (             -- 주민등록번호 (원천징수·지급명세서용, 첫 지원 전 필수). 버전 이력으로 보관 → 지원서가 당시 버전을 참조
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id        BIGINT       NOT NULL REFERENCES actor_profiles(user_id),
  rrn_enc        VARCHAR(300) NOT NULL,           -- AES-256-GCM
  rrn_hmac       CHAR(64)     NOT NULL,           -- HMAC-SHA256(서버 비밀 pepper) : 평문 복원 불가, 중복 가입(노쇼 이력 세탁) 탐지용
  key_version    SMALLINT     NOT NULL DEFAULT 1,
  consent_term_id BIGINT REFERENCES terms(id),    -- 고유식별정보 처리 동의 약관 버전
  consented_at   TIMESTAMPTZ  NOT NULL,
  is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
  superseded_at  TIMESTAMPTZ,
  purge_after    DATE,                            -- 보존기한 경과 후 파기 대상
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_tax_identity_active_user ON actor_tax_identities (user_id) WHERE is_active;
CREATE UNIQUE INDEX uq_tax_identity_active_hmac ON actor_tax_identities (rrn_hmac) WHERE is_active;

CREATE TABLE actor_penalty_events (             -- 노쇼 횟수 / 취소 패널티 원장. 점수제 아님. 공고당 종류별 1건만 유효
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id        BIGINT NOT NULL REFERENCES actor_profiles(user_id),
  posting_id     BIGINT NOT NULL,                 -- FK 는 아래 ALTER (job_postings 정의 이후)
  application_id BIGINT,
  kind           VARCHAR(20) NOT NULL CHECK (kind IN ('NO_SHOW','CANCEL_PENALTY')),
  note           VARCHAR(300),
  decided_by     BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  voided_at      TIMESTAMPTZ,                     -- 오기록·소명 인정 시 무효화
  voided_by      BIGINT REFERENCES users(id)
);
CREATE UNIQUE INDEX uq_penalty_per_posting ON actor_penalty_events (user_id, posting_id, kind) WHERE voided_at IS NULL;
CREATE INDEX ix_penalty_user ON actor_penalty_events (user_id, created_at DESC);

CREATE TABLE guardian_consents (                -- 미성년 법정대리인 동의 (현재 미성년 지원 차단. 구조만 선반영, 설정 minor_apply_enabled)
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id    BIGINT NOT NULL REFERENCES actor_profiles(user_id),
  guardian_name    VARCHAR(50)  NOT NULL,
  guardian_phone   VARCHAR(20)  NOT NULL,
  relationship     VARCHAR(20)  NOT NULL DEFAULT 'PARENT' CHECK (relationship IN ('PARENT','GUARDIAN')),
  scope            VARCHAR(20)  NOT NULL DEFAULT 'GENERAL' CHECK (scope IN ('GENERAL','PER_APPLICATION')),
  application_id   BIGINT,
  status           VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','VERIFIED','REJECTED','EXPIRED')),
  valid_until      DATE,
  verified_by      BIGINT REFERENCES users(id),
  verified_at      TIMESTAMPTZ,
  reject_reason    VARCHAR(300),
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_guardian_consents_actor ON guardian_consents (actor_user_id, status);

CREATE TABLE companies (                        -- 보출 업체
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name            VARCHAR(100) NOT NULL,
  biz_reg_no      VARCHAR(12)  NOT NULL,
  representative  VARCHAR(50)  NOT NULL,
  phone           VARCHAR(20),
  address         VARCHAR(200),
  region_id       BIGINT REFERENCES regions(id),
  introduction    VARCHAR(2000),
  status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','APPROVED','REJECTED','SUSPENDED')),
  approved_by     BIGINT REFERENCES users(id),
  approved_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  deleted_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_companies_biz ON companies (biz_reg_no) WHERE deleted_at IS NULL;

CREATE TABLE company_members (                  -- 한 사람이 여러 업체에 소속 가능 (프리랜서 반장)
  company_id      BIGINT      NOT NULL REFERENCES companies(id),
  user_id         BIGINT      NOT NULL REFERENCES users(id),
  member_role     VARCHAR(20) NOT NULL DEFAULT 'STAFF'
                  CHECK (member_role IN ('OWNER','MANAGER','SCHEDULER','FIELD_LEADER','STAFF')),
                  -- OWNER 대표 / MANAGER 팀장 / SCHEDULER 스케줄러 담당(1~4명, 취소 패널티 판정) / FIELD_LEADER 현장반장 / STAFF
  department_name VARCHAR(50),
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('INVITED','ACTIVE','REMOVED')),
  joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (company_id, user_id)
);
CREATE INDEX ix_company_members_user ON company_members (user_id);

CREATE TABLE company_verifications (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id    BIGINT NOT NULL REFERENCES companies(id),
  status        VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','APPROVED','REJECTED')),
  reject_reason VARCHAR(500),
  reviewed_by   BIGINT REFERENCES users(id),
  submitted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_at   TIMESTAMPTZ
);

CREATE TABLE company_starred_actors (          -- 업체의 '⭐ 출연자' 명단 (이전 합격자 중 저장, 새 공고 먼저 알림). 구독 후보 기능
  company_id     BIGINT NOT NULL REFERENCES companies(id),
  actor_user_id  BIGINT NOT NULL REFERENCES users(id),
  note           VARCHAR(200),                          -- 업체 내부 메모(출연자에게 비공개)
  created_by     BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (company_id, actor_user_id)
);

CREATE TABLE company_actor_blocks (             -- 업체 → 출연자 비공개 차단
  company_id     BIGINT NOT NULL REFERENCES companies(id),
  actor_user_id  BIGINT NOT NULL REFERENCES users(id),
  reason         VARCHAR(300),
  created_by     BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (company_id, actor_user_id)
);

-- [수익화 확장 대비] 지금은 FREE + subscription_enforcement=false → 전 기능 무료
CREATE TABLE subscription_plans (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code              VARCHAR(30)  NOT NULL UNIQUE,
  name              VARCHAR(50)  NOT NULL,
  monthly_price_krw INT          NOT NULL DEFAULT 0,
  is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TABLE plan_features (
  plan_id        BIGINT       NOT NULL REFERENCES subscription_plans(id) ON DELETE CASCADE,
  feature_code   VARCHAR(50)  NOT NULL,           -- VIEW_ACTOR_APPLICATIONS, TALENT_SEARCH, URGENT_TOP, ...
  enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
  limit_value    INT,
  PRIMARY KEY (plan_id, feature_code)
);
CREATE TABLE company_subscriptions (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id  BIGINT      NOT NULL REFERENCES companies(id),
  plan_id     BIGINT      NOT NULL REFERENCES subscription_plans(id),
  status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXPIRED','CANCELED')),
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_company_subscription_active ON company_subscriptions (company_id) WHERE status = 'ACTIVE';
CREATE TABLE usage_counters (
  company_id   BIGINT      NOT NULL REFERENCES companies(id),
  feature_code VARCHAR(50) NOT NULL,
  period_month CHAR(7)     NOT NULL,
  used_count   INT         NOT NULL DEFAULT 0 CHECK (used_count >= 0),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (company_id, feature_code, period_month)
);

-- ---------------------------------------------------------------------
-- 5. 공고 (핵심) posting → schedule(일정 구간) → position(역할)
-- ---------------------------------------------------------------------
CREATE TABLE job_postings (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id        BIGINT NOT NULL REFERENCES companies(id),
  production_id     BIGINT REFERENCES productions(id),          -- 실제 작품(내부용, 선택)
  title             VARCHAR(200) NOT NULL,                      -- 공개 제목 = 가제 (대부분 비공개 현장)
  description       TEXT,
  status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','OPEN','CLOSED','CANCELED','COMPLETED')),
  is_urgent         BOOLEAN NOT NULL DEFAULT FALSE,             -- (유료 상단 노출 후보. 현재는 무료)
  star_head_start_minutes SMALLINT CHECK (star_head_start_minutes BETWEEN 0 AND 1440),  -- ⭐ 출연자 우선 알림 시간(분). NULL=설정값(기본 1분). 직접 지정은 구독 기능(STAR_HEAD_START_CUSTOM)
  conceal_details   BOOLEAN NOT NULL DEFAULT TRUE,              -- 기본값 TRUE: 가제·지역·시간·역할만 공개, 상세는 합격자에게
  contact_phone     VARCHAR(20),
  apply_deadline_at TIMESTAMPTZ,
  first_shoot_date  DATE,                                       -- 일정 중 최소/최대 날짜 (앱이 갱신, 목록 정렬용)
  last_shoot_date   DATE,
  view_count        INT NOT NULL DEFAULT 0,
  published_at      TIMESTAMPTZ,
  closed_at         TIMESTAMPTZ,
  created_by        BIGINT NOT NULL REFERENCES users(id),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at        TIMESTAMPTZ
);
CREATE INDEX ix_job_postings_company ON job_postings (company_id, status);
CREATE INDEX ix_job_postings_open_date ON job_postings (first_shoot_date) WHERE status = 'OPEN' AND deleted_at IS NULL;
CREATE INDEX ix_job_postings_title_trgm ON job_postings USING gin (title gin_trgm_ops);

ALTER TABLE actor_penalty_events ADD CONSTRAINT fk_penalty_posting FOREIGN KEY (posting_id) REFERENCES job_postings(id);

CREATE TABLE job_schedules (                    -- 연속 근무 구간. 1시간 ~ 21일(3주, 숙박·심야·자정 넘김 포함)
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  posting_id           BIGINT NOT NULL REFERENCES job_postings(id),
  start_at             TIMESTAMPTZ NOT NULL,                    -- 집합 시각
  end_at               TIMESTAMPTZ NOT NULL,                    -- 종료 예정
  start_date           DATE GENERATED ALWAYS AS ((start_at AT TIME ZONE 'Asia/Seoul')::date) STORED,
  end_date             DATE GENERATED ALWAYS AS (((end_at AT TIME ZONE 'Asia/Seoul') - interval '1 second')::date) STORED,   -- 종료 정각(00:00)이 다음 날로 잡히지 않게 KST 로컬 시각에서 1초 감산(immutable)
  gather_tolerance_minutes INT NOT NULL DEFAULT 120 CHECK (gather_tolerance_minutes BETWEEN 0 AND 720),  -- 집합 시각 ±(기본 2시간) — 화면엔 "예상, 변동 가능"으로만 표시
  end_tolerance_minutes    INT NOT NULL DEFAULT 240 CHECK (end_tolerance_minutes BETWEEN 0 AND 1440),     -- 종료 시각 ±(기본 4시간), 연장 가능
  is_lodging           BOOLEAN NOT NULL DEFAULT FALSE,          -- 숙박 일정 (2박3일, 5박6일 …)
  region_id            BIGINT REFERENCES regions(id),
  shooting_location    VARCHAR(255),                            -- 실제 촬영지 (비공개 현장이면 합격자에게만)
  meeting_place        VARCHAR(200),                            -- 집합지
  meeting_address      VARCHAR(300),
  latitude             NUMERIC(9,6),
  longitude            NUMERIC(9,6),
  field_leader_user_id BIGINT REFERENCES users(id),             -- 담당 현장반장/팀장
  note                 VARCHAR(1000),
  status               VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('OPEN','CLOSED','CANCELED','POSTPONED','COMPLETED')),
  postponed_from_schedule_id BIGINT REFERENCES job_schedules(id),  -- 연기로 새로 만들어진 일정이면 원본
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_schedule_window CHECK (end_at >= start_at + interval '1 hour' AND end_at <= start_at + interval '21 days')
);
CREATE INDEX ix_job_schedules_dates ON job_schedules (start_date, end_date) WHERE status IN ('OPEN','CLOSED');
CREATE INDEX ix_job_schedules_posting ON job_schedules (posting_id);
CREATE INDEX ix_job_schedules_range ON job_schedules USING gist (tstzrange(start_at, end_at));

CREATE TABLE job_positions (                    -- 일정 내 모집 단위(역할). 예비·선착순 없음 (업체가 보고 선발)
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  schedule_id          BIGINT NOT NULL REFERENCES job_schedules(id),
  posting_id           BIGINT NOT NULL REFERENCES job_postings(id),
  category_id          BIGINT REFERENCES position_categories(id),
  title                VARCHAR(100) NOT NULL,                   -- "클럽 20대 남녀", "정장 형사", "행인"
  -- 역할별 시간/집합지 override (NULL 이면 일정 값)
  start_at             TIMESTAMPTZ,
  end_at               TIMESTAMPTZ,
  meeting_place        VARCHAR(200),
  meeting_address      VARCHAR(300),
  -- 인원 (정원 = headcount. 선발 통보 중 + 출연자 확정 합계가 정원을 넘지 못함)
  headcount            INT  NOT NULL CHECK (headcount > 0),
  selected_count       INT  NOT NULL DEFAULT 0 CHECK (selected_count >= 0),   -- SELECTED(합격 통보, 출연자 확정 대기)
  confirmed_count      INT  NOT NULL DEFAULT 0 CHECK (confirmed_count >= 0),  -- CONFIRMED(출연자 확정 완료)
  applied_count        INT  NOT NULL DEFAULT 0,                               -- 지원자 수 캐시
  -- 필수 필터(지원 자격). 사진은 지원 시 현재 사진 첨부가 항상 필수
  gender_req           VARCHAR(10) NOT NULL DEFAULT 'ANY' CHECK (gender_req IN ('ANY','MALE','FEMALE')),
  birth_year_min       SMALLINT,
  birth_year_max       SMALLINT,
  height_min           SMALLINT,
  height_max           SMALLINT,
  weight_min           SMALLINT,                                -- 체형 조건(몸무게 범위)
  weight_max           SMALLINT,
  hair_lengths         JSONB,                                   -- 허용 머리 길이 ["SHORT","MEDIUM"] / NULL = 무관
  tattoo_req           VARCHAR(10) NOT NULL DEFAULT 'ANY' CHECK (tattoo_req IN ('ANY','NONE')),   -- NONE = 문신 없는 사람만
  allow_minor          BOOLEAN NOT NULL DEFAULT FALSE,
  -- 보수: 시급제. MIN_WAGE 선택 시 해당 연도 최저시급 스냅샷. 9시간 이내 근무는 최저시급×8시간 고정, 초과 시 시급×근무시간
  wage_mode            VARCHAR(10) NOT NULL DEFAULT 'MIN_WAGE' CHECK (wage_mode IN ('MIN_WAGE','CUSTOM')),
  hourly_wage_krw      INT  NOT NULL CHECK (hourly_wage_krw > 0),
  min_wage_year        SMALLINT,
  pay_note             VARCHAR(1000),                           -- 비고: 식대(아침/점심/저녁)·교통·연장 안내 등 자유 기재
  dress_code           VARCHAR(300),
  requirements         VARCHAR(1000),
  status               VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED','CANCELED')),
  cloned_from_position_id BIGINT REFERENCES job_positions(id),  -- 일정 연기 시 원본 역할 매핑
  version              BIGINT NOT NULL DEFAULT 0,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_position_capacity CHECK (selected_count + confirmed_count <= headcount),
  CONSTRAINT ck_position_window CHECK ((start_at IS NULL AND end_at IS NULL)
        OR (start_at IS NOT NULL AND end_at IS NOT NULL AND end_at >= start_at + interval '1 hour' AND end_at <= start_at + interval '21 days')),
  CONSTRAINT ck_wage_mode CHECK (wage_mode <> 'MIN_WAGE' OR min_wage_year IS NOT NULL),
  CHECK (birth_year_min IS NULL OR birth_year_max IS NULL OR birth_year_min <= birth_year_max),
  CHECK (height_min IS NULL OR height_max IS NULL OR height_min <= height_max),
  CHECK (weight_min IS NULL OR weight_max IS NULL OR weight_min <= weight_max)
);
CREATE INDEX ix_job_positions_schedule ON job_positions (schedule_id);
CREATE INDEX ix_job_positions_posting ON job_positions (posting_id);

-- ---------------------------------------------------------------------
-- 6. 지원 / 선발 / 충돌 / 취소 판정 / 메시지 / 출결 / 정산
-- ---------------------------------------------------------------------
CREATE TABLE applications (                     -- 지원서 = 증빙 문서 (정보 스냅샷 + 전자서명 + 현재 사진 + 주민번호 버전 참조)
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  position_id    BIGINT NOT NULL REFERENCES job_positions(id),
  schedule_id    BIGINT NOT NULL REFERENCES job_schedules(id),
  posting_id     BIGINT NOT NULL REFERENCES job_postings(id),
  actor_user_id  BIGINT NOT NULL REFERENCES users(id),
  status         VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
                 CHECK (status IN ('APPLIED','SELECTED','CONFIRMED','REJECTED','AUTO_REJECTED',
                                   'CANCELED_BY_ACTOR','CANCELED_BY_COMPANY','CANCELED_BY_SYSTEM','EXPIRED')),
                 -- APPLIED 지원 → SELECTED 업체 합격 통보 → CONFIRMED 출연자 확정.
                 -- AUTO_REJECTED: 통보 없이 마감 (마감 후에도 업체가 가용 확인 뒤 SELECTED 로 재선발 가능)
                 -- EXPIRED: 합격 통보 후 출연자가 기한 내 응답하지 않음 / 일정 종료
  -- 근무 구간 스냅샷 (역할 override ?? 일정)
  work_start_at  TIMESTAMPTZ NOT NULL,
  work_end_at    TIMESTAMPTZ NOT NULL,
  start_date     DATE GENERATED ALWAYS AS ((work_start_at AT TIME ZONE 'Asia/Seoul')::date) STORED,
  reserved_range TSTZRANGE,                       -- SELECTED/CONFIRMED 시 [시작-버퍼, 종료+버퍼) : 겹침 판정·가용 판단 기준
  -- ▼ 지원 시점 정보 스냅샷 (인원 정보표 원천). 주민번호는 복제하지 않고 당시 버전(tax_identity_id)을 참조
  applicant_name     VARCHAR(50) NOT NULL,
  applicant_phone    VARCHAR(20) NOT NULL,        -- 업체는 지원 단계부터 열람 가능
  departure_location VARCHAR(200),                -- 집합지(탑승 희망지)
  profile_snapshot   JSONB NOT NULL,              -- 성별·출생일·키·몸무게·사이즈·머리·문신·집주소 등 지원 당시 값
  tax_identity_id    BIGINT REFERENCES actor_tax_identities(id),
  bank_code          VARCHAR(10),
  bank_name          VARCHAR(50),
  account_number_enc VARCHAR(300),                -- 프로필 계좌 불러오기 또는 직접 입력한 계좌
  account_holder     VARCHAR(50),
  message            VARCHAR(1000),
  -- ▼ 전자서명 증빙 (서명 이미지는 file_attachments: SIGNATURE, 현재 사진: APPLY_PHOTO)
  signed_at          TIMESTAMPTZ,
  signed_ip          INET,
  signed_user_agent  VARCHAR(300),
  signed_terms_version VARCHAR(20),
  snapshot_hash      CHAR(64),                    -- SHA-256(지원서 정규화 JSON + 서명 해시): 사후 변조 탐지
  -- ▼ 선발·확정 흐름
  selected_by        BIGINT REFERENCES users(id),
  selected_at        TIMESTAMPTZ,
  response_due_at    TIMESTAMPTZ,                 -- 합격 통보 후 출연자 확정 기한 (경과 시 EXPIRED)
  confirmed_at       TIMESTAMPTZ,
  conflict_override  BOOLEAN NOT NULL DEFAULT FALSE,   -- 출연자가 경고를 보고 겹치는 일정을 알면서 확정한 경우 (EXCLUDE 대상에서 제외, 충돌 추적)
  condition_snapshot JSONB,                       -- 합격 통보 시점 조건(시급·집합·의상) 스냅샷
  moved_from_application_id BIGINT REFERENCES applications(id),   -- 일정 연기로 이동된 지원이면 원본
  penalty_exempt     BOOLEAN NOT NULL DEFAULT FALSE,              -- 연기·업체 사정 취소 등 패널티 면제
  departure_requested_at TIMESTAMPTZ,             -- 집합 2시간 전 '참석 확인' 요청
  departure_confirmed_at TIMESTAMPTZ,             -- 출연자가 참석 확인
  canceled_at        TIMESTAMPTZ,
  cancel_reason      VARCHAR(500),                -- 확정 후 출연자 취소 사유 (스케줄러 판정에 사용)
  applied_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_selected_has_range CHECK (status NOT IN ('SELECTED','CONFIRMED') OR reserved_range IS NOT NULL),
  CONSTRAINT ck_work_window CHECK (work_end_at > work_start_at),
  -- 같은 출연자의 확정 구간이 겹치면 DB가 거부. 출연자가 경고를 확인하고 명시적으로 진행(conflict_override)한 행만 예외
  CONSTRAINT ex_applications_no_overlap EXCLUDE USING gist (actor_user_id WITH =, reserved_range WITH &&)
    WHERE (status = 'CONFIRMED' AND NOT conflict_override)
);
CREATE UNIQUE INDEX uq_applications_active ON applications (position_id, actor_user_id)
  WHERE status IN ('APPLIED','SELECTED','CONFIRMED');
CREATE INDEX ix_applications_actor_history ON applications (actor_user_id, status, start_date DESC);
CREATE INDEX ix_applications_position ON applications (position_id, status);
CREATE INDEX ix_applications_posting ON applications (posting_id, status);
CREATE INDEX ix_applications_reserved ON applications USING gist (actor_user_id, reserved_range) WHERE status IN ('SELECTED','CONFIRMED');
CREATE INDEX ix_applications_departure ON applications (work_start_at) WHERE status = 'CONFIRMED' AND departure_confirmed_at IS NULL;
CREATE INDEX ix_applications_response_due ON applications (response_due_at) WHERE status = 'SELECTED';

ALTER TABLE guardian_consents ADD CONSTRAINT fk_guardian_application FOREIGN KEY (application_id) REFERENCES applications(id);
ALTER TABLE actor_penalty_events ADD CONSTRAINT fk_penalty_application FOREIGN KEY (application_id) REFERENCES applications(id);

CREATE TABLE application_status_histories (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id  BIGINT NOT NULL REFERENCES applications(id),
  from_status     VARCHAR(20),
  to_status       VARCHAR(20) NOT NULL,
  changed_by      BIGINT REFERENCES users(id),
  reason          VARCHAR(500),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_app_hist_app ON application_status_histories (application_id, created_at);

CREATE TABLE schedule_conflicts (               -- 출연자가 겹치는 일정을 알고 둘 다 확정한 경우: 양쪽 업체 알림 + 출연자에게 확인할 때까지 매시간 경고
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id     BIGINT NOT NULL REFERENCES users(id),
  application_a_id  BIGINT NOT NULL REFERENCES applications(id),   -- 먼저 확정된 건
  application_b_id  BIGINT NOT NULL REFERENCES applications(id),   -- 겹침을 감수하고 확정한 건
  detected_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_alert_at     TIMESTAMPTZ,
  alert_count       INT NOT NULL DEFAULT 0,
  acknowledged_at   TIMESTAMPTZ,                  -- 출연자가 '확인'을 누르면 반복 경고 중지
  resolved_at       TIMESTAMPTZ,
  resolution        VARCHAR(20) CHECK (resolution IN ('ACK_ONLY','CANCELED_A','CANCELED_B')),
  CHECK (application_a_id <> application_b_id),
  UNIQUE (application_a_id, application_b_id)
);
CREATE INDEX ix_conflicts_open ON schedule_conflicts (last_alert_at) WHERE acknowledged_at IS NULL AND resolved_at IS NULL;

CREATE TABLE cancellation_reviews (             -- 확정 후 출연자 취소 → 업체 스케줄러 담당(1~4명)에게 알림 → 패널티 판정
  application_id  BIGINT PRIMARY KEY REFERENCES applications(id),
  company_id      BIGINT NOT NULL REFERENCES companies(id),
  posting_id      BIGINT NOT NULL REFERENCES job_postings(id),
  actor_user_id   BIGINT NOT NULL REFERENCES users(id),
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PENALIZED','CLEARED')),
  decided_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cancel_reviews_pending ON cancellation_reviews (company_id, created_at) WHERE status = 'PENDING';
CREATE TABLE cancellation_review_votes (        -- 한 명이라도 PENALTY 면 패널티 1건(공고당 1회, uq_penalty_per_posting 이 보장)
  application_id     BIGINT NOT NULL REFERENCES cancellation_reviews(application_id),
  scheduler_user_id  BIGINT NOT NULL REFERENCES users(id),
  vote               VARCHAR(12) NOT NULL CHECK (vote IN ('PENALTY','NO_PENALTY')),
  voted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (application_id, scheduler_user_id)
);

CREATE TABLE job_broadcasts (                   -- 단체 알림/메시지 발송 원장 (합격자 전체, 미확인자, 마감 후 가용 지원자, 임의 선택)
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id      BIGINT NOT NULL REFERENCES companies(id),
  posting_id      BIGINT NOT NULL REFERENCES job_postings(id),
  schedule_id     BIGINT REFERENCES job_schedules(id),
  position_id     BIGINT REFERENCES job_positions(id),
  sender_user_id  BIGINT NOT NULL REFERENCES users(id),
  target_filter   VARCHAR(30) NOT NULL DEFAULT 'ALL_CONFIRMED'
                  CHECK (target_filter IN ('ALL_SELECTED','ALL_CONFIRMED','DEPARTURE_UNCONFIRMED','CLOSED_AVAILABLE','CUSTOM')),
  title           VARCHAR(200)  NOT NULL,
  message_body    VARCHAR(2000) NOT NULL,
  channels        JSONB NOT NULL DEFAULT '["INAPP","PUSH"]',
  recipient_count INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_job_broadcasts_posting ON job_broadcasts (posting_id, created_at DESC);

CREATE TABLE actor_company_blocks (             -- 출연자가 특정 업체의 메시지를 차단 (스토어 UGC 정책 대응)
  actor_user_id BIGINT NOT NULL REFERENCES users(id),
  company_id    BIGINT NOT NULL REFERENCES companies(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (actor_user_id, company_id)
);

CREATE TABLE message_threads (                  -- 업체 ↔ 출연자 대화. 시작은 업체만 가능, 출연자는 답장만 (출연자 선발송 불가)
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id          BIGINT NOT NULL REFERENCES companies(id),
  actor_user_id       BIGINT NOT NULL REFERENCES users(id),
  posting_id          BIGINT REFERENCES job_postings(id),
  broadcast_id        BIGINT REFERENCES job_broadcasts(id),
  started_by_user_id  BIGINT NOT NULL REFERENCES users(id),     -- 반드시 업체 구성원
  subject             VARCHAR(200),
  last_message_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_threads_actor ON message_threads (actor_user_id, last_message_at DESC);
CREATE INDEX ix_threads_company ON message_threads (company_id, posting_id, last_message_at DESC);
CREATE TABLE messages (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  thread_id       BIGINT NOT NULL REFERENCES message_threads(id),
  sender_user_id  BIGINT NOT NULL REFERENCES users(id),
  sender_side     VARCHAR(8) NOT NULL CHECK (sender_side IN ('COMPANY','ACTOR')),
  body            VARCHAR(2000) NOT NULL,
  read_at         TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_messages_thread ON messages (thread_id, created_at);
CREATE TABLE job_broadcast_recipients (
  broadcast_id    BIGINT NOT NULL REFERENCES job_broadcasts(id),
  application_id  BIGINT NOT NULL REFERENCES applications(id),
  actor_user_id   BIGINT NOT NULL REFERENCES users(id),
  thread_id       BIGINT REFERENCES message_threads(id),
  delivered_at    TIMESTAMPTZ,                          -- 앱/푸시 전달 시각
  read_at         TIMESTAMPTZ,                          -- 출연자가 열어 본 시각 (읽음 확인)
  reminded_at     TIMESTAMPTZ,                          -- 미읽음 재안내 마지막 시각
  reminder_count  INT NOT NULL DEFAULT 0 CHECK (reminder_count BETWEEN 0 AND 5),
  PRIMARY KEY (broadcast_id, application_id)
);

CREATE TABLE attendance_sessions (              -- 팀장/담당 반장이 '출근 열기/퇴근 열기'를 눌러야 출연자 앱에 개인 QR 이 나타남 (대리 출석 방지)
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  schedule_id   BIGINT NOT NULL REFERENCES job_schedules(id),
  work_date     DATE   NOT NULL,                  -- 숙박 등 다일 일정은 날짜별 세션
  kind          VARCHAR(8) NOT NULL CHECK (kind IN ('CHECKIN','CHECKOUT')),
  opened_by     BIGINT NOT NULL REFERENCES users(id),
  opened_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at     TIMESTAMPTZ,
  nonce         VARCHAR(64) NOT NULL            -- QR 토큰 HMAC 입력(세션별 비밀). 토큰은 인원별·수초 단위 회전
);
CREATE UNIQUE INDEX uq_attendance_session_open ON attendance_sessions (schedule_id, work_date, kind) WHERE closed_at IS NULL;

CREATE TABLE attendances (                      -- 지원(확정) × 근무일 1행
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id  BIGINT NOT NULL REFERENCES applications(id),
  work_date       DATE   NOT NULL,
  result          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                  CHECK (result IN ('PENDING','ATTENDED','LATE','EARLY_LEAVE','NO_SHOW','EXCUSED')),
  checkin_at      TIMESTAMPTZ,
  checkout_at     TIMESTAMPTZ,
  checkin_session_id  BIGINT REFERENCES attendance_sessions(id),
  checkout_session_id BIGINT REFERENCES attendance_sessions(id),
  method_in       VARCHAR(8) CHECK (method_in IN ('QR','MANUAL')),
  method_out      VARCHAR(8) CHECK (method_out IN ('QR','MANUAL')),
  worked_minutes  INT NOT NULL DEFAULT 0 CHECK (worked_minutes >= 0),
  recorded_by     BIGINT REFERENCES users(id),
  note            VARCHAR(500),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (application_id, work_date)
);

CREATE TABLE settlements (                      -- 정산 기록 (송금은 업체가 직접). 세금 유형은 세무 확인 후 선택
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id  BIGINT NOT NULL UNIQUE REFERENCES applications(id),
  gross_amount    INT NOT NULL DEFAULT 0 CHECK (gross_amount >= 0),
  tax_type        VARCHAR(20) NOT NULL DEFAULT 'BUSINESS_3_3' CHECK (tax_type IN ('BUSINESS_3_3','DAILY_WAGE','NONE')),
  tax_amount      INT NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),         -- 소득세
  local_tax_amount INT NOT NULL DEFAULT 0 CHECK (local_tax_amount >= 0),  -- 지방소득세
  net_amount      INT NOT NULL DEFAULT 0 CHECK (net_amount >= 0),
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PAID','DISPUTED')),
  due_date        DATE,
  paid_at         TIMESTAMPTZ,
  paid_by         BIGINT REFERENCES users(id),         -- 업체가 '지급했다'고 직접 확인한 사람 (플랫폼은 송금하지 않음)
  payment_reference VARCHAR(100),                      -- 업체 이체 참조(선택)
  holder_name_matched BOOLEAN,                         -- 예금주=지원자 본인 이름 일치 여부(불일치 경고용)
  payout_batch_id BIGINT,                              -- FK 는 payout_batches 생성 뒤 추가
  memo            VARCHAR(500),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE settlement_items (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  settlement_id  BIGINT NOT NULL REFERENCES settlements(id) ON DELETE CASCADE,
  item_type      VARCHAR(20) NOT NULL CHECK (item_type IN ('BASE','OVERTIME','NIGHT','MEAL','TRANSPORT','BONUS','DEDUCTION')),
  amount         INT NOT NULL,
  quantity       NUMERIC(6,2),
  memo           VARCHAR(200)
);
CREATE INDEX ix_settlement_items ON settlement_items (settlement_id);

CREATE TABLE export_templates (                 -- 엑셀 양식: 인원 정보표 / 은행 대량이체(은행별) / 원천징수 자료
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(40) NOT NULL UNIQUE,
  name        VARCHAR(100) NOT NULL,
  kind        VARCHAR(20) NOT NULL CHECK (kind IN ('ROSTER','BANK_TRANSFER','WITHHOLDING')),
  bank_code   VARCHAR(10),
  columns     JSONB NOT NULL,                     -- [{header, source, format}] 컬럼 정의 (은행 양식은 실제 양식 확인 후 추가)
  is_active   BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE payout_batches (                   -- 이체 파일 묶음 (숨김 기능 BANK_TRANSFER_FILE). 파일 생성·지급 확인 기록만, 플랫폼은 송금 실행 ✕
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id   BIGINT NOT NULL REFERENCES companies(id),
  posting_id   BIGINT REFERENCES job_postings(id),
  schedule_id  BIGINT REFERENCES job_schedules(id),
  template_code VARCHAR(40) REFERENCES export_templates(code),
  item_count   INT NOT NULL DEFAULT 0 CHECK (item_count >= 0),
  total_amount BIGINT NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
  file_sha256  CHAR(64),
  status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','EXPORTED','PAID_CONFIRMED','VOID')),
  exported_by  BIGINT REFERENCES users(id),
  exported_at  TIMESTAMPTZ,
  confirmed_by BIGINT REFERENCES users(id),
  confirmed_at TIMESTAMPTZ,
  note         VARCHAR(300),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_payout_batches_company ON payout_batches (company_id, created_at DESC);
CREATE TABLE payout_batch_items (
  batch_id       BIGINT NOT NULL REFERENCES payout_batches(id) ON DELETE CASCADE,
  settlement_id  BIGINT NOT NULL REFERENCES settlements(id),
  application_id BIGINT NOT NULL REFERENCES applications(id),
  bank_code      VARCHAR(10),
  account_last4  VARCHAR(4),                         -- 전체 계좌는 복제하지 않음(파일 생성 시점에만 복호화)
  account_holder VARCHAR(50),
  amount         INT NOT NULL CHECK (amount >= 0),
  holder_name_matched BOOLEAN,
  PRIMARY KEY (batch_id, settlement_id)
);
ALTER TABLE settlements ADD CONSTRAINT fk_settlements_batch FOREIGN KEY (payout_batch_id) REFERENCES payout_batches(id);

CREATE TABLE reviews (                          -- 상호 평가 (P3)
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id   BIGINT NOT NULL REFERENCES applications(id),
  reviewer_user_id BIGINT NOT NULL REFERENCES users(id),
  target_type      VARCHAR(10) NOT NULL CHECK (target_type IN ('ACTOR','COMPANY')),
  target_id        BIGINT NOT NULL,
  score            SMALLINT NOT NULL CHECK (score BETWEEN 1 AND 5),
  comment          VARCHAR(1000),
  is_visible       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (application_id, reviewer_user_id)
);

CREATE TABLE bookmarks (
  user_id     BIGINT NOT NULL REFERENCES users(id),
  posting_id  BIGINT NOT NULL REFERENCES job_postings(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, posting_id)
);
CREATE TABLE saved_searches (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id),
  name        VARCHAR(50) NOT NULL,
  conditions  JSONB NOT NULL,
  notify      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 7. 게시판 (공고와 분리)
-- ---------------------------------------------------------------------
CREATE TABLE boards (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code             VARCHAR(30) NOT NULL UNIQUE,
  name             VARCHAR(50) NOT NULL,
  type             VARCHAR(20) NOT NULL CHECK (type IN ('NOTICE','FAQ','GENERAL','QNA','INQUIRY')),
  allow_comment    BOOLEAN NOT NULL DEFAULT TRUE,
  allow_attachment BOOLEAN NOT NULL DEFAULT TRUE,
  allow_secret     BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order       INT NOT NULL DEFAULT 0,
  is_active        BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE board_permissions (
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
  parent_id   BIGINT REFERENCES comments(id),
  author_id   BIGINT NOT NULL REFERENCES users(id),
  content     VARCHAR(2000) NOT NULL,
  status      VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED','HIDDEN','DELETED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at  TIMESTAMPTZ
);
CREATE INDEX ix_comments_post ON comments (post_id, created_at);

-- ---------------------------------------------------------------------
-- 8. 알림 / 아웃박스
-- ---------------------------------------------------------------------
CREATE TABLE notification_templates (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        VARCHAR(50)   NOT NULL,
  channel     VARCHAR(10)   NOT NULL CHECK (channel IN ('INAPP','PUSH','EMAIL','SMS','KAKAO')),
  title_tpl   VARCHAR(200)  NOT NULL,
  body_tpl    VARCHAR(1000) NOT NULL,
  is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
  UNIQUE (code, channel)
);
CREATE TABLE notifications (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT        NOT NULL REFERENCES users(id),
  broadcast_id BIGINT        REFERENCES job_broadcasts(id),
  type         VARCHAR(50)   NOT NULL,
  title        VARCHAR(200)  NOT NULL,
  body         VARCHAR(1000),
  link_path    VARCHAR(300),
  payload      JSONB,
  priority     VARCHAR(10)   NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL','CRITICAL')),   -- CRITICAL: 푸시 불가/미수신 시 폴백 채널로 재전달 (iPhone 웹 푸시 제약 대응)
  requires_ack BOOLEAN       NOT NULL DEFAULT FALSE,   -- TRUE: 사용자가 '확인'을 누를 때까지 재알림 (일정 충돌 경고)
  acked_at     TIMESTAMPTZ,
  read_at      TIMESTAMPTZ,
  created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX ix_notifications_critical_unread ON notifications (created_at) WHERE priority = 'CRITICAL' AND read_at IS NULL;   -- 폴백 배치용
CREATE TABLE notification_deliveries (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  notification_id  BIGINT NOT NULL REFERENCES notifications(id),
  channel          VARCHAR(10) NOT NULL CHECK (channel IN ('PUSH','EMAIL','SMS','KAKAO')),
  status           VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
  attempt_count    SMALLINT NOT NULL DEFAULT 0,
  fallback_of_id   BIGINT REFERENCES notification_deliveries(id),   -- 폴백으로 만들어진 전달이면 원래 전달
  provider_msg_id  VARCHAR(100),
  error_message    VARCHAR(500),
  sent_at          TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_deliveries_pending ON notification_deliveries (created_at) WHERE status = 'PENDING';
CREATE TABLE notification_settings (
  user_id     BIGINT NOT NULL REFERENCES users(id),
  type        VARCHAR(50) NOT NULL,
  channel     VARCHAR(10) NOT NULL,
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (user_id, type, channel)
);
CREATE TABLE device_tokens (                    -- FCM(안드로이드·웹). iPhone 웹은 홈 화면 추가(PWA) 시에만 웹푸시 가능 → 없으면 폴백 채널
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES users(id),
  platform     VARCHAR(10) NOT NULL CHECK (platform IN ('ANDROID','IOS','WEB')),
  token        VARCHAR(500) NOT NULL UNIQUE,
  is_pwa_installed BOOLEAN NOT NULL DEFAULT FALSE,   -- iPhone 웹푸시는 홈 화면에 추가(설치)된 PWA 에서만 가능
  permission   VARCHAR(10) NOT NULL DEFAULT 'DEFAULT' CHECK (permission IN ('GRANTED','DENIED','DEFAULT')),
  user_agent   VARCHAR(300),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at   TIMESTAMPTZ
);
CREATE INDEX ix_device_tokens_user ON device_tokens (user_id) WHERE revoked_at IS NULL;
CREATE TABLE outbox_events (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  aggregate_type VARCHAR(30) NOT NULL,
  aggregate_id   BIGINT NOT NULL,
  event_type     VARCHAR(50) NOT NULL,
  payload        JSONB NOT NULL,
  status         VARCHAR(10) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','DONE','FAILED')),
  available_at   TIMESTAMPTZ NOT NULL DEFAULT now(),        -- 이 시각 이후에 워커가 처리 (⭐ 우선 알림 후 일반 알림 지연 발송 등)
  attempts       INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error     VARCHAR(200),                              -- 실패 사유(비밀·개인정보 없이 요약만)
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at   TIMESTAMPTZ
);
CREATE INDEX ix_outbox_new ON outbox_events (available_at, id) WHERE status = 'NEW';

-- ---------------------------------------------------------------------
-- 9. 운영 / 감사 / 신고 / 설정
-- ---------------------------------------------------------------------
CREATE TABLE reports (
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
  action        VARCHAR(50) NOT NULL,
  entity_type   VARCHAR(30) NOT NULL,
  entity_id     BIGINT,
  before_data   JSONB,
  after_data    JSONB,
  ip_address    INET,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_logs (entity_type, entity_id, created_at DESC);
CREATE TABLE feature_flags (                    -- 기능 숨김 스위치: 개발은 해 두되 법무 확인 전에는 꺼 둠
  code               VARCHAR(40) PRIMARY KEY,
  enabled            BOOLEAN NOT NULL DEFAULT FALSE,
  description        VARCHAR(300) NOT NULL,
  needs_legal_review BOOLEAN NOT NULL DEFAULT FALSE,
  updated_by         BIGINT REFERENCES users(id),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_settings (
  key         VARCHAR(80) PRIMARY KEY,
  value       VARCHAR(1000) NOT NULL,
  description VARCHAR(200),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 9-2. 뷰 (백엔드 전용: 권한·마스킹·복호화는 애플리케이션 계층. BI/직접 조회 금지)
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_schedule_headcount_summary AS      -- [인원표] 다일(숙박) 일정도 지원 1건을 1명으로 센다
SELECT s.posting_id, s.id AS schedule_id, s.start_at, s.end_at,
       p.id AS position_id, p.title AS position_title, p.gender_req,
       p.headcount, p.applied_count, p.selected_count, p.confirmed_count,
       COUNT(DISTINCT a.id) FILTER (WHERE a.status = 'CONFIRMED' AND a.departure_confirmed_at IS NOT NULL) AS departure_confirmed_count,
       COUNT(DISTINCT att.application_id) FILTER (WHERE att.result IN ('ATTENDED','LATE','EARLY_LEAVE')) AS attended_count,
       COUNT(DISTINCT att.application_id) FILTER (WHERE att.result = 'NO_SHOW') AS noshow_count
FROM job_schedules s
JOIN job_positions p ON p.schedule_id = s.id
LEFT JOIN applications a ON a.position_id = p.id AND a.status IN ('SELECTED','CONFIRMED')
LEFT JOIN attendances att ON att.application_id = a.id
GROUP BY s.posting_id, s.id, p.id;

CREATE OR REPLACE VIEW v_schedule_confirmed_roster AS       -- [인원 정보표] 합격 통보 이상. 주민번호는 포함하지 않음(tax_identity_id 로 앱이 복호화·감사)
SELECT a.posting_id, a.schedule_id, a.position_id, p.title AS position_title,
       a.id AS application_id, a.actor_user_id, a.status,
       a.applicant_name, a.applicant_phone, a.departure_location,
       a.profile_snapshot ->> 'gender'         AS gender,
       a.profile_snapshot ->> 'birth_date'     AS birth_date,
       a.profile_snapshot ->> 'height_cm'      AS height_cm,
       a.profile_snapshot ->> 'weight_kg'      AS weight_kg,
       a.profile_snapshot ->> 'top_size'       AS top_size,
       a.profile_snapshot ->> 'bottom_size'    AS bottom_size,
       a.profile_snapshot ->> 'shoe_size'      AS shoe_size,
       a.profile_snapshot ->> 'home_address'   AS home_address,
       a.tax_identity_id,
       a.bank_code, a.bank_name, a.account_number_enc, a.account_holder,
       a.departure_confirmed_at,
       COALESCE(att.attended_days, 0)   AS attended_days,       -- 출근한 근무일 수 (숙박 일정 대응)
       COALESCE(att.noshow_days, 0)     AS noshow_days,
       COALESCE(att.worked_minutes, 0)  AS worked_minutes,
       att.first_checkin_at, att.last_checkout_at,
       COALESCE(st.gross_amount, 0) AS gross_amount, COALESCE(st.net_amount, 0) AS net_amount,
       COALESCE(st.status, 'PENDING') AS settlement_status
FROM applications a
JOIN job_positions p ON p.id = a.position_id
LEFT JOIN LATERAL (
    SELECT COUNT(*) FILTER (WHERE result IN ('ATTENDED','LATE','EARLY_LEAVE')) AS attended_days,
           COUNT(*) FILTER (WHERE result = 'NO_SHOW') AS noshow_days,
           SUM(worked_minutes) AS worked_minutes,
           MIN(checkin_at) AS first_checkin_at, MAX(checkout_at) AS last_checkout_at
    FROM attendances x WHERE x.application_id = a.id) att ON TRUE
LEFT JOIN settlements st ON st.application_id = a.id
WHERE a.status IN ('SELECTED','CONFIRMED');

-- ---------------------------------------------------------------------
-- 10. updated_at 트리거
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
-- 11. 시드
-- ---------------------------------------------------------------------
INSERT INTO roles (code, name, description) VALUES
  ('SUPER_ADMIN','최고관리자','전체 권한'),
  ('ADMIN','관리자','업체 승인, 회원 제재, 설정, 최저임금'),
  ('OPERATOR','운영자(CS)','문의·신고·게시글'),
  ('COMPANY','보출 업체','공고·선발·현장 관리'),
  ('ACTOR','출연자','공고 검색/지원');

INSERT INTO regions (code, name, level) VALUES
  ('11','서울특별시',1),('26','부산광역시',1),('27','대구광역시',1),('28','인천광역시',1),('29','광주광역시',1),
  ('30','대전광역시',1),('31','울산광역시',1),('36','세종특별자치시',1),('41','경기도',1),('51','강원특별자치도',1),
  ('43','충청북도',1),('44','충청남도',1),('52','전북특별자치도',1),('46','전라남도',1),('47','경상북도',1),
  ('48','경상남도',1),('50','제주특별자치도',1);
-- 시/군/구(level 2)는 행정표준코드 공공데이터를 마이그레이션 스크립트로 적재 (수도권 우선 검수)

INSERT INTO minimum_wages (year, hourly_krw, effective_from, source_note) VALUES
  (2025, 10030, '2025-01-01', '2025년도 적용 최저임금 시간급 10,030원 (지난 연도 참고)'),
  (2026, 10320, '2026-01-01', '2026년도 적용 최저임금 시간급 10,320원 (2026-10-28 웹 검색으로 확인, 고용노동부 고시 원문 재확인 권장)'),
  (2027, 10700, '2027-01-01', '2027년도 적용 최저임금 시간급 10,700원: 최저임금위원회 2026-07-14 의결, 고용노동부 2026-08-05 고시 (소상공인연합회 행정소송 제기 예정 보도 — 결과 모니터링)');

INSERT INTO subscription_plans (code, name, monthly_price_krw, is_active) VALUES
  ('FREE','무료 플랜',0,TRUE), ('PRO','구독 플랜(가격 미정)',0,FALSE);
INSERT INTO plan_features (plan_id, feature_code, enabled, limit_value)
SELECT id, f.code, f.en, f.lim FROM subscription_plans,
  (VALUES ('VIEW_ACTOR_APPLICATIONS',FALSE,NULL::int),('TALENT_SEARCH',FALSE,NULL),('URGENT_TOP',FALSE,NULL),('ROSTER_EXCEL_EXPORT',TRUE,NULL),('BROADCAST_MONTHLY',TRUE,1000),('STAR_ACTORS',TRUE,30),('STAR_HEAD_START_CUSTOM',FALSE,NULL)) AS f(code,en,lim)
WHERE subscription_plans.code = 'FREE';
INSERT INTO plan_features (plan_id, feature_code, enabled, limit_value)
SELECT id, f.code, f.en, f.lim FROM subscription_plans,
  (VALUES ('VIEW_ACTOR_APPLICATIONS',TRUE,NULL::int),('TALENT_SEARCH',TRUE,NULL),('URGENT_TOP',TRUE,NULL),('ROSTER_EXCEL_EXPORT',TRUE,NULL),('BROADCAST_MONTHLY',TRUE,10000),('STAR_ACTORS',TRUE,1000),('STAR_HEAD_START_CUSTOM',TRUE,NULL)) AS f(code,en,lim)
WHERE subscription_plans.code = 'PRO';

INSERT INTO position_categories (code, name, sort_order) VALUES
  ('GENERAL','일반',1),('STUDENT','교복/학생',2),('MILITARY','군인',3),('POLICE','경찰/제복',4),
  ('PATIENT','환자/병원',5),('FOREIGNER','외국인',6),('VEHICLE','차량 지참',7),('SPECIAL','특수/분장',8);

INSERT INTO boards (code, name, type, allow_secret, sort_order) VALUES
  ('NOTICE','공지사항','NOTICE',FALSE,1),('FAQ','자주 묻는 질문','FAQ',FALSE,2),
  ('FREE','자유게시판','GENERAL',FALSE,3),('INQUIRY','1:1 문의','INQUIRY',TRUE,4);

INSERT INTO export_templates (code, name, kind, columns) VALUES
  ('ROSTER_DEFAULT','인원 정보표 기본','ROSTER',
   '[{"header":"연번","source":"no"},{"header":"이름","source":"applicant_name"},{"header":"휴대폰","source":"applicant_phone"},{"header":"성별","source":"gender"},{"header":"출생연도","source":"birth_year"},{"header":"키","source":"height_cm"},{"header":"몸무게","source":"weight_kg"},{"header":"사이즈","source":"sizes"},{"header":"집주소","source":"home_address"},{"header":"집합지","source":"departure_location"},{"header":"은행","source":"bank_name"},{"header":"계좌번호","source":"account_number"},{"header":"예금주","source":"account_holder"},{"header":"주민등록번호","source":"rrn","note":"권한자 전용·파일 암호화"}]'),
  ('BANK_GENERIC','은행 대량이체 공통(임시)','BANK_TRANSFER',
   '[{"header":"입금은행","source":"bank_name"},{"header":"입금계좌","source":"account_number"},{"header":"예금주","source":"account_holder"},{"header":"입금액","source":"net_amount"},{"header":"메모","source":"memo"}]'),
  ('WITHHOLDING_DEFAULT','원천징수 자료 기본','WITHHOLDING',
   '[{"header":"성명","source":"applicant_name"},{"header":"주민등록번호","source":"rrn"},{"header":"지급일","source":"paid_at"},{"header":"지급액","source":"gross_amount"},{"header":"소득세","source":"tax_amount"},{"header":"지방소득세","source":"local_tax_amount"}]');

INSERT INTO permissions (code, name) VALUES
  ('APPLY_MANAGE','지원/확정/취소'),('PROFILE_EDIT','프로필 편집'),('REVIEW_WRITE','평가 작성'),
  ('JOB_MANAGE','공고 작성/수정/마감'),('APPLICANT_VIEW','지원자 열람'),('APPLICANT_DECIDE','합격/불합격 처리'),
  ('ACTOR_HISTORY_VIEW','지원자 역대 이력 열람'),('ACTOR_APPLICATIONS_VIEW','지원자 타 공고 지원목록 열람'),
  ('MESSAGE_SEND','메시지/단체 알림 발송'),('ROSTER_VIEW','인원표/인원정보표 열람'),('ROSTER_EXPORT','인원정보표 엑셀'),
  ('RRN_REVEAL','주민번호 열람/내보내기'),('ATTENDANCE_SESSION','출퇴근 QR 세션 열기'),('ATTENDANCE_RECORD','출석 기록'),
  ('SETTLEMENT_MANAGE','정산 기록/은행·원천징수 엑셀'),('MEMBER_MANAGE','업체 직원 관리'),('CANCEL_REVIEW','취소 패널티 판정'),
  ('COMPANY_APPROVE','업체 승인'),('USER_SUSPEND','회원 제재'),('JOB_HIDE','공고 숨김'),
  ('REPORT_HANDLE','신고 처리'),('BOARD_MANAGE','게시판 관리'),('INQUIRY_ANSWER','문의 답변'),
  ('TERMS_MANAGE','약관 관리'),('SETTINGS_MANAGE','운영 설정'),('WAGE_MANAGE','최저임금 관리'),('FLAG_MANAGE','기능 플래그 관리'),
  ('AUDIT_VIEW','감사로그 열람'),('CONSENT_VERIFY','법정대리인 동의서 검수'),('PLAN_MANAGE','요금제 관리');
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON
  (r.code='ACTOR'    AND p.code IN ('APPLY_MANAGE','PROFILE_EDIT','REVIEW_WRITE')) OR
  (r.code='COMPANY'  AND p.code IN ('JOB_MANAGE','APPLICANT_VIEW','APPLICANT_DECIDE','ACTOR_HISTORY_VIEW','ACTOR_APPLICATIONS_VIEW',
                                    'MESSAGE_SEND','ROSTER_VIEW','ROSTER_EXPORT','RRN_REVEAL','ATTENDANCE_SESSION','ATTENDANCE_RECORD',
                                    'SETTLEMENT_MANAGE','MEMBER_MANAGE','CANCEL_REVIEW','REVIEW_WRITE')) OR
  (r.code='OPERATOR' AND p.code IN ('REPORT_HANDLE','BOARD_MANAGE','INQUIRY_ANSWER','CONSENT_VERIFY')) OR
  (r.code='ADMIN'    AND p.code IN ('COMPANY_APPROVE','USER_SUSPEND','JOB_HIDE','REPORT_HANDLE','BOARD_MANAGE','INQUIRY_ANSWER',
                                    'TERMS_MANAGE','SETTINGS_MANAGE','WAGE_MANAGE','FLAG_MANAGE','AUDIT_VIEW','CONSENT_VERIFY','PLAN_MANAGE')) OR
  (r.code='SUPER_ADMIN');

INSERT INTO feature_flags (code, enabled, description, needs_legal_review) VALUES
  ('SETTLEMENT_RECORD',FALSE,'정산 기록(지급 예정액·세금 계산·지급 확인 기록)',TRUE),
  ('BANK_TRANSFER_FILE',FALSE,'은행 대량이체용 엑셀 생성(업체가 자기 기업뱅킹에 직접 업로드)',TRUE),
  ('WITHHOLDING_EXPORT',FALSE,'원천징수/지급명세 자료 엑셀',TRUE),
  ('PAYOUT_EXECUTION',FALSE,'플랫폼이 송금을 실행 — 설계만, 구현 ✕ (전자금융업 등록 검토 전 금지)',TRUE),
  ('MINOR_APPLY',FALSE,'미성년 지원 + 법정대리인 동의서',TRUE),
  ('TALENT_SEARCH',FALSE,'인재 검색(구독 기능 예정)',FALSE),
  ('URGENT_TOP',FALSE,'긴급/상단 노출(구독 기능 예정)',FALSE),
  ('SMS_FALLBACK',FALSE,'CRITICAL 알림 문자/알림톡 폴백(비용 발생)',FALSE),
  ('KAKAO_PHONE_CONSENT',FALSE,'카카오 전화번호 동의항목 로그인(심사 통과 후)',FALSE),
  ('REVIEW_DEMO_LOGIN',FALSE,'스토어 심사용 데모 계정 고정 인증번호 — 심사 기간에만 ON, 데모 계정은 가짜 데이터만',FALSE);

-- 약관 초안(법무 검토 전 문구, 효력 시각은 시드 시점). 가입 시 필수: SERVICE·PRIVACY·THIRD_PARTY_PROVISION. SENSITIVE_ID·E_SIGNATURE 는 첫 지원 때 동의
INSERT INTO terms (type, version, content, is_required, effective_at) VALUES
  ('SERVICE','0.1','[초안] 서비스 이용약관 — 법무 검토 전',TRUE,'2026-01-01 00:00+09'),
  ('PRIVACY','0.1','[초안] 개인정보 수집·이용 동의 — 법무 검토 전',TRUE,'2026-01-01 00:00+09'),
  ('THIRD_PARTY_PROVISION','0.1','[초안] 지원한 업체에 휴대폰 번호 등을 제공하는 데 대한 동의 — 법무 검토 전',TRUE,'2026-01-01 00:00+09'),
  ('SENSITIVE_ID','0.1','[초안] 고유식별정보(주민등록번호) 처리 동의 — 법무 검토 전',TRUE,'2026-01-01 00:00+09'),
  ('E_SIGNATURE','0.1','[초안] 전자서명 및 증빙 보관 동의 — 법무 검토 전',TRUE,'2026-01-01 00:00+09'),
  ('MARKETING','0.1','[초안] 마케팅 정보 수신 동의(선택)',FALSE,'2026-01-01 00:00+09');

INSERT INTO app_settings (key, value, description) VALUES
  ('time_estimate_notice','시간은 모두 예상이며 확정이 아니에요. 촬영 상황에 따라 바뀌거나 연장될 수 있어요.','공고·합격서에 항상 표시하는 안내 문구'),
  ('star_head_start_minutes','1','⭐ 출연자에게 새 공고 알림을 먼저 보내는 시간(분). 일반 알림은 이만큼 늦게 발송. 업체별 직접 지정은 구독 후'),
  ('min_app_version_android','1.0.0','이 버전 미만이면 업데이트 화면(강제)'),
  ('min_app_version_ios','1.0.0','iOS 앱/웹 최소 버전'),
  ('otp_expire_seconds','180','문자 인증번호 유효시간'),
  ('login_session_days','90','출연자 앱 로그인 유지 기간(일)'),
  ('payout_holder_must_match','true','이체 파일 생성 시 예금주가 지원자 본인 이름과 다르면 경고(직접지급 원칙)'),
  ('service_name','보조출연 헬프','서비스 표시 이름 (로고는 추후)'),
  ('subscription_enforcement','false','true 로 바꾸면 plan_features 기반 유료 기능 제한 작동 (현재 전 기능 무료)'),
  ('free_open_application_history','true','무료 운영 중 업체에 지원자의 타 공고 지원목록 열람 허용'),
  ('auto_reject_on_close','true','마감 시 미선발 지원자(APPLIED) 자동 불합격'),
  ('selected_response_hours','12','합격 통보 후 출연자 확정 응답 기한(시간). 집합 시각이 더 빠르면 집합 3시간 전까지'),
  ('conflict_gap_minutes','120','두 확정 사이 간격이 이 값보다 작아도 충돌로 간주(이동 시간). 예약 구간 양끝 버퍼 = 이 값의 절반'),
  ('conflict_reminder_interval_minutes','60','일정 충돌 경고 재알림 주기(출연자가 확인을 누를 때까지)'),
  ('departure_open_before_minutes','120','참석 확인 요청 시작: 집합 N분 전'),
  ('departure_close_before_minutes','30','참석 확인 마감: 집합 N분 전 (미응답자는 반장에게 알림)'),
  ('reserve_candidate_window_days','1','마감 후 추가 합격 후보 가용 판단 범위(±일)'),
  ('cancel_review_timeout_hours','24','취소 패널티 판정 대기 제한(경과 시 미부과 처리)'),
  ('min_pay_hours','8','근무시간이 아래 기준 이하일 때 보장하는 최소 급여 시간'),
  ('fixed_pay_threshold_hours','9','이 시간 이내 근무는 시급×min_pay_hours 고정, 초과 시 시급×근무시간'),
  ('max_schedule_days','21','일정 최대 길이(일). 변경 시 CHECK 제약도 함께 수정'),
  ('critical_fallback_after_minutes','10','CRITICAL 알림이 이 시간 내 푸시 성공/열람이 없으면 폴백 채널로 재전달'),
  ('critical_fallback_channels','["EMAIL"]','폴백 채널 순서. SMS/KAKAO 는 사용자 옵트인(notification_settings CRITICAL_FALLBACK)과 크레딧 필요'),
  ('qr_rotate_seconds','30','출퇴근 개인 QR 토큰 회전 주기(초)'),
  ('require_rrn_before_apply','true','첫 지원 전 주민등록번호 등록 필수. 단 개인정보보호법 제24조의2 제3항에 따라 회원가입 단계에서는 주민번호 없이 가입 가능해야 하므로 가입 필수 항목에 넣지 않는다 (법무 확인)'),
  ('evidence_retention_years','5','지원서·서명·정산 증빙 보존 연수. 근로 서류 법정 3년(근로기준법 제42조: 근로계약서=근로관계 종료일부터, 임금대장=마지막 기재일부터) 이상으로 설정, 세법 보존은 세무 확인. 분쟁 중에는 보류'),
  ('export_password_protect','true','주민번호가 포함된 엑셀은 암호화 파일로만 제공'),
  ('minor_apply_enabled','false','미성년자 지원 허용(현재 불가, 구조만 선반영)'),
  ('minor_age_limit','19','이 만 나이 미만은 미성년 (동의서 흐름 활성화 시 적용)'),
  ('min_actor_age','15','지원 가능 최소 만 나이'),
  ('night_restrict_age','18','이 만 나이 미만은 심야 일정 차단(미성년 허용 시)'),
  ('ad_push_quiet_start','21','광고성 알림 금지 시작(KST)'),
  ('ad_push_quiet_end','8','광고성 알림 금지 종료(KST)');

-- ---------------------------------------------------------------------
-- 보강 인덱스: 외래키 열 중 조인·삭제·조회 경로가 있는 것 (관리자 처리자 열 등 저빈도는 제외)
-- ---------------------------------------------------------------------
CREATE INDEX ix_app_schedule ON applications (schedule_id, status);
CREATE INDEX ix_notif_delivery_notif ON notification_deliveries (notification_id);
CREATE INDEX ix_bcast_recipient_app ON job_broadcast_recipients (application_id);
CREATE INDEX ix_bcast_recipient_actor ON job_broadcast_recipients (actor_user_id);
CREATE INDEX ix_bcast_company ON job_broadcasts (company_id, created_at DESC);
CREATE INDEX ix_thread_posting ON message_threads (posting_id);
CREATE INDEX ix_auth_provider_user ON user_auth_providers (user_id);
CREATE INDEX ix_saved_search_user ON saved_searches (user_id);
CREATE INDEX ix_bookmark_posting ON bookmarks (posting_id);
CREATE INDEX ix_conflict_actor ON schedule_conflicts (actor_user_id);
CREATE INDEX ix_penalty_application ON actor_penalty_events (application_id);
CREATE INDEX ix_cancel_review_actor ON cancellation_reviews (actor_user_id);
CREATE INDEX ix_audit_actor ON audit_logs (actor_user_id, created_at DESC);
CREATE INDEX ix_file_uploader ON files (uploader_user_id);
CREATE INDEX ix_company_block_actor ON company_actor_blocks (actor_user_id);
CREATE INDEX ix_starred_actor ON company_starred_actors (actor_user_id);
CREATE INDEX ix_actor_block_company ON actor_company_blocks (company_id);
CREATE INDEX ix_verification_company ON company_verifications (company_id);
CREATE INDEX ix_post_author ON posts (author_id);
CREATE INDEX ix_comment_author ON comments (author_id);
CREATE INDEX ix_payout_item_settlement ON payout_batch_items (settlement_id);
CREATE INDEX ix_deletion_user ON account_deletion_requests (user_id);
