package kr.bojohelp.auth;

import java.util.regex.Pattern;

/** 국내 휴대폰 번호 정규화: 숫자만("01012345678"). +82 10… 도 허용. 형식이 틀리면 IllegalArgumentException. */
public final class PhoneNumber {

    private static final Pattern KR_MOBILE = Pattern.compile("^01[016789]\\d{7,8}$");

    private PhoneNumber() {}

    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("phone");
        String d = raw.replaceAll("[\\s-]", "");
        if (d.startsWith("+82")) d = "0" + d.substring(3);
        if (!KR_MOBILE.matcher(d).matches()) throw new IllegalArgumentException("phone");
        return d;
    }

    /** 로그·응답용 마스킹: 010****5678 */
    public static String mask(String normalized) {
        return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 4);
    }
}
