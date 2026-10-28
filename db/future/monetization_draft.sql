-- =====================================================================
-- [초안·미적용] 수익화 확장 DDL — schema.sql 적용 이후 별도 Flyway 버전(V9 등)으로 도입
-- 전제: subscription_plans / plan_features / company_subscriptions / usage_counters 는 이미 schema.sql 에 있음
-- 도입 순서: ① 구독(빌링키·청구서) ② 크레딧 ③ 유료 노출 ④ 쿠폰
-- =====================================================================

CREATE TABLE billing_accounts (                 -- 업체의 결제수단 (PG 빌링키는 앱 암호화)
  company_id        BIGINT PRIMARY KEY REFERENCES companies(id),
  pg_provider       VARCHAR(20)  NOT NULL,                 -- TOSS, PORTONE ...
  pg_customer_key   VARCHAR(100) NOT NULL UNIQUE,
  billing_key_enc   VARCHAR(500),
  card_brand        VARCHAR(30),
  card_last4        CHAR(4),
  status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE subscription_invoices (            -- 청구서 (기간 단위)
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id       BIGINT NOT NULL REFERENCES companies(id),
  subscription_id  BIGINT NOT NULL REFERENCES company_subscriptions(id),
  period_start     DATE   NOT NULL,
  period_end       DATE   NOT NULL,
  supply_krw       INT    NOT NULL CHECK (supply_krw >= 0),
  vat_krw          INT    NOT NULL DEFAULT 0 CHECK (vat_krw >= 0),
  status           VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED','VOID')),
  tax_invoice_no   VARCHAR(50),                             -- 전자세금계산서 발행 번호
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (subscription_id, period_start)
);

CREATE TABLE payment_transactions (             -- PG 거래 원장 (구독·크레딧·노출 공통)
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id       BIGINT NOT NULL REFERENCES companies(id),
  kind             VARCHAR(20) NOT NULL CHECK (kind IN ('SUBSCRIPTION','CREDIT_PURCHASE','PROMOTION')),
  invoice_id       BIGINT REFERENCES subscription_invoices(id),
  amount_krw       INT    NOT NULL CHECK (amount_krw >= 0),
  status           VARCHAR(20) NOT NULL CHECK (status IN ('READY','PAID','FAILED','CANCELED','PARTIAL_CANCELED')),
  pg_payment_key   VARCHAR(100) UNIQUE,
  idempotency_key  VARCHAR(100) UNIQUE,                     -- 이중 결제 방지
  failure_reason   VARCHAR(300),
  raw_response     JSONB,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_company ON payment_transactions (company_id, created_at DESC);

CREATE TABLE credit_wallets (                   -- 알림톡/SMS 등 종량 크레딧 잔액
  company_id  BIGINT PRIMARY KEY REFERENCES companies(id),
  balance     INT NOT NULL DEFAULT 0 CHECK (balance >= 0),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE credit_transactions (              -- 잔액 변경 원장 (잔액 = 원장 합계, 불일치 점검 배치)
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id    BIGINT NOT NULL REFERENCES companies(id),
  type          VARCHAR(10) NOT NULL CHECK (type IN ('CHARGE','USE','REFUND','EXPIRE','BONUS')),
  amount        INT NOT NULL,                               -- USE/EXPIRE 는 음수
  balance_after INT NOT NULL CHECK (balance_after >= 0),
  ref_type      VARCHAR(30),                                -- BROADCAST, PAYMENT ...
  ref_id        BIGINT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_tx_company ON credit_transactions (company_id, created_at DESC);

CREATE TABLE promotions (                       -- 유료 노출 (긴급 상단 고정 / 추천 공고)
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id    BIGINT NOT NULL REFERENCES companies(id),
  posting_id    BIGINT NOT NULL REFERENCES job_postings(id),
  type          VARCHAR(20) NOT NULL CHECK (type IN ('URGENT_TOP','FEATURED')),
  starts_at     TIMESTAMPTZ NOT NULL,
  ends_at       TIMESTAMPTZ NOT NULL CHECK (ends_at > starts_at),
  price_krw     INT NOT NULL CHECK (price_krw >= 0),
  payment_id    BIGINT REFERENCES payment_transactions(id),
  status        VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','ACTIVE','ENDED','CANCELED')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_promotions_active ON promotions (type, starts_at, ends_at) WHERE status IN ('SCHEDULED','ACTIVE');

CREATE TABLE coupons (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code          VARCHAR(30) NOT NULL UNIQUE,
  kind          VARCHAR(20) NOT NULL CHECK (kind IN ('PLAN_TRIAL','PERCENT_OFF','AMOUNT_OFF','CREDIT')),
  value         INT NOT NULL,
  plan_id       BIGINT REFERENCES subscription_plans(id),
  max_redemptions INT,
  valid_until   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE coupon_redemptions (
  coupon_id     BIGINT NOT NULL REFERENCES coupons(id),
  company_id    BIGINT NOT NULL REFERENCES companies(id),
  redeemed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (coupon_id, company_id)
);
