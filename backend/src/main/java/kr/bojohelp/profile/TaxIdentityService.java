package kr.bojohelp.profile;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import kr.bojohelp.auth.Hasher;
import kr.bojohelp.crypto.FieldCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 주민등록번호 등록·조회(API-ME14/ME15). 쓰기 전용: 응답에는 마스킹된 값만 나가고 평문은 저장소에도(암호화+해시만) 로그에도 없다.
 * 가입 필수가 아니며 첫 지원 전에 등록한다. 고유식별정보 동의(SENSITIVE_ID 약관 최신 버전)를 함께 받는다.
 */
@Service
public class TaxIdentityService {

    public record TaxIdentityRequest(@NotBlank String rrn, @NotBlank String termVersion) {}

    private final JdbcClient jdbc;
    private final FieldCipher cipher;
    private final Hasher hasher;

    public TaxIdentityService(JdbcClient jdbc, FieldCipher cipher, Hasher hasher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.hasher = hasher;
    }

    @Transactional
    public Map<String, Object> register(long userId, TaxIdentityRequest req) {
        var profile = jdbc.sql("SELECT birth_date, gender FROM actor_profiles WHERE user_id = :u").param("u", userId)
                .query((rs, n) -> new Object[] {rs.getObject("birth_date", LocalDate.class), rs.getString("gender")}).optional();
        if (profile.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT);   // 프로필을 먼저 저장

        RrnPolicy.Parsed parsed;
        try {
            parsed = RrnPolicy.parse(req.rrn());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        // 프로필의 생년월일·성별과 일치해야 한다(성별 OTHER 는 주민번호 성별 자리를 비교하지 않는다)
        boolean genderOk = "OTHER".equals(profile.get()[1]) || parsed.gender().equals(profile.get()[1]);
        if (!parsed.birthDate().equals(profile.get()[0]) || !genderOk) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);

        var term = jdbc.sql("SELECT id, version FROM terms WHERE type = 'SENSITIVE_ID' AND effective_at <= now() ORDER BY effective_at DESC LIMIT 1")
                .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getString("version")}).optional();
        if (term.isEmpty() || !term.get()[1].equals(req.termVersion())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long termId = (Long) term.get()[0];

        String hmac = hasher.hash("rrn:" + parsed.digits());
        Optional<String> mine = jdbc.sql("SELECT rrn_hmac FROM actor_tax_identities WHERE user_id = :u AND is_active").param("u", userId).query(String.class).optional();
        if (mine.isPresent() && mine.get().equals(hmac)) return maskedBody(parsed.digits());   // 같은 번호 재등록은 변경 없음

        jdbc.sql("UPDATE actor_tax_identities SET is_active = FALSE, superseded_at = now() WHERE user_id = :u AND is_active").param("u", userId).update();
        long id;
        try {
            id = jdbc.sql("""
                    INSERT INTO actor_tax_identities (user_id, rrn_enc, rrn_hmac, key_version, consent_term_id, consented_at)
                    VALUES (:u, :enc, :h, :kv, :t, now()) RETURNING id
                    """)
                    .param("u", userId).param("enc", cipher.encrypt(parsed.digits())).param("h", hmac).param("kv", cipher.activeVersion()).param("t", termId)
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);   // 이미 다른 계정에 등록된 번호
        }
        jdbc.sql("INSERT INTO user_consents (user_id, term_id, agreed) VALUES (:u, :t, TRUE) ON CONFLICT (user_id, term_id) DO NOTHING")
                .param("u", userId).param("t", termId).update();
        jdbc.sql("INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, after_data) VALUES (:u, 'RRN_REGISTER', 'ACTOR_TAX_IDENTITY', :id, CAST(:d AS jsonb))")
                .param("u", userId).param("id", id).param("d", "{\"keyVersion\":" + cipher.activeVersion() + "}").update();
        return maskedBody(parsed.digits());
    }

    public Map<String, Object> status(long userId) {
        Optional<String> enc = jdbc.sql("SELECT rrn_enc FROM actor_tax_identities WHERE user_id = :u AND is_active").param("u", userId).query(String.class).optional();
        if (enc.isEmpty()) return Map.of("registered", false);
        return maskedBody(cipher.decrypt(enc.get()));
    }

    private static Map<String, Object> maskedBody(String digits) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registered", true);
        m.put("masked", RrnPolicy.mask(digits));
        return m;
    }
}
