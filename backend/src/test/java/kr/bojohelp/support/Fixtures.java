package kr.bojohelp.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** 통합 시험용 시나리오 재료: 출연자(프로필·주민번호·계좌 완비), 승인된 업체, 공고 JSON, 지원 파일. */
public final class Fixtures {

    public record Actor(String token, long userId, String phone) {}

    public record Company(String ownerToken, long companyId, long ownerId) {}

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final RecordingSmsSender sms;
    private static final java.util.concurrent.atomic.AtomicInteger SERIAL = new java.util.concurrent.atomic.AtomicInteger(100000);

    public Fixtures(MockMvc mvc, JdbcClient jdbc, RecordingSmsSender sms) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.sms = sms;
    }

    public String send(RequestBuilder rb, int expected) throws Exception {
        var res = mvc.perform(rb).andReturn().getResponse();
        if (res.getStatus() != expected) {
            throw new AssertionError("응답 상태 " + res.getStatus() + " (기대 " + expected + ") 본문=" + res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        }
        return res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    public org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb, String token, String body) {
        var b = rb.contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    /** 문자 가입 → 프로필 → 주민번호(출연자마다 다른 번호) → 정산 계좌. 키 175·몸무게 68·1990-01-01 남성, 머리 짧음, 문신 없음. */
    public Actor actor(String phoneDigits, String name) throws Exception {
        String t = TestUsers.signup(mvc, sms, phoneDigits, name);
        String profile = """
                {"birthDate":"1990-01-01","gender":"MALE","heightCm":175,"weightKg":68,"topSize":"100","bottomSize":"32","shoeSize":270,
                 "hairLength":"SHORT","hasTattoo":false,"canDrive":true,"homeAddress":"서울 강남구 테헤란로 1","defaultDepartureLocation":"강남역 1번 출구"}
                """;
        send(json(put("/me/actor-profile"), t, profile), 204);
        int serial = SERIAL.getAndIncrement();
        String rrn = "900101-1" + String.format("%06d", serial);
        send(json(put("/me/actor-profile/tax-identity"), t, "{\"rrn\":\"" + rrn + "\",\"termVersion\":\"0.1\"}"), 200);
        send(json(put("/me/actor-profile/payout"), t, "{\"bankCode\":\"004\",\"accountNumber\":\"11012345" + String.format("%04d", serial % 10000) + "\",\"holder\":\"" + name + "\"}"), 204);
        long id = jdbc.sql("SELECT id FROM users WHERE phone = :p").param("p", phoneDigits).query(Long.class).single();
        return new Actor(t, id, phoneDigits);
    }

    public Company company(String email, String name, String bizFirst9) throws Exception {
        String t = TestUsers.emailSignup(mvc, email, "Passw0rd1234", name + " 대표");
        TestUsers.markEmailVerified(jdbc, email);
        String body = "{\"name\":\"" + name + "\",\"bizRegNo\":\"" + TestUsers.bizNo(bizFirst9) + "\",\"representative\":\"대표\",\"phone\":\"02-123-4567\",\"address\":\"서울\"}";
        long cid = ((Number) JsonPath.read(send(json(post("/companies"), t, body), 201), "$.id")).longValue();
        jdbc.sql("UPDATE companies SET status = 'APPROVED' WHERE id = :id").param("id", cid).update();
        long owner = jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", email).query(Long.class).single();
        // 승인 뒤 토큰에 COMPANY 역할이 필요한 경우를 위해 다시 로그인
        return new Company(TestUsers.emailLogin(mvc, email, "Passw0rd1234"), cid, owner);
    }

    /** 며칠 뒤 09:00(KST) 시작, 길이 hours 시간인 일정 하나·역할 하나짜리 공고 JSON. */
    public static String postingJson(String title, int daysAhead, int hours, int headcount, String extraPosition) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(daysAhead).withHour(0).withMinute(0).withSecond(0).withNano(0);   // 09:00 KST
        OffsetDateTime end = start.plusHours(hours);
        return """
                {"title":"%s","description":"시험 공고","schedules":[{"startAt":"%s","endAt":"%s","regionId":1,"shootingLocation":"비밀 촬영지","meetingPlace":"강남역 1번 출구","meetingAddress":"서울 강남구 강남대로 396",
                 "positions":[{"title":"행인","headcount":%d,"wageMode":"MIN_WAGE"%s}]}]}
                """.formatted(title, start, end, headcount, extraPosition == null ? "" : "," + extraPosition);
    }

    /** 정확한 시작·끝 시각을 지정한 공고 JSON(현장 당일 시험용). */
    public static String postingJsonAt(String title, OffsetDateTime start, OffsetDateTime end, int headcount) {
        return """
                {"title":"%s","schedules":[{"startAt":"%s","endAt":"%s","regionId":1,"shootingLocation":"비밀 촬영지","meetingPlace":"정문","meetingAddress":"서울 강남구",
                 "positions":[{"title":"행인","headcount":%d,"wageMode":"MIN_WAGE"}]}]}
                """.formatted(title, start, end, headcount);
    }

    public long createAndPublish(Company c, String postingJson) throws Exception {
        long id = ((Number) JsonPath.read(send(json(post("/company/jobs"), c.ownerToken(), postingJson), 201), "$.id")).longValue();
        send(json(post("/company/jobs/" + id + "/publish"), c.ownerToken(), "{}"), 200);
        return id;
    }

    public long positionOf(long postingId) {
        return jdbc.sql("SELECT id FROM job_positions WHERE posting_id = :p ORDER BY id LIMIT 1").param("p", postingId).query(Long.class).single();
    }

    /** 현재 사진·서명 업로드 후 지원. 성공하면 applicationId. */
    public long apply(Actor a, long positionId, int expected) throws Exception {
        byte[] jpg = kr.bojohelp.file.ImageSanitizerTest.image(800, 600, "jpg");
        long photo = upload(a, "PHOTO", jpg);
        long sig = upload(a, "SIGNATURE", kr.bojohelp.file.ImageSanitizerTest.image(300, 120, "jpg"));
        String body = "{\"photoFileId\":" + photo + ",\"signatureFileId\":" + sig + ",\"agreeESignature\":true,\"useProfileAccount\":true,\"message\":\"열심히 하겠습니다\"}";
        String res = send(json(post("/positions/" + positionId + "/applications"), a.token(), body), expected);
        return expected == 201 ? ((Number) JsonPath.read(res, "$.id")).longValue() : -1;
    }

    public long upload(Actor a, String kind, byte[] bytes) throws Exception {
        MockMultipartHttpServletRequestBuilder rb = multipart("/me/apply-files");
        var req = rb.file(new MockMultipartFile("file", kind.toLowerCase() + ".jpg", "image/jpeg", bytes)).param("kind", kind).header("Authorization", "Bearer " + a.token());
        return ((Number) JsonPath.read(send(req, 201), "$.fileId")).longValue();
    }
}
