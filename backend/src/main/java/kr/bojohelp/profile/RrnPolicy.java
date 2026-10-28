package kr.bojohelp.profile;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * 주민등록번호 형식·일치 검사와 마스킹. 2020-10 이후 발급 번호는 검증숫자 규칙을 따르지 않으므로 체크섬은 검사하지 않고,
 * 형식(13자리), 생년월일 유효성, 성별 자리 값, 프로필의 생년월일·성별과의 일치만 확인한다. 평문은 어떤 로그에도 남기지 않는다.
 */
public final class RrnPolicy {

    public record Parsed(String digits, LocalDate birthDate, String gender) {}

    private RrnPolicy() {}

    /** 형식이 틀리면 IllegalArgumentException(메시지에 값을 넣지 않는다). */
    public static Parsed parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("rrn");
        String d = raw.replaceAll("[\\s-]", "");
        if (!d.matches("\\d{13}")) throw new IllegalArgumentException("rrn");
        int yy = Integer.parseInt(d.substring(0, 2));
        int mm = Integer.parseInt(d.substring(2, 4));
        int dd = Integer.parseInt(d.substring(4, 6));
        int g = d.charAt(6) - '0';
        int century;
        switch (g) {
            case 1, 2, 5, 6 -> century = 1900;
            case 3, 4, 7, 8 -> century = 2000;
            case 9, 0 -> century = 1800;
            default -> throw new IllegalArgumentException("rrn");
        }
        LocalDate birth;
        try {
            birth = LocalDate.of(century + yy, mm, dd);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("rrn");
        }
        String gender = (g % 2 == 1) ? "MALE" : "FEMALE";
        return new Parsed(d, birth, gender);
    }

    /** 900101-1****** 형태(앞 7자리만 보임). */
    public static String mask(String digits) {
        return digits.substring(0, 6) + "-" + digits.charAt(6) + "******";
    }
}
