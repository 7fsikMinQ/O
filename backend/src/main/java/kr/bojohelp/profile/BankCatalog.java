package kr.bojohelp.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/** 은행 코드(금융결제원 표준 3자리) → 이름. 일반 지식 기반이라 은행 이체 파일 기능(BANK_TRANSFER_FILE)을 켜기 전에 공식 표로 재확인한다(docs/16). */
public final class BankCatalog {

    public static final Map<String, String> BANKS = new LinkedHashMap<>();

    static {
        BANKS.put("004", "KB국민은행");
        BANKS.put("088", "신한은행");
        BANKS.put("020", "우리은행");
        BANKS.put("081", "하나은행");
        BANKS.put("011", "NH농협은행");
        BANKS.put("003", "IBK기업은행");
        BANKS.put("090", "카카오뱅크");
        BANKS.put("092", "토스뱅크");
        BANKS.put("089", "케이뱅크");
        BANKS.put("071", "우체국");
        BANKS.put("023", "SC제일은행");
        BANKS.put("002", "KDB산업은행");
        BANKS.put("007", "수협은행");
        BANKS.put("031", "iM뱅크(대구)");
        BANKS.put("032", "부산은행");
        BANKS.put("034", "광주은행");
        BANKS.put("035", "제주은행");
        BANKS.put("037", "전북은행");
        BANKS.put("039", "경남은행");
        BANKS.put("045", "새마을금고");
        BANKS.put("048", "신협");
    }

    private BankCatalog() {}
}
