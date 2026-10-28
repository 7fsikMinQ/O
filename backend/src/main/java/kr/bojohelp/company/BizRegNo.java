package kr.bojohelp.company;

/** 사업자등록번호 10자리 형식·검증숫자 검사와 표시 형식(123-45-67890). */
public final class BizRegNo {

    private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

    private BizRegNo() {}

    /** 숫자 10자리로 정규화하고 검증숫자가 맞으면 "123-45-67890" 를 돌려준다. 틀리면 IllegalArgumentException. */
    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("biz");
        String d = raw.replaceAll("[\\s-]", "");
        if (!d.matches("\\d{10}")) throw new IllegalArgumentException("biz");
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (d.charAt(i) - '0') * WEIGHTS[i];
        sum += ((d.charAt(8) - '0') * 5) / 10;
        int check = (10 - sum % 10) % 10;
        if (check != d.charAt(9) - '0') throw new IllegalArgumentException("biz");
        return d.substring(0, 3) + "-" + d.substring(3, 5) + "-" + d.substring(5);
    }
}
