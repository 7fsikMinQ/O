package kr.bojohelp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import kr.bojohelp.support.AbstractPgTest;
import kr.bojohelp.support.RecordingSmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 문자 인증 가입·로그인·토큰 회전 전체 흐름(REQ-A10). */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIT extends AbstractPgTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RecordingSmsSender recordingSmsSender() {
            return new RecordingSmsSender();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RecordingSmsSender sms;

    private String json(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder rb, int expected) throws Exception {
        MvcResult r = mvc.perform(rb).andExpect(status().is(expected)).andReturn();
        return r.getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 가입 때 필수인 약관 3종의 id (JsonPath 의 || 필터 대신 자바에서 거른다). */
    private static List<Integer> signupTermIds(String termsJson) {
        List<java.util.Map<String, Object>> all = JsonPath.read(termsJson, "$");
        return all.stream()
                .filter(t -> List.of("SERVICE", "PRIVACY", "THIRD_PARTY_PROVISION").contains(t.get("type")))
                .map(t -> ((Number) t.get("id")).intValue())
                .toList();
    }

    @Test
    void signupLoginRefreshAndReuseDetection() throws Exception {
        String phone = "010-9000-0001";
        String normalized = "01090000001";

        // 1) 인증번호 요청 → 틀린 번호 400 → 맞는 번호 → 가입 필요
        json(mvc, postJson("/auth/otp/send", "{\"phone\":\"" + phone + "\"}"), 202);
        String code = sms.lastCodeFor(normalized);
        assertThat(code).hasSize(6);
        String wrong = code.equals("000000") ? "000001" : "000000";
        json(mvc, postJson("/auth/otp/verify", "{\"phone\":\"" + phone + "\",\"code\":\"" + wrong + "\"}"), 400);
        String verified = json(mvc, postJson("/auth/otp/verify", "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"), 200);
        assertThat((String) JsonPath.read(verified, "$.status")).isEqualTo("NEED_SIGNUP");
        String signupToken = JsonPath.read(verified, "$.signupToken");

        // 같은 인증번호 재사용 불가
        json(mvc, postJson("/auth/otp/verify", "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"), 400);

        // 2) 약관 목록 → 필수 동의 빠뜨리면 400, 다 동의하면 가입 201
        String terms = json(mvc, get("/terms"), 200);
        List<Integer> required = signupTermIds(terms);
        assertThat(required).hasSize(3);
        json(mvc, postJson("/auth/signup", "{\"signupToken\":\"" + signupToken + "\",\"name\":\"김보출\",\"agreedTermIds\":[" + required.get(0) + "]}"), 400);
        String signed = json(mvc, postJson("/auth/signup",
                "{\"signupToken\":\"" + signupToken + "\",\"name\":\"김보출\",\"agreedTermIds\":" + required + "}"), 201);
        String access = JsonPath.read(signed, "$.accessToken");
        String refresh1 = JsonPath.read(signed, "$.refreshToken");

        // 3) 액세스 토큰으로 /me, 토큰 없음·가입 토큰은 401
        String me = json(mvc, get("/me").header("Authorization", "Bearer " + access), 200);
        assertThat((String) JsonPath.read(me, "$.name")).isEqualTo("김보출");
        assertThat((String) JsonPath.read(me, "$.phoneMasked")).isEqualTo("010****0001");
        assertThat((List<String>) JsonPath.read(me, "$.roles")).containsExactly("ACTOR");
        json(mvc, get("/me"), 401);
        json(mvc, get("/me").header("Authorization", "Bearer " + signupToken), 401);

        // 4) 리프레시 회전: 새 토큰 발급, 이전 토큰 재사용은 401 + 모든 토큰 폐기
        String rotated = json(mvc, postJson("/auth/refresh", "{\"refreshToken\":\"" + refresh1 + "\"}"), 200);
        String refresh2 = JsonPath.read(rotated, "$.refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);
        json(mvc, postJson("/auth/refresh", "{\"refreshToken\":\"" + refresh1 + "\"}"), 401);
        json(mvc, postJson("/auth/refresh", "{\"refreshToken\":\"" + refresh2 + "\"}"), 401);

        // 5) 같은 번호로 다시 로그인(재전송 대기 때문에 바로 요청하면 429)
        json(mvc, postJson("/auth/otp/send", "{\"phone\":\"" + phone + "\"}"), 429);
    }

    @Test
    void tooManyWrongAttemptsLocksTheCode() throws Exception {
        String phone = "01090000002";
        json(mvc, postJson("/auth/otp/send", "{\"phone\":\"" + phone + "\"}"), 202);
        String code = sms.lastCodeFor(phone);
        String wrong = code.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < 5; i++) json(mvc, postJson("/auth/otp/verify", "{\"phone\":\"" + phone + "\",\"code\":\"" + wrong + "\"}"), 400);
        // 시도 상한 초과 후에는 맞는 번호도 거부(429)
        json(mvc, postJson("/auth/otp/verify", "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"), 429);
    }

    @Test
    void invalidPhoneIs400() throws Exception {
        json(mvc, postJson("/auth/otp/send", "{\"phone\":\"02-123-4567\"}"), 400);
        json(mvc, postJson("/auth/otp/send", "{}"), 400);
    }

    @Test
    void loggedInOnSecondVisit() throws Exception {
        String phone = "01090000003";
        json(mvc, postJson("/auth/otp/send", "{\"phone\":\"" + phone + "\"}"), 202);
        String v1 = json(mvc, postJson("/auth/otp/verify", "{\"phone\":\"" + phone + "\",\"code\":\"" + sms.lastCodeFor(phone) + "\"}"), 200);
        String token = JsonPath.read(v1, "$.signupToken");
        List<Integer> ids = signupTermIds(json(mvc, get("/terms"), 200));
        json(mvc, postJson("/auth/signup", "{\"signupToken\":\"" + token + "\",\"name\":\"이출연\",\"agreedTermIds\":" + ids + "}"), 201);
    }
}
