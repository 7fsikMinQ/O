package kr.bojohelp.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** 사진 업로드·서명 URL·삭제·권한. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSmsConfig.class)
@TestPropertySource(properties = "app.storage.local-dir=build/test-files")
class PhotoFlowIT extends AbstractPgTest {

    @Autowired MockMvc mvc;
    @Autowired RecordingSmsSender sms;
    @Autowired JdbcClient jdbc;

    private MockMultipartHttpServletRequestBuilder upload(String token, byte[] bytes, String name, String kind) {
        var rb = multipart("/me/actor-profile/photos").file(new MockMultipartFile("file", name, "image/jpeg", bytes)).param("kind", kind);
        return (MockMultipartHttpServletRequestBuilder) (token == null ? rb : rb.header("Authorization", "Bearer " + token));
    }

    private String body(org.springframework.test.web.servlet.RequestBuilder rb, int expected) throws Exception {
        return mvc.perform(rb).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void uploadListDownloadDelete() throws Exception {
        String t = TestUsers.signup(mvc, sms, "01080000001", "사진");
        byte[] jpg = ImageSanitizerTest.image(2400, 1800, "jpg");

        body(upload(null, jpg, "a.jpg", "PROFILE"), 401);
        String created = body(upload(t, jpg, "../../evil.jpg", "PROFILE"), 201);
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        assertThat((Integer) JsonPath.read(created, "$.width")).isEqualTo(1600);
        String url = JsonPath.read(created, "$.url");
        assertThat(url).startsWith("/files/");

        // 서명 URL 로 내려받기(JPEG), 변조·만료는 403
        byte[] downloaded = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded[0] & 0xFF).isEqualTo(0xFF);
        body(get(url.replaceAll("sig=.", "sig=x")), 403);
        body(get(url.replaceAll("exp=\\d+", "exp=1")), 403);
        body(get(url.substring(0, url.indexOf('?'))), 400);   // 서명 없이는 열 수 없음

        // 저장 정보: 경로 문자가 제거된 이름, 비공개, 로컬 저장소
        var f = jdbc.sql("SELECT original_name, is_private, storage_provider, status FROM files f JOIN file_attachments a ON a.file_id = f.id WHERE a.id = :id")
                .param("id", id).query((rs, n) -> new Object[] {rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getString(4)}).single();
        assertThat((String) f[0]).doesNotContain("/").doesNotContain("\\");
        assertThat(f[1]).isEqualTo(true);
        assertThat(f[2]).isEqualTo("LOCAL");

        String listed = body(get("/me/actor-profile/photos").header("Authorization", "Bearer " + t), 200);
        assertThat((Integer) JsonPath.read(listed, "$.length()")).isEqualTo(1);

        // 다른 사람은 삭제할 수 없고(404), 본인은 삭제 후 URL 도 404
        String other = TestUsers.signup(mvc, sms, "01080000002", "타인");
        body(delete("/me/actor-profile/photos/" + id).header("Authorization", "Bearer " + other), 404);
        body(delete("/me/actor-profile/photos/" + id).header("Authorization", "Bearer " + t), 204);
        body(get("/me/actor-profile/photos").header("Authorization", "Bearer " + t), 200);
        body(get(url), 404);
    }

    @Test
    void rejectsBadUploadsAndLimitsCount() throws Exception {
        String t = TestUsers.signup(mvc, sms, "01080000003", "제한");
        body(upload(t, "text".getBytes(), "a.jpg", "PROFILE"), 400);
        body(upload(t, ImageSanitizerTest.image(60, 60, "jpg"), "a.jpg", "PROFILE"), 400);
        body(upload(t, ImageSanitizerTest.image(500, 500, "jpg"), "a.jpg", "SELFIE"), 400);
        byte[] ok = ImageSanitizerTest.image(500, 500, "jpg");
        for (int i = 0; i < 5; i++) body(upload(t, ok, "p" + i + ".jpg", "PROFILE"), 201);
        body(upload(t, ok, "p6.jpg", "PROFILE"), 409);          // 종류당 5장
        body(upload(t, ok, "full.jpg", "FULL_BODY"), 201);      // 다른 종류는 별도 한도
    }
}
