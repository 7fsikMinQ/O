package kr.bojohelp.application;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.bojohelp.common.Json;

/** 역할의 필수 요건(성별·출생연도·키·몸무게·머리 길이·문신)과 지원자 프로필 대조. 사유는 출연자가 읽는 쉬운 말. */
final class Eligibility {

    private Eligibility() {}

    static List<String> check(Map<String, Object> profile, Map<String, Object> pos, LocalDate todayKst, boolean minorAllowed) {
        List<String> why = new ArrayList<>();
        LocalDate birth = (LocalDate) profile.get("birthDate");
        int age = Period.between(birth, todayKst).getYears();
        if (age < 19 && !(minorAllowed && Boolean.TRUE.equals(pos.get("allowMinor")))) why.add("만 19세 미만은 아직 지원할 수 없어요.");
        String genderReq = (String) pos.get("genderReq");
        String gender = (String) profile.get("gender");
        if (genderReq != null && !"ANY".equals(genderReq) && !genderReq.equals(gender)) why.add("성별 조건이 맞지 않아요.");
        Integer bmin = num(pos.get("birthYearMin")), bmax = num(pos.get("birthYearMax"));
        if (bmin != null && birth.getYear() < bmin) why.add("출생연도 조건이 맞지 않아요.");
        if (bmax != null && birth.getYear() > bmax) why.add("출생연도 조건이 맞지 않아요.");
        int h = ((Number) profile.get("heightCm")).intValue(), w = ((Number) profile.get("weightKg")).intValue();
        Integer hmin = num(pos.get("heightMin")), hmax = num(pos.get("heightMax")), wmin = num(pos.get("weightMin")), wmax = num(pos.get("weightMax"));
        if ((hmin != null && h < hmin) || (hmax != null && h > hmax)) why.add("키 조건이 맞지 않아요.");
        if ((wmin != null && w < wmin) || (wmax != null && w > wmax)) why.add("몸무게 조건이 맞지 않아요.");
        Object hair = pos.get("hairLengths");
        if (hair != null) {
            List<?> allowed = hair instanceof String s ? Json.read(s, List.class) : (List<?>) hair;
            String mine = (String) profile.get("hairLength");
            if (!allowed.isEmpty() && (mine == null || !allowed.contains(mine))) why.add("머리 길이 조건이 맞지 않아요.");
        }
        if ("NONE".equals(pos.get("tattooReq")) && Boolean.TRUE.equals(profile.get("hasTattoo"))) why.add("문신이 없는 분만 지원할 수 있어요.");
        return why;
    }

    private static Integer num(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }
}
