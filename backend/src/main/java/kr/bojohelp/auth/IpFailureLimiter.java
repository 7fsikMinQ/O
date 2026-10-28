package kr.bojohelp.auth;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * IP 단위 로그인 실패 제한(메모리, 단일 서버). 한 IP 에서 15분 동안 20번 실패하면(계정 종류 무관) 그 IP 의 로그인 시도를 막는다.
 * 존재하지 않는 이메일 시도는 DB 에 기록하지 않으므로 이 제한이 계정 추측(이메일 돌려 대입)을 막는 유일한 장치다. 재시작하면 초기화된다.
 */
@Component
public class IpFailureLimiter {

    static final long WINDOW_MILLIS = 15 * 60 * 1000L;
    static final int MAX_FAILURES = 20;

    private final Map<String, ArrayDeque<Long>> failures = new ConcurrentHashMap<>();
    private final LongSupplier now;

    public IpFailureLimiter() {
        this(System::currentTimeMillis);
    }

    IpFailureLimiter(LongSupplier now) {
        this.now = now;
    }

    public boolean isBlocked(String ip) {
        ArrayDeque<Long> q = failures.get(ip);
        if (q == null) return false;
        synchronized (q) {
            purge(q);
            return q.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String ip) {
        ArrayDeque<Long> q = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            purge(q);
            q.addLast(now.getAsLong());
        }
        if (failures.size() > 10_000) failures.entrySet().removeIf(e -> { synchronized (e.getValue()) { purge(e.getValue()); return e.getValue().isEmpty(); } });
    }

    private void purge(ArrayDeque<Long> q) {
        long cutoff = now.getAsLong() - WINDOW_MILLIS;
        while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
    }
}
