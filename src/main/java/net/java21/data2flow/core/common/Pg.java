package net.java21.data2flow.core.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PostgreSQL 값 변환 도우미. 배열은 텍스트 리터럴로 넘기고 SQL에서 {@code CAST(:x AS bigint[])}로 바꾼다.
 * 시각은 UTC {@link OffsetDateTime}으로 넘긴다(저장·API 모두 UTC).
 */
public final class Pg {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})(\\.\\d{1,3}){3}$");

    private Pg() {
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** {@code {1,2,3}} */
    public static String bigintArray(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    /** {@code {"A","B"}} — 큰따옴표와 역슬래시를 이스케이프한다 */
    public static String textArray(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    public static List<Long> longList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        List<Long> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (Object o : (Object[]) array.getArray()) {
            result.add(((Number) o).longValue());
        }
        return result;
    }

    public static List<String> stringList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        List<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (Object o : (Object[]) array.getArray()) {
            result.add((String) o);
        }
        return result;
    }

    /**
     * inet 열에 넣을 값. IP 리터럴(IPv4 점 표기, ':'가 든 IPv6)만 받고 나머지는 null로 버린다.
     * 리터럴만 넘기므로 {@link InetAddress#getByName}이 DNS를 조회하지 않는다.
     */
    public static String inetOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String ip = raw.strip();
        if (ip.isEmpty() || ip.length() > 45) {
            return null;
        }
        boolean v4 = IPV4.matcher(ip).matches();
        if (!v4 && !ip.contains(":")) {
            return null;
        }
        if (v4) {
            for (String part : ip.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return null;
                }
            }
            return ip;
        }
        if (!ip.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(ip).getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}
