package kr.bojohelp.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** 메시지(업체가 시작·출연자는 답장만), 차단, 단체 공지의 읽음 확인·미읽음 재안내(최대 5회), ⭐ 출연자, 알림함, 새 공고 알림 순서. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSmsConfig.class)
@TestPropertySource(properties = "app.storage.local-dir=build/test-files")
class MessageFlowIT extends AbstractPgTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RecordingSmsSender sms;
    @Autowired kr.bojohelp.outbox.OutboxWorker worker;
    @Autowired kr.bojohelp.application.SelectionService selection;
    Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(mvc, jdbc, sms);
    }

    private String auth(String path, String token) throws Exception {
        return fx.send(get(path).header("Authorization", "Bearer " + token), 200);
    }

    @Test
    void messagesAreStartedByCompanyOnly() throws Exception {
        var c = fx.company("msg1@test.example", "메시지사", "500100100");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("메시지 공고", 6, 8, 2, null));
        var a = fx.actor("01030000001", "받는이");
        var stranger = fx.actor("01030000002", "지원안함");
        fx.apply(a, fx.positionOf(posting), 201);

        // 지원하지 않은 사람에게는 보낼 수 없다(업체가 임의로 연락 불가)
        fx.send(fx.json(post("/company/messages"), c.ownerToken(), "{\"actorUserId\":" + stranger.userId() + ",\"body\":\"안녕\"}"), 403);
        // 출연자가 먼저 시작하는 경로는 없다
        fx.send(fx.json(post("/company/messages"), a.token(), "{\"actorUserId\":" + a.userId() + ",\"body\":\"먼저\"}"), 404);

        String started = fx.send(fx.json(post("/company/messages"), c.ownerToken(), "{\"actorUserId\":" + a.userId() + ",\"subject\":\"집합 안내\",\"body\":\"강남역 1번 출구에서 만나요\"}"), 201);
        long thread = ((Number) JsonPath.read(started, "$.threadId")).longValue();
        // 받은 알림 + 안 읽음 1
        assertThat((Integer) JsonPath.read(auth("/notifications/unread-count", a.token()), "$.count")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(auth("/me/messages/threads", a.token()), "$[0].unread")).isEqualTo(1);
        // 대화를 열면 읽음, 답장 가능 → 업체에 알림
        String opened = auth("/me/messages/threads/" + thread, a.token());
        assertThat((List<?>) JsonPath.read(opened, "$.messages")).hasSize(1);
        assertThat((Integer) JsonPath.read(auth("/me/messages/threads", a.token()), "$[0].unread")).isZero();
        fx.send(fx.json(post("/me/messages/threads/" + thread + "/reply"), a.token(), "{\"body\":\"네, 확인했어요\"}"), 200);
        assertThat((Integer) JsonPath.read(auth("/company/messages/threads", c.ownerToken()), "$[0].unread")).isEqualTo(1);
        // 남의 대화는 볼 수 없다
        fx.send(get("/me/messages/threads/" + thread).header("Authorization", "Bearer " + stranger.token()), 404);

        // 차단하면 답장도, 업체의 새 메시지도 막힌다
        long companyId = c.companyId();
        fx.send(put("/me/blocks/companies/" + companyId).header("Authorization", "Bearer " + a.token()), 204);
        fx.send(fx.json(post("/me/messages/threads/" + thread + "/reply"), a.token(), "{\"body\":\"또\"}"), 403);
        fx.send(fx.json(post("/company/messages/threads/" + thread + "/reply"), c.ownerToken(), "{\"body\":\"보내볼게요\"}"), 403);
        // 차단한 업체의 공고는 목록에서 사라진다
        assertThat((List<?>) JsonPath.read(fx.send(get("/jobs?q=메시지 공고").header("Authorization", "Bearer " + a.token()), 200), "$.items")).isEmpty();
        fx.send(delete("/me/blocks/companies/" + companyId).header("Authorization", "Bearer " + a.token()), 204);

        // 신고
        fx.send(fx.json(post("/reports"), a.token(), "{\"targetType\":\"COMPANY\",\"targetId\":" + companyId + ",\"reasonCode\":\"SPAM\",\"detail\":\"광고\"}"), 201);
    }

    @Test
    void broadcastReadReceiptsAndReminders() throws Exception {
        var c = fx.company("msg2@test.example", "공지사", "500100200");
        long posting = fx.createAndPublish(c, Fixtures.postingJson("공지 공고", 7, 8, 3, null));
        long pos = fx.positionOf(posting);
        var a = fx.actor("01030000011", "읽는이");
        var b = fx.actor("01030000012", "안읽는이");
        var d = fx.actor("01030000013", "미선발");
        long appA = fx.apply(a, pos, 201), appB = fx.apply(b, pos, 201);
        fx.apply(d, pos, 201);
        fx.send(fx.json(post("/company/applications/decide"), c.ownerToken(), "{\"decision\":\"SELECT\",\"applicationIds\":[" + appA + "," + appB + "]}"), 200);
        fx.send(fx.json(post("/applications/" + appA + "/confirm"), a.token(), "{}"), 200);
        fx.send(fx.json(post("/applications/" + appB + "/confirm"), b.token(), "{}"), 200);

        // 확정자 전체에게 공지(미선발자 제외)
        String res = fx.send(fx.json(post("/company/broadcasts"), c.ownerToken(), "{\"postingId\":" + posting + ",\"target\":\"ALL_CONFIRMED\",\"title\":\"집합 장소 안내\",\"body\":\"정문 앞입니다\"}"), 201);
        long bid = ((Number) JsonPath.read(res, "$.broadcastId")).longValue();
        assertThat((Integer) JsonPath.read(res, "$.recipientCount")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(auth("/notifications/unread-count", d.token()), "$.count")).isZero();   // 미선발자에게는 가지 않는다

        // A 만 읽는다 → 수신 현황에 읽음/안 읽음
        long threadA = jdbc.sql("SELECT thread_id FROM job_broadcast_recipients WHERE broadcast_id = :b AND actor_user_id = :u").param("b", bid).param("u", a.userId()).query(Long.class).single();
        auth("/me/messages/threads/" + threadA, a.token());
        String receipts = auth("/company/broadcasts/" + bid + "/receipts", c.ownerToken());
        assertThat((List<Object>) JsonPath.read(receipts, "$.recipients[?(@.readAt == null)]")).hasSize(1);

        // 안 읽은 사람에게만 재안내, 사람당 최대 5회
        for (int i = 0; i < 5; i++) assertThat((Integer) JsonPath.read(fx.send(fx.json(post("/company/broadcasts/" + bid + "/remind-unread"), c.ownerToken(), "{}"), 200), "$.reminded")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(fx.send(fx.json(post("/company/broadcasts/" + bid + "/remind-unread"), c.ownerToken(), "{}"), 200), "$.reminded")).isZero();
        assertThat(jdbc.sql("SELECT reminder_count FROM job_broadcast_recipients WHERE broadcast_id = :b AND actor_user_id = :u").param("b", bid).param("u", b.userId()).query(Integer.class).single()).isEqualTo(5);
        // 재안내 권한은 대표·팀장만: 다른 업체는 404
        var other = fx.company("msg3@test.example", "타사", "500100300");
        fx.send(fx.json(post("/company/broadcasts/" + bid + "/remind-unread"), other.ownerToken(), "{}"), 404);

        // 알림함: 읽음 처리·전체 읽음
        String list = auth("/notifications", b.token());
        long nid = ((Number) JsonPath.read(list, "$[0].id")).longValue();
        fx.send(patch("/notifications/" + nid + "/read").header("Authorization", "Bearer " + b.token()), 204);
        fx.send(post("/notifications/read-all").header("Authorization", "Bearer " + b.token()), 200);
        assertThat((Integer) JsonPath.read(auth("/notifications/unread-count", b.token()), "$.count")).isZero();
    }

    @Test
    void starActorsGetNewPostingFirst() throws Exception {
        var c = fx.company("msg4@test.example", "별사", "500100400");
        long old = fx.createAndPublish(c, Fixtures.postingJson("지난 공고", 6, 8, 2, null));
        var star = fx.actor("01030000021", "별이");
        var plain = fx.actor("01030000022", "보통이");
        var muted = fx.actor("01030000023", "알림끔");
        long sApp = fx.apply(star, fx.positionOf(old), 201);
        fx.send(fx.json(post("/company/applications/decide"), c.ownerToken(), "{\"decision\":\"SELECT\",\"applicationIds\":[" + sApp + "]}"), 200);
        fx.send(fx.json(post("/applications/" + sApp + "/confirm"), star.token(), "{}"), 200);
        long mApp = fx.apply(muted, fx.positionOf(old), 201);
        fx.send(fx.json(post("/company/applications/decide"), c.ownerToken(), "{\"decision\":\"SELECT\",\"applicationIds\":[" + mApp + "]}"), 200);
        fx.send(fx.json(post("/applications/" + mApp + "/confirm"), muted.token(), "{}"), 200);

        // 함께한 적 없는 출연자는 ⭐ 불가, 함께한 사람은 가능, 알림 끈 사람은 우선 알림 제외
        fx.send(fx.json(put("/company/starred-actors/" + plain.userId()), c.ownerToken(), "{\"note\":\"x\"}"), 403);
        fx.send(fx.json(put("/company/starred-actors/" + star.userId()), c.ownerToken(), "{\"note\":\"성실\"}"), 200);
        fx.send(fx.json(put("/company/starred-actors/" + muted.userId()), c.ownerToken(), "{}"), 200);
        fx.send(fx.json(put("/me/settings/star-alerts"), muted.token(), "{\"enabled\":false}"), 204);
        assertThat((List<?>) JsonPath.read(auth("/company/starred-actors", c.ownerToken()), "$")).hasSize(2);

        // 새 공고: ⭐ 는 즉시, 일반 사용자는 우선 시간(기본 1분) 뒤에 워커가 처리
        jdbc.sql("UPDATE app_settings SET value = '1' WHERE key = 'star_head_start_minutes'").update();
        long fresh = fx.createAndPublish(c, Fixtures.postingJson("새 공고 알림", 9, 8, 2, null));
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'NEW_POSTING_STAR' AND user_id = :u").param("u", star.userId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'NEW_POSTING_STAR' AND user_id = :u").param("u", muted.userId()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'NEW_POSTING' AND link_path = '/jobs/" + fresh + "' AND user_id = :u").param("u", plain.userId()).query(Integer.class).single()).isZero();   // 아직 우선 시간 중
        worker.runOnce();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'NEW_POSTING' AND link_path = '/jobs/" + fresh + "' AND user_id = :u").param("u", plain.userId()).query(Integer.class).single()).isZero();   // 1분이 안 지남
        jdbc.sql("UPDATE outbox_events SET available_at = now() - interval '1 second' WHERE event_type = 'NEW_POSTING_ALERT' AND aggregate_id = " + fresh).update();
        worker.runOnce();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'NEW_POSTING' AND link_path = '/jobs/" + fresh + "' AND user_id = :u").param("u", plain.userId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'NEW_POSTING' AND link_path = '/jobs/" + fresh + "' AND user_id = :u").param("u", star.userId()).query(Integer.class).single()).isZero();   // ⭐ 는 이미 받음
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'NEW_POSTING' AND link_path = '/jobs/" + fresh + "' AND user_id = :u").param("u", muted.userId()).query(Integer.class).single()).isEqualTo(1);   // 끈 사람은 일반 알림으로
        fx.send(delete("/company/starred-actors/" + muted.userId()).header("Authorization", "Bearer " + c.ownerToken()), 204);
    }
}
