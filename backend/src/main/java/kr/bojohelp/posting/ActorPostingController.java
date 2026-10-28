package kr.bojohelp.posting;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import kr.bojohelp.common.Rows;

/** 출연자의 공고 탐색(API-JS01~03)과 공개 마스터(MS02·MS05). 로그인 없이도 열린다(로그인했다면 차단 업체 제외·내 지원 상태 포함). */
@RestController
public class ActorPostingController {

    private final PostingQuery query;
    private final JdbcClient jdbc;

    public ActorPostingController(PostingQuery query, JdbcClient jdbc) {
        this.query = query;
        this.jdbc = jdbc;
    }

    private static Long uid(Jwt jwt) {
        return jwt == null ? null : Long.valueOf(jwt.getSubject());
    }

    @GetMapping("/jobs")
    Map<String, Object> search(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long regionId, @RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return query.search(uid(jwt), from, to, regionId, q, cursor, limit);
    }

    @GetMapping("/jobs/calendar")
    List<Map<String, Object>> calendar(@RequestParam String month, @RequestParam(required = false) Long regionId) {
        return query.calendar(month, regionId);
    }

    @GetMapping("/jobs/{id}")
    Map<String, Object> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return query.detailForActor(id, uid(jwt));
    }

    @GetMapping("/master/position-categories")
    List<Map<String, Object>> categories() {
        return jdbc.sql("SELECT id, code, name FROM position_categories WHERE is_active ORDER BY sort_order").query(Rows.camel()).list();
    }

    @GetMapping("/master/minimum-wage")
    Map<String, Object> minimumWage(@RequestParam(required = false) Integer year) {
        int y = year != null ? year : java.time.ZonedDateTime.now(WageRules.KST).getYear();
        return jdbc.sql("SELECT year, hourly_krw FROM minimum_wages WHERE year = :y").param("y", y).query(Rows.camel()).optional()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
}
