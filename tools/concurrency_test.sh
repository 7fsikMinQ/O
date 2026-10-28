#!/bin/bash
# 동시성 검증: pgbench 로 (1) 정원 원자 증가 (2) 같은 사람의 겹치는 시간 동시 확정을 실제로 경쟁시켜 본다.
# 사용: PGHOST=/tmp PGPORT=5544 PGUSER=postgres tools/concurrency_test.sh   (임시 DB 를 만들고 지운다)
set -euo pipefail
DB=conc_test
PB=${PGBENCH:-pgbench}
dropdb --if-exists $DB; createdb $DB
psql -q -v ON_ERROR_STOP=1 -d $DB -f "$(dirname "$0")/../db/schema.sql"
psql -q -d $DB <<'SQL'
INSERT INTO users(email,name,phone) SELECT 'u'||g||'@x.com','U'||g,'010-0000-'||lpad(g::text,4,'0') FROM generate_series(1,5) g;
INSERT INTO companies(name,biz_reg_no,representative) VALUES ('C','111-11-11111','R');
INSERT INTO job_postings(company_id,title,created_by) VALUES (1,'T',1);
INSERT INTO job_schedules(posting_id,shoot_date,call_time,end_time) VALUES (1,'2026-11-10','06:00','12:00');
INSERT INTO job_positions(schedule_id,posting_id,title,headcount) VALUES (1,1,'정원10',10);
INSERT INTO job_positions(schedule_id,posting_id,title,headcount) SELECT 1,1,'겹침'||g,5 FROM generate_series(1,20) g;
INSERT INTO actor_profiles(user_id,birth_date,gender) VALUES (2,'1990-01-01','MALE');
SQL
# (1) 200 요청이 정원 10 에 동시에 몰림 → 최종 confirmed_count 는 정확히 10
cat > /tmp/conc1.sql <<'SQL'
UPDATE job_positions SET confirmed_count = confirmed_count + 1 WHERE id = 1 AND confirmed_count < headcount;
SQL
$PB -n -c 50 -j 8 -t 4 -f /tmp/conc1.sql $DB >/dev/null
C1=$(psql -Atq -d $DB -c "SELECT confirmed_count FROM job_positions WHERE id=1")
# (2) 한 사람(2번)의 겹치는 확정 20건 동시 시도 → 정확히 1건만 성공 (제약 위반은 pgbench 가 계속 진행하도록 허용)
cat > /tmp/conc2.sql <<'SQL'
\set pid random(2,21)
INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone,status,confirmed_time_range)
VALUES (:pid,1,1,'2026-11-10',2,'A','010','CONFIRMED','[2026-11-09 20:00+00,2026-11-10 04:00+00)') ON CONFLICT DO NOTHING;
SQL
$PB -n -c 20 -j 4 -t 3 --max-tries=1 -f /tmp/conc2.sql $DB >/dev/null 2>&1 || true
C2=$(psql -Atq -d $DB -c "SELECT count(*) FROM applications WHERE actor_user_id=2 AND status='CONFIRMED'")
dropdb $DB
echo "정원 10 / 동시 200 요청 → confirmed_count=$C1 (기대 10)"
echo "겹치는 시간 동시 확정 60 시도 → CONFIRMED 행=$C2 (기대 1)"
[ "$C1" = "10" ] && [ "$C2" = "1" ] && echo "PASS" || { echo "FAIL"; exit 1; }
