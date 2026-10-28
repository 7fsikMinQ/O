package kr.bojohelp.support;

import java.util.ArrayList;
import java.util.List;
import kr.bojohelp.mail.EmailSender;

/** 시험용: 보낸 메일을 기억하고, failNext 가 켜져 있으면 발송에 실패한다(재시도 시험). */
public class RecordingEmailSender implements EmailSender {

    public record Sent(String to, String subject, String body) {}

    private final List<Sent> sent = new ArrayList<>();
    public volatile boolean failNext;

    @Override
    public synchronized void send(String to, String subject, String body) {
        if (failNext) throw new IllegalStateException("smtp down");
        sent.add(new Sent(to, subject, body));
    }

    public synchronized List<Sent> to(String email) {
        return sent.stream().filter(s -> s.to().equalsIgnoreCase(email)).toList();
    }

    public synchronized Sent last(String email) {
        List<Sent> l = to(email);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }
}
