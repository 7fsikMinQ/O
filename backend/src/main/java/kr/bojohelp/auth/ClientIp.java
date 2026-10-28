package kr.bojohelp.auth;

import jakarta.servlet.http.HttpServletRequest;

/** 요청의 접속 IP. 서버는 Caddy/Cloudflare 뒤에 있고 forward-headers-strategy=framework 이므로 getRemoteAddr() 가 실제 접속자 IP 를 돌려준다(백엔드가 직접 노출되지 않는 전제). */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank()) return "0.0.0.0";
        int zone = ip.indexOf('%');
        return zone > 0 ? ip.substring(0, zone) : ip;
    }

    /** 메일·로그용 마스킹: 203.0.113.*** / 2001:db8:***. */
    public static String mask(String ip) {
        if (ip.contains(":")) {
            String[] g = ip.split(":");
            return g.length >= 2 ? g[0] + ":" + g[1] + ":***" : "***";
        }
        int last = ip.lastIndexOf('.');
        return last > 0 ? ip.substring(0, last) + ".***" : "***";
    }
}
