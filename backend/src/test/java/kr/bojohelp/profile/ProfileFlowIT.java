package kr.bojohelp.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import kr.bojohelp.support.AbstractPgTest;
import kr.bojohelp.support.RecordingSmsSender;
import kr.bojohelp.support.TestSmsConfig;
import kr.bojohelp.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 프로필·계좌·주민번호: 저장·조회·마스킹·암호화·중복 방지·감사. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSmsConfig.class)
class ProfileFlowIT extends AbstractPgTest {

    private static final String RRN = "9001011234567";
    private static final String ACCOUNT = "110123456789";
    private static final String PROFILE = """
            {"birthDate":"1990-01-01","gender":"MALE","heightCm":175,"weightKg":68,"topSize":"100","bottomSize":"32","shoeSize":270,
             "hairLength":"SHORT","hasTattoo":false,"canDrive":true,"homeAddress":"서울 강남구 테헤란로 1","introduction":"성실합니다"}
            """;

    @Autowired MockMvc mvc;
    @Autowired RecordingSmsSender sms;
    @Autowired JdbcClient jdbc;

    private MockHttpServletRequestBuilder putJson(String path, String token, String body) {
        return put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String body(MockHttpServletRequestBuilder rb, int expected) throws Exception {
        return mvc.perform(rb).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void requiresLoginAndActorRole() throws Exception {
        body(get("/me/actor-profile"), 401);
        body(get("/me/actor-profile/tax-identity"), 401);
    }

    @Test
    void profileSaveAndRead() throws Exception {
        String t = TestUsers.signup(mvc, sms, "01070000001", "프로필");
        body(get("/me/actor-profile").header("Authorization", "Bearer " + t), 404);
        body(putJson("/me/actor-profile", t, PROFILE.replace("\"heightCm\":175", "\"heightCm\":10")), 400);
        body(putJson("/me/actor-profile", t, PROFILE.replace("\"hairLength\":\"SHORT\"", "\"hairLength\":\"BALD\"")), 400);
        body(putJson("/me/actor-profile", t, PROFILE.replace("\"hasTattoo\":false", "\"regionId\":99999999")), 400);
        body(putJson("/me/actor-profile", t, PROFILE), 204);

        String got = body(get("/me/actor-profile").header("Authorization", "Bearer " + t), 200);
        assertThat((Integer) JsonPath.read(got, "$.heightCm")).isEqualTo(175);
        assertThat((String) JsonPath.read(got, "$.homeAddress")).contains("강남구");
        assertThat((Boolean) JsonPath.read(got, "$.isAdult")).isTrue();
        assertThat((Boolean) JsonPath.read(got, "$.hasTaxIdentity")).isFalse();

        // 수정(upsert)
        body(putJson("/me/actor-profile", t, PROFILE.replace("\"weightKg\":68", "\"weightKg\":70")), 204);
        assertThat((Integer) JsonPath.read(body(get("/me/actor-profile").header("Authorization", "Bearer " + t), 200), "$.weightKg")).isEqualTo(70);
    }

    @Test
    void payoutAccountIsEncryptedAndMasked() throws Exception {
        String t = TestUsers.signup(mvc, sms, "01070000002", "계좌");
        String payout = "{\"bankCode\":\"004\",\"accountNumber\":\"" + ACCOUNT + "\",\"holder\":\"계좌\",\"defaultDepartureLocation\":\"사당역\"}";
        body(putJson("/me/actor-profile/payout", t, payout), 409);   // 프로필 먼저
        body(putJson("/me/actor-profile", t, PROFILE), 204);
        body(putJson("/me/actor-profile/payout", t, payout.replace("\"004\"", "\"999\"")), 400);
        body(putJson("/me/actor-profile/payout", t, payout.replace(ACCOUNT, "12")), 400);
        body(putJson("/me/actor-profile/payout", t, payout), 204);

        String got = body(get("/me/actor-profile/payout").header("Authorization", "Bearer " + t), 200);
        assertThat(got).doesNotContain(ACCOUNT);
        assertThat((String) JsonPath.read(got, "$.accountMasked")).isEqualTo("****6789");
        assertThat((Boolean) JsonPath.read(got, "$.holderMatchesName")).isTrue();
        assertThat((String) JsonPath.read(got, "$.defaultDepartureLocation")).isEqualTo("사당역");

        String stored = jdbc.sql("SELECT account_number_enc FROM actor_payout_accounts a JOIN users u ON u.id = a.user_id WHERE u.phone = '01070000002'")
                .query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain(ACCOUNT);

        // 예금주가 본인 이름과 다르면 표시
        body(putJson("/me/actor-profile/payout", t, payout.replace("\"holder\":\"계좌\"", "\"holder\":\"타인\"")), 204);
        assertThat((Boolean) JsonPath.read(body(get("/me/actor-profile/payout").header("Authorization", "Bearer " + t), 200), "$.holderMatchesName")).isFalse();
    }

    @Test
    void taxIdentityIsWriteOnlyEncryptedUniqueAndAudited() throws Exception {
        String a = TestUsers.signup(mvc, sms, "01070000003", "주민");
        String rrnBody = "{\"rrn\":\"900101-1234567\",\"termVersion\":\"0.1\"}";
        body(putJson("/me/actor-profile/tax-identity", a, rrnBody), 409);   // 프로필 먼저
        body(putJson("/me/actor-profile", a, PROFILE), 204);

        body(putJson("/me/actor-profile/tax-identity", a, rrnBody.replace("0.1", "9.9")), 400);              // 약관 버전 불일치
        body(putJson("/me/actor-profile/tax-identity", a, rrnBody.replace("900101-1234567", "900102-1234567")), 400);   // 생년월일 불일치
        body(putJson("/me/actor-profile/tax-identity", a, rrnBody.replace("900101-1234567", "900101-2234567")), 400);   // 성별 불일치
        body(putJson("/me/actor-profile/tax-identity", a, rrnBody.replace("900101-1234567", "12345")), 400);            // 형식 오류

        String ok = body(putJson("/me/actor-profile/tax-identity", a, rrnBody), 200);
        assertThat(ok).doesNotContain(RRN).doesNotContain("1234567");
        assertThat((String) JsonPath.read(ok, "$.masked")).isEqualTo("900101-1******");
        String status = body(get("/me/actor-profile/tax-identity").header("Authorization", "Bearer " + a), 200);
        assertThat(status).doesNotContain(RRN);
        assertThat((Boolean) JsonPath.read(status, "$.registered")).isTrue();
        body(putJson("/me/actor-profile/tax-identity", a, rrnBody), 200);   // 같은 번호 재등록은 변경 없음

        var row = jdbc.sql("SELECT rrn_enc, rrn_hmac, key_version, consent_term_id FROM actor_tax_identities t JOIN users u ON u.id = t.user_id WHERE u.phone = '01070000003' AND t.is_active")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getInt(3), rs.getObject(4)}).single();
        assertThat((String) row[0]).startsWith("v1:").doesNotContain(RRN);
        assertThat((String) row[1]).hasSize(64).doesNotContain(RRN);
        assertThat(row[3]).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action = 'RRN_REGISTER' AND after_data::text NOT LIKE '%1234567%'").query(Integer.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM user_consents c JOIN terms t ON t.id = c.term_id JOIN users u ON u.id = c.user_id WHERE u.phone = '01070000003' AND t.type = 'SENSITIVE_ID'").query(Integer.class).single()).isEqualTo(1);

        // 같은 주민번호를 다른 계정이 등록하려 하면 거부(중복 가입·노쇼 이력 세탁 방지)
        String b = TestUsers.signup(mvc, sms, "01070000004", "중복");
        body(putJson("/me/actor-profile", b, PROFILE), 204);
        body(putJson("/me/actor-profile/tax-identity", b, rrnBody), 409);

        // 주민번호 등록 후에는 생년월일·성별 변경 불가, 다른 항목은 변경 가능
        body(putJson("/me/actor-profile", a, PROFILE.replace("1990-01-01", "1991-01-01")), 409);
        body(putJson("/me/actor-profile", a, PROFILE.replace("\"heightCm\":175", "\"heightCm\":176")), 204);
    }
}
