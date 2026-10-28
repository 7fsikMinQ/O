package kr.bojohelp.security;

import java.util.List;
import kr.bojohelp.auth.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 기본 거부. 공개 경로만 연다. 인증(JWT·문자 OTP)은 auth 모듈에서 채운다(PGM-B01).
 * API 는 토큰 인증(무상태)이라 CSRF 를 끄되, 쿠키를 쓰는 웹 로그인 경로를 만들 때 다시 켠다(13번 §3 #6).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h.httpStrictTransportSecurity(Customizer.withDefaults()))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/features", "/app/config", "/terms", "/master/regions").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/otp/send", "/auth/otp/verify", "/auth/signup", "/auth/refresh", "/auth/logout").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/me/actor-profile/**", "/me/actor-profile").hasRole("ACTOR")
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /** 액세스 토큰만 통과(가입 토큰으로는 API 호출 불가). */
    @Bean
    JwtDecoder jwtDecoder(TokenService tokens) {
        return tokens.accessDecoder();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** 허용 출처는 환경변수(app.cors.allowed-origins, 쉼표 구분)로만 연다. 비어 있으면 교차 출처 요청을 모두 거부한다. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:}") List<String> origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins.stream().filter(o -> !o.isBlank()).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /** Boot 가 임시 사용자·비밀번호(로그에 출력)를 만들지 않도록 '사용자 없음' 서비스를 둔다. 실제 인증은 auth 모듈(JWT·문자 OTP)이 대체. */
    @Bean
    UserDetailsService noDefaultUser() {
        return username -> {
            throw new UsernameNotFoundException("no user");
        };
    }

    /** 업체·관리자 비밀번호 해시용(출연자는 비밀번호 없음). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
