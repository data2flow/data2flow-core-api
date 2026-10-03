package net.java21.data2flow.core.mail.service;

import net.java21.data2flow.core.config.CoreProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 메일 링크와 시각 표기. 공개 페이지는 언어별 주소를 둔다(ko는 접두사 없음, 그 밖은 {@code /en}·{@code /ja}·{@code /zh}, ADR-037).
 * 경로는 화면 정의(spec/detail/IAM/screens.md)를 따른다: {@code /invitations/{token}}, {@code /password-reset/{token}},
 * {@code /signup/verify/{token}}.
 */
@Component
public class MailLinks {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CoreProperties properties;

    public MailLinks(CoreProperties properties) {
        this.properties = properties;
    }

    public String link(String path, Locale locale) {
        String lang = locale == null ? "ko" : locale.getLanguage();
        String prefix = "ko".equals(lang) || lang.isEmpty() ? "" : "/" + lang;
        return properties.webBaseUrl() + prefix + path;
    }

    /** 받는 사람 시간대로 표시(저장은 UTC) */
    public static String format(Instant instant, String timezone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null ? "Asia/Seoul" : timezone);
        } catch (RuntimeException ex) {
            zone = ZoneId.of("Asia/Seoul");
        }
        return FORMAT.format(instant.atZone(zone)) + " (" + zone.getId() + ")";
    }

    public static Locale locale(String language) {
        return language == null || language.isBlank() ? Locale.KOREAN : Locale.of(language);
    }
}
