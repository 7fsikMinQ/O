package kr.bojohelp.posting;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 보수 계산: 9시간 이내 근무는 시급×8시간 고정, 초과 시 시급×근무시간(CLAUDE.md §2-7). 금액은 원 정수. */
public final class WageRules {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private WageRules() {}

    public static int year(OffsetDateTime start) {
        return start.atZoneSameInstant(KST).getYear();
    }

    public static long estimate(int hourlyWage, OffsetDateTime start, OffsetDateTime end, int fixedThresholdHours, int minPayHours) {
        double hours = Duration.between(start, end).toMinutes() / 60.0;
        return hours <= fixedThresholdHours ? (long) hourlyWage * minPayHours : Math.round(hourlyWage * hours);
    }
}
