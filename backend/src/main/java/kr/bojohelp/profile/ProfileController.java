package kr.bojohelp.profile;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 출연자 프로필·계좌·주민번호(ACTOR 역할 전용, SecurityConfig 에서 제한) + 지역·은행 마스터. */
@RestController
public class ProfileController {

    private final ProfileService profiles;
    private final PayoutAccountService payouts;
    private final TaxIdentityService taxIds;
    private final JdbcClient jdbc;

    public ProfileController(ProfileService profiles, PayoutAccountService payouts, TaxIdentityService taxIds, JdbcClient jdbc) {
        this.profiles = profiles;
        this.payouts = payouts;
        this.taxIds = taxIds;
        this.jdbc = jdbc;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @GetMapping("/me/actor-profile")
    Map<String, Object> profile(@AuthenticationPrincipal Jwt jwt) {
        return profiles.get(uid(jwt));
    }

    @PutMapping("/me/actor-profile")
    ResponseEntity<Void> saveProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileService.ProfileRequest req) {
        profiles.save(uid(jwt), req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/actor-profile/payout")
    Map<String, Object> payout(@AuthenticationPrincipal Jwt jwt) {
        return payouts.get(uid(jwt));
    }

    @PutMapping("/me/actor-profile/payout")
    ResponseEntity<Void> savePayout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PayoutAccountService.PayoutRequest req) {
        payouts.save(uid(jwt), req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/actor-profile/tax-identity")
    Map<String, Object> taxIdentity(@AuthenticationPrincipal Jwt jwt) {
        return taxIds.status(uid(jwt));
    }

    @PutMapping("/me/actor-profile/tax-identity")
    Map<String, Object> registerTaxIdentity(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TaxIdentityService.TaxIdentityRequest req) {
        return taxIds.register(uid(jwt), req);
    }

    /** API-MS01: 지역(시/도 → 시/군/구). 로그인 전에도 조회. */
    @GetMapping("/master/regions")
    List<Map<String, Object>> regions() {
        return jdbc.sql("SELECT id, parent_id, code, name, level FROM regions ORDER BY level, code")
                .query((rs, n) -> Map.<String, Object>of("id", rs.getLong("id"), "parentId", rs.getObject("parent_id") == null ? 0L : rs.getLong("parent_id"),
                        "code", rs.getString("code"), "name", rs.getString("name"), "level", rs.getInt("level")))
                .list();
    }

    /** API-MS04: 은행 코드. */
    @GetMapping("/master/banks")
    List<Map<String, String>> banks() {
        return BankCatalog.BANKS.entrySet().stream().map(e -> Map.of("code", e.getKey(), "name", e.getValue())).toList();
    }
}
