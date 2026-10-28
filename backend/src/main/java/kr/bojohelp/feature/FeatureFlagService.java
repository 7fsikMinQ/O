package kr.bojohelp.feature;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** feature_flags 조회. 호출량이 적어 캐시 없이 매번 조회한다(필요해지면 짧은 TTL 캐시 추가). */
@Service
public class FeatureFlagService {

    private final JdbcClient jdbc;

    public FeatureFlagService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEnabled(String code) {
        return jdbc.sql("SELECT enabled FROM feature_flags WHERE code = :code")
                .param("code", code)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /** 클라이언트용 공개 목록: 코드 → 켜짐 여부 (법무 메모·수정자 등은 제외). */
    public Map<String, Boolean> publicFlags() {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        jdbc.sql("SELECT code, enabled FROM feature_flags ORDER BY code")
                .query((rs, n) -> {
                    flags.put(rs.getString("code"), rs.getBoolean("enabled"));
                    return null;
                })
                .list();
        return flags;
    }
}
