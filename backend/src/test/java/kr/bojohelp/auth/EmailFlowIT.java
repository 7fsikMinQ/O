package kr.bojohelp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.bojohelp.outbox.OutboxWorker;
import kr.bojohelp.support.AbstractPgTest;
import kr.bojohelp.support.RecordingEmailSender;
import kr.bojohelp.support.TestMailConfig;
import kr.bojohelp.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 이메일 확인, 임시 비밀번호 재설정·변경, IP 단위 로그인 잠금과 알림, 아웃박스 재시도. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMailConfig.class)
@TestPropertySource(properties = "app.storage.local-dir=build/test-files")
class EmailFlowIT extends AbstractPgTest {

    private static final String PW = "Passw0rd1234";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RecordingEmailSender mail;
    @Autowired OutboxWorker worker;

    private String send(RequestBuilder rb, int expected) throws Exception {
        return mvc.perform(rb).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder rb, String token, String body, String ip) {
        var b = rb.contentType(MediaType.APPLICATION_JSON).content(body);
        if (ip != null) b = b.with(r -> { r.setRemoteAddr(ip); return r; });
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private String login(String email, String password, String ip, int expected) throws Exception {
        return send(json(post("/auth/email/login"), null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", ip), expected);
    }

    private String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as("본문에서 %s 를 찾을 수 없음", regex).isTrue();
        return m.group(1);
    }

    @Test
    void emailVerificationFlow() throws Exception {
        String email = "verify1@test.example";
        String access = TestUsers.emailSignup(mvc, email, PW, "확인");
        assertThat(worker.runOnce()).isGreaterThanOrEqualTo(1);
        var m = mail.last(email);
        assertThat(m).isNotNull();
        assertThat(m.subject()).contains("이메일");
        String token = extract(m.body(), "token=([A-Za-z0-9_-]+)");

        // 발송 후 아웃박스 본문(토큰)은 지워진다
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_type = 'EMAIL_SEND' AND status = 'DONE' AND payload::text LIKE '%token=%'").query(Integer.class).single()).isZero();
        // DB 에는 토큰 원문이 없다
        assertThat(jdbc.sql("SELECT count(*) FROM email_verifications WHERE token_hash = :t").param("t", token).query(Integer.class).single()).isZero();

        // 확인 전에는 업체 등록 불가(403), 재발송은 1분에 1번(429)
        String body = "{\"name\":\"확인상사\",\"bizRegNo\":\"" + TestUsers.bizNo("123123123") + "\",\"representative\":\"대표\"}";
        send(json(post("/companies"), access, body, null), 403);
        send(post("/auth/email/resend-verification").header("Authorization", "Bearer " + access), 429);

        send(json(post("/auth/email/verify"), null, "{\"token\":\"wrong\"}", null), 400);
        send(json(post("/auth/email/verify"), null, "{\"token\":\"" + token + "\"}", null), 204);
        send(json(post("/auth/email/verify"), null, "{\"token\":\"" + token + "\"}", null), 400);   // 1회용
        send(post("/auth/email/resend-verification").header("Authorization", "Bearer " + access), 409);   // 이미 확인됨
        send(json(post("/companies"), access, body, null), 201);
        assertThat(jdbc.sql("SELECT email_verified_at IS NOT NULL FROM users WHERE email = :e").param("e", email).query(Boolean.class).single()).isTrue();

        // 만료된 토큰
        String email2 = "verify2@test.example";
        TestUsers.emailSignup(mvc, email2, PW, "만료");
        worker.runOnce();
        String token2 = extract(mail.last(email2).body(), "token=([A-Za-z0-9_-]+)");
        jdbc.sql("UPDATE email_verifications SET expires_at = now() - interval '1 minute' WHERE user_id = (SELECT id FROM users WHERE email = :e)").param("e", email2).update();
        send(json(post("/auth/email/verify"), null, "{\"token\":\"" + token2 + "\"}", null), 400);
    }

    @Test
    void invitationRequiresVerifiedEmails() throws Exception {
        String owner = TestUsers.emailSignup(mvc, "own3@test.example", PW, "대표3");
        TestUsers.markEmailVerified(jdbc, "own3@test.example");
        String invitee = TestUsers.emailSignup(mvc, "inv3@test.example", PW, "초대3");
        long id = ((Number) JsonPath.read(send(json(post("/companies"), owner,
                "{\"name\":\"초대상사\",\"bizRegNo\":\"" + TestUsers.bizNo("321321321") + "\",\"representative\":\"대표\"}", null), 201), "$.id")).longValue();
        String invite = "{\"email\":\"inv3@test.example\",\"memberRole\":\"STAFF\"}";
        send(json(post("/companies/" + id + "/members"), owner, invite, null), 404);   // 확인되지 않은 이메일은 초대 대상이 아님(이메일 선점 방지)
        TestUsers.markEmailVerified(jdbc, "inv3@test.example");
        send(json(post("/companies/" + id + "/members"), owner, invite, null), 201);
        jdbc.sql("UPDATE users SET email_verified_at = NULL WHERE email = 'inv3@test.example'").update();
        send(post("/me/company-invitations/" + id + "/accept").header("Authorization", "Bearer " + invitee), 403);   // 수락도 확인 후에만
    }

    @Test
    void temporaryPasswordResetThenChange() throws Exception {
        String email = "reset1@test.example";
        String access = TestUsers.emailSignup(mvc, email, PW, "재설정");
        worker.runOnce();
        String refreshOld = JsonPath.read(login(email, PW, null, 200), "$.refreshToken");

        // 없는 계정도 같은 응답(202), 메일은 안 감
        int before = mail.to("ghost@test.example").size();
        send(json(post("/auth/password/reset-request"), null, "{\"email\":\"ghost@test.example\"}", null), 202);
        worker.runOnce();
        assertThat(mail.to("ghost@test.example")).hasSize(before);

        send(json(post("/auth/password/reset-request"), null, "{\"email\":\"" + email + "\"}", null), 202);
        worker.runOnce();
        String temp = extract(mail.last(email).body(), "임시 비밀번호: (\\S+)");
        assertThat(temp).hasSize(12);
        // DB 에는 해시만, 아웃박스 본문은 비워짐
        assertThat(jdbc.sql("SELECT temp_hash FROM password_resets WHERE user_id = (SELECT id FROM users WHERE email = :e) ORDER BY id DESC LIMIT 1").param("e", email).query(String.class).single())
                .startsWith("$2").doesNotContain(temp);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE payload::text LIKE :p").param("p", "%" + temp + "%").query(Integer.class).single()).isZero();

        login(email, PW, null, 200);   // 기존 비밀번호는 그대로 유효(남이 재설정을 눌러도 계정이 바뀌지 않음)

        // 임시 비밀번호 로그인 → 변경 전용 제한 토큰
        String limited = login(email, temp, null, 200);
        assertThat((Boolean) JsonPath.read(limited, "$.passwordChangeRequired")).isTrue();
        assertThat((Object) JsonPath.read(limited, "$.refreshToken")).isNull();
        String limitedToken = JsonPath.read(limited, "$.accessToken");
        send(get("/me").header("Authorization", "Bearer " + limitedToken), 403);
        send(get("/me/companies").header("Authorization", "Bearer " + limitedToken), 403);

        // 새 비밀번호 규칙 → 변경
        send(json(put("/me/password"), limitedToken, "{\"newPassword\":\"short\"}", null), 400);
        send(json(put("/me/password"), limitedToken, "{\"newPassword\":\"" + PW + "\"}", null), 400);   // 이전과 같은 비밀번호
        String changed = send(json(put("/me/password"), limitedToken, "{\"newPassword\":\"NewPassw0rd5678\"}", null), 200);
        String newAccess = JsonPath.read(changed, "$.accessToken");
        send(get("/me").header("Authorization", "Bearer " + newAccess), 200);

        // 이전 비밀번호·임시 비밀번호는 무효, 이전 기기의 리프레시 토큰도 무효
        login(email, PW, null, 401);
        login(email, temp, null, 401);
        send(json(post("/auth/refresh"), null, "{\"refreshToken\":\"" + refreshOld + "\"}", null), 401);
        login(email, "NewPassw0rd5678", null, 200);
        worker.runOnce();
        assertThat(mail.last(email).subject()).contains("비밀번호가 바뀌었어요");

        // 일반 변경: 현재 비밀번호 필요
        send(json(put("/me/password"), newAccess, "{\"newPassword\":\"Another12345x\"}", null), 403);
        send(json(put("/me/password"), newAccess, "{\"currentPassword\":\"wrong\",\"newPassword\":\"Another12345x\"}", null), 403);
        send(json(put("/me/password"), newAccess, "{\"currentPassword\":\"NewPassw0rd5678\",\"newPassword\":\"Another12345x\"}", null), 200);

        // 재설정 요청은 이메일당 1시간 3회까지만 실제로 발송
        String email2 = "reset2@test.example";
        TestUsers.emailSignup(mvc, email2, PW, "한도");
        worker.runOnce();
        int base = mail.to(email2).size();
        for (int i = 0; i < 5; i++) send(json(post("/auth/password/reset-request"), null, "{\"email\":\"" + email2 + "\"}", null), 202);
        worker.runOnce();
        assertThat(mail.to(email2).size() - base).isEqualTo(3);
    }

    @Test
    void lockIsPerIpAndOwnerIsNotified() throws Exception {
        String email = "lock1@test.example";
        TestUsers.emailSignup(mvc, email, PW, "잠금");
        worker.runOnce();
        int mailsBefore = mail.to(email).size();

        for (int i = 0; i < 5; i++) login(email, "WrongPass999", "203.0.113.10", 401);
        login(email, PW, "203.0.113.10", 429);    // 그 IP 는 맞는 비밀번호도 막힘
        login(email, PW, "198.51.100.20", 200);   // 다른 IP 는 정상 로그인(피해자는 영향 없음)
        login(email, PW, "203.0.113.10", 429);    // 다른 IP 성공은 막힌 IP 를 풀지 않음

        worker.runOnce();
        var notice = mail.to(email).stream().skip(mailsBefore).filter(s -> s.subject().contains("여러 번 잘못")).toList();
        assertThat(notice).hasSize(1);   // 막힌 순간 한 번만
        assertThat(notice.get(0).body()).contains("203.0.113.***").doesNotContain("203.0.113.10");

        // 없는 이메일 시도는 기록하지 않는다
        for (int i = 0; i < 3; i++) login("nobody" + i + "@test.example", "WrongPass999", "203.0.113.55", 401);
        assertThat(jdbc.sql("SELECT count(*) FROM login_histories WHERE login_id LIKE 'nobody%'").query(Integer.class).single()).isZero();
    }

    @Test
    void outboxRetriesWithBackoffAndScrubsPayload() throws Exception {
        String email = "retry1@test.example";
        mail.failNext = true;
        TestUsers.emailSignup(mvc, email, PW, "재시도");
        worker.runOnce();
        mail.failNext = false;
        var row = jdbc.sql("SELECT status, attempts, last_error, available_at > now() FROM outbox_events WHERE payload->>'to' = :e").param("e", email)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4)}).single();
        assertThat(row[0]).isEqualTo("NEW");
        assertThat(row[1]).isEqualTo(1);
        assertThat((String) row[2]).doesNotContain(email);
        assertThat(row[3]).isEqualTo(true);   // 다음 시도는 뒤로 미뤄짐
        assertThat(mail.to(email)).isEmpty();

        jdbc.sql("UPDATE outbox_events SET available_at = now() WHERE payload->>'to' = :e").param("e", email).update();
        worker.runOnce();
        assertThat(mail.to(email)).hasSize(1);
        assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE aggregate_type = 'EMAIL' AND aggregate_id = (SELECT id FROM users WHERE email = :e)").param("e", email).query(String.class).list()).containsOnly("DONE");
    }
}
