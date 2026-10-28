package kr.bojohelp.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 업체·관리자 계정: 가입·로그인, 이메일 확인, 비밀번호 재설정·변경. */
@RestController
public class EmailAuthController {

    public record VerifyRequest(@jakarta.validation.constraints.NotBlank String token) {}

    private final EmailAuthService service;
    private final EmailVerificationService verification;
    private final PasswordService passwords;

    public EmailAuthController(EmailAuthService service, EmailVerificationService verification, PasswordService passwords) {
        this.service = service;
        this.verification = verification;
        this.passwords = passwords;
    }

    @PostMapping("/auth/email/signup")
    ResponseEntity<Map<String, Object>> signup(@Valid @RequestBody EmailAuthService.SignupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body(service.signup(req)));
    }

    @PostMapping("/auth/email/login")
    Map<String, Object> login(@Valid @RequestBody EmailAuthService.LoginRequest req, HttpServletRequest http) {
        EmailAuthService.LoginResult r = service.login(req, ClientIp.of(http));
        if (r.secondPasswordRequired()) {   // 1차는 맞았고 관리자 계정이라 2차 비밀번호 칸이 필요함
            Map<String, Object> need = new LinkedHashMap<>();
            need.put("status", "SECOND_PASSWORD_REQUIRED");
            return need;
        }
        Map<String, Object> m = body(r.tokens());
        m.put("passwordChangeRequired", r.passwordChangeRequired());
        return m;
    }

    @PostMapping("/auth/email/verify")
    ResponseEntity<Void> verify(@Valid @RequestBody VerifyRequest req) {
        verification.verify(req.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/email/resend-verification")
    ResponseEntity<Void> resend(@AuthenticationPrincipal Jwt jwt) {
        verification.resend(Long.parseLong(jwt.getSubject()));
        return ResponseEntity.accepted().build();
    }

    /** 계정이 있든 없든 항상 202(계정 유무를 숨김). */
    @PostMapping("/auth/password/reset-request")
    ResponseEntity<Void> resetRequest(@Valid @RequestBody PasswordService.ResetRequest req, HttpServletRequest http) {
        passwords.requestReset(req.email(), ClientIp.of(http));
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/me/password")
    Map<String, Object> changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordService.ChangeRequest req) {
        boolean viaTemp = Boolean.TRUE.equals(jwt.getClaim("pwd_change"));
        return body(passwords.change(Long.parseLong(jwt.getSubject()), viaTemp, req));
    }

    private static Map<String, Object> body(AuthService.Tokens t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", t.accessToken());
        m.put("expiresIn", t.expiresIn());
        m.put("refreshToken", t.refreshToken());
        return m;
    }
}
