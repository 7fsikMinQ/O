package kr.bojohelp.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.bojohelp.crypto.FieldCipher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 기본 정산 계좌(API-ME04/ME05). 계좌번호는 AES-GCM 암호화 저장, 응답은 끝 4자리만. 예금주가 본인 이름과 다르면 표시(직접지급 원칙). */
@Service
public class PayoutAccountService {

    public record PayoutRequest(
            @NotBlank String bankCode,
            @NotBlank String accountNumber,
            @NotBlank @Size(max = 50) String holder,
            @Size(max = 200) String defaultDepartureLocation) {}

    private final JdbcClient jdbc;
    private final FieldCipher cipher;

    public PayoutAccountService(JdbcClient jdbc, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Transactional
    public void save(long userId, PayoutRequest r) {
        String bankName = BankCatalog.BANKS.get(r.bankCode());
        String digits = r.accountNumber().replaceAll("[\\s-]", "");
        if (bankName == null || !digits.matches("\\d{8,16}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (jdbc.sql("SELECT count(*) FROM actor_profiles WHERE user_id = :u").param("u", userId).query(Integer.class).single() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);   // 프로필을 먼저 저장
        }
        jdbc.sql("""
                INSERT INTO actor_payout_accounts (user_id, bank_code, bank_name, account_number_enc, account_holder, key_version)
                VALUES (:u, :code, :name, :enc, :holder, :kv)
                ON CONFLICT (user_id) DO UPDATE SET bank_code = EXCLUDED.bank_code, bank_name = EXCLUDED.bank_name,
                    account_number_enc = EXCLUDED.account_number_enc, account_holder = EXCLUDED.account_holder,
                    key_version = EXCLUDED.key_version, consented_at = now(), updated_at = now()
                """)
                .param("u", userId).param("code", r.bankCode()).param("name", bankName).param("enc", cipher.encrypt(digits))
                .param("holder", r.holder().trim()).param("kv", cipher.activeVersion()).update();
        if (r.defaultDepartureLocation() != null) {
            jdbc.sql("UPDATE actor_profiles SET default_departure_location = :d, updated_at = now() WHERE user_id = :u")
                    .param("d", r.defaultDepartureLocation()).param("u", userId).update();
        }
    }

    public Map<String, Object> get(long userId) {
        return jdbc.sql("""
                SELECT a.bank_code, a.bank_name, a.account_number_enc, a.account_holder, p.default_departure_location, u.name
                  FROM actor_payout_accounts a JOIN actor_profiles p ON p.user_id = a.user_id JOIN users u ON u.id = a.user_id
                 WHERE a.user_id = :u
                """)
                .param("u", userId)
                .query((rs, n) -> {
                    String number = cipher.decrypt(rs.getString("account_number_enc"));
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("bankCode", rs.getString("bank_code"));
                    o.put("bankName", rs.getString("bank_name"));
                    o.put("accountMasked", "****" + number.substring(number.length() - 4));
                    o.put("holder", rs.getString("account_holder"));
                    o.put("holderMatchesName", rs.getString("account_holder").equals(rs.getString("name")));
                    o.put("defaultDepartureLocation", rs.getString("default_departure_location"));
                    return o;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
