package kr.bojohelp.operation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** 인원 정보표 엑셀(주민번호·계좌 전체 포함)은 **비밀번호로 암호화한 파일로만** 내보낸다(AES-256, 설정 export_password_protect). */
@Service
public class RosterExportService {

    private static final String[] HEADERS = {"이름", "휴대폰", "역할", "성별", "생년월일", "주민등록번호", "집 주소", "은행", "예금주", "계좌번호", "상태"};

    private final RosterService rosters;

    public RosterExportService(RosterService rosters) {
        this.rosters = rosters;
    }

    public byte[] encryptedXlsx(long scheduleId, String password) {
        List<Map<String, Object>> rows = rosters.rosterForExport(scheduleId);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream plain = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("인원 정보표");
            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) head.createCell(i).setCellValue(HEADERS[i]);
            int r = 1;
            for (Map<String, Object> m : rows) {
                Row row = sheet.createRow(r++);
                String[] keys = {"name", "phone", "positionTitle", "gender", "birthDate", "rrn", "homeAddress", "bankName", "accountHolder", "accountNumber", "status"};
                for (int i = 0; i < keys.length; i++) {
                    Object v = m.get(keys[i]);
                    row.createCell(i).setCellValue(v == null ? "" : v.toString());   // 문자열로만 넣어 수식 주입을 막는다
                }
            }
            for (int i = 0; i < HEADERS.length; i++) sheet.autoSizeColumn(i);
            wb.write(plain);

            POIFSFileSystem fs = new POIFSFileSystem();
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword(password);
            try (OPCPackage opc = OPCPackage.open(new ByteArrayInputStream(plain.toByteArray())); OutputStream os = enc.getDataStream(fs)) {
                opc.save(os);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            fs.close();
            return out.toByteArray();
        } catch (IOException | java.security.GeneralSecurityException | org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IllegalStateException("엑셀 생성에 실패했어요", e);
        }
    }
}
