package kr.bojohelp.admin;

import kr.bojohelp.auth.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 관리자 1명을 환경변수(ADMIN_EMAIL, ADMIN_PASSWORD)로 만든다. 이미 있으면 아무것도 바꾸지 않는다(비밀번호 재설정 용도 아님).
 * 값이 없으면 건너뛴다. 운영 오픈 전에는 관리자 2차 인증(TOTP)을 반드시 추가한다(13번 §3 #13).
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;

    AdminBootstrap(JdbcClient jdbc, PasswordEncoder encoder, @Value("${app.admin.email:}") String email, @Value("${app.admin.password:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.email = email == null ? "" : email.trim().toLowerCase();
        this.password = password == null ? "" : password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isEmpty() && password.isEmpty()) return;
        if (email.isEmpty() || !PasswordPolicy.isAcceptable(password, email)) {
            throw new IllegalStateException("ADMIN_EMAIL/ADMIN_PASSWORD 가 올바르지 않습니다(비밀번호는 10자 이상, 영문+숫자).");
        }
        if (jdbc.sql("SELECT count(*) FROM users WHERE email = :e").param("e", email).query(Integer.class).single() > 0) return;
        long id = jdbc.sql("INSERT INTO users (email, password_hash, name, status, email_verified_at) VALUES (:e, :h, '관리자', 'ACTIVE', now()) RETURNING id")
                .param("e", email).param("h", encoder.encode(password)).query(Long.class).single();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) SELECT :u, id FROM roles WHERE code = 'SUPER_ADMIN'").param("u", id).update();
        log.info("초기 관리자 계정을 만들었습니다 (id={})", id);   // 이메일·비밀번호는 로그에 남기지 않는다
    }
}
