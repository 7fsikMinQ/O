package kr.bojohelp.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import kr.bojohelp.support.AbstractPgTest;
import kr.bojohelp.support.Fixtures;
import kr.bojohelp.support.RecordingSmsSender;
import kr.bojohelp.support.TestSmsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 공고 작성·게시·탐색·마감·복사, 최저시급·9시간 보수 규칙, 비공개 현장 규칙, 업체 간 접근 차단. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSmsConfig.class)
@TestPropertySource(properties = "app.storage.local-dir=build/test-files")
class PostingFlowIT extends AbstractPgTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RecordingSmsSender sms;
    Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(mvc, jdbc, sms);
    }

    @Test
    void createPublishSearchAndWageRules() throws Exception {
        var c = fx.company("posting1@test.example", "공고사", "300100100");
        // 임시저장 → 게시 전에는 출연자에게 안 보임
        String created = fx.send(fx.json(post("/company/jobs"), c.ownerToken(), Fixtures.postingJson("가제 드라마 A", 6, 8, 3, null)), 201);
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        assertThat((String) JsonPath.read(created, "$.status")).isEqualTo("DRAFT");
        fx.send(get("/jobs/" + id), 404);

        // 보수: 최저시급(근무 시작일 연도), 8시간 이내 → 시급×8시간 고정
        double hourly = JsonPath.read(created, "$.schedules[0].positions[0].hourlyWageKrw") instanceof Number n ? n.doubleValue() : 0;
        long expected = (long) hourly * 8;
        assertThat(((Number) JsonPath.read(created, "$.schedules[0].positions[0].estimatedPayKrw")).longValue()).isEqualTo(expected);
        assertThat((String) JsonPath.read(created, "$.timeNotice")).contains("예상");

        // 9시간 초과(11시간)면 시급×근무시간
        String longJob = fx.send(fx.json(post("/company/jobs"), c.ownerToken(), Fixtures.postingJson("긴 촬영", 6, 11, 2, null)), 201);
        long hourly2 = ((Number) JsonPath.read(longJob, "$.schedules[0].positions[0].hourlyWageKrw")).longValue();
        assertThat(((Number) JsonPath.read(longJob, "$.schedules[0].positions[0].estimatedPayKrw")).longValue()).isEqualTo(hourly2 * 11);

        // 직접 입력 시급은 최저시급 이상이어야 한다
        fx.send(fx.json(post("/company/jobs"), c.ownerToken(), Fixtures.postingJson("낮은 시급", 6, 8, 1, "\"wageMode\":\"CUSTOM\",\"hourlyWageKrw\":5000").replace("\"wageMode\":\"MIN_WAGE\",", "")), 400);
        fx.send(fx.json(post("/company/jobs"), c.ownerToken(), Fixtures.postingJson("높은 시급", 6, 8, 1, "\"wageMode\":\"CUSTOM\",\"hourlyWageKrw\":15000").replace("\"wageMode\":\"MIN_WAGE\",", "")), 201);
        // 일정 길이: 1시간 미만, 21일 초과는 400
        fx.send(fx.json(post("/company/jobs"), c.ownerToken(), Fixtures.postingJson("짧은", 6, 0, 1, null)), 400);
        fx.send(fx.json(post("/company/jobs"), c.ownerToken(), Fixtures.postingJson("긴", 6, 24 * 22, 1, null)), 400);
        fx.send(fx.json(post("/company/jobs"), c.ownerToken(), Fixtures.postingJson("21일", 6, 24 * 21, 1, null)), 201);

        // 게시 → 출연자 탐색(비로그인 가능), 비공개 현장은 숨김
        fx.send(fx.json(post("/company/jobs/" + id + "/publish"), c.ownerToken(), "{}"), 200);
        fx.send(fx.json(post("/company/jobs/" + id + "/publish"), c.ownerToken(), "{}"), 409);   // 두 번 게시 불가
        String list = fx.send(get("/jobs?q=가제 드라마"), 200);
        assertThat((List<?>) JsonPath.read(list, "$.items")).hasSize(1);
        String detail = fx.send(get("/jobs/" + id), 200);
        assertThat((Boolean) JsonPath.read(detail, "$.detailsHidden")).isTrue();
        assertThat((Object) JsonPath.read(detail, "$.schedules[0].shootingLocation")).isNull();
        assertThat((Object) JsonPath.read(detail, "$.schedules[0].meetingAddress")).isNull();
        assertThat((Integer) JsonPath.read(detail, "$.schedules[0].positions[0].remaining")).isEqualTo(3);

        // 달력: 일정 날짜에 1건
        String start = JsonPath.read(detail, "$.firstShootDate");
        String cal = fx.send(get("/jobs/calendar?month=" + start.substring(0, 7)), 200);
        assertThat((List<?>) JsonPath.read(cal, "$[?(@.date=='" + start + "')]")).hasSize(1);

        // 게시 뒤에는 구조 수정 불가(제목은 가능), 정원은 늘릴 수 있고 이미 뽑은 인원 아래로는 못 줄임
        String edit = Fixtures.postingJson("가제 드라마 A", 6, 8, 3, null).replace("시험 공고", "수정된 설명");
        assertThat((String) JsonPath.read(fx.send(fx.json(put("/company/jobs/" + id), c.ownerToken(), edit), 200), "$.description")).isEqualTo("수정된 설명");
        long pos = fx.positionOf(id);
        fx.send(fx.json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/company/positions/" + pos), c.ownerToken(), "{\"headcount\":5}"), 200);

        // 마감 → 목록에서 사라짐, 마감 해제는 정원이 남아 있을 때
        fx.send(fx.json(post("/company/jobs/" + id + "/close"), c.ownerToken(), "{}"), 200);
        assertThat((List<?>) JsonPath.read(fx.send(get("/jobs?q=가제 드라마"), 200), "$.items")).isEmpty();
        fx.send(fx.json(post("/company/jobs/" + id + "/reopen"), c.ownerToken(), "{}"), 200);

        // 복사: 임시저장으로 새로 생기고 날짜를 민다
        String copied = fx.send(fx.json(post("/company/jobs/" + id + "/copy?shiftDays=7"), c.ownerToken(), "{}"), 201);
        assertThat((String) JsonPath.read(copied, "$.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(copied, "$.firstShootDate")).isNotEqualTo(start);
    }

    @Test
    void otherCompanyCannotTouchMyPosting() throws Exception {
        var mine = fx.company("posting2@test.example", "내 업체", "300100200");
        var other = fx.company("posting3@test.example", "타 업체", "300100300");
        long id = ((Number) JsonPath.read(fx.send(fx.json(post("/company/jobs"), mine.ownerToken(), Fixtures.postingJson("남의 공고", 6, 8, 1, null)), 201), "$.id")).longValue();
        fx.send(get("/company/jobs/" + id).header("Authorization", "Bearer " + other.ownerToken()), 404);
        fx.send(fx.json(post("/company/jobs/" + id + "/publish"), other.ownerToken(), "{}"), 404);
        fx.send(fx.json(put("/company/jobs/" + id), other.ownerToken(), Fixtures.postingJson("탈취", 6, 8, 1, null)), 404);
        fx.send(get("/company/jobs"), 401);
        // 승인 전 업체는 공고를 못 만든다
        var pending = fx.company("posting4@test.example", "대기 업체", "300100400");
        jdbc.sql("UPDATE companies SET status = 'PENDING' WHERE id = :id").param("id", pending.companyId()).update();
        fx.send(fx.json(post("/company/jobs"), pending.ownerToken(), Fixtures.postingJson("대기", 6, 8, 1, null)), 403);
    }
}
