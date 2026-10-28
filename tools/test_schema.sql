-- 스키마 동작 검증 (제약이 의도대로 작동하는지). 실패 시 예외로 중단된다.
\set ON_ERROR_STOP on
BEGIN;
-- 재실행 가능하도록 업무 데이터만 비우고 식별자 시퀀스 초기화 (시드 테이블은 유지, 전체가 ROLLBACK 됨)
TRUNCATE users, companies RESTART IDENTITY CASCADE;
INSERT INTO users(email,name,phone) VALUES ('co@x.com','대표','010-0000-0001'),('a@x.com','A','010-0000-0002'),('b@x.com','B','010-0000-0003');
INSERT INTO companies(name,biz_reg_no,representative) VALUES ('보출A','123-45-67890','대표');
INSERT INTO job_postings(company_id,title,created_by) VALUES (1,'T',1);
INSERT INTO job_schedules(posting_id,shoot_date,call_time,end_time) VALUES
  (1,'2026-11-10','06:00','11:00'), (1,'2026-11-10','20:00','23:30'), (1,'2026-11-10','09:00','14:00');
INSERT INTO job_positions(schedule_id,posting_id,title,headcount,standby_headcount) VALUES (1,1,'오전 행인',1,1),(2,1,'야간 행인',2,0),(3,1,'겹치는 낮',2,0);
INSERT INTO actor_profiles(user_id,birth_date,gender) VALUES (2,'1990-01-01','MALE'),(3,'1995-01-01','FEMALE');

-- 1) 같은 날, 시간이 안 겹치는 두 건은 둘 다 확정 가능
INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone,status,confirmed_time_range)
VALUES (1,1,1,'2026-11-10',2,'A','010-0000-0002','CONFIRMED','[2026-11-09 21:00+00, 2026-11-10 03:00+00)');   -- KST 06~12 (버퍼 포함)
INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone,status,confirmed_time_range)
VALUES (2,2,1,'2026-11-10',2,'A','010-0000-0002','CONFIRMED','[2026-11-10 10:00+00, 2026-11-10 15:30+00)');   -- KST 19~24:30
DO $$ BEGIN RAISE NOTICE 'OK 1: non-overlapping same-day double confirm allowed'; END $$;

-- 2) 시간이 겹치면 DB가 확정을 거부
DO $$ BEGIN
  BEGIN
    INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone,status,confirmed_time_range)
    VALUES (3,3,1,'2026-11-10',2,'A','010-0000-0002','CONFIRMED','[2026-11-10 00:00+00, 2026-11-10 05:00+00)');
    RAISE EXCEPTION 'FAIL 2: overlap not blocked';
  EXCEPTION WHEN exclusion_violation THEN RAISE NOTICE 'OK 2: overlapping confirm blocked'; END;
END $$;

-- 3) 겹치는 건도 '지원'(APPLIED)은 가능 (다중 지원 허용)
INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone)
VALUES (3,3,1,'2026-11-10',2,'A','010-0000-0002');
DO $$ BEGIN RAISE NOTICE 'OK 3: overlapping APPLIED allowed'; END $$;

-- 4) CONFIRMED 인데 time range 없으면 거부
DO $$ BEGIN
  BEGIN
    INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone,status)
    VALUES (2,2,1,'2026-11-10',3,'B','010-0000-0003','CONFIRMED');
    RAISE EXCEPTION 'FAIL 4';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'OK 4: CONFIRMED requires time range'; END;
END $$;

-- 5) 같은 역할 중복 지원 차단, 취소 후 재지원 허용
DO $$ BEGIN
  BEGIN
    INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone)
    VALUES (3,3,1,'2026-11-10',2,'A','010-0000-0002');
    RAISE EXCEPTION 'FAIL 5';
  EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'OK 5a: duplicate active application blocked'; END;
END $$;
UPDATE applications SET status='CANCELED_BY_ACTOR' WHERE position_id=3 AND actor_user_id=2;
INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone)
VALUES (3,3,1,'2026-11-10',2,'A','010-0000-0002');
DO $$ BEGIN RAISE NOTICE 'OK 5b: re-apply after cancel allowed'; END $$;

-- 6) 정원 초과 차단
DO $$ BEGIN
  BEGIN UPDATE job_positions SET confirmed_count = headcount + 1 WHERE id = 1; RAISE EXCEPTION 'FAIL 6';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'OK 6: overfill blocked'; END;
END $$;

-- 7) 마감 시 자동 불합격: APPLIED 는 AUTO_REJECTED, 예비합격(WAITLISTED)은 유지
INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone,status)
VALUES (1,1,1,'2026-11-10',3,'B','010-0000-0003','WAITLISTED');
INSERT INTO applications(position_id,schedule_id,posting_id,shoot_date,actor_user_id,applicant_name,applicant_phone,status)
VALUES (2,2,1,'2026-11-10',3,'B','010-0000-0003','APPLIED');
UPDATE applications SET status='AUTO_REJECTED', decided_at=now(), decision_reason='모집 마감 자동 불합격'
 WHERE posting_id=1 AND status IN ('APPLIED','SHORTLISTED');
DO $$ DECLARE w int; r int; BEGIN
  SELECT count(*) INTO w FROM applications WHERE status='WAITLISTED';
  SELECT count(*) INTO r FROM applications WHERE status='AUTO_REJECTED';
  IF w <> 1 OR r < 1 THEN RAISE EXCEPTION 'FAIL 7 w=% r=%', w, r; END IF;
  RAISE NOTICE 'OK 7: auto-reject keeps standby (waitlisted=%, auto_rejected=%)', w, r;
END $$;

-- 8) 인원표/인원정보표 뷰 동작
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM v_schedule_confirmed_roster WHERE posting_id=1;
  IF n <> 2 THEN RAISE EXCEPTION 'FAIL 8 roster=%', n; END IF;
  PERFORM 1 FROM v_schedule_headcount_summary WHERE posting_id=1;
  RAISE NOTICE 'OK 8: views work (roster rows=%)', n;
END $$;

-- 9) 권한/설정 시드
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM role_permissions rp JOIN roles r ON r.id=rp.role_id WHERE r.code='SUPER_ADMIN';
  IF n < 20 THEN RAISE EXCEPTION 'FAIL 9 perms=%', n; END IF;
  RAISE NOTICE 'OK 9: seeds (super admin perms=%)', n;
END $$;
ROLLBACK;
