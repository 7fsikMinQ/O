package kr.bojohelp.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 전용(/admin/** 은 SecurityConfig 에서 ADMIN·SUPER_ADMIN 만 허용). */
@RestController
public class AdminCompanyController {

    private final AdminCompanyService service;

    public AdminCompanyController(AdminCompanyService service) {
        this.service = service;
    }

    @GetMapping("/admin/companies")
    List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @GetMapping("/admin/companies/{id}")
    Map<String, Object> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.detail(Long.parseLong(jwt.getSubject()), id);
    }

    @PostMapping("/admin/companies/{id}/approve")
    ResponseEntity<Void> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.approve(Long.parseLong(jwt.getSubject()), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/companies/{id}/reject")
    ResponseEntity<Void> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody AdminCompanyService.RejectRequest req) {
        service.reject(Long.parseLong(jwt.getSubject()), id, req.reason());
        return ResponseEntity.noContent().build();
    }
}
