package kr.bojohelp.auth;

/** 문자 발송 어댑터. 실제 대행사 구현은 폐쇄 베타 전에 추가한다(개발은 ConsoleSmsSender). */
public interface SmsSender {
    void send(String normalizedPhone, String message);
}
