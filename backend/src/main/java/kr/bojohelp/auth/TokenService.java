package kr.bojohelp.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** HS256 JWT. 액세스 토큰(typ=access, 기본 15분)과 가입 토큰(typ=signup, 번호 인증 직후 15분)을 같은 키·다른 typ 로 발급하고, 서로 대신 쓸 수 없게 검증한다. */
@Service
public class TokenService {

    public static final String TYP_ACCESS = "access";
    public static final String TYP_SIGNUP = "signup";

    private final JwtEncoder encoder;
    private final NimbusJwtDecoder accessDecoder;
    private final NimbusJwtDecoder signupDecoder;
    private final Clock clock;
    private final AuthProperties props;

    public TokenService(AuthProperties props, Clock clock) {
        byte[] secret = props.jwtSecret() == null ? new byte[0] : props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalStateException("app.auth.jwt-secret must be at least 32 bytes");
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessDecoder = decoder(key, TYP_ACCESS);
        this.signupDecoder = decoder(key, TYP_SIGNUP);
        this.clock = clock;
        this.props = props;
    }

    private static NimbusJwtDecoder decoder(SecretKey key, String typ) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> typCheck = jwt -> typ.equals(jwt.getClaimAsString("typ"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong token type", null));
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), typCheck));
        return d;
    }

    /** 리소스 서버가 쓰는 디코더(액세스 토큰 전용). */
    public JwtDecoder accessDecoder() {
        return accessDecoder;
    }

    public String accessToken(long userId, List<String> roles) {
        return encode(TYP_ACCESS, String.valueOf(userId), Duration.ofMinutes(props.accessTokenMinutes()), roles, null, false);
    }

    /** 임시 비밀번호로 로그인했을 때 주는 제한 토큰: 역할이 없고 pwd_change=true 라 비밀번호 변경(PUT /me/password)과 로그아웃만 가능하다. */
    public String limitedAccessToken(long userId) {
        return encode(TYP_ACCESS, String.valueOf(userId), Duration.ofMinutes(props.accessTokenMinutes()), List.of("PWD_CHANGE"), null, true);
    }

    public long accessTokenSeconds() {
        return Duration.ofMinutes(props.accessTokenMinutes()).toSeconds();
    }

    public String signupToken(String normalizedPhone) {
        return encode(TYP_SIGNUP, "signup", Duration.ofMinutes(props.signupTokenMinutes()), List.of(), normalizedPhone, false);
    }

    /** 유효한 가입 토큰이면 인증된 번호를, 아니면 null. */
    public String phoneFromSignupToken(String token) {
        try {
            return signupDecoder.decode(token).getClaimAsString("phone");
        } catch (JwtException e) {
            return null;
        }
    }

    private String encode(String typ, String subject, Duration ttl, List<String> roles, String phone, boolean pwdChange) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().subject(subject).issuedAt(now).expiresAt(now.plus(ttl)).claim("typ", typ);
        if (!roles.isEmpty()) b.claim("roles", roles);
        if (phone != null) b.claim("phone", phone);
        if (pwdChange) b.claim("pwd_change", true);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), b.build())).getTokenValue();
    }
}
