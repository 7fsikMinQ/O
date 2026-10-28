package kr.bojohelp.company;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 업체 데이터 접근 검사의 단일 지점: 활성 구성원이 아니면 업체의 존재를 숨기고(404), 직책이 부족하면 403. */
@Component
public class CompanyAccess {

    public record Membership(long companyId, String role, String companyStatus) {}

    public static final Set<String> MANAGERS = Set.of("OWNER", "MANAGER");

    private final JdbcClient jdbc;

    public CompanyAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Membership require(long userId, long companyId, Set<String> allowedRoles) {
        Membership m = jdbc.sql("""
                SELECT m.company_id, m.member_role, c.status FROM company_members m JOIN companies c ON c.id = m.company_id
                 WHERE m.company_id = :c AND m.user_id = :u AND m.status = 'ACTIVE' AND c.deleted_at IS NULL
                """)
                .param("c", companyId).param("u", userId)
                .query((rs, n) -> new Membership(rs.getLong(1), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!allowedRoles.contains(m.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return m;
    }
}
