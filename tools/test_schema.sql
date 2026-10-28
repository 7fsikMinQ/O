-- 스키마 v1.1 동작 검증. 업무 규칙이 DB 에서 실제로 지켜지는지 확인한다. 실패 시 예외로 중단, 전체 ROLLBACK 되어 재실행 가능.
\set ON_ERROR_STOP on
BEGIN;
-- (시드 검증은 TRUNCATE 이전에 수행)
DO $$ DECLARE w int; p int; BEGIN
  SELECT hourly_krw INTO w FROM minimum_wages WHERE year=2026; IF w IS DISTINCT FROM 10320 THEN RAISE EXCEPTION 'FAIL seed 최저시급 2026'; END IF;
  SELECT hourly_krw INTO w FROM minimum_wages WHERE year=2027; IF w IS DISTINCT FROM 10700 THEN RAISE EXCEPTION 'FAIL seed 최저시급 2027'; END IF;
  SELECT count(*) INTO p FROM regions WHERE level=1; IF p<>17 THEN RAISE EXCEPTION 'FAIL seed 시도 %',p; END IF;
  SELECT count(*) INTO p FROM role_permissions rp JOIN roles r ON r.id=rp.role_id WHERE r.code='SUPER_ADMIN'; IF p<25 THEN RAISE EXCEPTION 'FAIL seed 권한 %',p; END IF;
  RAISE NOTICE 'OK T0: 시드(최저시급 2026=10,320 · 2027=10,700 · 시/도 17 · 권한 %)', p;
END $$;
TRUNCATE users, companies RESTART IDENTITY CASCADE;
INSERT INTO users(email,name,phone) VALUES ('co@x.com','대표','010-0000-0001'),('a@x.com','A','010-0000-0002'),('b@x.com','B','010-0000-0003'),('c@x.com','C','010-0000-0004');
INSERT INTO companies(name,biz_reg_no,representative) VALUES ('보출A','123-45-67890','대표');
INSERT INTO actor_profiles(user_id,birth_date,gender,height_cm,weight_kg) VALUES (2,'1990-01-01','MALE',178,72),(3,'1995-05-05','FEMALE',165,52),(4,'1988-03-03','MALE',172,70);
INSERT INTO job_postings(company_id,title,created_by) VALUES (1,'사극 A현장(가제)',1),(1,'현대극 B(가제)',1);
-- 일정: 1) 오전 6~12  2) 야간 20~익일 02(자정 넘김)  3) 오전 9~14(1과 겹침)  4) 2박3일 숙박  5) 5박6일 숙박
INSERT INTO job_schedules(posting_id,start_at,end_at,is_lodging) VALUES
  (1,'2026-11-10 06:00+09','2026-11-10 12:00+09',false),
  (1,'2026-11-10 20:00+09','2026-11-11 02:00+09',false),
  (2,'2026-11-10 09:00+09','2026-11-10 14:00+09',false),
  (2,'2026-11-20 08:00+09','2026-11-22 18:00+09',true),
  (2,'2026-12-01 08:00+09','2026-12-06 18:00+09',true);
INSERT INTO job_positions(schedule_id,posting_id,title,headcount,wage_mode,hourly_wage_krw,min_wage_year) VALUES
  (1,1,'오전 행인',2,'MIN_WAGE',10320,2026),(2,1,'야간 행인',2,'MIN_WAGE',10320,2026),(3,2,'겹치는 낮',2,'CUSTOM',12000,NULL),(4,2,'2박3일',1,'MIN_WAGE',10320,2026);

-- T1 일정 길이: 1시간 미만·7일 초과 거부, 2박3일·5박6일·자정 넘김 허용, 날짜 계산(KST)
DO $$ DECLARE d1 date; d2 date; BEGIN
  BEGIN INSERT INTO job_schedules(posting_id,start_at,end_at) VALUES (1,'2026-11-10 06:00+09','2026-11-10 06:30+09'); RAISE EXCEPTION 'FAIL T1a';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN INSERT INTO job_schedules(posting_id,start_at,end_at) VALUES (1,'2026-11-10 06:00+09','2026-12-02 06:00+09'); RAISE EXCEPTION 'FAIL T1b';   -- 22일
  EXCEPTION WHEN check_violation THEN NULL; END;
  INSERT INTO job_schedules(posting_id,start_at,end_at) VALUES (1,'2026-11-10 06:00+09','2026-12-01 06:00+09');   -- 정확히 21일은 허용
  SELECT start_date,end_date INTO d1,d2 FROM job_schedules WHERE id=2;
  IF d1<>'2026-11-10' OR d2<>'2026-11-11' THEN RAISE EXCEPTION 'FAIL T1c % %',d1,d2; END IF;
  SELECT start_date,end_date INTO d1,d2 FROM job_schedules WHERE id=5;
  IF d2-d1 <> 5 THEN RAISE EXCEPTION 'FAIL T1d'; END IF;
  RAISE NOTICE 'OK T1: 1시간~21일(3주) 범위, 22일 거부, 자정 넘김(2일)·5박6일 허용, KST 날짜 생성열';
END $$;

-- T2 시급제: 최저시급 선택이면 기준 연도 필수
DO $$ BEGIN
  BEGIN INSERT INTO job_positions(schedule_id,posting_id,title,headcount,wage_mode,hourly_wage_krw) VALUES (1,1,'x',1,'MIN_WAGE',10320); RAISE EXCEPTION 'FAIL T2';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'OK T2: MIN_WAGE 는 min_wage_year 필수'; END;
END $$;

-- 지원 3건 (APPLIED)
INSERT INTO applications(position_id,schedule_id,posting_id,actor_user_id,work_start_at,work_end_at,applicant_name,applicant_phone,profile_snapshot)
SELECT p.id,p.schedule_id,p.posting_id,2,s.start_at,s.end_at,'A','010-0000-0002','{"gender":"MALE","height_cm":178,"weight_kg":72,"home_address":"서울 강남구"}'::jsonb
FROM job_positions p JOIN job_schedules s ON s.id=p.schedule_id WHERE p.id IN (1,2,3);

-- T3 합격 통보(SELECTED)는 예약 구간 필수. 통보 단계에서는 겹침이 있어도 허용 (경고만)
DO $$ BEGIN
  BEGIN UPDATE applications SET status='SELECTED' WHERE position_id=1 AND actor_user_id=2; RAISE EXCEPTION 'FAIL T3a';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
UPDATE applications SET status='SELECTED', selected_at=now(), reserved_range='[2026-11-09 20:00+00,2026-11-10 04:00+00)' WHERE position_id=1 AND actor_user_id=2;
UPDATE applications SET status='SELECTED', selected_at=now(), reserved_range='[2026-11-10 10:00+00,2026-11-10 18:00+00)' WHERE position_id=2 AND actor_user_id=2;
UPDATE applications SET status='SELECTED', selected_at=now(), reserved_range='[2026-11-10 00:00+00,2026-11-10 06:00+00)' WHERE position_id=3 AND actor_user_id=2;
DO $$ BEGIN RAISE NOTICE 'OK T3: SELECTED 는 구간 필수, 겹치는 SELECTED 3건 허용'; END $$;

-- T4 출연자 확정: 겹치지 않는 2건은 둘 다 확정, 겹치는 3번째는 DB 가 거부
UPDATE applications SET status='CONFIRMED', confirmed_at=now() WHERE position_id=1 AND actor_user_id=2;
UPDATE applications SET status='CONFIRMED', confirmed_at=now() WHERE position_id=2 AND actor_user_id=2;
DO $$ BEGIN
  BEGIN UPDATE applications SET status='CONFIRMED', confirmed_at=now() WHERE position_id=3 AND actor_user_id=2; RAISE EXCEPTION 'FAIL T4';
  EXCEPTION WHEN exclusion_violation THEN RAISE NOTICE 'OK T4: 비겹침 2건 확정 허용, 겹치는 확정은 DB 거부'; END;
END $$;

-- T5 경고를 보고 의도적으로 진행(conflict_override) → 확정되고 충돌 추적 행 생성
UPDATE applications SET status='CONFIRMED', confirmed_at=now(), conflict_override=true WHERE position_id=3 AND actor_user_id=2;
INSERT INTO schedule_conflicts(actor_user_id,application_a_id,application_b_id) VALUES (2,1,3);
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM schedule_conflicts WHERE acknowledged_at IS NULL AND resolved_at IS NULL;
  IF n<>1 THEN RAISE EXCEPTION 'FAIL T5'; END IF;
  UPDATE schedule_conflicts SET acknowledged_at=now(), resolution='ACK_ONLY' WHERE application_b_id=3;
  SELECT count(*) INTO n FROM schedule_conflicts WHERE acknowledged_at IS NULL AND resolved_at IS NULL;
  IF n<>0 THEN RAISE EXCEPTION 'FAIL T5b'; END IF;
  RAISE NOTICE 'OK T5: 명시적 진행(override) 허용 + 충돌 추적/확인 시 재알림 대상에서 제외';
END $$;

-- T6 정원: 통보+확정 합계가 정원 초과 불가
DO $$ BEGIN
  UPDATE job_positions SET selected_count=1, confirmed_count=1 WHERE id=1;
  BEGIN UPDATE job_positions SET selected_count=2 WHERE id=1; RAISE EXCEPTION 'FAIL T6';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'OK T6: selected+confirmed <= headcount'; END;
END $$;

-- T7 마감 자동 불합격은 APPLIED 만. 이후 업체가 가용 확인 후 같은 지원을 다시 SELECTED 로 (예비 제도 없이 재선발)
INSERT INTO applications(position_id,schedule_id,posting_id,actor_user_id,work_start_at,work_end_at,applicant_name,applicant_phone,profile_snapshot)
SELECT 4,4,2,u,s.start_at,s.end_at,'N','010',  '{}'::jsonb FROM job_schedules s, (VALUES (3),(4)) v(u) WHERE s.id=4;
UPDATE applications SET status='AUTO_REJECTED' WHERE schedule_id=4 AND status='APPLIED';
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM applications WHERE status='AUTO_REJECTED'; IF n<>2 THEN RAISE EXCEPTION 'FAIL T7a %',n; END IF;
  -- 마감 후 추가 합격 후보: 2026-11-20 08:00~22일 18:00 일정의 ±1일 안에 SELECTED/CONFIRMED 가 없는 지원자
  SELECT count(DISTINCT a.actor_user_id) INTO n FROM applications a
   WHERE a.schedule_id=4 AND a.status='AUTO_REJECTED'
     AND NOT EXISTS (SELECT 1 FROM applications b WHERE b.actor_user_id=a.actor_user_id AND b.status IN ('SELECTED','CONFIRMED')
                       AND b.reserved_range && tstzrange(a.work_start_at - interval '1 day', a.work_end_at + interval '1 day'));
  IF n<>2 THEN RAISE EXCEPTION 'FAIL T7b %',n; END IF;
  UPDATE applications SET status='SELECTED', selected_at=now(), reserved_range=tstzrange(work_start_at,work_end_at) WHERE schedule_id=4 AND actor_user_id=3;
  RAISE NOTICE 'OK T7: 자동 불합격 후 ±1일 가용 판단 쿼리 → 재선발(SELECTED) 가능';
END $$;

-- T8 활성 중복 지원 차단, 취소 후 재지원 허용
DO $$ BEGIN
  BEGIN INSERT INTO applications(position_id,schedule_id,posting_id,actor_user_id,work_start_at,work_end_at,applicant_name,applicant_phone,profile_snapshot)
        SELECT 4,4,2,3,start_at,end_at,'N','010','{}' FROM job_schedules WHERE id=4; RAISE EXCEPTION 'FAIL T8a';
  EXCEPTION WHEN unique_violation THEN NULL; END;
  UPDATE applications SET status='CANCELED_BY_ACTOR', canceled_at=now(), cancel_reason='개인 사정', reserved_range=NULL WHERE schedule_id=4 AND actor_user_id=3;
  INSERT INTO applications(position_id,schedule_id,posting_id,actor_user_id,work_start_at,work_end_at,applicant_name,applicant_phone,profile_snapshot)
        SELECT 4,4,2,3,start_at,end_at,'N','010','{}' FROM job_schedules WHERE id=4;
  RAISE NOTICE 'OK T8: 활성 중복 차단 / 취소 후 재지원 허용';
END $$;

-- T9 취소 패널티: 스케줄러 4명이 모두 PENALTY 투표해도 공고당 1건만 유효
INSERT INTO users(email,name) VALUES ('s1@x.com','S1'),('s2@x.com','S2');
INSERT INTO company_members(company_id,user_id,member_role) VALUES (1,5,'SCHEDULER'),(1,6,'SCHEDULER');
INSERT INTO cancellation_reviews(application_id,company_id,posting_id,actor_user_id) VALUES (1,1,1,2);
INSERT INTO cancellation_review_votes VALUES (1,5,'PENALTY',now()),(1,6,'PENALTY',now());
INSERT INTO actor_penalty_events(user_id,posting_id,application_id,kind,decided_by) VALUES (2,1,1,'CANCEL_PENALTY',5);
DO $$ BEGIN
  BEGIN INSERT INTO actor_penalty_events(user_id,posting_id,application_id,kind,decided_by) VALUES (2,1,1,'CANCEL_PENALTY',6); RAISE EXCEPTION 'FAIL T9';
  EXCEPTION WHEN unique_violation THEN NULL; END;
  UPDATE actor_penalty_events SET voided_at=now() WHERE user_id=2 AND posting_id=1;      -- 소명 인정 시 무효화 후 재부과 가능
  INSERT INTO actor_penalty_events(user_id,posting_id,application_id,kind,decided_by) VALUES (2,1,1,'CANCEL_PENALTY',5);
  RAISE NOTICE 'OK T9: 공고당 패널티 1건(중복 투표 무관), 무효화 후 재부과 가능';
END $$;

-- T10 주민번호: 활성 해시 중복(동일인 재가입) 거부, 버전 교체 허용
INSERT INTO terms(type,version,content,is_required,effective_at) VALUES ('SENSITIVE_ID','t10','고유식별정보 처리 동의',true,now());
INSERT INTO actor_tax_identities(user_id,rrn_enc,rrn_hmac,consent_term_id,consented_at) SELECT 2,'enc1',repeat('a',64),id,now() FROM terms WHERE version='t10';
DO $$ BEGIN
  BEGIN INSERT INTO actor_tax_identities(user_id,rrn_enc,rrn_hmac,consent_term_id,consented_at) SELECT 3,'enc2',repeat('a',64),id,now() FROM terms WHERE version='t10'; RAISE EXCEPTION 'FAIL T10a';
  EXCEPTION WHEN unique_violation THEN NULL; END;
  UPDATE actor_tax_identities SET is_active=false, superseded_at=now() WHERE user_id=2;
  INSERT INTO actor_tax_identities(user_id,rrn_enc,rrn_hmac,consent_term_id,consented_at) SELECT 2,'enc3',repeat('b',64),id,now() FROM terms WHERE version='t10';
  RAISE NOTICE 'OK T10: 동일 주민번호 해시 중복 가입 차단 / 버전 교체 이력 보존';
END $$;

-- T11 다일(숙박) 출결: 지원 × 근무일 1행, 중복 거부
UPDATE applications SET status='SELECTED', selected_at=now(), reserved_range=tstzrange(work_start_at,work_end_at) WHERE schedule_id=4 AND actor_user_id=3 AND status='APPLIED';
INSERT INTO attendances(application_id,work_date,result) SELECT a.id, d::date, 'PENDING' FROM applications a, generate_series('2026-11-20'::date,'2026-11-22'::date,'1 day') d WHERE a.schedule_id=4 AND a.status='SELECTED';
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM attendances WHERE work_date BETWEEN '2026-11-20' AND '2026-11-22'; IF n<>3 THEN RAISE EXCEPTION 'FAIL T11a %',n; END IF;
  BEGIN INSERT INTO attendances(application_id,work_date) SELECT application_id,work_date FROM attendances LIMIT 1; RAISE EXCEPTION 'FAIL T11b';
  EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'OK T11: 숙박 3일 → 출결 3행, 같은 날 중복 거부'; END;
END $$;

-- T12 메시지: 업체가 시작한 스레드에 출연자가 답장
INSERT INTO message_threads(company_id,actor_user_id,posting_id,started_by_user_id,subject) VALUES (1,3,2,1,'추가 합격 문의');
INSERT INTO messages(thread_id,sender_user_id,sender_side,body) VALUES (1,1,'COMPANY','해당 일자 가능하신가요?'),(1,3,'ACTOR','가능합니다');
DO $$ DECLARE n int; BEGIN SELECT count(*) INTO n FROM messages WHERE thread_id=1; IF n<>2 THEN RAISE EXCEPTION 'FAIL T12'; END IF; RAISE NOTICE 'OK T12: 업체→출연자 스레드와 답장'; END $$;

-- T11b 다일 출결이 있어도 인원표는 지원 1건을 1명으로 센다 (3일 출근 → attended_count=1)
UPDATE attendances SET result='ATTENDED', worked_minutes=540;
DO $$ DECLARE n int; d int; BEGIN
  SELECT attended_count INTO n FROM v_schedule_headcount_summary WHERE position_id=4;
  IF n<>1 THEN RAISE EXCEPTION 'FAIL T11b attended_count=%', n; END IF;
  SELECT attended_days INTO d FROM v_schedule_confirmed_roster WHERE schedule_id=4 AND actor_user_id=3;
  IF d<>3 THEN RAISE EXCEPTION 'FAIL T11c attended_days=%', d; END IF;
  RAISE NOTICE 'OK T11b: 숙박 3일 출근 → 인원표 1명, 정보표 출근 3일';
END $$;

-- T11d 설계 한계 확인: override 행은 EXCLUDE 대상 밖이므로, 앱이 모든 CONFIRMED(override 포함) 대상으로 겹침을 먼저 검사해야 한다
-- A(06~12 정상) , B(09~14 override), C(13~18): C 는 A 와 안 겹쳐 DB 가 통과시키지만 B 와는 겹침 → 앱 검사 쿼리가 잡아야 함
INSERT INTO applications(position_id,schedule_id,posting_id,actor_user_id,work_start_at,work_end_at,applicant_name,applicant_phone,profile_snapshot,status,reserved_range,conflict_override)
VALUES (3,3,2,4,'2026-12-10 06:00+09','2026-12-10 12:00+09','C','010','{}','CONFIRMED','[2026-12-09 21:00+00,2026-12-10 03:00+00)',false);
INSERT INTO applications(position_id,schedule_id,posting_id,actor_user_id,work_start_at,work_end_at,applicant_name,applicant_phone,profile_snapshot,status,reserved_range,conflict_override)
VALUES (2,2,1,4,'2026-12-10 09:00+09','2026-12-10 14:00+09','C','010','{}','CONFIRMED','[2026-12-10 00:00+00,2026-12-10 05:00+00)',true);
DO $$ DECLARE n int; BEGIN
  INSERT INTO applications(position_id,schedule_id,posting_id,actor_user_id,work_start_at,work_end_at,applicant_name,applicant_phone,profile_snapshot,status,reserved_range)
  VALUES (1,1,1,4,'2026-12-10 13:00+09','2026-12-10 18:00+09','C','010','{}','SELECTED','[2026-12-10 03:30+00,2026-12-10 09:30+00)');
  -- DB 는 이 SELECTED 의 확정(CONFIRMED, override=false)을 B 와의 겹침으로는 막지 못한다 → 앱 검사 쿼리 필요
  SELECT count(*) INTO n FROM applications x, applications y
   WHERE x.actor_user_id=4 AND y.actor_user_id=4 AND x.id<y.id AND x.status IN ('SELECTED','CONFIRMED') AND y.status IN ('SELECTED','CONFIRMED')
     AND x.reserved_range && y.reserved_range;
  IF n < 2 THEN RAISE EXCEPTION 'FAIL T11d overlap pairs=%', n; END IF;
  RAISE NOTICE 'OK T11d: 앱 검사 쿼리(전체 CONFIRMED/SELECTED 대상)가 override 행 포함 겹침 쌍 %개를 탐지', n;
END $$;

-- T4b 간격이 짧으면 겹침: 양끝 버퍼 60분(= conflict_gap_minutes 120 의 절반)을 붙이면 간격 90분은 겹치고 150분은 겹치지 않는다
DO $$ BEGIN
  IF NOT (tstzrange('2026-12-20 06:00+09'::timestamptz - interval '60 minutes', '2026-12-20 12:00+09'::timestamptz + interval '60 minutes')
       && tstzrange('2026-12-20 13:30+09'::timestamptz - interval '60 minutes', '2026-12-20 18:00+09'::timestamptz + interval '60 minutes')) THEN RAISE EXCEPTION 'FAIL T4b 간격 90분은 겹침이어야 함'; END IF;
  IF (tstzrange('2026-12-20 06:00+09'::timestamptz - interval '60 minutes', '2026-12-20 12:00+09'::timestamptz + interval '60 minutes')
       && tstzrange('2026-12-20 14:30+09'::timestamptz - interval '60 minutes', '2026-12-20 18:00+09'::timestamptz + interval '60 minutes')) THEN RAISE EXCEPTION 'FAIL T4b 간격 150분은 겹침이 아니어야 함'; END IF;
  RAISE NOTICE 'OK T4b: 간격 90분=겹침(경고), 150분=비겹침';
END $$;

-- T14 중요(CRITICAL) 알림 폴백 대상 조회 + 재전달 기록 (iPhone 웹푸시 불가 사용자 대응)
INSERT INTO notifications(user_id,type,title,priority) VALUES (2,'APPLICATION_SELECTED','합격했어요','CRITICAL'),(2,'MESSAGE_RECEIVED','메시지','NORMAL');
INSERT INTO device_tokens(user_id,platform,token,is_pwa_installed,permission) VALUES (2,'WEB','tok-ios-safari-tab',false,'DEFAULT');
INSERT INTO notification_deliveries(notification_id,channel,status) SELECT id,'PUSH','FAILED' FROM notifications WHERE priority='CRITICAL';
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM notifications nt
   WHERE nt.priority='CRITICAL' AND nt.read_at IS NULL
     AND NOT EXISTS (SELECT 1 FROM notification_deliveries d WHERE d.notification_id=nt.id AND d.status='SENT');
  IF n<>1 THEN RAISE EXCEPTION 'FAIL T14a %', n; END IF;
  INSERT INTO notification_deliveries(notification_id,channel,status,fallback_of_id)
    SELECT d.notification_id,'EMAIL','PENDING',d.id FROM notification_deliveries d JOIN notifications nt ON nt.id=d.notification_id WHERE nt.priority='CRITICAL' AND d.channel='PUSH' AND d.status='FAILED';
  RAISE NOTICE 'OK T14: CRITICAL 알림만 폴백 대상 조회(%건), 폴백 전달이 원래 전달에 연결', n;
END $$;

-- T15 숨김 기능 플래그: 정산·이체·미성년·송금 실행은 기본 OFF, 법무 확인 필요 표시
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM feature_flags WHERE code IN ('SETTLEMENT_RECORD','BANK_TRANSFER_FILE','WITHHOLDING_EXPORT','PAYOUT_EXECUTION','MINOR_APPLY') AND (enabled OR NOT needs_legal_review);
  IF n<>0 THEN RAISE EXCEPTION 'FAIL T15 %', n; END IF;
  RAISE NOTICE 'OK T15: 돈·법무 관련 5개 기능은 기본 OFF + 법무 확인 필요';
END $$;

-- T16 이체 배치: 지원서-정산-배치 연결, 상태 CHECK
DO $$ DECLARE sid bigint; bid bigint; BEGIN
  INSERT INTO settlements(application_id,gross_amount,net_amount) SELECT id,82560,79834 FROM applications ORDER BY id LIMIT 1 RETURNING id INTO sid;
  INSERT INTO payout_batches(company_id,item_count,total_amount) VALUES (1,1,79834) RETURNING id INTO bid;
  INSERT INTO payout_batch_items(batch_id,settlement_id,application_id,account_last4,amount,holder_name_matched)
    SELECT bid,sid,application_id,'1234',79834,true FROM settlements WHERE id=sid;
  UPDATE settlements SET payout_batch_id=bid WHERE id=sid;
  BEGIN UPDATE payout_batches SET status='SENT' WHERE id=bid; RAISE EXCEPTION 'FAIL T16 상태 CHECK';
  EXCEPTION WHEN check_violation THEN NULL; END;
  RAISE NOTICE 'OK T16: 이체 배치 연결(송금 실행 상태 없음), 잘못된 상태 거부';
END $$;

-- T17 시간 허용 오차: 기본 집합 ±2시간 / 종료 ±4시간, 범위 밖 거부, 안내 문구 시드
DO $$ DECLARE g int; e int; BEGIN
  SELECT gather_tolerance_minutes,end_tolerance_minutes INTO g,e FROM job_schedules ORDER BY id LIMIT 1;
  IF g<>120 OR e<>240 THEN RAISE EXCEPTION 'FAIL T17a % %',g,e; END IF;
  BEGIN UPDATE job_schedules SET gather_tolerance_minutes=999 WHERE id=(SELECT min(id) FROM job_schedules); RAISE EXCEPTION 'FAIL T17b';
  EXCEPTION WHEN check_violation THEN NULL; END;
  IF NOT EXISTS (SELECT 1 FROM app_settings WHERE key='time_estimate_notice') THEN RAISE EXCEPTION 'FAIL T17c'; END IF;
  RAISE NOTICE 'OK T17: 집합 ±2h·종료 ±4h 기본값, 범위 CHECK, 예상시간 안내 문구';
END $$;

-- T18 휴대폰 로그인 계정(이메일 없음) 허용, 연락처 전무 거부, OTP·삭제 요청·차단 기록
DO $$ BEGIN
  INSERT INTO users(email,name,phone) VALUES (NULL,'폰전용','010-5555-0001');
  BEGIN INSERT INTO users(email,name,phone) VALUES (NULL,'연락처없음',NULL); RAISE EXCEPTION 'FAIL T18a';
  EXCEPTION WHEN check_violation THEN NULL; END;
  INSERT INTO otp_challenges(phone_hash,purpose,code_hash,expires_at) VALUES (repeat('a',64),'LOGIN',repeat('b',64),now()+interval '3 minutes');
  BEGIN INSERT INTO otp_challenges(phone_hash,purpose,code_hash,expires_at) VALUES (repeat('a',64),'X',repeat('b',64),now()); RAISE EXCEPTION 'FAIL T18b';
  EXCEPTION WHEN check_violation THEN NULL; END;
  INSERT INTO account_deletion_requests(contact_hash,channel) VALUES (repeat('c',64),'WEB');
  BEGIN INSERT INTO account_deletion_requests(channel) VALUES ('APP'); RAISE EXCEPTION 'FAIL T18c';
  EXCEPTION WHEN check_violation THEN NULL; END;
  INSERT INTO actor_company_blocks(actor_user_id,company_id) VALUES (2,1);
  IF (SELECT enabled FROM feature_flags WHERE code='REVIEW_DEMO_LOGIN') THEN RAISE EXCEPTION 'FAIL T18d'; END IF;
  RAISE NOTICE 'OK T18: 폰 전용 계정·OTP·웹/앱 탈퇴 요청·업체 차단, 심사 데모 로그인 기본 OFF';
END $$;

-- T19 공지 읽음 확인·재안내 횟수 상한, ⭐ 출연자 중복 방지, ⭐ 한도 시드
DO $$ DECLARE bid bigint; aid bigint; BEGIN
  INSERT INTO job_broadcasts(company_id,posting_id,sender_user_id,title,message_body) VALUES (1,1,1,'공지','내용') RETURNING id INTO bid;
  SELECT id INTO aid FROM applications ORDER BY id LIMIT 1;
  INSERT INTO job_broadcast_recipients(broadcast_id,application_id,actor_user_id,delivered_at) SELECT bid,id,actor_user_id,now() FROM applications WHERE id=aid;
  UPDATE job_broadcast_recipients SET read_at=now() WHERE broadcast_id=bid;
  BEGIN UPDATE job_broadcast_recipients SET reminder_count=6 WHERE broadcast_id=bid; RAISE EXCEPTION 'FAIL T19a';
  EXCEPTION WHEN check_violation THEN NULL; END;
  INSERT INTO company_starred_actors(company_id,actor_user_id,note) VALUES (1,2,'성실');
  BEGIN INSERT INTO company_starred_actors(company_id,actor_user_id) VALUES (1,2); RAISE EXCEPTION 'FAIL T19b';
  EXCEPTION WHEN unique_violation THEN NULL; END;
  IF (SELECT limit_value FROM plan_features pf JOIN subscription_plans sp ON sp.id=pf.plan_id WHERE sp.code='FREE' AND pf.feature_code='STAR_ACTORS')<>30 THEN RAISE EXCEPTION 'FAIL T19c'; END IF;
  RAISE NOTICE 'OK T19: 읽음 확인·재안내 5회 상한, ⭐ 중복 거부, 무료 ⭐ 30명 한도';
END $$;

-- T20 ⭐ 우선 알림: 일반 알림은 우선 시간(1분)만큼 늦게 처리, 범위 CHECK, 구독 전 직접 지정 기능 OFF
DO $$ DECLARE n int; BEGIN
  INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,available_at) VALUES
    ('JOB_POSTING',1,'NOTIFY_STARRED','{}', now()),
    ('JOB_POSTING',1,'NOTIFY_GENERAL','{}', now() + (SELECT value::int FROM app_settings WHERE key='star_head_start_minutes') * interval '1 minute');
  SELECT count(*) INTO n FROM outbox_events WHERE status='NEW' AND available_at <= now() AND event_type IN ('NOTIFY_STARRED','NOTIFY_GENERAL');
  IF n<>1 THEN RAISE EXCEPTION 'FAIL T20a %', n; END IF;
  BEGIN UPDATE job_postings SET star_head_start_minutes=5000 WHERE id=1; RAISE EXCEPTION 'FAIL T20b';
  EXCEPTION WHEN check_violation THEN NULL; END;
  IF (SELECT enabled FROM plan_features pf JOIN subscription_plans sp ON sp.id=pf.plan_id WHERE sp.code='FREE' AND pf.feature_code='STAR_HEAD_START_CUSTOM') THEN RAISE EXCEPTION 'FAIL T20c'; END IF;
  RAISE NOTICE 'OK T20: ⭐ 알림이 먼저 처리되고 일반 알림은 1분 뒤, 시간 직접 지정은 무료 플랜 OFF';
END $$;

-- T21 약관 초안 시드: 6종, 가입 필수 3종(SERVICE·PRIVACY·THIRD_PARTY_PROVISION) 포함, 종류당 현재 유효 버전 1개
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM (SELECT DISTINCT ON (type) id FROM terms WHERE effective_at <= now() ORDER BY type, effective_at DESC) t;
  IF n<>6 THEN RAISE EXCEPTION 'FAIL T21a %', n; END IF;
  IF (SELECT count(*) FROM terms WHERE type IN ('SERVICE','PRIVACY','THIRD_PARTY_PROVISION') AND is_required) <> 3 THEN RAISE EXCEPTION 'FAIL T21b'; END IF;
  RAISE NOTICE 'OK T21: 약관 초안 6종 시드, 가입 필수 3종';
END $$;

-- T13 뷰
DO $$ DECLARE n int; BEGIN
  SELECT count(*) INTO n FROM v_schedule_confirmed_roster; IF n<1 THEN RAISE EXCEPTION 'FAIL T13a'; END IF;
  PERFORM 1 FROM v_schedule_headcount_summary;
  RAISE NOTICE 'OK T13: 뷰(인원표·정보표) 동작, 정보표 행=%', n;
END $$;
ROLLBACK;
