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
 * 최초 관리자 1명을 만든다(이미 관리자가 있으면 아무것도 하지 않는다).
 * - ADMIN_EMAIL/ADMIN_PASSWORD 가 있으면 그 값(비밀번호 10자 이상·영문+숫자).
 * - 없으면 사용자 결정(2026-10-28)대로 아이디 admin / 비밀번호 admin 으로 만든다. 로그인 후 바꿀 수 있다.
 * 관리자는 로그인 때 2차 비밀번호도 필요하며 그 값은 DB(admin_second_password)에 고정되어 있다.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final String DEFAULT_LOGIN = "admin@bojohelp.local";
    static final String DEFAULT_PASSWORD = "admin";

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
        boolean custom = !email.isEmpty() || !password.isEmpty();
        if (custom && (email.isEmpty() || !PasswordPolicy.isAcceptable(password, email))) {
            throw new IllegalStateException("ADMIN_EMAIL/ADMIN_PASSWORD 가 올바르지 않습니다(비밀번호는 10자 이상, 영문+숫자).");
        }
        String login = custom ? email : DEFAULT_LOGIN;
        String pw = custom ? password : DEFAULT_PASSWORD;
        if (!custom && jdbc.sql("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE r.code = 'SUPER_ADMIN'").query(Integer.class).single() > 0) return;
        if (jdbc.sql("SELECT count(*) FROM users WHERE email = :e").param("e", login).query(Integer.class).single() > 0) return;
        long id = jdbc.sql("INSERT INTO users (email, password_hash, name, status, email_verified_at) VALUES (:e, :h, '관리자', 'ACTIVE', now()) RETURNING id")
                .param("e", login).param("h", encoder.encode(pw)).query(Long.class).single();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) SELECT :u, id FROM roles WHERE code = 'SUPER_ADMIN'").param("u", id).update();
        if (custom) log.info("초기 관리자 계정을 만들었습니다 (id={})", id);   // 이메일·비밀번호는 로그에 남기지 않는다
        else log.warn("기본 관리자(admin/admin)를 만들었습니다. 로그인한 뒤 비밀번호를 바꾸세요. (id={})", id);
    }
}
