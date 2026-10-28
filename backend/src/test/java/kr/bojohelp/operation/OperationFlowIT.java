package kr.bojohelp.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

/** 인원 정보표(마스킹·권한), 참석 확인 시간대, QR 출퇴근(스태프가 열고 스캔·재스캔·변조·타 업체), 노쇼, 취소 패널티 투표, 일정 연기 자동 이동. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSmsConfig.class)
@TestPropertySource(properties = "app.storage.local-dir=build/test-files")
class OperationFlowIT extends AbstractPgTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RecordingSmsSender sms;
    @Autowired AttendanceService attendance;
    @Autowired JobsScheduler jobs;
    Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(mvc, jdbc, sms);
    }

    private String staffOf(Fixtures.Company c, String email, String role) throws Exception {
        TestUsers.emailSignup(mvc, email, "Passw0rd1234", "구성원");
        long uid = jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", email).query(Long.class).single();
        jdbc.sql("INSERT INTO company_members (company_id, user_id, member_role, status) VALUES (:c, :u, :r, 'ACTIVE')").param("c", c.companyId()).param("u", uid).param("r", role).update();
        return TestUsers.emailLogin(mvc, email, "Passw0rd1234");
    }

    private void selectAndConfirm(Fixtures.Company c, Fixtures.Actor a, long appId) throws Exception {
        fx.send(fx.json(post("/company/applications/decide"), c.ownerToken(), "{\"decision\":\"SELECT\",\"applicationIds\":[" + appId + "]}"), 200);
        fx.send(fx.json(post("/applications/" + appId + "/confirm"), a.token(), "{}"), 200);
    }

    @Test
    void rosterQrAttendanceAndNoShow() throws Exception {
        var c = fx.company("op1@test.example", "현장사", "600100100");
        var other = fx.company("op2@test.example", "타현장", "600100200");
        String staff = staffOf(c, "op1-staff@test.example", "STAFF");
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3).withNano(0);
        long posting = fx.createAndPublish(c, Fixtures.postingJsonAt("현장 시험", start, start.plusHours(8), 3));
        long pos = fx.positionOf(posting);
        long schedule = jdbc.sql("SELECT schedule_id FROM job_positions WHERE id = :p").param("p", pos).query(Long.class).single();
        var a = fx.actor("01040000001", "출석이");
        var b = fx.actor("01040000002", "노쇼이");
        long appA = fx.apply(a, pos, 201), appB = fx.apply(b, pos, 201);
        selectAndConfirm(c, a, appA);
        selectAndConfirm(c, b, appB);

        // 인원 정보표: 대표는 보고, 스태프는 못 본다(주민번호 마스킹, 계좌 끝 4자리)
        String roster = fx.send(get("/company/schedules/" + schedule + "/roster").header("Authorization", "Bearer " + c.ownerToken()), 200);
        assertThat((List<?>) JsonPath.read(roster, "$")).hasSize(2);
        assertThat((String) JsonPath.read(roster, "$[0].rrnMasked")).matches("900101-1\\*{6}");
        assertThat((String) JsonPath.read(roster, "$[0].accountMasked")).startsWith("****");
        assertThat(roster).doesNotContain("9001011");
        fx.send(get("/company/schedules/" + schedule + "/roster").header("Authorization", "Bearer " + staff), 403);
        fx.send(get("/company/schedules/" + schedule + "/roster").header("Authorization", "Bearer " + other.ownerToken()), 404);
        assertThat((Integer) JsonPath.read(fx.send(get("/company/schedules/" + schedule + "/headcount").header("Authorization", "Bearer " + staff), 200), "$[0].confirmedCount")).isEqualTo(2);

        // QR: 세션이 열리기 전에는 없다 → 스태프도 세션을 열 수 있다 → 확정 인원만 QR
        fx.send(get("/me/applications/" + appA + "/qr").header("Authorization", "Bearer " + a.token()), 409);
        String date = start.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        String s1 = fx.send(fx.json(post("/company/schedules/" + schedule + "/attendance-sessions"), staff, "{\"kind\":\"CHECKIN\",\"workDate\":\"" + date + "\"}"), 200);
        long session = ((Number) JsonPath.read(s1, "$.sessionId")).longValue();
        assertThat((Boolean) JsonPath.read(fx.send(fx.json(post("/company/schedules/" + schedule + "/attendance-sessions"), staff, "{\"kind\":\"CHECKIN\",\"workDate\":\"" + date + "\"}"), 200), "$.alreadyOpen")).isTrue();
        fx.send(fx.json(post("/company/schedules/" + schedule + "/attendance-sessions"), other.ownerToken(), "{\"kind\":\"CHECKIN\"}"), 404);
        String qr = JsonPath.read(fx.send(get("/me/applications/" + appA + "/qr").header("Authorization", "Bearer " + a.token()), 200), "$.token");
        fx.send(get("/me/applications/" + appA + "/qr").header("Authorization", "Bearer " + b.token()), 404);

        // 스캔: 변조·타 업체·이중 스캔
        fx.send(fx.json(post("/company/attendance/scan"), staff, "{\"token\":\"" + qr.substring(0, qr.length() - 2) + "ab\"}"), 400);
        fx.send(fx.json(post("/company/attendance/scan"), other.ownerToken(), "{\"token\":\"" + qr + "\"}"), 404);
        String ok = fx.send(fx.json(post("/company/attendance/scan"), staff, "{\"token\":\"" + qr + "\"}"), 200);
        assertThat((String) JsonPath.read(ok, "$.name")).isEqualTo("출석이");
        assertThat((Boolean) JsonPath.read(fx.send(fx.json(post("/company/attendance/scan"), staff, "{\"token\":\"" + qr + "\"}"), 200), "$.alreadyRecorded")).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM attendances WHERE application_id = :a AND checkin_at IS NOT NULL").param("a", appA).query(Integer.class).single()).isEqualTo(1);

        // 참석 확인: 집합 2시간 전~30분 전에만
        fx.send(post("/applications/" + appA + "/departure-confirm").header("Authorization", "Bearer " + a.token()), 409);   // 아직 3시간 전
        jdbc.sql("UPDATE applications SET work_start_at = now() + interval '60 minutes' WHERE id = :a").param("a", appA).update();
        fx.send(post("/applications/" + appA + "/departure-confirm").header("Authorization", "Bearer " + a.token()), 200);
        assertThat((List<?>) JsonPath.read(fx.send(get("/company/schedules/" + schedule + "/departure-pending").header("Authorization", "Bearer " + c.ownerToken()), 200), "$")).hasSize(1);   // B 만 미응답
        jdbc.sql("UPDATE applications SET work_start_at = now() + interval '90 minutes' WHERE id = :a").param("a", appB).update();
        assertThat(attendance.requestDepartureConfirmations()).isEqualTo(1);   // B 에게 참석 확인 요청 알림
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'DEPARTURE_CONFIRM' AND user_id = :u").param("u", b.userId()).query(Integer.class).single()).isEqualTo(1);
        jdbc.sql("UPDATE applications SET work_start_at = now() + interval '20 minutes' WHERE id = :a").param("a", appB).update();
        fx.send(post("/applications/" + appB + "/departure-confirm").header("Authorization", "Bearer " + b.token()), 409);   // 30분 전을 넘김
        assertThat(attendance.notifyMissingDepartures()).isEqualTo(1);   // 반장·담당자에게 미응답 알림(일정당 1번)
        assertThat(attendance.notifyMissingDepartures()).isZero();

        // 퇴근
        fx.send(fx.json(post("/company/attendance-sessions/" + session + "/close"), staff, "{}"), 204);
        fx.send(fx.json(post("/company/schedules/" + schedule + "/attendance-sessions"), staff, "{\"kind\":\"CHECKOUT\",\"workDate\":\"" + date + "\"}"), 200);
        String out = JsonPath.read(fx.send(get("/me/applications/" + appA + "/qr").header("Authorization", "Bearer " + a.token()), 200), "$.token");
        assertThat((String) JsonPath.read(fx.send(fx.json(post("/company/attendance/scan"), staff, "{\"token\":\"" + out + "\"}"), 200), "$.kind")).isEqualTo("CHECKOUT");
        assertThat(jdbc.sql("SELECT checkout_at IS NOT NULL FROM attendances WHERE application_id = :a").param("a", appA).query(Boolean.class).single()).isTrue();

        // 노쇼는 횟수만 올라가고(점수 없음), 정정하면 되돌아간다
        String day = date;
        fx.send(fx.json(put("/company/schedules/" + schedule + "/attendances"), c.ownerToken(), "[{\"applicationId\":" + appB + ",\"workDate\":\"" + day + "\",\"result\":\"NO_SHOW\",\"note\":\"연락 두절\"}]"), 200);
        assertThat(jdbc.sql("SELECT noshow_count FROM actor_profiles WHERE user_id = :u").param("u", b.userId()).query(Integer.class).single()).isEqualTo(1);
        fx.send(fx.json(put("/company/schedules/" + schedule + "/attendances"), c.ownerToken(), "[{\"applicationId\":" + appB + ",\"workDate\":\"" + day + "\",\"result\":\"NO_SHOW\"}]"), 200);
        assertThat(jdbc.sql("SELECT noshow_count FROM actor_profiles WHERE user_id = :u").param("u", b.userId()).query(Integer.class).single()).isEqualTo(1);   // 두 번 표시해도 1
        fx.send(fx.json(put("/company/schedules/" + schedule + "/attendances"), c.ownerToken(), "[{\"applicationId\":" + appB + ",\"workDate\":\"" + day + "\",\"result\":\"EXCUSED\"}]"), 200);
        assertThat(jdbc.sql("SELECT noshow_count FROM actor_profiles WHERE user_id = :u").param("u", b.userId()).query(Integer.class).single()).isZero();
    }

    @Test
    void rosterExcelIsPasswordEncrypted() throws Exception {
        var c = fx.company("op4@test.example", "엑셀사", "600100400");
        String staff = staffOf(c, "op4-staff@test.example", "SCHEDULER");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("엑셀 시험", 8, 8, 2, null));
        long pos = fx.positionOf(posting);
        long schedule = jdbc.sql("SELECT schedule_id FROM job_positions WHERE id = :p").param("p", pos).query(Long.class).single();
        var a = fx.actor("01040000021", "엑셀이");
        selectAndConfirm(c, a, fx.apply(a, pos, 201));

        fx.send(get("/company/schedules/" + schedule + "/roster.xlsx?password=secret123").header("Authorization", "Bearer " + staff), 403);   // 대표·팀장만
        fx.send(get("/company/schedules/" + schedule + "/roster.xlsx?password=abc").header("Authorization", "Bearer " + c.ownerToken()), 400);   // 비밀번호 필수
        byte[] file = mvc.perform(get("/company/schedules/" + schedule + "/roster.xlsx?password=secret123").header("Authorization", "Bearer " + c.ownerToken()))
                .andReturn().getResponse().getContentAsByteArray();
        // 비밀번호 없이는 열리지 않고, 파일 안에 주민번호가 평문으로 보이지 않는다
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(file))).isInstanceOf(org.apache.poi.EncryptedDocumentException.class);
        assertThat(new String(file, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("900101-1");
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(file), "secret123")) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("엑셀이");
            assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).matches("900101-1\\d{6}");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action = 'ROSTER_EXPORT' AND entity_id = :s").param("s", schedule).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void cancellationVoteAndPostpone() throws Exception {
        var c = fx.company("op3@test.example", "판정사", "600100300");
        String s1 = staffOf(c, "op3-s1@test.example", "SCHEDULER"), s2 = staffOf(c, "op3-s2@test.example", "SCHEDULER");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("취소 시험", 8, 8, 3, null));
        long pos = fx.positionOf(posting);
        var a = fx.actor("01040000011", "취소이");
        var b = fx.actor("01040000012", "취소둘");
        var d = fx.actor("01040000013", "연기됨");
        long appA = fx.apply(a, pos, 201), appB = fx.apply(b, pos, 201), appD = fx.apply(d, pos, 201);
        selectAndConfirm(c, a, appA);
        selectAndConfirm(c, b, appB);

        // 확정 취소: 사유 필수 → 판정 대기
        fx.send(delete("/applications/" + appA).header("Authorization", "Bearer " + a.token()), 400);
        fx.send(delete("/applications/" + appA + "?reason=가족 행사").header("Authorization", "Bearer " + a.token()), 200);
        assertThat((List<?>) JsonPath.read(fx.send(get("/company/cancellation-reviews").header("Authorization", "Bearer " + s1), 200), "$")).hasSize(1);
        fx.send(fx.json(post("/company/cancellation-reviews/" + appA + "/vote"), c.ownerToken(), "{\"vote\":\"PENALTY\"}"), 403);   // 대표는 투표권 없음(스케줄러만)
        assertThat((String) JsonPath.read(fx.send(fx.json(post("/company/cancellation-reviews/" + appA + "/vote"), s1, "{\"vote\":\"NO_PENALTY\"}"), 200), "$.status")).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(fx.send(fx.json(post("/company/cancellation-reviews/" + appA + "/vote"), s2, "{\"vote\":\"PENALTY\"}"), 200), "$.status")).isEqualTo("PENALIZED");   // 한 명이라도 부과
        assertThat(jdbc.sql("SELECT penalty_count FROM actor_profiles WHERE user_id = :u").param("u", a.userId()).query(Integer.class).single()).isEqualTo(1);
        fx.send(fx.json(post("/company/cancellation-reviews/" + appA + "/vote"), s2, "{\"vote\":\"PENALTY\"}"), 409);   // 이미 끝남
        // 전원 미부과면 CLEARED, 시간이 지나면 자동 미부과
        fx.send(delete("/applications/" + appB + "?reason=몸살").header("Authorization", "Bearer " + b.token()), 200);
        fx.send(fx.json(post("/company/cancellation-reviews/" + appB + "/vote"), s1, "{\"vote\":\"NO_PENALTY\"}"), 200);
        assertThat((String) JsonPath.read(fx.send(fx.json(post("/company/cancellation-reviews/" + appB + "/vote"), s2, "{\"vote\":\"NO_PENALTY\"}"), 200), "$.status")).isEqualTo("CLEARED");
        assertThat(jdbc.sql("SELECT penalty_count FROM actor_profiles WHERE user_id = :u").param("u", b.userId()).query(Integer.class).single()).isZero();

        // 일정 연기: 지원·합격자가 새 일정으로 자동 이동(합격은 SELECTED 로 시작, 패널티 없음)
        long posting2 = fx.createAndPublish(c, Fixtures.postingJson("연기 시험", 9, 8, 3, null));
        long pos2 = fx.positionOf(posting2);
        long sched2 = jdbc.sql("SELECT schedule_id FROM job_positions WHERE id = :p").param("p", pos2).query(Long.class).single();
        long x = fx.apply(a, pos2, 201), y = fx.apply(b, pos2, 201), z = fx.apply(d, pos2, 201);
        selectAndConfirm(c, a, x);
        fx.send(fx.json(post("/company/applications/decide"), c.ownerToken(), "{\"decision\":\"SELECT\",\"applicationIds\":[" + y + "]}"), 200);
        OffsetDateTime ns = OffsetDateTime.now(ZoneOffset.UTC).plusDays(20).withHour(0).withMinute(0).withSecond(0).withNano(0);
        String res = fx.send(fx.json(post("/company/schedules/" + sched2 + "/postpone"), c.ownerToken(), "{\"newStartAt\":\"" + ns + "\",\"newEndAt\":\"" + ns.plusHours(8) + "\",\"reason\":\"우천\"}"), 201);
        long newSched = ((Number) JsonPath.read(res, "$.newScheduleId")).longValue();
        assertThat((Integer) JsonPath.read(res, "$.movedApplications")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT status FROM job_schedules WHERE id = :s").param("s", sched2).query(String.class).single()).isEqualTo("POSTPONED");
        assertThat(jdbc.sql("SELECT status || ':' || penalty_exempt FROM applications WHERE id = :a").param("a", x).query(String.class).single()).isEqualTo("CANCELED_BY_SYSTEM:true");
        long newPos = jdbc.sql("SELECT id FROM job_positions WHERE schedule_id = :s").param("s", newSched).query(Long.class).single();
        assertThat(jdbc.sql("SELECT selected_count || '/' || confirmed_count || '/' || applied_count FROM job_positions WHERE id = :p").param("p", newPos).query(String.class).single()).isEqualTo("2/0/1");
        long movedA = jdbc.sql("SELECT id FROM applications WHERE moved_from_application_id = :o").param("o", x).query(Long.class).single();
        assertThat(jdbc.sql("SELECT status FROM applications WHERE id = :a").param("a", movedA).query(String.class).single()).isEqualTo("SELECTED");   // 확정이었어도 다시 확정해야 함
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'POSTPONED' AND user_id = :u").param("u", a.userId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM file_attachments WHERE entity_type = 'APPLICATION' AND entity_id = :a").param("a", movedA).query(Integer.class).single()).isEqualTo(2);   // 서명·사진도 따라감
        fx.send(fx.json(post("/applications/" + movedA + "/confirm"), a.token(), "{}"), 200);
        // 연기된 일정을 취소해도 패널티 판정이 생기지 않는다
        fx.send(delete("/applications/" + movedA + "?reason=못 가요").header("Authorization", "Bearer " + a.token()), 200);
        assertThat(jdbc.sql("SELECT count(*) FROM cancellation_reviews WHERE application_id = :a").param("a", movedA).query(Integer.class).single()).isZero();
        // 시작된 일정·과거 날짜로는 연기 불가
        fx.send(fx.json(post("/company/schedules/" + sched2 + "/postpone"), c.ownerToken(), "{\"newStartAt\":\"" + ns + "\",\"newEndAt\":\"" + ns.plusHours(8) + "\"}"), 409);   // 이미 연기됨
        jobs.runOnce();   // 배치가 오류 없이 돈다
    }
}
