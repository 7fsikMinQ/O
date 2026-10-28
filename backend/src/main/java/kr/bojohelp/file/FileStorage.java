package kr.bojohelp.file;

import java.util.Optional;

/** 파일 저장소 어댑터. 키는 서버가 만든 상대 경로이며 사용자 입력을 키로 쓰지 않는다. */
public interface FileStorage {

    /** 저장소 종류 이름(files.storage_provider 와 일치: LOCAL / R2). */
    String provider();

    /** files.bucket 에 기록할 이름. */
    String bucket();

    void put(String key, byte[] data);

    Optional<byte[]> get(String key);

    void delete(String key);
}
