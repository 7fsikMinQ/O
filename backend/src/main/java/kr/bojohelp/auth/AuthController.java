package kr.bojohelp.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** API-AU13/14 (문자 인증), AU17 (가입 완료), AU05/06 (토큰 갱신·로그아웃), AU12 (약관). 로그인 전에 호출하므로 SecurityConfig 에서 공개. */
@RestController
public class AuthController {

    public record OtpSendRequest(@NotBlank String phone) {}

    public record OtpVerifyRequest(@NotBlank String phone, @NotBlank @Size(min = 6, max = 6) String code, @Size(max = 100) String deviceId) {}

    public record SignupRequest(@NotBlank String signupToken, @NotBlank @Size(max = 50) String name, List<Long> agreedTermIds, @Size(max = 100) String deviceId) {}

    public record RefreshRequest(@NotBlank String refreshToken, @Size(max = 100) String deviceId) {}

    private final AuthService auth;
    private final OtpService otp;

    public AuthController(AuthService auth, OtpService otp) {
        this.auth = auth;
        this.otp = otp;
    }

    @PostMapping("/auth/otp/send")
    ResponseEntity<Map<String, Object>> send(@Valid @RequestBody OtpSendRequest req) {
        int seconds = otp.send(req.phone());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("expiresInSeconds", seconds));
    }

    @PostMapping("/auth/otp/verify")
    Map<String, Object> verify(@Valid @RequestBody OtpVerifyRequest req) {
        AuthService.VerifyOutcome out = auth.verifyOtp(req.phone(), req.code(), req.deviceId());
        Map<String, Object> body = new LinkedHashMap<>();
        if (out.tokens() == null) {
            body.put("status", "NEED_SIGNUP");
            body.put("signupToken", out.signupToken());
        } else {
            body.put("status", "LOGGED_IN");
            body.putAll(tokenBody(out.tokens()));
        }
        return body;
    }

    @PostMapping("/auth/signup")
    ResponseEntity<Map<String, Object>> signup(@Valid @RequestBody SignupRequest req) {
        AuthService.Tokens t = auth.completeSignup(req.signupToken(), req.name(), req.agreedTermIds(), req.deviceId());
        return ResponseEntity.status(HttpStatus.CREATED).body(tokenBody(t));
    }

    @PostMapping("/auth/refresh")
    Map<String, Object> refresh(@Valid @RequestBody RefreshRequest req) {
        return tokenBody(auth.refresh(req.refreshToken(), req.deviceId()));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/terms")
    List<AuthService.Term> terms() {
        return auth.latestTerms();
    }

    private static Map<String, Object> tokenBody(AuthService.Tokens t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", t.accessToken());
        m.put("expiresIn", t.expiresIn());
        m.put("refreshToken", t.refreshToken());
        return m;
    }
}
