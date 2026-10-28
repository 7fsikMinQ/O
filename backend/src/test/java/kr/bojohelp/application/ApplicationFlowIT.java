package kr.bojohelp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** 지원 → 합격 통보 → 확정, 정원 동시성, 겹침 경고/override, 마감 자동 불합격·재선발, 응답 기한 만료, 취소·접근 차단. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSmsConfig.class)
@TestPropertySource(properties = "app.storage.local-dir=build/test-files")
class ApplicationFlowIT extends AbstractPgTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RecordingSmsSender sms;
    @Autowired SelectionService selection;
    Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(mvc, jdbc, sms);
    }

    private String decide(Fixtures.Company c, String decision, long... ids) throws Exception {
        String arr = java.util.Arrays.toString(ids);
        return fx.send(fx.json(post("/company/applications/decide"), c.ownerToken(), "{\"decision\":\"" + decision + "\",\"applicationIds\":" + arr + "}"), 200);
    }

    private int count(String sql, Object... p) {
        var s = jdbc.sql(sql);
        for (int i = 0; i < p.length; i++) s = s.param(String.valueOf(i + 1), p[i]);
        return s.query(Integer.class).single();
    }

    @Test
    void applySelectConfirmFlow() throws Exception {
        var c = fx.company("app1@test.example", "지원사", "400100100");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("가제 지원 A", 6, 8, 2, null));
        long pos = fx.positionOf(posting);
        var a = fx.actor("01050000001", "에이");
        var b = fx.actor("01050000002", "비이");
        var d = fx.actor("01050000003", "디이");

        // 지원 폼: 자격 충족, 전자서명 약관 동의 필요
        String form = fx.send(get("/positions/" + pos + "/apply-form").header("Authorization", "Bearer " + a.token()), 200);
        assertThat((Boolean) JsonPath.read(form, "$.eligible")).isTrue();
        assertThat((Object) JsonPath.read(form, "$.eSignatureTerm")).isNotNull();

        long appA = fx.apply(a, pos, 201);
        fx.apply(a, pos, 409);   // 같은 역할 중복 지원
        long appB = fx.apply(b, pos, 201);
        long appD = fx.apply(d, pos, 201);
        assertThat(count("SELECT applied_count FROM job_positions WHERE id = " + pos)).isEqualTo(3);
        // 서명·사진이 지원서에 붙고 스냅샷 해시가 남는다
        assertThat(count("SELECT count(*) FROM file_attachments WHERE entity_type = 'APPLICATION' AND entity_id = " + appA)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT snapshot_hash FROM applications WHERE id = :id").param("id", appA).query(String.class).single()).hasSize(64);
        // 서명 파일 재사용 불가(남의 파일·이미 쓴 파일)
        long used = jdbc.sql("SELECT file_id FROM file_attachments WHERE entity_id = :a AND usage = 'SIGNATURE'").param("a", appA).query(Long.class).single();
        fx.send(fx.json(post("/positions/" + fx.positionOf(fx.createAndPublish(c, Fixtures.postingJson("가제 지원 B", 7, 8, 1, null))) + "/applications"), b.token(),
                "{\"photoFileId\":" + used + ",\"signatureFileId\":" + used + ",\"agreeESignature\":true,\"useProfileAccount\":true}"), 400);

        // 업체: 지원자 목록 — 번호는 보이고 주민번호·주소는 가려짐
        String list = fx.send(get("/company/positions/" + pos + "/applications").header("Authorization", "Bearer " + c.ownerToken()), 200);
        assertThat((List<?>) JsonPath.read(list, "$")).hasSize(3);
        assertThat((String) JsonPath.read(list, "$[0].applicantPhone")).isEqualTo("01050000001");
        String detail = fx.send(get("/company/applications/" + appA).header("Authorization", "Bearer " + c.ownerToken()), 200);
        assertThat((String) JsonPath.read(detail, "$.rrnMasked")).matches("900101-1\\*{6}");
        assertThat(detail).doesNotContain("테헤란로").doesNotContain("accountMasked\":\"****");   // 합격 통보 전에는 주소·계좌 숨김
        assertThat(detail).doesNotContain("9001011");

        // 정원 2: A·B 통보 → D 는 정원 초과로 실패(부분 성공)
        String res = decide(c, "SELECT", appA, appB, appD);
        assertThat((List<Boolean>) JsonPath.read(res, "$[*].ok")).containsExactly(true, true, false);
        assertThat(count("SELECT selected_count FROM job_positions WHERE id = " + pos)).isEqualTo(2);
        // 합격 통보 후에는 집주소·계좌 끝자리가 보이고, 출연자는 현장 정보를 볼 수 있다
        String after = fx.send(get("/company/applications/" + appA).header("Authorization", "Bearer " + c.ownerToken()), 200);
        assertThat(after).contains("테헤란로").contains("accountMasked");
        String mine = fx.send(get("/me/applications/" + appA).header("Authorization", "Bearer " + a.token()), 200);
        assertThat((String) JsonPath.read(mine, "$.meetingAddress")).contains("강남대로");
        assertThat((Object) JsonPath.read(mine, "$.shootingLocation")).isEqualTo("비밀 촬영지");
        // 합격서에는 금액이 없다
        String offer = fx.send(get("/me/applications/" + appA + "/offer").header("Authorization", "Bearer " + a.token()), 200);
        assertThat(offer).doesNotContain("hourly").doesNotContain("Pay").contains("근로계약서는 현장에서");

        // 출연자 확정 → 확정 인원으로 이동, 두 번 눌러도 안전, 다른 사람은 확정 불가
        fx.send(fx.json(post("/applications/" + appA + "/confirm"), a.token(), "{}"), 200);
        fx.send(fx.json(post("/applications/" + appA + "/confirm"), a.token(), "{}"), 200);
        fx.send(fx.json(post("/applications/" + appB + "/confirm"), a.token(), "{}"), 404);
        assertThat(count("SELECT confirmed_count FROM job_positions WHERE id = " + pos)).isEqualTo(1);
        assertThat(count("SELECT selected_count FROM job_positions WHERE id = " + pos)).isEqualTo(1);

        // B 가 거절 → 정원 반환 → D 통보 성공
        fx.send(delete("/applications/" + appB).header("Authorization", "Bearer " + b.token()), 200);
        assertThat((List<Boolean>) JsonPath.read(decide(c, "SELECT", appD), "$[*].ok")).containsExactly(true);

        // 불합격(사유 없음): 새 공고에서 확인
        long posting2 = fx.createAndPublish(c, Fixtures.postingJson("가제 지원 C", 8, 8, 1, null));
        long appB2 = fx.apply(b, fx.positionOf(posting2), 201);
        assertThat((List<Boolean>) JsonPath.read(decide(c, "REJECT", appB2), "$[*].ok")).containsExactly(true);
        assertThat(jdbc.sql("SELECT status FROM applications WHERE id = :id").param("id", appB2).query(String.class).single()).isEqualTo("REJECTED");
    }

    @Test
    void eligibilityAndPreconditions() throws Exception {
        var c = fx.company("app2@test.example", "자격사", "400100200");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("여성 전용", 6, 8, 2, "\"genderReq\":\"FEMALE\""));
        long pos = fx.positionOf(posting);
        var male = fx.actor("01050000011", "남성");
        fx.apply(male, pos, 400);   // 성별 조건

        long open = fx.positionOf(fx.createAndPublish(c, Fixtures.postingJson("키 조건", 6, 8, 2, "\"heightMin\":180")));
        fx.apply(male, open, 400);   // 키 175 < 180

        // 주민번호 없이는 첫 지원 불가
        String t = TestUsers.signup(mvc, sms, "01050000012", "무번호");
        fx.send(fx.json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/me/actor-profile"), t,
                "{\"birthDate\":\"1990-01-01\",\"gender\":\"MALE\",\"heightCm\":175,\"weightKg\":68}"), 204);
        long any = fx.positionOf(fx.createAndPublish(c, Fixtures.postingJson("누구나", 6, 8, 2, null)));
        var noRrn = new Fixtures.Actor(t, 0, "01050000012");
        fx.apply(noRrn, any, 409);

        // 업체가 차단한 출연자는 지원 불가, 로그인 안 하면 401, 업체 계정은 지원 불가(역할)
        jdbc.sql("INSERT INTO company_actor_blocks (company_id, actor_user_id) VALUES (:c, :u)").param("c", c.companyId()).param("u", male.userId()).update();
        fx.apply(male, any, 403);
        fx.send(get("/positions/" + any + "/apply-form"), 401);
        fx.send(get("/positions/" + any + "/apply-form").header("Authorization", "Bearer " + c.ownerToken()), 403);
    }

    @Test
    void capacityNeverExceededUnderConcurrency() throws Exception {
        var c = fx.company("app3@test.example", "동시사", "400100300");
        long pos = fx.positionOf(fx.createAndPublish(c, Fixtures.postingJson("동시 선발", 6, 8, 2, null)));
        List<Long> apps = new ArrayList<>();
        for (int i = 0; i < 6; i++) apps.add(fx.apply(fx.actor("0105001" + String.format("%04d", i), "동시" + i), pos, 201));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Object>> futures = new ArrayList<>();
        for (Long id : apps) {
            Callable<Object> task = () -> selection.decide(c.ownerId(), c.companyId(), List.of(id), "SELECT", null);
            futures.add(pool.submit(task));
        }
        int ok = 0;
        for (Future<Object> f : futures) {
            List<java.util.Map<String, Object>> r = (List<java.util.Map<String, Object>>) f.get();
            if (Boolean.TRUE.equals(r.get(0).get("ok"))) ok++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(2);   // 정원 2 → 정확히 2명만 합격 통보
        assertThat(count("SELECT selected_count + confirmed_count FROM job_positions WHERE id = " + pos)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM applications WHERE position_id = " + pos + " AND status = 'SELECTED'")).isEqualTo(2);
    }

    @Test
    void overlappingScheduleNeedsAcknowledgement() throws Exception {
        var c1 = fx.company("app4@test.example", "겹침1", "400100400");
        var c2 = fx.company("app5@test.example", "겹침2", "400100500");
        var x = fx.actor("01050000021", "겹침이");
        long p1 = fx.createAndPublish(c1, Fixtures.postingJson("첫 일정", 10, 8, 1, null));       // 09:00~17:00
        long p2 = fx.createAndPublish(c2, Fixtures.postingJson("겹치는 일정", 10, 6, 1, null));   // 09:00~15:00
        long a1 = fx.apply(x, fx.positionOf(p1), 201);
        long a2 = fx.apply(x, fx.positionOf(p2), 201);
        decide(c1, "SELECT", a1);
        decide(c2, "SELECT", a2);
        // 아직 통보만 된 다른 일정과 겹쳐도 경고가 뜬다(앱은 SELECTED 와도 겹침을 검사). 알고 확정하면 업체 알림은 없다(확정된 일정과 겹친 것이 아니므로)
        fx.send(fx.json(post("/applications/" + a1 + "/confirm"), x.token(), "{}"), 409);
        fx.send(fx.json(post("/applications/" + a1 + "/confirm"), x.token(), "{\"acknowledgeConflict\":true}"), 200);
        assertThat(count("SELECT count(*) FROM schedule_conflicts WHERE actor_user_id = " + x.userId())).isZero();

        // 겹침 확인 없이 확정 → 409 + 겹친 일정 목록
        String conflict = fx.send(fx.json(post("/applications/" + a2 + "/confirm"), x.token(), "{}"), 409);
        assertThat((String) JsonPath.read(conflict, "$.code")).isEqualTo("SCHEDULE_CONFLICT");
        assertThat((List<?>) JsonPath.read(conflict, "$.conflicts")).hasSize(1);
        assertThat(jdbc.sql("SELECT status FROM applications WHERE id = :id").param("id", a2).query(String.class).single()).isEqualTo("SELECTED");

        // 알고 확정하면 양쪽 업체 알림 + 출연자 확인 요구(requires_ack), 충돌 행 생성
        fx.send(fx.json(post("/applications/" + a2 + "/confirm"), x.token(), "{\"acknowledgeConflict\":true}"), 200);
        assertThat(count("SELECT count(*) FROM schedule_conflicts WHERE actor_user_id = " + x.userId() + " AND acknowledged_at IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = " + c1.ownerId() + " AND type = 'SCHEDULE_CONFLICT'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = " + c2.ownerId() + " AND type = 'SCHEDULE_CONFLICT'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = " + x.userId() + " AND type = 'SCHEDULE_CONFLICT' AND requires_ack")).isEqualTo(1);

        // 확인할 때까지 1시간마다 재알림(시간을 당겨 시험), 확인하면 멈춤
        jdbc.sql("UPDATE schedule_conflicts SET last_alert_at = now() - interval '2 hours'").update();
        assertThat(selection.remindOpenConflicts()).isEqualTo(1);
        assertThat(selection.remindOpenConflicts()).isZero();   // 방금 알렸으니 아직
        long cid = jdbc.sql("SELECT id FROM schedule_conflicts WHERE actor_user_id = :u").param("u", x.userId()).query(Long.class).single();
        fx.send(post("/me/conflicts/" + cid + "/ack").header("Authorization", "Bearer " + x.token()), 204);
        jdbc.sql("UPDATE schedule_conflicts SET last_alert_at = now() - interval '2 hours'").update();
        assertThat(selection.remindOpenConflicts()).isZero();
    }

    @Test
    void closeAutoRejectsAndReselectionRules() throws Exception {
        var c = fx.company("app6@test.example", "마감사", "400100600");
        var other = fx.company("app7@test.example", "다른사", "400100700");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("마감 시험", 12, 8, 1, null));
        long pos = fx.positionOf(posting);
        var a = fx.actor("01050000031", "선발됨");
        var b = fx.actor("01050000032", "탈락");
        var busy = fx.actor("01050000033", "다른일정");
        long appA = fx.apply(a, pos, 201), appB = fx.apply(b, pos, 201), appBusy = fx.apply(busy, pos, 201);
        // busy 는 같은 날 다른 업체에서 확정
        long otherApp = fx.apply(busy, fx.positionOf(fx.createAndPublish(other, Fixtures.postingJson("같은 날", 12, 8, 1, null))), 201);
        decide(other, "SELECT", otherApp);
        fx.send(fx.json(post("/applications/" + otherApp + "/confirm"), busy.token(), "{}"), 200);

        decide(c, "SELECT", appA);
        fx.send(fx.json(post("/company/jobs/" + posting + "/close"), c.ownerToken(), "{}"), 200);
        // 통보 안 한 지원은 자동 불합격, 통보한 지원은 유지
        assertThat(jdbc.sql("SELECT status FROM applications WHERE id = :id").param("id", appB).query(String.class).single()).isEqualTo("AUTO_REJECTED");
        assertThat(jdbc.sql("SELECT status FROM applications WHERE id = :id").param("id", appA).query(String.class).single()).isEqualTo("SELECTED");
        fx.send(fx.json(post("/applications/" + appA + "/confirm"), a.token(), "{}"), 200);
        fx.send(get("/positions/" + pos + "/apply-form").header("Authorization", "Bearer " + b.token()), 200);
        fx.apply(fx.actor("01050000034", "늦은지원"), pos, 409);   // 마감 뒤 신규 지원 불가

        // A 가 취소하면 자리가 생기고, 같은 날 다른 확정이 있는 사람은 후보에서 빠진다
        fx.send(delete("/applications/" + appA + "?reason=개인사정").header("Authorization", "Bearer " + a.token()), 200);
        String cand = fx.send(get("/company/jobs/" + posting + "/reserve-candidates").header("Authorization", "Bearer " + c.ownerToken()), 200);
        assertThat((List<Integer>) JsonPath.read(cand, "$[*].id")).contains((int) appB).doesNotContain((int) appBusy);
        String bad = decide(c, "SELECT", appBusy);
        assertThat((Boolean) JsonPath.read(bad, "$[0].ok")).isFalse();
        assertThat((List<Boolean>) JsonPath.read(decide(c, "SELECT", appB), "$[*].ok")).containsExactly(true);   // 마감 후 재선발
        // 확정 취소는 사유 필수였고 스케줄러 판정 대기가 생겼다
        assertThat(count("SELECT count(*) FROM cancellation_reviews WHERE application_id = " + appA + " AND status = 'PENDING'")).isEqualTo(1);
    }

    @Test
    void expiryCompanyCancelAndRrnReveal() throws Exception {
        var c = fx.company("app8@test.example", "만료사", "400100800");
        var o = fx.company("app9@test.example", "타업체", "400100900");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("만료 시험", 9, 8, 1, null));
        long pos = fx.positionOf(posting);
        var a = fx.actor("01050000041", "무응답");
        long app = fx.apply(a, pos, 201);
        decide(c, "SELECT", app);
        // 기한 경과 → 만료, 정원 반환, 확정은 410
        jdbc.sql("UPDATE applications SET response_due_at = now() - interval '1 minute' WHERE id = :id").param("id", app).update();
        fx.send(fx.json(post("/applications/" + app + "/confirm"), a.token(), "{}"), 410);
        assertThat(selection.expireOverdue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM applications WHERE id = :id").param("id", app).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(count("SELECT selected_count FROM job_positions WHERE id = " + pos)).isZero();

        // 다른 업체는 지원자를 볼 수 없다
        fx.send(get("/company/applications/" + app).header("Authorization", "Bearer " + o.ownerToken()), 404);
        // 주민번호 전체 열람: 사유 필수, 감사 기록, 응답에만 평문
        fx.send(fx.json(post("/company/applications/" + app + "/reveal-rrn"), c.ownerToken(), "{\"reason\":\"\"}"), 400);
        String r = fx.send(fx.json(post("/company/applications/" + app + "/reveal-rrn"), c.ownerToken(), "{\"reason\":\"원천징수 신고\"}"), 200);
        assertThat((String) JsonPath.read(r, "$.rrn")).matches("900101-1\\d{6}");
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action = 'RRN_REVEAL' AND entity_id = " + app)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT after_data::text FROM audit_logs WHERE action = 'RRN_REVEAL' AND entity_id = :id").param("id", app).query(String.class).single()).doesNotContain("900101");
        fx.send(fx.json(post("/company/applications/" + app + "/reveal-rrn"), o.ownerToken(), "{\"reason\":\"x\"}"), 404);
    }
}
