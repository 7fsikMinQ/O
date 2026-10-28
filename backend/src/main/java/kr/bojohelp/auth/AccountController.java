package kr.bojohelp.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import kr.bojohelp.common.Rows;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 계정 탈퇴(앱 안 + 로그인 없이 웹, 스토어 요구)와 관심 공고. 탈퇴는 진행 중인 합격·확정 건이 없을 때만 즉시 처리하고, 개인정보 파기는 보존기간 뒤 배치가 한다. */
@RestController
public class AccountController {

    public record PublicDeletionReq(@NotBlank String contact) {}

    private final JdbcClient jdbc;
    private final Hasher hasher;
    private final RefreshTokenService refresh;
    private final AuthProperties props;

    public AccountController(JdbcClient jdbc, Hasher hasher, RefreshTokenService refresh, AuthProperties props) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.refresh = refresh;
        this.props = props;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @DeleteMapping("/me")
    @Transactional
    ResponseEntity<Map<String, Object>> withdraw(@AuthenticationPrincipal Jwt jwt) {
        long id = uid(jwt);
        int active = jdbc.sql("SELECT count(*) FROM applications WHERE actor_user_id = :u AND status IN ('SELECTED','CONFIRMED') AND work_end_at > now()").param("u", id).query(Integer.class).single();
        if (active > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "합격·확정된 일정이 남아 있어요. 먼저 정리해 주세요.");
        int ownerOf = jdbc.sql("SELECT count(*) FROM company_members WHERE user_id = :u AND member_role = 'OWNER' AND status = 'ACTIVE'").param("u", id).query(Integer.class).single();
        if (ownerOf > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "대표로 있는 업체가 있어요. 고객센터로 문의해 주세요.");
        jdbc.sql("UPDATE applications SET status = 'CANCELED_BY_ACTOR', canceled_at = now(), cancel_reason = '탈퇴', updated_at = now() WHERE actor_user_id = :u AND status = 'APPLIED'").param("u", id).update();
        jdbc.sql("UPDATE users SET status = 'WITHDRAWN', deleted_at = now(), updated_at = now() WHERE id = :u").param("u", id).update();
        refresh.revokeAll(id);
        jdbc.sql("INSERT INTO account_deletion_requests (user_id, channel, status, processed_at) VALUES (:u, 'APP', 'DONE', now())").param("u", id).update();
        return ResponseEntity.ok(Map.of("status", "WITHDRAWN"));
    }

    /** 로그인 없이 웹에서 요청(번호·이메일 해시만 저장하고 운영자가 본인 확인 뒤 처리). 계정 유무는 숨긴다. */
    @PostMapping("/public/account-deletion-requests")
    ResponseEntity<Void> publicRequest(@Valid @RequestBody PublicDeletionReq req) {
        String contact = req.contact().trim().toLowerCase();
        jdbc.sql("INSERT INTO account_deletion_requests (contact_hash, channel) VALUES (:h, 'WEB')").param("h", hasher.hash("contact:" + contact)).update();
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/jobs/{id}/bookmark")
    ResponseEntity<Void> bookmark(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        if (jdbc.sql("SELECT count(*) FROM job_postings WHERE id = :id AND deleted_at IS NULL").param("id", id).query(Integer.class).single() == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.sql("INSERT INTO bookmarks (user_id, posting_id) VALUES (:u, :p) ON CONFLICT DO NOTHING").param("u", uid(jwt)).param("p", id).update();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/jobs/{id}/bookmark")
    ResponseEntity<Void> unbookmark(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        jdbc.sql("DELETE FROM bookmarks WHERE user_id = :u AND posting_id = :p").param("u", uid(jwt)).param("p", id).update();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/bookmarks")
    List<Map<String, Object>> bookmarks(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql("""
                SELECT p.id, p.title, p.status, c.name AS company_name, p.first_shoot_date FROM bookmarks b JOIN job_postings p ON p.id = b.posting_id JOIN companies c ON c.id = p.company_id
                 WHERE b.user_id = :u AND p.deleted_at IS NULL ORDER BY b.created_at DESC LIMIT 200
                """).param("u", uid(jwt)).query(Rows.camel()).list();
    }

    @GetMapping("/me/activity")
    Map<String, Object> activity(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql("SELECT confirmed_count, attended_count, noshow_count, penalty_count FROM actor_profiles WHERE user_id = :u").param("u", uid(jwt)).query(Rows.camel()).optional()
                .orElse(Map.of("confirmedCount", 0, "attendedCount", 0, "noshowCount", 0, "penaltyCount", 0));
    }
}
