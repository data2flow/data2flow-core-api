package net.java21.data2flow.core.devicesearch.domain;

/** 검색식 구문·필드 오류(400 DEVICE_QUERY_INVALID). column은 1부터 */
public class DeviceQueryException extends RuntimeException {

    private final int column;

    public DeviceQueryException(int column, String message) {
        super(message);
        this.column = column;
    }

    public int column() {
        return column;
    }
}
