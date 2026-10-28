package kr.bojohelp.common;

import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

/** 조회 결과를 JSON 으로 내보내기 좋은 Map 으로 바꾼다: 열 이름은 camelCase, 시각은 UTC ISO-8601 문자열, 날짜는 yyyy-MM-dd, jsonb 는 객체. */
public final class Rows {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Rows() {}

    public static RowMapper<Map<String, Object>> camel() {
        return (rs, rowNum) -> {
            ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                m.put(camelCase(md.getColumnLabel(i)), convert(rs.getObject(i)));
            }
            return m;
        };
    }

    static Object convert(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.toInstant().toString();
        if (v instanceof OffsetDateTime o) return o.toInstant().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof PGobject p) {
            String type = p.getType();
            if ("jsonb".equals(type) || "json".equals(type)) {
                try {
                    return JSON.readValue(p.getValue(), Object.class);
                } catch (RuntimeException e) {
                    return p.getValue();
                }
            }
            return p.getValue();
        }
        return v;
    }

    public static String camelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') up = true;
            else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }
}
