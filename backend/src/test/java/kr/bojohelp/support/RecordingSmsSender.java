package kr.bojohelp.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.bojohelp.auth.SmsSender;

/** 시험용: 마지막으로 "보낸" 인증번호를 번호별로 기억한다. */
public class RecordingSmsSender implements SmsSender {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");
    private final Map<String, String> lastCode = new ConcurrentHashMap<>();

    @Override
    public void send(String normalizedPhone, String message) {
        Matcher m = CODE.matcher(message);
        if (m.find()) lastCode.put(normalizedPhone, m.group(1));
    }

    public String lastCodeFor(String normalizedPhone) {
        return lastCode.get(normalizedPhone);
    }
}
