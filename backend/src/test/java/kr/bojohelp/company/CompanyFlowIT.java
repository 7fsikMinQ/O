package kr.bojohelp.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import kr.bojohelp.support.AbstractPgTest;
import kr.bojohelp.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 업체·관리자 이메일 계정, 업체 등록·승인·반려·재신청, 직원 초대·직책 규칙, 관리자 접근 제한. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.storage.local-dir=build/test-files", "app.admin.email=admin@test.example", "app.admin.password=AdminPass1234"})
class CompanyFlowIT extends AbstractPgTest {

    private static final String PW = "Passw0rd1234";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private String send(RequestBuilder rb, int expected) throws Exception {
        return mvc.perform(rb).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder rb, String token, String body) {
        var b = rb.contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder rb, String token) {
        return rb.header("Authorization", "Bearer " + token);
    }

    /** 이메일 가입 + 확인 완료(메일 확인 절차 자체는 EmailFlowIT 에서 시험). */
    private String signupVerified(String email, String name) throws Exception {
        String t = TestUsers.emailSignup(mvc, email, PW, name);
        TestUsers.markEmailVerified(jdbc, email);
        return t;
    }

    private long register(String token, String biz, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"bizRegNo\":\"" + biz + "\",\"representative\":\"대표\",\"phone\":\"02-123-4567\",\"address\":\"서울\"}";
        return ((Number) JsonPath.read(send(json(post("/companies"), token, body), 201), "$.id")).longValue();
    }

    private void upload(String token, long companyId, byte[] bytes, int expected) throws Exception {
        send(multipart("/companies/" + companyId + "/license").file(new MockMultipartFile("file", "license.pdf", "application/pdf", bytes)).header("Authorization", "Bearer " + token), expected);
    }

    @Test
    void emailAccountRulesAndLockout() throws Exception {
        String email = "rules@test.example";
        TestUsers.emailSignup(mvc, email, PW, "규칙");
        send(json(post("/auth/email/signup"), null, "{\"email\":\"" + email + "\",\"password\":\"" + PW + "\",\"name\":\"중복\",\"agreedTermIds\":[1,2]}"), 409);
        send(json(post("/auth/email/signup"), null, "{\"email\":\"weak@test.example\",\"password\":\"short1\",\"name\":\"약함\",\"agreedTermIds\":[1,2]}"), 400);
        send(json(post("/auth/email/signup"), null, "{\"email\":\"noterms@test.example\",\"password\":\"" + PW + "\",\"name\":\"동의없음\",\"agreedTermIds\":[]}"), 400);

        assertThat(TestUsers.emailLogin(mvc, email, PW)).isNotBlank();
        send(json(post("/auth/email/login"), null, "{\"email\":\"nobody@test.example\",\"password\":\"" + PW + "\"}"), 401);   // 없는 이메일도 같은 응답
        for (int i = 0; i < 5; i++) send(json(post("/auth/email/login"), null, "{\"email\":\"" + email + "\",\"password\":\"WrongPass999\"}"), 401);
        send(json(post("/auth/email/login"), null, "{\"email\":\"" + email + "\",\"password\":\"" + PW + "\"}"), 429);   // 5회 실패 후엔 맞는 비밀번호도 잠금
        // 비밀번호 해시는 평문이 아님
        assertThat(jdbc.sql("SELECT password_hash FROM users WHERE email = :e").param("e", email).query(String.class).single()).startsWith("$2").doesNotContain(PW);
    }

    @Test
    void companyRegistrationApprovalAndRejection() throws Exception {
        String owner = signupVerified("owner1@test.example", "대표1");
        String other = signupVerified("other1@test.example", "타인1");
        String biz = TestUsers.bizNo("111222333");

        send(json(post("/companies"), owner, "{\"name\":\"가짜\",\"bizRegNo\":\"123-45-67890\",\"representative\":\"대표\"}"), 400);   // 검증숫자 불일치
        long id = register(owner, biz, "보출A");
        send(json(post("/companies"), other, "{\"name\":\"중복\",\"bizRegNo\":\"" + biz + "\",\"representative\":\"대표\"}"), 409);

        String mine = send(auth(get("/me/companies"), owner), 200);
        assertThat((String) JsonPath.read(mine, "$[0].status")).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(mine, "$[0].memberRole")).isEqualTo("OWNER");

        // 사업자등록증: PDF 허용, 아닌 것은 거부, 3개 제한, 남은 접근 불가
        upload(owner, id, "%PDF-1.4 test".getBytes(), 201);
        upload(owner, id, "plain text".getBytes(), 400);
        upload(other, id, "%PDF-1.4 x".getBytes(), 404);
        upload(owner, id, "%PDF-1.4 b".getBytes(), 201);
        upload(owner, id, "%PDF-1.4 c".getBytes(), 201);
        upload(owner, id, "%PDF-1.4 d".getBytes(), 409);

        // 승인 전에는 구성원만 본다(사업자번호는 구성원에게만)
        send(auth(get("/companies/" + id), other), 404);
        assertThat((String) JsonPath.read(send(auth(get("/companies/" + id), owner), 200), "$.bizRegNo")).isEqualTo(biz.substring(0, 3) + "-" + biz.substring(3, 5) + "-" + biz.substring(5));

        // 관리자: 일반 사용자는 403, 관리자는 목록·상세(서명 URL)·승인
        send(auth(get("/admin/companies"), owner), 403);
        send(get("/admin/companies"), 401);
        String admin = TestUsers.adminLogin(mvc, "admin@test.example", "AdminPass1234");
        String pending = send(auth(get("/admin/companies?status=PENDING"), admin), 200);
        assertThat((List<?>) JsonPath.read(pending, "$[?(@.id==" + id + ")]")).hasSize(1);
        String detail = send(auth(get("/admin/companies/" + id), admin), 200);
        assertThat((Integer) JsonPath.read(detail, "$.licenses.length()")).isEqualTo(3);
        String url = JsonPath.read(detail, "$.licenses[0].url");
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf")).andExpect(header().string("Content-Disposition", "attachment"));

        send(auth(post("/admin/companies/" + id + "/approve"), admin), 204);
        send(auth(post("/admin/companies/" + id + "/approve"), admin), 409);   // 이미 처리됨
        assertThat(jdbc.sql("SELECT status FROM companies WHERE id = :i").param("i", id).query(String.class).single()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT status FROM company_verifications WHERE company_id = :i ORDER BY id DESC LIMIT 1").param("i", id).query(String.class).single()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action = 'COMPANY_APPROVE' AND entity_id = :i").param("i", id).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action = 'COMPANY_LICENSE_VIEW' AND entity_id = :i").param("i", id).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_type = 'COMPANY_APPROVED' AND aggregate_id = :i").param("i", id).query(Integer.class).single()).isEqualTo(1);
        String publicView = send(auth(get("/companies/" + id), other), 200);   // 승인되면 누구나 공개 프로필 조회
        assertThat(publicView).doesNotContain("bizRegNo");

        // 반려 → 사유 필수 → 재신청 → 다시 대기
        long id2 = register(other, TestUsers.bizNo("444555666"), "보출B");
        send(auth(post("/admin/companies/" + id2 + "/reject"), admin).contentType(MediaType.APPLICATION_JSON).content("{}"), 400);
        send(json(post("/admin/companies/" + id2 + "/reject"), admin, "{\"reason\":\"등록증이 흐려요\"}"), 204);
        upload(other, id2, "%PDF-1.4 z".getBytes(), 409);   // 반려 상태에서는 재신청 먼저
        send(auth(post("/companies/" + id + "/resubmit"), owner), 409);   // 승인된 업체는 재신청 불가(409)
        send(auth(post("/companies/" + id2 + "/resubmit"), other), 200);
        assertThat(jdbc.sql("SELECT status FROM companies WHERE id = :i").param("i", id2).query(String.class).single()).isEqualTo("PENDING");
        upload(other, id2, "%PDF-1.4 z".getBytes(), 201);
    }

    @Test
    void memberInvitationsAndRoleRules() throws Exception {
        String owner = signupVerified("owner2@test.example", "대표2");
        String mgr = signupVerified("mgr2@test.example", "팀장");
        String staff = signupVerified("staff2@test.example", "스태프");
        String outsider = signupVerified("out2@test.example", "외부");
        long id = register(owner, TestUsers.bizNo("777888999"), "보출C");
        String base = "/companies/" + id;

        send(auth(get(base + "/members"), outsider), 404);
        send(json(post(base + "/members"), owner, "{\"email\":\"ghost@test.example\",\"memberRole\":\"STAFF\"}"), 404);          // 가입 안 한 이메일
        send(json(post(base + "/members"), owner, "{\"email\":\"mgr2@test.example\",\"memberRole\":\"OWNER\"}"), 400);           // 대표는 지정 불가
        send(json(post(base + "/members"), owner, "{\"email\":\"mgr2@test.example\",\"memberRole\":\"MANAGER\"}"), 201);
        send(json(post(base + "/members"), owner, "{\"email\":\"mgr2@test.example\",\"memberRole\":\"MANAGER\"}"), 409);         // 중복 초대

        // 수락 전에는 업체 데이터 접근 불가, 수락하면 COMPANY 역할이 붙고 접근 가능
        send(auth(get(base + "/members"), mgr), 404);
        assertThat((Integer) JsonPath.read(send(auth(get("/me/company-invitations"), mgr), 200), "$.length()")).isEqualTo(1);
        send(auth(post("/me/company-invitations/" + id + "/accept"), mgr), 204);
        assertThat((Integer) JsonPath.read(send(auth(get(base + "/members"), mgr), 200), "$.length()")).isEqualTo(2);

        // 팀장은 스태프를 초대할 수 있지만 팀장을 지정할 수 없다
        send(json(post(base + "/members"), mgr, "{\"email\":\"staff2@test.example\",\"memberRole\":\"MANAGER\"}"), 403);
        send(json(post(base + "/members"), mgr, "{\"email\":\"staff2@test.example\",\"memberRole\":\"STAFF\",\"departmentName\":\"현장\"}"), 201);
        send(auth(post("/me/company-invitations/" + id + "/accept"), staff), 204);
        send(auth(get(base + "/members"), staff), 403);                                                                            // 스태프는 직원 목록 불가
        send(json(post(base + "/members"), staff, "{\"email\":\"out2@test.example\",\"memberRole\":\"STAFF\"}"), 403);

        // 대표는 보호되고, 팀장 변경은 대표만
        long ownerId = jdbc.sql("SELECT id FROM users WHERE email = 'owner2@test.example'").query(Long.class).single();
        long mgrId = jdbc.sql("SELECT id FROM users WHERE email = 'mgr2@test.example'").query(Long.class).single();
        long staffId = jdbc.sql("SELECT id FROM users WHERE email = 'staff2@test.example'").query(Long.class).single();
        send(json(patch(base + "/members/" + ownerId), mgr, "{\"memberRole\":\"STAFF\"}"), 403);
        send(json(patch(base + "/members/" + mgrId), mgr, "{\"memberRole\":\"STAFF\"}"), 403);

        // 스케줄러 담당은 최대 4명
        send(json(patch(base + "/members/" + staffId), mgr, "{\"memberRole\":\"SCHEDULER\"}"), 204);
        for (int i = 0; i < 3; i++) {
            String u = signupVerified("sch" + i + "@test.example", "스케줄러" + i);
            send(json(post(base + "/members"), owner, "{\"email\":\"sch" + i + "@test.example\",\"memberRole\":\"SCHEDULER\"}"), 201);
            send(auth(post("/me/company-invitations/" + id + "/accept"), u), 204);
        }
        send(json(post(base + "/members"), owner, "{\"email\":\"out2@test.example\",\"memberRole\":\"SCHEDULER\"}"), 409);   // 5번째

        // 제외하면 업체 데이터 접근이 막힌다
        send(json(patch(base + "/members/" + staffId), owner, "{\"status\":\"REMOVED\"}"), 204);
        send(auth(get(base + "/members"), staff), 404);

        // 프로필 수정은 대표·팀장만
        send(json(patch(base), mgr, "{\"introduction\":\"소개\"}"), 204);
        send(json(patch(base), staff, "{\"introduction\":\"해킹\"}"), 404);
    }
}
