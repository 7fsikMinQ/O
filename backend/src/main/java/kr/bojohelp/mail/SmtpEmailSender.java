package kr.bojohelp.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP 로 발송(spring.mail.*). 개발에서는 docker compose 의 Mailpit(localhost:1025, 수신함 http://localhost:8025)이 받는다. */
@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    SmtpEmailSender(JavaMailSender mail, @Value("${app.mail.from:no-reply@bojohelp.local}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(subject);
        m.setText(body);
        mail.send(m);
    }
}
