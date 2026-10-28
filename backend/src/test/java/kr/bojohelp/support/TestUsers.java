package kr.bojohelp.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 시험용 가입 도우미: 문자 인증 → 약관 동의 가입까지 거쳐 액세스 토큰을 돌려준다. */
public final class TestUsers {

    private TestUsers() {}

    public static String signup(MockMvc mvc, RecordingSmsSender sms, String phoneDigits, String name) throws Exception {
        mvc.perform(post("/auth/otp/send").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + phoneDigits + "\"}"))
                .andExpect(status().isAccepted());
        String verified = mvc.perform(post("/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phoneDigits + "\",\"code\":\"" + sms.lastCodeFor(phoneDigits) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String signupToken = JsonPath.read(verified, "$.signupToken");
        List<Map<String, Object>> terms = JsonPath.read(mvc.perform(get("/terms")).andReturn().getResponse().getContentAsString(), "$");
        List<Integer> ids = terms.stream()
                .filter(t -> List.of("SERVICE", "PRIVACY", "THIRD_PARTY_PROVISION").contains(t.get("type")))
                .map(t -> ((Number) t.get("id")).intValue()).toList();
        String signed = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signupToken\":\"" + signupToken + "\",\"name\":\"" + name + "\",\"agreedTermIds\":" + ids + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(signed, "$.accessToken");
    }

    /** 업체·관리자용 이메일 가입(필수 약관 2종 동의) → 액세스 토큰. */
    public static String emailSignup(MockMvc mvc, String email, String password, String name) throws Exception {
        List<Map<String, Object>> terms = JsonPath.read(mvc.perform(get("/terms")).andReturn().getResponse().getContentAsString(), "$");
        List<Integer> ids = terms.stream().filter(t -> List.of("SERVICE", "PRIVACY").contains(t.get("type"))).map(t -> ((Number) t.get("id")).intValue()).toList();
        String signed = mvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"name\":\"" + name + "\",\"agreedTermIds\":" + ids + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(signed, "$.accessToken");
    }

    public static String emailLogin(MockMvc mvc, String email, String password) throws Exception {
        String res = mvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.accessToken");
    }

    /** 앞 9자리에 검증숫자를 붙인 유효한 사업자등록번호(10자리). */
    public static String bizNo(String first9) {
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (first9.charAt(i) - '0') * w[i];
        sum += ((first9.charAt(8) - '0') * 5) / 10;
        return first9 + ((10 - sum % 10) % 10);
    }
}
