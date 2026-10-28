package kr.bojohelp.auth;

import java.security.SecureRandom;

/** 임시 비밀번호: 헷갈리는 글자(0 O 1 l I)를 뺀 12자, 영문과 숫자를 반드시 포함. */
public final class PasswordGenerator {

    private static final String LETTERS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {}

    public static String temporary() {
        String all = LETTERS + DIGITS;
        StringBuilder sb = new StringBuilder();
        sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        for (int i = 2; i < 12; i++) sb.append(all.charAt(RANDOM.nextInt(all.length())));
        char[] c = sb.toString().toCharArray();
        for (int i = c.length - 1; i > 0; i--) {   // 앞 두 글자 위치가 고정되지 않게 섞기
            int j = RANDOM.nextInt(i + 1);
            char t = c[i]; c[i] = c[j]; c[j] = t;
        }
        return new String(c);
    }
}
