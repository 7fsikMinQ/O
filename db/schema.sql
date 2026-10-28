-- =====================================================================
-- 보조출연 매칭 플랫폼 - PostgreSQL 16+ 초기 스키마 (v1.0: 운영 규칙 반영 최종본)
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
CREATE EXTENSION IF NOT EXISTS btree_gist;     -- 시간 겹침 배제 제약(EXCLUDE)에 필요

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
  type         VARCHAR(30) NOT NULL CHECK (type IN ('SERVICE','PRIVACY','MARKETING','LOCATION','YOUTH','THIRD_PARTY_PROVISION')),
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
                 CHECK (type IN ('DRAMA','MOVIE','AD','MV','OTT','VARIETY','ETC')),
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
  storage_provider  VARCHAR(20) NOT NULL DEFAULT 'R2' CHECK (storage_provider IN ('R2','GCS','S3','LOCAL')),
  bucket            VARCHAR(100) NOT NULL,
  object_key        VARCHAR(500) NOT NULL,
  original_name    VARCHAR(255) NOT NULL,
  content_type      VARCHAR(100) NOT NULL,
  size_bytes        BIGINT      NOT NULL CHECK (size_bytes >= 0),
  checksum_sha256   VARCHAR(64),
  width             INT,                                                     -- 이미지일 때
  height            INT,
  variants          JSONB,                                                   -- {"thumb":"key","medium":"key"} 리사이즈본 (public_url 은 저장하지 않고 키+CDN 도메인으로 조합)
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
                                      'APPLICATION','GUARDIAN_CONSENT','JOB_BROADCAST','POST','COMMENT','REPORT','PRODUCTION')),
  entity_id    BIGINT      NOT NULL,
  usage        VARCHAR(30) NOT NULL
               CHECK (usage IN ('PROFILE_PHOTO','FULL_BODY_PHOTO','RESUME','BIZ_LICENSE','LOGO',
                                'JOB_IMAGE','REF_IMAGE','APPLY_DOC','COSTUME_PHOTO','PARENTAL_CONSENT','FAMILY_RELATION_PROOF',
                                'ATTACHMENT','EVIDENCE')),
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
  birth_date     DATE        NOT NULL,            -- 출생연도 범위 매칭 + 미성년 판정
  gender         VARCHAR(10) NOT NULL CHECK (gender IN ('MALE','FEMALE','OTHER')),
  height_cm      SMALLINT CHECK (height_cm BETWEEN 50 AND 250),
  weight_kg      SMALLINT CHECK (weight_kg BETWEEN 20 AND 300),
  top_size       VARCHAR(10),                     -- 95, 100, 55, 66 …
  bottom_size    VARCHAR(10),
  shoe_size      SMALLINT,                        -- mm
  hair_style     VARCHAR(100),                    -- 머리 기장/염색 (사극·현대극 구분)
  has_tattoo     BOOLEAN     NOT NULL DEFAULT FALSE,
  can_drive      BOOLEAN     NOT NULL DEFAULT FALSE,   -- 운전 가능
  has_vehicle    BOOLEAN     NOT NULL DEFAULT FALSE,   -- 차량 지참 가능
  region_id      BIGINT REFERENCES regions(id),
  default_departure_location VARCHAR(200),         -- 지원 시 자동 입력되는 기본 출발지/탑승 희망지
  introduction   VARCHAR(1000),
  experience_note VARCHAR(1000),
  tags           JSONB       NOT NULL DEFAULT '[]',    -- 개방형 특기/보유의상: ["수영","영어회화","정장보유","경찰복보유"]
  is_searchable  BOOLEAN     NOT NULL DEFAULT TRUE,    -- 업체 인재검색 노출 동의
  -- 신뢰도 (원천: reliability_events / attendances / reviews. 아래는 캐시)
  reliability_score INT      NOT NULL DEFAULT 100,
  apply_blocked_until TIMESTAMPTZ,                     -- 노쇼 패널티 지원 제한
  confirmed_count INT        NOT NULL DEFAULT 0,
  attended_count INT         NOT NULL DEFAULT 0,
  noshow_count   INT         NOT NULL DEFAULT 0,
  rating_avg     NUMERIC(3,2),
  rating_count   INT         NOT NULL DEFAULT 0,
  history_disclosure_consented_at TIMESTAMPTZ,     -- 역대 합격/지원 이력을 업체에 공개하는 데 동의한 시각 (제3자 제공 동의)
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_actor_spec ON actor_profiles (gender, birth_date, height_cm, weight_kg);
CREATE INDEX ix_actor_tags ON actor_profiles USING gin (tags jsonb_path_ops);

-- 민감정보는 프로필과 분리 저장 (애플리케이션 계층 AES-256-GCM 암호화, 키는 DB 밖 보관, key_version 으로 회전)
CREATE TABLE actor_payout_accounts (            -- 정산용 계좌 (선택 수집)
  user_id            BIGINT PRIMARY KEY REFERENCES actor_profiles(user_id),
  bank_code          VARCHAR(10)  NOT NULL,
  bank_name          VARCHAR(50)  NOT NULL,
  account_number_enc VARCHAR(300) NOT NULL,
  account_holder     VARCHAR(50)  NOT NULL,
  key_version        SMALLINT     NOT NULL DEFAULT 1,
  consented_at       TIMESTAMPTZ  NOT NULL,
  updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TABLE actor_tax_identities (             -- 원천징수 신고용 식별번호 (법무 검토 전에는 테이블만 두고 수집 기능 OFF)
  user_id        BIGINT PRIMARY KEY REFERENCES actor_profiles(user_id),
  rrn_enc        VARCHAR(300) NOT NULL,
  key_version    SMALLINT     NOT NULL DEFAULT 1,
  consented_at   TIMESTAMPTZ  NOT NULL,
  purge_after    DATE,                            -- 보관기한 후 파기 배치 대상
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TABLE reliability_events (               -- 성실도 점수 원장 (이의제기·소명 근거)
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id        BIGINT NOT NULL REFERENCES actor_profiles(user_id),
  application_id BIGINT,                          -- applications FK 는 아래 순서 문제로 앱 레벨 + 후행 ALTER
  delta          INT NOT NULL,
  reason_code    VARCHAR(30) NOT NULL,            -- NO_SHOW, LATE, LATE_CANCEL, ATTEND_BONUS, APPEAL_RESTORE …
  note           VARCHAR(300),
  created_by     BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reliability_user ON reliability_events (user_id, created_at DESC);

CREATE TABLE guardian_consents (                -- 미성년 출연자 법정대리인 동의 (파일은 file_attachments: PARENTAL_CONSENT / FAMILY_RELATION_PROOF)
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id    BIGINT NOT NULL REFERENCES actor_profiles(user_id),
  guardian_name    VARCHAR(50)  NOT NULL,
  guardian_phone   VARCHAR(20)  NOT NULL,
  relationship     VARCHAR(20)  NOT NULL DEFAULT 'PARENT' CHECK (relationship IN ('PARENT','GUARDIAN')),
  scope            VARCHAR(20)  NOT NULL DEFAULT 'GENERAL' CHECK (scope IN ('GENERAL','PER_APPLICATION')),
  application_id   BIGINT,                         -- scope=PER_APPLICATION 일 때 (FK 는 아래 ALTER)
  status           VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','VERIFIED','REJECTED','EXPIRED')),
  valid_until      DATE,                           -- 일반 동의의 유효기간 (app_settings.guardian_consent_valid_days)
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
  member_role VARCHAR(20) NOT NULL DEFAULT 'STAFF'
              CHECK (member_role IN ('OWNER','MANAGER','FIELD_LEADER','STAFF')),   -- 대표 / 캐스팅팀장 / 현장반장 / 일반
  department_name VARCHAR(50),

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

-- [수익화 확장 대비] 요금제·기능 게이트. 지금은 FREE 플랜 + subscription_enforcement=false 로 전 기능 무료 개방
CREATE TABLE subscription_plans (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code              VARCHAR(30)  NOT NULL UNIQUE,          -- FREE, PRO, ENTERPRISE
  name              VARCHAR(50)  NOT NULL,
  monthly_price_krw INT          NOT NULL DEFAULT 0,
  is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TABLE plan_features (                    -- 새 유료 기능 = 행 추가 (컬럼 추가 없음)
  plan_id        BIGINT       NOT NULL REFERENCES subscription_plans(id) ON DELETE CASCADE,
  feature_code   VARCHAR(50)  NOT NULL,                    -- VIEW_ACTOR_APPLICATIONS, ROSTER_EXCEL_EXPORT, BROADCAST_MONTHLY, ...
  enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
  limit_value    INT,                                      -- 월 한도 등 (NULL=무제한)
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
CREATE TABLE usage_counters (                   -- 월 한도형 기능 사용량 (단체 메시지 건수 등). enforcement=false 여도 집계는 쌓아 둔다
  company_id   BIGINT      NOT NULL REFERENCES companies(id),
  feature_code VARCHAR(50) NOT NULL,
  period_month CHAR(7)     NOT NULL,                       -- 'YYYY-MM' (KST)
  used_count   INT         NOT NULL DEFAULT 0 CHECK (used_count >= 0),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (company_id, feature_code, period_month)
);

-- ---------------------------------------------------------------------
-- 5. 공고 (핵심 도메인)  posting → schedule(일자) → position(역할/모집)
-- ---------------------------------------------------------------------
CREATE TABLE job_postings (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id       BIGINT NOT NULL REFERENCES companies(id),
  production_id    BIGINT REFERENCES productions(id),
  episode_info     VARCHAR(50),                 -- 회차 (예: 4화)
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
  gathering_type   VARCHAR(20) NOT NULL DEFAULT 'ON_SITE' CHECK (gathering_type IN ('BUS_SHUTTLE','ON_SITE','BOTH')),
  shooting_location VARCHAR(255),                 -- 실제 촬영 지역 (비공개 현장이면 확정자에게만)
  meeting_place    VARCHAR(200),                  -- 집합 장소명 (역할별 override 가능: job_positions)
  meeting_address  VARCHAR(300),
  latitude         NUMERIC(9,6),
  longitude        NUMERIC(9,6),
  field_leader_user_id BIGINT REFERENCES users(id),  -- 당일 담당 현장반장
  note             VARCHAR(1000),
  status           VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                   CHECK (status IN ('OPEN','CLOSED','CANCELED','POSTPONED','COMPLETED')),   -- 우천/일정 변경
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_job_schedules_date ON job_schedules (shoot_date, status);
CREATE INDEX ix_job_schedules_posting ON job_schedules (posting_id);

CREATE TABLE job_positions (                    -- 일자 내 모집 단위(역할). 같은 촬영에 역할 N개를 한 공고로
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  schedule_id      BIGINT NOT NULL REFERENCES job_schedules(id),
  posting_id       BIGINT NOT NULL REFERENCES job_postings(id),   -- 조회 편의 비정규화
  category_id      BIGINT REFERENCES position_categories(id),
  title            VARCHAR(100) NOT NULL,         -- "클럽 20대 남녀", "정장 형사", "행인"
  -- 역할별 집결 override (NULL 이면 schedule 값 사용)
  call_time        TIME,
  end_time         TIME,
  meeting_place    VARCHAR(200),
  meeting_address  VARCHAR(300),
  -- 모집 조건
  headcount        INT  NOT NULL CHECK (headcount > 0),
  standby_headcount INT NOT NULL DEFAULT 0 CHECK (standby_headcount >= 0),   -- 예비 합격 정원
  gender_req       VARCHAR(10) NOT NULL DEFAULT 'ANY' CHECK (gender_req IN ('ANY','MALE','FEMALE')),
  birth_year_min   SMALLINT,                      -- 출생연도 범위 (현장 관행, 해가 바뀌어도 불변)
  birth_year_max   SMALLINT,
  height_min       SMALLINT,
  height_max       SMALLINT,
  allow_minor      BOOLEAN NOT NULL DEFAULT FALSE,   -- 미성년 지원 허용 (TRUE 여도 법정대리인 동의 필수)
  min_reliability_score SMALLINT,                 -- 업체가 거는 최소 성실도 (NULL=제한 없음)
  -- 보수
  pay_type         VARCHAR(10) NOT NULL DEFAULT 'DAILY' CHECK (pay_type IN ('DAILY','HOURLY','FIXED','NEGOTIABLE')),
  pay_amount       INT CHECK (pay_amount >= 0),
  overtime_rule    VARCHAR(255),                  -- 연장/야간 안내 텍스트
  overtime_hourly_amount INT CHECK (overtime_hourly_amount >= 0),
  meal_provided    BOOLEAN NOT NULL DEFAULT FALSE,
  meal_allowance_amount INT CHECK (meal_allowance_amount >= 0),
  transport_provided BOOLEAN NOT NULL DEFAULT FALSE,
  -- 의상/지원 요건
  dress_code       VARCHAR(300),                  -- 의상 준비 조건 (예: 개인 검정 정장·구두 지참)
  requirements     VARCHAR(1000),
  requires_costume_photo BOOLEAN NOT NULL DEFAULT FALSE,   -- 지원 시 의상 착장 사진 필수
  requires_document BOOLEAN NOT NULL DEFAULT FALSE,
  apply_mode       VARCHAR(20) NOT NULL DEFAULT 'APPROVAL' CHECK (apply_mode IN ('APPROVAL','FIRST_COME')),
  applied_count    INT NOT NULL DEFAULT 0,        -- 캐시 (지원 트랜잭션에서 갱신)
  confirmed_count  INT NOT NULL DEFAULT 0,
  standby_count    INT NOT NULL DEFAULT 0,
  status           VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','FULL','CLOSED','CANCELED')),
  version          BIGINT NOT NULL DEFAULT 0,     -- JPA @Version (낙관적 락)
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (confirmed_count <= headcount),
  CHECK (standby_count <= standby_headcount),
  CHECK (birth_year_min IS NULL OR birth_year_max IS NULL OR birth_year_min <= birth_year_max),
  CHECK (height_min IS NULL OR height_max IS NULL OR height_min <= height_max)
);
CREATE INDEX ix_job_positions_schedule ON job_positions (schedule_id);
CREATE INDEX ix_job_positions_posting ON job_positions (posting_id);

-- ---------------------------------------------------------------------
-- 6. 지원 / 출석 / 정산 / 평가
-- ---------------------------------------------------------------------
CREATE TABLE applications (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  position_id    BIGINT NOT NULL REFERENCES job_positions(id),
  schedule_id    BIGINT NOT NULL REFERENCES job_schedules(id),   -- 비정규화(조회/충돌검사)
  posting_id     BIGINT NOT NULL REFERENCES job_postings(id),
  shoot_date     DATE   NOT NULL,                                -- 비정규화
  actor_user_id  BIGINT NOT NULL REFERENCES users(id),
  status         VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
                 CHECK (status IN ('APPLIED','SHORTLISTED','WAITLISTED','CONFIRMED','REJECTED','AUTO_REJECTED',
                                   'CANCELED_BY_ACTOR','CANCELED_BY_COMPANY','CANCELED_BY_SYSTEM','EXPIRED')),
                 -- WAITLISTED = 업체가 선택한 '예비 합격'(통보됨). AUTO_REJECTED = 마감 시 미통보 지원자 자동 불합격.
  -- ▼ 지원 시점 작성/확인값 스냅샷 (= 인원 정보표 원천). 합격(CONFIRMED) 전에는 업체 API 가 마스킹하여 노출
  applicant_name     VARCHAR(50) NOT NULL,
  applicant_phone    VARCHAR(20) NOT NULL,
  departure_location VARCHAR(200),                -- 출발지/탑승 희망지
  bank_code          VARCHAR(10),
  bank_name          VARCHAR(50),
  account_number_enc VARCHAR(300),                -- 앱 레벨 AES-256-GCM. 정산 완료 후 sensitive_purge_days 경과 시 NULL 처리(파기 배치)
  account_holder     VARCHAR(50),
  sensitive_purged_at TIMESTAMPTZ,
  message        VARCHAR(1000),
  decided_by     BIGINT REFERENCES users(id),
  decided_at     TIMESTAMPTZ,
  decision_reason VARCHAR(500),
  condition_snapshot JSONB,                       -- 확정 시점 조건(일당·집결·의상) 스냅샷 → 분쟁 대비
  confirmed_time_range TSTZRANGE,                 -- 확정 시 [집합시각, 종료시각+버퍼) → 시간 겹침 확정을 DB가 차단
  departure_requested_at TIMESTAMPTZ,             -- 전일 '참석 확인' 요청 발송 시각
  departure_confirmed_at TIMESTAMPTZ,             -- 출연자가 참석 확인을 누른 시각
  canceled_at    TIMESTAMPTZ,
  applied_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_confirmed_has_range CHECK (status <> 'CONFIRMED' OR confirmed_time_range IS NOT NULL),
  -- 같은 출연자는 시간이 겹치는 두 건을 동시에 확정받을 수 없다. 시간이 안 겹치면 같은 날 여러 건 확정 가능.
  CONSTRAINT ex_applications_no_overlap EXCLUDE USING gist (actor_user_id WITH =, confirmed_time_range WITH &&)
    WHERE (status = 'CONFIRMED')
);
-- 같은 역할에 활성 지원은 1건만 (취소 후 재지원 허용)
CREATE UNIQUE INDEX uq_applications_active ON applications (position_id, actor_user_id)
  WHERE status IN ('APPLIED','SHORTLISTED','WAITLISTED','CONFIRMED');
-- 업체의 지원자 이력 조회(역대 합격 / 날짜별 지원 목록)
CREATE INDEX ix_applications_actor_history ON applications (actor_user_id, status, shoot_date DESC);
CREATE INDEX ix_applications_actor_date ON applications (actor_user_id, shoot_date DESC);
CREATE INDEX ix_applications_position ON applications (position_id, status);
CREATE INDEX ix_applications_posting ON applications (posting_id, status);
CREATE INDEX ix_applications_departure ON applications (shoot_date) WHERE status = 'CONFIRMED' AND departure_confirmed_at IS NULL;
CREATE INDEX ix_applications_purge ON applications (updated_at) WHERE account_number_enc IS NOT NULL AND sensitive_purged_at IS NULL;

ALTER TABLE guardian_consents ADD CONSTRAINT fk_guardian_application FOREIGN KEY (application_id) REFERENCES applications(id);

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

CREATE TABLE job_broadcasts (                   -- 합격자 단체 알림/메시지 발송 원장 (수신 현황은 notifications.broadcast_id 로 집계)
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id      BIGINT NOT NULL REFERENCES companies(id),
  posting_id      BIGINT NOT NULL REFERENCES job_postings(id),
  schedule_id     BIGINT REFERENCES job_schedules(id),     -- NULL = 공고 전체
  position_id     BIGINT REFERENCES job_positions(id),     -- NULL = 일자 전체
  sender_user_id  BIGINT NOT NULL REFERENCES users(id),
  target_filter   VARCHAR(30) NOT NULL DEFAULT 'ALL_CONFIRMED'
                  CHECK (target_filter IN ('ALL_CONFIRMED','UNCONFIRMED_DEPARTURE','WAITLISTED')),
  title           VARCHAR(200)  NOT NULL,
  message_body    VARCHAR(2000) NOT NULL,
  channels        JSONB NOT NULL DEFAULT '["INAPP","PUSH"]',   -- INAPP, PUSH, KAKAO, SMS
  recipient_count INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_job_broadcasts_schedule ON job_broadcasts (schedule_id, created_at DESC);

CREATE TABLE attendances (                      -- 출석 (확정 지원 1건당 1행)
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id  BIGINT NOT NULL UNIQUE REFERENCES applications(id),
  result          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                  CHECK (result IN ('PENDING','ATTENDED','LATE','EARLY_LEAVE','NO_SHOW','EXCUSED')),
  checkin_at      TIMESTAMPTZ,
  checkout_at     TIMESTAMPTZ,
  overtime_minutes INT NOT NULL DEFAULT 0,
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

CREATE TABLE settlement_items (                 -- 기본급/연장/야간/식대/교통/공제 내역
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  settlement_id  BIGINT NOT NULL REFERENCES settlements(id) ON DELETE CASCADE,
  item_type      VARCHAR(20) NOT NULL CHECK (item_type IN ('BASE','OVERTIME','NIGHT','MEAL','TRANSPORT','BONUS','DEDUCTION')),
  amount         INT NOT NULL,                    -- 공제는 음수
  quantity       NUMERIC(6,2),                    -- 연장 시간 등
  memo           VARCHAR(200)
);
CREATE INDEX ix_settlement_items ON settlement_items (settlement_id);
ALTER TABLE reliability_events ADD CONSTRAINT fk_reliability_application FOREIGN KEY (application_id) REFERENCES applications(id);

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
  broadcast_id BIGINT REFERENCES job_broadcasts(id),   -- 업체 단체 메시지로 생성된 경우
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
-- 9-2. 인원표 / 인원 정보표 뷰 (백엔드 전용: 권한 검사·복호화·마스킹은 애플리케이션 계층에서 수행. BI/직접조회 금지)
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW v_schedule_headcount_summary AS      -- [인원표]
SELECT s.posting_id, s.id AS schedule_id, s.shoot_date,
       p.id AS position_id, p.title AS position_title, p.gender_req,
       p.headcount, p.standby_headcount, p.applied_count, p.confirmed_count, p.standby_count,
       COUNT(a.id) FILTER (WHERE a.departure_confirmed_at IS NOT NULL)        AS departure_confirmed_count,
       COUNT(att.id) FILTER (WHERE att.result IN ('ATTENDED','LATE','EARLY_LEAVE')) AS attended_count,
       COUNT(att.id) FILTER (WHERE att.result = 'NO_SHOW')                    AS noshow_count
FROM job_schedules s
JOIN job_positions p ON p.schedule_id = s.id
LEFT JOIN applications a ON a.position_id = p.id AND a.status = 'CONFIRMED'
LEFT JOIN attendances att ON att.application_id = a.id
GROUP BY s.posting_id, s.id, s.shoot_date, p.id;

CREATE OR REPLACE VIEW v_schedule_confirmed_roster AS       -- [인원 정보표] 합격자만
SELECT a.posting_id, a.schedule_id, a.position_id, p.title AS position_title,
       a.id AS application_id, a.actor_user_id,
       a.applicant_name, a.applicant_phone, a.departure_location,
       ap.gender, ap.birth_date, ap.height_cm, ap.weight_kg, ap.top_size, ap.bottom_size, ap.shoe_size,
       a.bank_code, a.bank_name, a.account_number_enc, a.account_holder,
       a.message AS applicant_note, a.departure_confirmed_at,
       COALESCE(att.result, 'PENDING') AS attendance_result,
       att.checkin_at, att.checkout_at, att.overtime_minutes,
       COALESCE(st.gross_amount, p.pay_amount, 0) AS gross_amount,
       COALESCE(st.tax_amount, 0)                 AS tax_amount,
       COALESCE(st.net_amount, p.pay_amount, 0)   AS net_amount,
       COALESCE(st.status, 'PENDING')             AS settlement_status
FROM applications a
JOIN job_positions p ON p.id = a.position_id
LEFT JOIN actor_profiles ap ON ap.user_id = a.actor_user_id
LEFT JOIN attendances att ON att.application_id = a.id
LEFT JOIN settlements st ON st.application_id = a.id
WHERE a.status = 'CONFIRMED';

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

INSERT INTO app_settings (key, value, description) VALUES
  ('confirm_buffer_minutes','60','확정 시간범위 양끝 이동 버퍼(분). 겹침 판정에 사용'),
  ('default_shoot_hours','12','종료시간 미입력 시 가정하는 촬영 시간(시간)'),
  ('free_open_application_history','true','무료 운영 기간 모든 업체에 지원자의 타 공고 지원목록 열람 허용 (false 면 구독 기능 VIEW_ACTOR_APPLICATIONS 필요)'),
  ('subscription_enforcement','false','true 로 바꾸면 plan_features 기반 유료 기능 제한 작동'),
  ('auto_reject_on_close','true','마감 시 미통보 지원자(APPLIED/SHORTLISTED) 자동 불합격'),
  ('min_actor_age','15','지원 가능 최소 만 나이 (15세 미만 취직인허가 대상은 v1 미지원, 법무 확인)'),
  ('minor_age_limit','19','이 만 나이 미만은 법정대리인 동의서 필수 (민법상 미성년)'),
  ('night_restrict_age','18','이 만 나이 미만은 철야/야간 일정 지원 차단 (근로기준법상 연소자 기준, 법무 확인)'),
  ('guardian_consent_valid_days','180','일반 법정대리인 동의서 유효기간(일)'),
  ('sensitive_purge_days','90','정산 완료 후 지원서 내 계좌 스냅샷 파기까지 일수'),
  ('departure_check_hour','18','촬영 전날 참석 확인 요청 발송 시각(KST)'),
  ('departure_deadline_hour','22','참석 확인 마감 시각(KST) — 미응답자는 반장 확인 대상'),
  ('noshow_penalty_points','30','노쇼 시 성실도 차감'),
  ('noshow_block_days','7','노쇼 시 지원 제한 일수'),
  ('ad_push_quiet_start','21','광고성 알림 금지 시작(KST)'),
  ('ad_push_quiet_end','8','광고성 알림 금지 종료(KST)');

-- 요금제 시드: 지금은 FREE 만 사용. PRO 는 구조 검증용(비활성)
INSERT INTO subscription_plans (code, name, monthly_price_krw, is_active) VALUES
  ('FREE','무료 플랜',0,TRUE), ('PRO','프로 플랜(예정)',0,FALSE);
INSERT INTO plan_features (plan_id, feature_code, enabled, limit_value)
SELECT id, f.code, f.en, f.lim FROM subscription_plans,
  (VALUES ('VIEW_ACTOR_APPLICATIONS',FALSE,NULL::int),('ROSTER_EXCEL_EXPORT',TRUE,NULL),('BROADCAST_MONTHLY',TRUE,300)) AS f(code,en,lim)
WHERE subscription_plans.code = 'FREE';
INSERT INTO plan_features (plan_id, feature_code, enabled, limit_value)
SELECT id, f.code, f.en, f.lim FROM subscription_plans,
  (VALUES ('VIEW_ACTOR_APPLICATIONS',TRUE,NULL::int),('ROSTER_EXCEL_EXPORT',TRUE,NULL),('BROADCAST_MONTHLY',TRUE,5000)) AS f(code,en,lim)
WHERE subscription_plans.code = 'PRO';

-- 권한 시드 (플랫폼 역할 단위). 업체 내부 직책별 제한은 company_members.member_role 로 애플리케이션에서 추가 검사
INSERT INTO permissions (code, name) VALUES
  ('APPLY_MANAGE','지원/취소'),('PROFILE_EDIT','프로필 편집'),('REVIEW_WRITE','평가 작성'),
  ('JOB_MANAGE','공고 작성/수정/마감'),('APPLICANT_VIEW','지원자 열람'),('APPLICANT_DECIDE','합격/불합격 처리'),
  ('ACTOR_HISTORY_VIEW','지원자 역대 이력 열람'),('ACTOR_APPLICATIONS_VIEW','지원자 타 공고 지원목록 열람'),
  ('BROADCAST_SEND','합격자 단체 메시지'),('ROSTER_VIEW','인원표/인원정보표 열람'),('ROSTER_EXPORT','인원정보표 엑셀'),
  ('ATTENDANCE_RECORD','출석 기록'),('SETTLEMENT_MANAGE','정산 기록'),('MEMBER_MANAGE','업체 직원 관리'),
  ('COMPANY_APPROVE','업체 승인'),('USER_SUSPEND','회원 제재'),('JOB_HIDE','공고 숨김'),
  ('REPORT_HANDLE','신고 처리'),('BOARD_MANAGE','게시판 관리'),('INQUIRY_ANSWER','문의 답변'),
  ('TERMS_MANAGE','약관 관리'),('SETTINGS_MANAGE','운영 설정'),('AUDIT_VIEW','감사로그 열람'),
  ('CONSENT_VERIFY','법정대리인 동의서 검수'),('PLAN_MANAGE','요금제 관리');
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON
  (r.code='ACTOR'    AND p.code IN ('APPLY_MANAGE','PROFILE_EDIT','REVIEW_WRITE')) OR
  (r.code='COMPANY'  AND p.code IN ('JOB_MANAGE','APPLICANT_VIEW','APPLICANT_DECIDE','ACTOR_HISTORY_VIEW','ACTOR_APPLICATIONS_VIEW',
                                    'BROADCAST_SEND','ROSTER_VIEW','ROSTER_EXPORT','ATTENDANCE_RECORD','SETTLEMENT_MANAGE','MEMBER_MANAGE','REVIEW_WRITE')) OR
  (r.code='OPERATOR' AND p.code IN ('REPORT_HANDLE','BOARD_MANAGE','INQUIRY_ANSWER','CONSENT_VERIFY')) OR
  (r.code='ADMIN'    AND p.code IN ('COMPANY_APPROVE','USER_SUSPEND','JOB_HIDE','REPORT_HANDLE','BOARD_MANAGE','INQUIRY_ANSWER',
                                    'TERMS_MANAGE','SETTINGS_MANAGE','AUDIT_VIEW','CONSENT_VERIFY','PLAN_MANAGE')) OR
  (r.code='SUPER_ADMIN');
