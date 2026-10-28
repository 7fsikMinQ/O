package kr.bojohelp.appconfig;

import java.util.Map;
import kr.bojohelp.feature.FeatureFlagService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** API-MS06 /features, API-MS07 /app/config — 로그인 전에도 호출(공개). */
@RestController
public class AppConfigController {

    public record AppConfig(boolean updateRequired, Map<String, Boolean> flags) {}

    private final FeatureFlagService flags;
    private final JdbcClient jdbc;

    public AppConfigController(FeatureFlagService flags, JdbcClient jdbc) {
        this.flags = flags;
        this.jdbc = jdbc;
    }

    @GetMapping("/features")
    Map<String, Boolean> features() {
        return flags.publicFlags();
    }

    @GetMapping("/app/config")
    AppConfig config(@RequestParam String platform, @RequestParam String version) {
        String key = switch (platform) {
            case "android" -> "min_app_version_android";
            case "ios", "web" -> "min_app_version_ios";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        String min = jdbc.sql("SELECT value FROM app_settings WHERE key = :k")
                .param("k", key)
                .query(String.class)
                .optional()
                .orElse("0.0.0");
        boolean update;
        try {
            update = AppVersion.compare(version, min) < 0;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return new AppConfig(update, flags.publicFlags());
    }
}
