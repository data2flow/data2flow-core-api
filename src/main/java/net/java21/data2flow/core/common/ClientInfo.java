package net.java21.data2flow.core.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청자 IP·User-Agent. 모든 요청이 BFF를 거치므로 BFF가 다시 쓴 {@code X-Forwarded-For}의 첫 값이 사용자 IP다(design/auth.md §5).
 *
 * @param ip        사용자 IP(inet 리터럴이 아니면 null)
 * @param userAgent User-Agent(300자까지)
 */
public record ClientInfo(String ip, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0];
        return of(ip, request.getHeader("User-Agent"));
    }

    public static ClientInfo of(String ip, String userAgent) {
        String ua = userAgent == null ? null : (userAgent.length() > 300 ? userAgent.substring(0, 300) : userAgent);
        return new ClientInfo(Pg.inetOrNull(ip), ua);
    }
}
