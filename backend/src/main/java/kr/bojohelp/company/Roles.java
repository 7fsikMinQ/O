package kr.bojohelp.company;

import java.util.Set;

/** 업체 직책 묶음. 규칙은 CLAUDE.md §2 와 docs/03 의 권한 부록. */
public final class Roles {

    private Roles() {}

    /** 공고 작성·선발·메시지·인원표: 대표·팀장·스케줄러(선발 권한) */
    public static final Set<String> PLANNERS = Set.of("OWNER", "MANAGER", "SCHEDULER");
    /** 현장 업무(인원 정보표·단체 알림·출결 보정)까지: 반장 포함 */
    public static final Set<String> FIELD = Set.of("OWNER", "MANAGER", "SCHEDULER", "FIELD_LEADER");
    /** QR 출퇴근: 해당 업체 구성원 누구나(스태프 포함) */
    public static final Set<String> ANY = Set.of("OWNER", "MANAGER", "SCHEDULER", "FIELD_LEADER", "STAFF");
    /** 민감 작업(주민번호 열람·합격 취소·미읽음 재안내·엑셀) */
    public static final Set<String> MANAGERS = Set.of("OWNER", "MANAGER");
    /** 취소 패널티 투표 */
    public static final Set<String> SCHEDULERS = Set.of("SCHEDULER");
}
