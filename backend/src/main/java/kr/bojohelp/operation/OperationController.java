package kr.bojohelp.operation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.company.CompanyContext;
import kr.bojohelp.company.Roles;
import kr.bojohelp.posting.PostingQuery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 현장 운영: 인원표·정보표·출퇴근 세션/QR·참석 확인·수기 출석·취소 판정·일정 연기(API-OP01~02, OP06~07, OP18~22, OP25, AP07, AP09). */
@RestController
public class OperationController {

    public record SessionReq(@NotBlank @Pattern(regexp = "CHECKIN|CHECKOUT") String kind, LocalDate workDate) {}

    public record ScanReq(@NotBlank String token) {}

    public record VoteReq(@NotBlank String vote) {}

    public record PostponeReq(@NotNull OffsetDateTime newStartAt, @NotNull OffsetDateTime newEndAt, String reason) {}

    private final RosterService rosters;
    private final AttendanceService attendance;
    private final CancelReviewService cancelReviews;
    private final PostponeService postpone;
    private final PostingQuery postings;
    private final CompanyContext companies;
    private final AuditService audit;
    private final RosterExportService exports;

    public OperationController(RosterService rosters, AttendanceService attendance, CancelReviewService cancelReviews, PostponeService postpone, PostingQuery postings, CompanyContext companies, AuditService audit, RosterExportService exports) {
        this.exports = exports;
        this.rosters = rosters;
        this.attendance = attendance;
        this.cancelReviews = cancelReviews;
        this.postpone = postpone;
        this.postings = postings;
        this.companies = companies;
        this.audit = audit;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @GetMapping("/company/schedules/{id}/headcount")
    List<Map<String, Object>> headcount(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), postings.companyOfSchedule(id), Roles.ANY);
        return rosters.headcount(id);
    }

    @GetMapping("/company/schedules/{id}/roster")
    List<Map<String, Object>> roster(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), postings.companyOfSchedule(id), Roles.FIELD);
        audit.record(uid(jwt), "ROSTER_VIEW", "JOB_SCHEDULE", id, null);
        return rosters.roster(id);
    }

    /** 주민번호·계좌가 들어가므로 대표·팀장만, 비밀번호(6자 이상)로 암호화한 파일로만 내려준다. 내려받은 사실은 감사 기록에 남는다. */
    @GetMapping("/company/schedules/{id}/roster.xlsx")
    ResponseEntity<byte[]> rosterXlsx(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam String password) {
        companies.require(uid(jwt), postings.companyOfSchedule(id), Roles.MANAGERS);
        if (password == null || password.length() < 6 || password.length() > 64) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "파일 비밀번호는 6자 이상으로 정해 주세요.");
        audit.record(uid(jwt), "ROSTER_EXPORT", "JOB_SCHEDULE", id, null);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"roster.xlsx\"").header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store").contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(exports.encryptedXlsx(id, password));
    }

    @GetMapping("/company/schedules/{id}/departure-pending")
    List<Map<String, Object>> departurePending(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), postings.companyOfSchedule(id), Roles.FIELD);
        return attendance.departurePending(id);
    }

    @PutMapping("/company/schedules/{id}/attendances")
    Map<String, Object> manual(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody List<AttendanceService.ManualItem> items) {
        companies.require(uid(jwt), postings.companyOfSchedule(id), Roles.FIELD);
        return Map.of("updated", attendance.manual(uid(jwt), id, items));
    }

    @PostMapping("/company/schedules/{id}/attendance-sessions")
    Map<String, Object> openSession(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody SessionReq req) {
        companies.require(uid(jwt), postings.companyOfSchedule(id), Roles.ANY);   // 스태프 포함 업체 구성원 누구나
        return attendance.openSession(uid(jwt), id, req.kind(), req.workDate());
    }

    @GetMapping("/company/schedules/{id}/attendance-sessions")
    List<Map<String, Object>> sessions(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), postings.companyOfSchedule(id), Roles.ANY);
        return attendance.sessions(id);
    }

    @PostMapping("/company/attendance-sessions/{id}/close")
    ResponseEntity<Void> closeSession(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        companies.require(uid(jwt), postings.companyOfSchedule(attendance.scheduleOfSession(id)), Roles.ANY);
        attendance.closeSession(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/company/attendance/scan")
    Map<String, Object> scan(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ScanReq req) {
        return attendance.scan(uid(jwt), req.token(), companyId -> companies.require(uid(jwt), companyId, Roles.ANY));
    }

    @GetMapping("/me/applications/{id}/qr")
    Map<String, Object> qr(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return attendance.qr(uid(jwt), id);
    }

    @PostMapping("/applications/{id}/departure-confirm")
    Map<String, Object> departure(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return attendance.confirmDeparture(uid(jwt), id);
    }

    @GetMapping("/company/cancellation-reviews")
    List<Map<String, Object>> reviews(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId) {
        var m = companies.resolve(uid(jwt), companyId, Roles.PLANNERS);
        return cancelReviews.pending(m.companyId(), uid(jwt));
    }

    @PostMapping("/company/cancellation-reviews/{applicationId}/vote")
    Map<String, Object> vote(@AuthenticationPrincipal Jwt jwt, @PathVariable long applicationId, @RequestParam(required = false) Long companyId, @Valid @RequestBody VoteReq req) {
        var m = companies.resolve(uid(jwt), companyId, Roles.SCHEDULERS);   // 투표는 스케줄러만
        return cancelReviews.vote(uid(jwt), m.companyId(), applicationId, req.vote());
    }

    @PostMapping("/company/schedules/{id}/postpone")
    ResponseEntity<Map<String, Object>> postpone(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody PostponeReq req) {
        companies.require(uid(jwt), postings.companyOfSchedule(id), Roles.PLANNERS);
        return ResponseEntity.status(HttpStatus.CREATED).body(postpone.postpone(uid(jwt), id, req.newStartAt(), req.newEndAt(), req.reason()));
    }
}
