package kr.bojohelp.common;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 확정하려는 일정이 이미 잡힌 다른 일정과 겹치거나 너무 가까울 때(409). 응답에 겹친 일정 목록을 실어 앱이 경고 시트를 띄운다. */
public class ConflictingScheduleException extends ResponseStatusException {

    private final transient List<Map<String, Object>> conflicts;

    public ConflictingScheduleException(List<Map<String, Object>> conflicts) {
        super(HttpStatus.CONFLICT, "겹치는 일정이 있어요. 그래도 확정하려면 한 번 더 확인해 주세요.");
        this.conflicts = conflicts;
    }

    public List<Map<String, Object>> conflicts() {
        return conflicts;
    }
}
