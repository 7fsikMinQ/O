package kr.bojohelp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import kr.bojohelp.support.AbstractPgTest;
import kr.bojohelp.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자는 2차 비밀번호(DB 고정값)가 맞아야 로그인된다. 칸은 1차가 맞은 뒤 관리자일 때만 요구되고, 일반 계정은 영향이 없다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.admin.email=second@test.example", "app.admin.password=AdminPass1234", "app.storage.local-dir=build/test-files"})
class AdminSecondPasswordIT extends AbstractPgTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private String login(String body, int expected) throws Exception {
        return mvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(r -> { r.setRemoteAddr("198.51.100.7"); return r; }))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminNeedsSecondPassword() throws Exception {
        String first = "\"email\":\"second@test.example\",\"password\":\"AdminPass1234\"";
        // 1차만 → 2차 칸 요구(토큰 없음)
        String need = login("{" + first + "}", 200);
        assertThat((String) JsonPath.read(need, "$.status")).isEqualTo("SECOND_PASSWORD_REQUIRED");
        assertThat(need).doesNotContain("accessToken");
        // 틀린 2차 → 401, 맞는 2차 → 토큰
        login("{" + first + ",\"secondPassword\":\"nope-nope\"}", 401);
        String ok = login("{" + first + ",\"secondPassword\":\"admin1234\"}", 200);
        String token = JsonPath.read(ok, "$.accessToken");
        mvc.perform(get("/admin/companies").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        // 1차가 틀리면 2차를 맞게 보내도 401(계정 종류가 드러나지 않음)
        login("{\"email\":\"second@test.example\",\"password\":\"Wrong-pass-1\",\"secondPassword\":\"admin1234\"}", 401);
    }

    @Test
    void normalUserNeverSeesSecondPassword() throws Exception {
        TestUsers.emailSignup(mvc, "plain@test.example", "Passw0rd1234", "일반");
        String res = login("{\"email\":\"plain@test.example\",\"password\":\"Passw0rd1234\"}", 200);
        assertThat(res).contains("accessToken").doesNotContain("SECOND_PASSWORD_REQUIRED");
    }

    @Test
    void secondPasswordCannotBeChangedOrDeleted() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE admin_second_password SET secret_hash = 'x'").update()).hasMessageContaining("수정");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM admin_second_password").update()).hasMessageContaining("삭제");
        assertThat(jdbc.sql("SELECT count(*) FROM admin_second_password").query(Integer.class).single()).isEqualTo(1);
    }
}
