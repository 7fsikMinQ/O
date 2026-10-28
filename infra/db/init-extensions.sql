-- 운영 DB 최초 1회(슈퍼유저로): 앱 계정은 확장 생성 권한이 없어도 되도록 미리 만든다.
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;
