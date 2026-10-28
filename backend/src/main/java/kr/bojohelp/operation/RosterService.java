package kr.bojohelp.operation;

import java.util.List;
import java.util.Map;
import kr.bojohelp.common.Rows;
import kr.bojohelp.crypto.FieldCipher;
import kr.bojohelp.profile.RrnPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 인원표(역할별 정원·지원·통보·확정·참석 확인·출석 집계)와 인원 정보표. 주민번호는 항상 마스킹, 계좌는 끝 4자리만 — 전체는 엑셀(암호화)·열람 API 만. */
@Service
public class RosterService {

    private final JdbcClient jdbc;
    private final FieldCipher cipher;

    public RosterService(JdbcClient jdbc, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public List<Map<String, Object>> headcount(long scheduleId) {
        return jdbc.sql("SELECT * FROM v_schedule_headcount_summary WHERE schedule_id = :s ORDER BY position_id").param("s", scheduleId).query(Rows.camel()).list();
    }

    /** 합격 통보 이상 인원의 정보표. */
    public List<Map<String, Object>> roster(long scheduleId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT a.id AS application_id, a.actor_user_id, a.status, a.applicant_name AS name, a.applicant_phone AS phone, pos.title AS position_title, a.departure_location,
                       a.profile_snapshot ->> 'gender' AS gender, a.profile_snapshot ->> 'birth_date' AS birth_date, (a.profile_snapshot ->> 'height_cm')::int AS height_cm,
                       (a.profile_snapshot ->> 'weight_kg')::int AS weight_kg, a.profile_snapshot ->> 'top_size' AS top_size, a.profile_snapshot ->> 'bottom_size' AS bottom_size,
                       a.profile_snapshot ->> 'shoe_size' AS shoe_size, a.profile_snapshot ->> 'home_address' AS home_address, a.profile_snapshot ->> 'home_address_detail' AS home_address_detail,
                       a.bank_name, a.account_holder, a.account_number_enc, t.rrn_enc, a.departure_confirmed_at, a.conflict_override,
                       (SELECT count(*) FROM company_starred_actors st JOIN job_postings p ON p.id = a.posting_id WHERE st.company_id = p.company_id AND st.actor_user_id = a.actor_user_id) > 0 AS starred,
                       att.result AS attendance_result, att.checkin_at, att.checkout_at
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id LEFT JOIN actor_tax_identities t ON t.id = a.tax_identity_id
                  LEFT JOIN attendances att ON att.application_id = a.id AND att.work_date = (a.work_start_at AT TIME ZONE 'Asia/Seoul')::date
                 WHERE a.schedule_id = :s AND a.status IN ('SELECTED','CONFIRMED') ORDER BY pos.id, a.applicant_name
                """).param("s", scheduleId).query(Rows.camel()).list();
        for (Map<String, Object> r : rows) {
            String enc = (String) r.remove("accountNumberEnc");
            if (enc != null) {
                String n = cipher.decrypt(enc);
                r.put("accountMasked", "****" + n.substring(Math.max(0, n.length() - 4)));
            }
            String rrn = (String) r.remove("rrnEnc");
            r.put("rrnMasked", rrn == null ? null : RrnPolicy.mask(cipher.decrypt(rrn)));
        }
        return rows;
    }

    /** 엑셀 내보내기용 원본(주민번호·계좌 전체). 호출하는 쪽이 암호화 파일로만 내보내고 감사를 남긴다. */
    public List<Map<String, Object>> rosterForExport(long scheduleId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT a.id AS application_id, a.applicant_name AS name, a.applicant_phone AS phone, pos.title AS position_title,
                       a.profile_snapshot ->> 'gender' AS gender, a.profile_snapshot ->> 'birth_date' AS birth_date, a.profile_snapshot ->> 'home_address' AS home_address,
                       a.bank_name, a.account_holder, a.account_number_enc, t.rrn_enc, a.status
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id LEFT JOIN actor_tax_identities t ON t.id = a.tax_identity_id
                 WHERE a.schedule_id = :s AND a.status IN ('SELECTED','CONFIRMED') ORDER BY pos.id, a.applicant_name
                """).param("s", scheduleId).query(Rows.camel()).list();
        for (Map<String, Object> r : rows) {
            String enc = (String) r.remove("accountNumberEnc");
            r.put("accountNumber", enc == null ? null : cipher.decrypt(enc));
            String rrn = (String) r.remove("rrnEnc");
            if (rrn != null) {
                String d = cipher.decrypt(rrn);
                r.put("rrn", d.substring(0, 6) + "-" + d.substring(6));
            } else r.put("rrn", null);
        }
        return rows;
    }
}
