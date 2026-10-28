package kr.bojohelp.auth;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** API-ME01 (일부): 내 정보. 번호는 마스킹해서 돌려준다. */
@RestController
public class MeController {

    private final JdbcClient jdbc;
    private final AuthService auth;

    public MeController(JdbcClient jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @GetMapping("/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        long id = Long.parseLong(jwt.getSubject());
        var row = jdbc.sql("SELECT name, phone FROM users WHERE id = :id AND deleted_at IS NULL").param("id", id)
                .query((rs, n) -> new String[] {rs.getString("name"), rs.getString("phone")}).single();
        return Map.of(
                "id", id,
                "name", row[0],
                "phoneMasked", row[1] == null ? "" : PhoneNumber.mask(row[1]),
                "roles", auth.rolesOf(id));
    }
}
