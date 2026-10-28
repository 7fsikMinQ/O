package kr.bojohelp.mail;

/** 메일 발송 어댑터(개발: SMTP → Mailpit, 운영: 실제 SMTP/Resend/SES). 본문은 평문. */
public interface EmailSender {
    void send(String to, String subject, String body);
}
