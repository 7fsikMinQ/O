package kr.bojohelp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import kr.bojohelp.support.AbstractPgTest;
import kr.bojohelp.support.Fixtures;
import kr.bojohelp.support.RecordingSmsSender;
import kr.bojohelp.support.TestSmsConfig;
import kr.bojohelp.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 회원 정지·공고 숨김·기능 스위치(법무 확인 필요 항목, 송금 실행 금지)·최저시급·감사 로그, 탈퇴와 관심 공고. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSmsConfig.class)
@TestPropertySource(properties = {"app.storage.local-dir=build/test-files", "app.admin.email=ops@test.example", "app.admin.password=AdminPass1234"})
class AdminOpsIT extends AbstractPgTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RecordingSmsSender sms;
    Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(mvc, jdbc, sms);
    }

    private String admin() throws Exception {
        return TestUsers.adminLogin(mvc, "ops@test.example", "AdminPass1234");
    }

    private String auth(String path, String token, int expected) throws Exception {
        return fx.send(get(path).header("Authorization", "Bearer " + token), expected);
    }

    @Test
    void adminOperations() throws Exception {
        String admin = admin();
        var a = fx.actor("01010000001", "정지대상");
        var c = fx.company("ops1@test.example", "운영사", "700100100");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("숨길 공고", 6, 8, 1, null));

        // 일반 사용자는 관리자 API 접근 불가, 목록의 번호는 마스킹
        auth("/admin/users", a.token(), 403);
        String users = auth("/admin/users?q=정지대상", admin, 200);
        assertThat((String) JsonPath.read(users, "$[0].phone")).isEqualTo("010****0001");

        // 정지: 사유 필수, 로그인·갱신 차단, 해제하면 복구. 관리자 계정은 정지 불가
        fx.send(fx.json(post("/admin/users/" + a.userId() + "/suspend"), admin, "{\"reason\":\"\"}"), 400);
        fx.send(fx.json(post("/admin/users/" + a.userId() + "/suspend"), admin, "{\"reason\":\"신고 누적\"}"), 204);
        jdbc.sql("UPDATE otp_challenges SET created_at = created_at - interval '5 minutes'").update();   // 재전송 대기시간(60초) 건너뛰기
        fx.send(fx.json(post("/auth/otp/send"), null, "{\"phone\":\"01010000001\"}"), 202);
        String code = sms.lastCodeFor("01010000001");
        fx.send(fx.json(post("/auth/otp/verify"), null, "{\"phone\":\"01010000001\",\"code\":\"" + code + "\"}"), 403);
        fx.send(fx.json(post("/admin/users/" + a.userId() + "/restore"), admin, "{}"), 204);
        long adminId = jdbc.sql("SELECT id FROM users WHERE email = 'ops@test.example'").query(Long.class).single();
        fx.send(fx.json(post("/admin/users/" + adminId + "/suspend"), admin, "{\"reason\":\"x\"}"), 403);

        // 공고 숨김 → 출연자 목록에서 사라진다
        assertThat((List<?>) JsonPath.read(fx.send(get("/jobs?q=숨길 공고"), 200), "$.items")).hasSize(1);
        fx.send(fx.json(post("/admin/jobs/" + posting + "/hide"), admin, "{\"reason\":\"부적절\"}"), 204);
        assertThat((List<?>) JsonPath.read(fx.send(get("/jobs?q=숨길 공고"), 200), "$.items")).isEmpty();

        // 기능 스위치: 법무 확인 필요 항목은 confirmLegal 없이 못 켠다, 송금 실행은 영원히 못 켠다, 켜면 서버가 404 를 거두고 끄면 다시 404
        fx.send(fx.json(put("/admin/feature-flags/SETTLEMENT_RECORD"), admin, "{\"enabled\":true,\"confirmLegal\":false}"), 400);
        fx.send(fx.json(put("/admin/feature-flags/PAYOUT_EXECUTION"), admin, "{\"enabled\":true,\"confirmLegal\":true}"), 403);
        fx.send(fx.json(put("/admin/feature-flags/SETTLEMENT_RECORD"), admin, "{\"enabled\":true,\"confirmLegal\":true}"), 204);
        assertThat((Boolean) JsonPath.read(fx.send(get("/features"), 200), "$.SETTLEMENT_RECORD")).isTrue();
        fx.send(fx.json(put("/admin/feature-flags/SETTLEMENT_RECORD"), admin, "{\"enabled\":false,\"confirmLegal\":false}"), 204);
        assertThat((Boolean) JsonPath.read(fx.send(get("/features"), 200), "$.SETTLEMENT_RECORD")).isFalse();

        // 최저시급 등록·변경 → 공고 작성에 반영, 설정 변경, 감사 로그, 통계
        fx.send(fx.json(put("/admin/minimum-wages/2099"), admin, "{\"hourlyKrw\":20000}"), 204);
        assertThat((Integer) JsonPath.read(fx.send(get("/master/minimum-wage?year=2099"), 200), "$.hourlyKrw")).isEqualTo(20000);
        fx.send(fx.json(put("/admin/settings/star_limit_free"), admin, "{\"value\":\"30\"}"), 204);
        fx.send(fx.json(put("/admin/settings/no_such_key"), admin, "{\"value\":\"1\"}"), 404);
        String logs = auth("/admin/audit-logs?limit=100", admin, 200);
        assertThat((List<String>) JsonPath.read(logs, "$[*].action")).contains("USER_SUSPEND", "JOB_HIDE", "FEATURE_FLAG", "MIN_WAGE_CHANGE");
        assertThat((Integer) JsonPath.read(auth("/admin/stats", admin, 200), "$.users")).isGreaterThan(1);
    }

    @Test
    void withdrawalAndBookmarks() throws Exception {
        var c = fx.company("ops2@test.example", "탈퇴사", "700100200");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("탈퇴 시험", 6, 8, 1, null));
        var a = fx.actor("01010000011", "탈퇴자");
        fx.send(put("/jobs/" + posting + "/bookmark").header("Authorization", "Bearer " + a.token()), 204);
        assertThat((List<?>) JsonPath.read(auth("/me/bookmarks", a.token(), 200), "$")).hasSize(1);
        fx.send(delete("/jobs/" + posting + "/bookmark").header("Authorization", "Bearer " + a.token()), 204);

        long app = fx.apply(a, fx.positionOf(posting), 201);
        fx.send(fx.json(post("/company/applications/decide"), c.ownerToken(), "{\"decision\":\"SELECT\",\"applicationIds\":[" + app + "]}"), 200);
        fx.send(fx.json(post("/applications/" + app + "/confirm"), a.token(), "{}"), 200);
        fx.send(delete("/me").header("Authorization", "Bearer " + a.token()), 409);   // 확정된 일정이 있으면 먼저 정리
        fx.send(delete("/applications/" + app + "?reason=개인사정").header("Authorization", "Bearer " + a.token()), 200);
        fx.send(delete("/me").header("Authorization", "Bearer " + a.token()), 200);
        assertThat(jdbc.sql("SELECT status FROM users WHERE id = :u").param("u", a.userId()).query(String.class).single()).isEqualTo("WITHDRAWN");
        // 같은 번호로 새로 가입할 수 있다(옛 계정은 삭제 표시)
        jdbc.sql("UPDATE otp_challenges SET created_at = created_at - interval '5 minutes'").update();
        assertThat(TestUsers.signup(mvc, sms, "01010000011", "다시")).isNotBlank();
        // 로그인 없이 웹에서 탈퇴 요청(번호는 해시로만 저장)
        fx.send(fx.json(post("/public/account-deletion-requests"), null, "{\"contact\":\"01012345678\"}"), 202);
        assertThat(jdbc.sql("SELECT count(*) FROM account_deletion_requests WHERE channel = 'WEB' AND contact_hash IS NOT NULL").query(Integer.class).single()).isGreaterThanOrEqualTo(1);
    }
}
