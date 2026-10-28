package kr.bojohelp.mail;

/** 메일 문구(쉬운 말). 링크·임시 비밀번호는 본문에만 들어가고 로그·DB 에는 발송 후 남지 않는다. */
public final class EmailTemplates {

    public record Mail(String subject, String body) {}

    private EmailTemplates() {}

    public static Mail verifyEmail(String name, String link) {
        return new Mail("[보조출연 헬프] 이메일 주소를 확인해 주세요",
                name + "님, 안녕하세요.\n\n아래 주소를 눌러 이메일 확인을 마쳐 주세요. (24시간 안에 한 번만 쓸 수 있어요)\n" + link
                        + "\n\n본인이 가입하지 않았다면 이 메일은 무시해 주세요.");
    }

    public static Mail temporaryPassword(String name, String tempPassword) {
        return new Mail("[보조출연 헬프] 임시 비밀번호를 보내 드려요",
                name + "님, 안녕하세요.\n\n임시 비밀번호: " + tempPassword
                        + "\n\n30분 안에 이 임시 비밀번호로 로그인한 뒤 새 비밀번호로 바꿔 주세요. 한 번만 쓸 수 있어요.\n"
                        + "기존 비밀번호는 아직 바뀌지 않았어요. 본인이 요청하지 않았다면 이 메일은 무시해 주세요.");
    }

    public static Mail passwordChanged(String name) {
        return new Mail("[보조출연 헬프] 비밀번호가 바뀌었어요",
                name + "님, 비밀번호가 방금 변경되었어요. 모든 기기에서 로그아웃되었어요.\n본인이 바꾼 게 아니라면 바로 비밀번호 재설정을 하고 고객센터로 알려 주세요.");
    }

    public static Mail loginLocked(String name, String maskedIp, int minutes) {
        return new Mail("[보조출연 헬프] 비밀번호를 여러 번 잘못 입력했어요",
                name + "님, 계정에 비밀번호를 5번 잘못 입력한 기록이 있어요.\n접속 위치(" + maskedIp + ")에서의 로그인을 " + minutes
                        + "분 동안 막았어요. 다른 곳에서는 평소처럼 로그인할 수 있어요.\n본인이 아니라면 비밀번호를 바꾸는 것을 권해요.");
    }
}
