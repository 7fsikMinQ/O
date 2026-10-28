package kr.bojohelp.common;

import tools.jackson.databind.json.JsonMapper;

/** 서버 내부에서 JSON 문자열이 필요할 때(스냅샷·페이로드) 쓰는 공용 매퍼. */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static String write(Object o) {
        return MAPPER.writeValueAsString(o);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
