package kr.bojohelp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/** 이메일 코드 가입·로그인(문자 비용 없는 채널): 코드 메일 → 가입 → 재로그인, 업체·관리자 계정은 이 경로로 들어올 수 없음. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMailConfig.class)
@TestPropertySource(properties = {"app.auth.otp-channel=email", "app.auth.otp-resend-cooldown-seconds=0", "app.storage.local-dir=build/test-files"})
class EmailOtpFlowIT extends AbstractPgTest {

    @Autowired MockMvc mvc;
    @Autowired RecordingEmailSender mail;
    @Autowired OutboxWorker worker;

    private String send(RequestBuilder rb, int expected) throws Exception {
        return mvc.perform(rb).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String codeFor(String email) {
        worker.runOnce();
        Matcher m = Pattern.compile("인증번호: (\\d{6})").matcher(mail.last(email).body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void signupAndLoginByEmailCode() throws Exception {
        String email = "actor1@test.example";
        send(json("/auth/otp/send", "{\"email\":\"" + email + "\"}"), 202);
        assertThat(mail.last(email)).isNull();   // 아웃박스 워커가 보낸 뒤에야 도착
        String code = codeFor(email);
        assertThat(mail.last(email).subject()).contains("인증번호");

        String wrong = code.equals("000000") ? "000001" : "000000";
        send(json("/auth/otp/verify", "{\"email\":\"" + email + "\",\"code\":\"" + wrong + "\"}"), 400);
        String verified = send(json("/auth/otp/verify", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"), 200);
        assertThat((String) JsonPath.read(verified, "$.status")).isEqualTo("NEED_SIGNUP");
        String signupToken = JsonPath.read(verified, "$.signupToken");

        List<Map<String, Object>> terms = JsonPath.read(send(get("/terms"), 200), "$");
        List<Integer> ids = terms.stream().filter(t -> List.of("SERVICE", "PRIVACY", "THIRD_PARTY_PROVISION").contains(t.get("type"))).map(t -> ((Number) t.get("id")).intValue()).toList();
        String signed = send(json("/auth/signup", "{\"signupToken\":\"" + signupToken + "\",\"name\":\"이메일출연\",\"agreedTermIds\":" + ids + "}"), 201);
        String access = JsonPath.read(signed, "$.accessToken");
        String me = send(get("/me").header("Authorization", "Bearer " + access), 200);
        assertThat((List<String>) JsonPath.read(me, "$.roles")).containsExactly("ACTOR");
        assertThat((String) JsonPath.read(me, "$.phoneMasked")).isEmpty();   // 연락처는 프로필에서 입력

        // 같은 이메일로 다시 로그인
        send(json("/auth/otp/send", "{\"email\":\"" + email + "\"}"), 202);
        String again = send(json("/auth/otp/verify", "{\"email\":\"" + email + "\",\"code\":\"" + codeFor(email) + "\"}"), 200);
        assertThat((String) JsonPath.read(again, "$.status")).isEqualTo("LOGGED_IN");

        // 형식이 틀린 이메일은 400, 설정 채널이 이메일이라 번호만 보내면 400
        send(json("/auth/otp/send", "{\"email\":\"not-an-email\"}"), 400);
        send(json("/auth/otp/send", "{\"phone\":\"010-1234-5678\"}"), 400);

        // 설정에 채널이 노출된다
        assertThat((String) JsonPath.read(send(get("/app/config?platform=web&version=1.0.0"), 200), "$.otpChannel")).isEqualTo("email");
    }

    @Test
    void companyAccountCannotEnterByEmailCode() throws Exception {
        String email = "company-owner@test.example";
        TestUsers.emailSignup(mvc, email, "Passw0rd1234", "업체주");
        send(json("/auth/otp/send", "{\"email\":\"" + email + "\"}"), 202);
        String code = codeFor(email);
        send(json("/auth/otp/verify", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"), 409);   // 비밀번호 로그인만 허용
    }
}
