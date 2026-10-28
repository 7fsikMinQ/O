package kr.bojohelp.auth;

/** 업체·관리자 비밀번호 규칙: 10자 이상, 영문과 숫자를 모두 포함, 이메일 아이디와 같지 않음. */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static boolean isAcceptable(String password, String email) {
        if (password == null || password.length() < 10 || password.length() > 72) return false;   // BCrypt 는 72바이트까지
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        return letter && digit && (email == null || !password.equalsIgnoreCase(email));
    }
}
