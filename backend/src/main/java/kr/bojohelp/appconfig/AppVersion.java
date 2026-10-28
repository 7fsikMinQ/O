package kr.bojohelp.appconfig;

/** "1.2.10" 형식의 앱 버전 비교(숫자 구성요소만). 형식이 틀리면 IllegalArgumentException. */
public final class AppVersion {

    private AppVersion() {}

    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(x[i], y[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null || !v.matches("\\d+(\\.\\d+){0,2}")) {
            throw new IllegalArgumentException("invalid version");
        }
        String[] parts = v.split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }
}
