package kr.bojohelp.company;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * "내 업체" 결정: companyId 를 주면 그 업체의 구성원인지 검사하고, 안 주면 활성 구성원인 업체가 딱 하나일 때 그 업체를 쓴다.
 * 승인된 업체만 공고·선발 기능을 쓸 수 있다(승인 전에는 403).
 */
@Component
public class CompanyContext {

    private final CompanyAccess access;
    private final JdbcClient jdbc;

    public CompanyContext(CompanyAccess access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    public CompanyAccess.Membership resolve(long userId, Long companyId, Set<String> roles) {
        long cid;
        if (companyId != null) cid = companyId;
        else {
            List<Long> mine = jdbc.sql("SELECT m.company_id FROM company_members m JOIN companies c ON c.id = m.company_id WHERE m.user_id = :u AND m.status = 'ACTIVE' AND c.deleted_at IS NULL ORDER BY m.company_id")
                    .param("u", userId).query(Long.class).list();
            if (mine.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (mine.size() > 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업체가 여러 곳이에요. companyId 를 함께 보내 주세요.");
            cid = mine.get(0);
        }
        CompanyAccess.Membership m = access.require(userId, cid, roles);
        if (!"APPROVED".equals(m.companyStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "업체 승인 후에 쓸 수 있어요.");
        return m;
    }

    /** 이미 정해진 업체(예: 공고가 속한 업체)에 대한 접근 검사. */
    public CompanyAccess.Membership require(long userId, long companyId, Set<String> roles) {
        CompanyAccess.Membership m = access.require(userId, companyId, roles);
        if (!"APPROVED".equals(m.companyStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "업체 승인 후에 쓸 수 있어요.");
        return m;
    }
}
