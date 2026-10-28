package kr.bojohelp.posting;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import kr.bojohelp.company.CompanyContext;
import kr.bojohelp.company.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 업체 공고 관리(API-JB01~JB12, MS02/MS05). 대표·팀장·스케줄러가 쓴다. 공고가 속한 업체의 구성원인지는 CompanyContext 가 검사한다. */
@RestController
public class CompanyPostingController {

    public record HeadcountReq(int headcount) {}

    public record ReasonReq(String reason) {}

    private final PostingService postings;
    private final PostingQuery query;
    private final CompanyContext companies;

    public CompanyPostingController(PostingService postings, PostingQuery query, CompanyContext companies) {
        this.postings = postings;
        this.query = query;
        this.companies = companies;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @PostMapping("/company/jobs")
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId, @Valid @RequestBody PostingService.PostingReq req) {
        var m = companies.resolve(uid(jwt), companyId, Roles.PLANNERS);
        return ResponseEntity.status(HttpStatus.CREATED).body(postings.create(uid(jwt), m, req));
    }

    @PutMapping("/company/jobs/{id}")
    Map<String, Object> update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody PostingService.PostingReq req) {
        companies.require(uid(jwt), query.companyOf(id), Roles.PLANNERS);
        return postings.update(uid(jwt), id, req);
    }

    @PatchMapping("/company/positions/{id}")
    Map<String, Object> headcount(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody HeadcountReq req) {
        companies.require(uid(jwt), query.companyOfPosition(id), Roles.PLANNERS);
        return postings.changeHeadcount(uid(jwt), id, req.headcount());
    }

    @PostMapping("/company/jobs/{id}/publish")
    Map<String, Object> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), query.companyOf(id), Roles.PLANNERS);
        return postings.publish(uid(jwt), id);
    }

    @PostMapping("/company/jobs/{id}/close")
    Map<String, Object> close(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), query.companyOf(id), Roles.PLANNERS);
        return postings.close(uid(jwt), id, "COMPANY_CLOSE");
    }

    @PostMapping("/company/jobs/{id}/reopen")
    Map<String, Object> reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), query.companyOf(id), Roles.PLANNERS);
        return postings.reopen(uid(jwt), id);
    }

    @PostMapping("/company/jobs/{id}/cancel")
    Map<String, Object> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody(required = false) ReasonReq req) {
        companies.require(uid(jwt), query.companyOf(id), Roles.MANAGERS);
        return postings.cancel(uid(jwt), id, req == null || req.reason() == null ? "업체 사정으로 공고 취소" : req.reason());
    }

    @PostMapping("/company/jobs/{id}/copy")
    ResponseEntity<Map<String, Object>> copy(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam(defaultValue = "0") int shiftDays) {
        companies.require(uid(jwt), query.companyOf(id), Roles.PLANNERS);
        return ResponseEntity.status(HttpStatus.CREATED).body(postings.copy(uid(jwt), id, shiftDays));
    }

    @GetMapping("/company/jobs")
    List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId, @RequestParam(required = false) String status) {
        var m = companies.resolve(uid(jwt), companyId, Roles.ANY);
        return query.companyList(m.companyId(), status);
    }

    @GetMapping("/company/jobs/{id}")
    Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), query.companyOf(id), Roles.ANY);
        return query.forCompany(id);
    }

    @GetMapping("/company/dashboard")
    Map<String, Object> dashboard(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId) {
        var m = companies.resolve(uid(jwt), companyId, Roles.ANY);
        return query.dashboard(m.companyId());
    }
}
