package net.java21.data2flow.core.dataexchange.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 정기 내보내기 전달 대상(TSD-07.02, API-TSD-23·56): S3 호환 저장소 또는 SFTP. 실패는 {@link IOException}(메시지가 원인)으로 알린다.
 */
public interface DeliveryTarget extends AutoCloseable {

    /** 파일 쓰기(같은 이름이 있으면 덮어씀 — 판 번호가 이름에 있어 새 판은 새 파일, BR-TSD-28) */
    void put(String name, Path file, String contentType) throws IOException;

    /** 파일 지우기(연결 테스트 정리용) */
    void delete(String name) throws IOException;

    @Override
    void close();
}
