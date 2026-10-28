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
}
