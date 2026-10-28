package kr.bojohelp.auth;

import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailAuthController {

    private final EmailAuthService service;

    public EmailAuthController(EmailAuthService service) {
        this.service = service;
    }

    @PostMapping("/auth/email/signup")
    ResponseEntity<Map<String, Object>> signup(@Valid @RequestBody EmailAuthService.SignupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body(service.signup(req)));
    }

    @PostMapping("/auth/email/login")
    Map<String, Object> login(@Valid @RequestBody EmailAuthService.LoginRequest req) {
        return body(service.login(req));
    }

    private static Map<String, Object> body(AuthService.Tokens t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", t.accessToken());
        m.put("expiresIn", t.expiresIn());
        m.put("refreshToken", t.refreshToken());
        return m;
    }
}
