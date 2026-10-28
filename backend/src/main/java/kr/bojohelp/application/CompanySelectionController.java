package kr.bojohelp.application;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import kr.bojohelp.company.CompanyContext;
import kr.bojohelp.company.Roles;
import kr.bojohelp.posting.PostingQuery;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 업체의 지원자 열람·합격 통보·불합격·합격 취소·마감 후 추가 합격 후보·주민번호 열람(API-SL01~SL08, OP26). */
@RestController
public class CompanySelectionController {

    public record DecideReq(@NotEmpty List<Long> applicationIds, @NotNull String decision, Integer responseHours) {}

    public record ReasonReq(String reason) {}

    private final ApplicantQuery applicants;
    private final SelectionService selection;
    private final PostingQuery postings;
    private final CompanyContext companies;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public CompanySelectionController(ApplicantQuery applicants, SelectionService selection, PostingQuery postings, CompanyContext companies, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.applicants = applicants;
        this.selection = selection;
        this.postings = postings;
        this.companies = companies;
        this.jdbc = jdbc;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    private long companyOfApplication(long applicationId) {
        return jdbc.sql("SELECT p.company_id FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE a.id = :id").param("id", applicationId).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/company/positions/{id}/applications")
    List<Map<String, Object>> byPosition(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam(required = false) String status) {
        companies.require(uid(jwt), postings.companyOfPosition(id), Roles.FIELD);
        return applicants.forPosition(id, status);
    }

    @GetMapping("/company/jobs/{id}/applications")
    List<Map<String, Object>> byPosting(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam(required = false) String status) {
        companies.require(uid(jwt), postings.companyOf(id), Roles.FIELD);
        return applicants.forPosting(id, status);
    }

    @GetMapping("/company/applications/{id}")
    Map<String, Object> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        long cid = companyOfApplication(id);
        companies.require(uid(jwt), cid, Roles.FIELD);
        return applicants.detail(id, cid);
    }

    @GetMapping("/company/actors/{userId}/history")
    Map<String, Object> history(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId, @RequestParam(required = false) Long companyId) {
        var m = companies.resolve(uid(jwt), companyId, Roles.FIELD);
        return applicants.actorHistory(userId, m.companyId());
    }

    @GetMapping("/company/actors/{userId}/applications")
    List<Map<String, Object>> others(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId, @RequestParam(required = false) Long companyId) {
        var m = companies.resolve(uid(jwt), companyId, Roles.FIELD);
        return applicants.otherApplications(userId, m.companyId());
    }

    @PostMapping("/company/applications/decide")
    List<Map<String, Object>> decide(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId, @jakarta.validation.Valid @RequestBody DecideReq req) {
        var m = companies.resolve(uid(jwt), companyId, Roles.PLANNERS);
        return selection.decide(uid(jwt), m.companyId(), req.applicationIds(), req.decision(), req.responseHours());
    }

    @PostMapping("/company/applications/{id}/cancel")
    Map<String, Object> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody(required = false) ReasonReq req) {
        long cid = companyOfApplication(id);
        companies.require(uid(jwt), cid, Roles.MANAGERS);
        return selection.companyCancel(uid(jwt), cid, id, req == null || req.reason() == null ? "업체 사정으로 취소" : req.reason());
    }

    @GetMapping("/company/jobs/{id}/reserve-candidates")
    List<Map<String, Object>> reserve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), postings.companyOf(id), Roles.PLANNERS);
        return applicants.reserveCandidates(id);
    }

    @PostMapping("/company/applications/{id}/reveal-rrn")
    Map<String, Object> reveal(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody ReasonReq req) {
        long cid = companyOfApplication(id);
        companies.require(uid(jwt), cid, Roles.MANAGERS);
        return applicants.revealRrn(uid(jwt), cid, id, req.reason());
    }
}
