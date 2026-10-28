package kr.bojohelp.admin;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
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

/** 관리자 API(SecurityConfig 가 /admin/** 를 ADMIN·SUPER_ADMIN 으로 제한). */
@RestController
public class AdminController {

    public record ReasonReq(String reason) {}

    public record ValueReq(@NotBlank String value) {}

    public record WageReq(int hourlyKrw) {}

    public record FlagReq(boolean enabled, boolean confirmLegal) {}

    public record StatusReq(@NotBlank String status) {}

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @GetMapping("/admin/users")
    List<Map<String, Object>> users(@RequestParam(required = false) String q, @RequestParam(required = false) String status) {
        return admin.users(q, status);
    }

    @PostMapping("/admin/users/{id}/suspend")
    ResponseEntity<Void> suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody ReasonReq req) {
        admin.setUserStatus(uid(jwt), id, true, req.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/users/{id}/restore")
    ResponseEntity<Void> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        admin.setUserStatus(uid(jwt), id, false, null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/jobs")
    List<Map<String, Object>> jobs(@RequestParam(required = false) String status) {
        return admin.jobs(status);
    }

    @PostMapping("/admin/jobs/{id}/hide")
    ResponseEntity<Void> hide(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody(required = false) ReasonReq req) {
        admin.hideJob(uid(jwt), id, req == null ? null : req.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/reports")
    List<Map<String, Object>> reports(@RequestParam(required = false) String status) {
        return admin.reports(status);
    }

    @PostMapping("/admin/reports/{id}/resolve")
    ResponseEntity<Void> resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody StatusReq req) {
        admin.resolveReport(uid(jwt), id, req.status());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/settings")
    List<Map<String, Object>> settings() {
        return admin.settings();
    }

    @PutMapping("/admin/settings/{key}")
    ResponseEntity<Void> setting(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @RequestBody ValueReq req) {
        admin.putSetting(uid(jwt), key, req.value());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/admin/minimum-wages/{year}")
    ResponseEntity<Void> wage(@AuthenticationPrincipal Jwt jwt, @PathVariable int year, @RequestBody WageReq req) {
        admin.putMinimumWage(uid(jwt), year, req.hourlyKrw());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/feature-flags")
    List<Map<String, Object>> flags() {
        return admin.flags();
    }

    @PutMapping("/admin/feature-flags/{code}")
    ResponseEntity<Void> flag(@AuthenticationPrincipal Jwt jwt, @PathVariable String code, @RequestBody FlagReq req) {
        admin.putFlag(uid(jwt), code, req.enabled(), req.confirmLegal());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/audit-logs")
    List<Map<String, Object>> audit(@RequestParam(required = false) String action, @RequestParam(required = false) Long before, @RequestParam(defaultValue = "50") int limit) {
        return admin.auditLogs(action, before, limit);
    }

    @GetMapping("/admin/stats")
    Map<String, Object> stats() {
        return admin.stats();
    }
}
