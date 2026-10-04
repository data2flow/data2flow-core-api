package net.java21.data2flow.core.mail.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.contracts.secret.SecretCipher;
import net.java21.data2flow.core.branding.dto.BrandingDtos.MailBranding;
import net.java21.data2flow.core.branding.service.BrandingService;
import net.java21.data2flow.core.extservice.repository.ExternalServiceRepository;
import net.java21.data2flow.core.extservice.repository.ExternalServiceRepository.ExternalServiceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 계정 메일(초대·비밀번호 재설정·가입 신청 안내) 발송. 메일 서버는 조직의 외부 서비스 설정 MAIL(OPS-07.02)을 그때그때 읽어 쓴다
 * (알람 알림 채널 OPS-06과 별개, ADR-033). 본문은 받는 사람 언어(ko·en·ja·zh, ADR-037)로 메시지 번들 {@code mail.<종류>.*}에서 만든다.
 * MAIL이 설정되지 않았거나 꺼져 있으면 보내지 않고 경고만 남긴다(관리자는 재발송으로 복구).
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    public static final String KIND = "MAIL";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ExternalServiceRepository configs;
    private final SecretCipher cipher;
    private final MessageSource messages;
    private final JsonMapper json;
    private final ObjectProvider<BrandingService> branding;

    public MailService(ExternalServiceRepository configs, SecretCipher cipher, MessageSource messages, JsonMapper json,
                       ObjectProvider<BrandingService> branding) {
        this.configs = configs;
        this.cipher = cipher;
        this.messages = messages;
        this.json = json;
        this.branding = branding;
    }

    /**
     * @param template 메시지 키 접두사(예: {@code mail.invitation}) — {@code .subject}, {@code .body}
     * @return 보냈으면 true
     */
    public boolean send(long organizationId, String to, String template, Locale locale, Object... args) {
        ExternalServiceRow config = configs.findByKind(organizationId, KIND).filter(ExternalServiceRow::enabled).orElse(null);
        if (config == null) {
            log.warn("메일 서버(OPS-07.02 MAIL)가 설정되지 않아 {} 메일을 보내지 않습니다", template);
            return false;
        }
        Map<String, Object> settings = json.readValue(config.settings(), MAP);
        Secret password = config.secretEnc() == null ? null : cipher.decrypt(config.secretEnc(), secretContext(organizationId));
        JavaMailSenderImpl sender = sender(settings, password);
        String subject = messages.getMessage(template + ".subject", args, locale);
        String body = messages.getMessage(template + ".body", args, locale);
        // 브랜딩(DSH-13.01): 발신 이름·서명이 있으면 메일에 적용한다
        BrandingService brandingService = branding.getIfAvailable();
        MailBranding mailBranding = brandingService == null ? null : brandingService.mailBranding(organizationId).orElse(null);
        if (mailBranding != null && mailBranding.signature() != null) {
            body = body + "\n\n-- \n" + mailBranding.signature();
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            String from = String.valueOf(settings.getOrDefault("from", "no-reply@data2flow.java21.net"));
            Object fromName = mailBranding != null && mailBranding.senderName() != null ? mailBranding.senderName() : settings.get("fromName");
            if (fromName == null) {
                helper.setFrom(from);
            } else {
                helper.setFrom(from, String.valueOf(fromName));
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
            return true;
        } catch (MessagingException | java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException("메일을 만들지 못했습니다", ex);
        }
    }

    /** 연결 테스트(API-OPS-42). 실패하면 예외 */
    public void testConnection(Map<String, Object> settings, Secret password) throws MessagingException {
        sender(settings, password).testConnection();
    }

    /** 비밀값 암호문 context: 다른 행·열로 옮긴 암호문은 풀리지 않는다 */
    public static String secretContext(long organizationId) {
        return "data2flow_core.external_service_config.secret_enc:" + organizationId + ":" + KIND;
    }

    static JavaMailSenderImpl sender(Map<String, Object> settings, Secret password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(String.valueOf(settings.get("host")));
        sender.setPort(Integer.parseInt(String.valueOf(settings.getOrDefault("port", 587))));
        Object username = settings.get("username");
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties props = sender.getJavaMailProperties();
        if (username != null && !String.valueOf(username).isBlank()) {
            sender.setUsername(String.valueOf(username));
            sender.setPassword(password == null ? null : password.reveal());
            props.put("mail.smtp.auth", "true");
        }
        props.put("mail.smtp.starttls.enable", String.valueOf(Boolean.parseBoolean(String.valueOf(settings.getOrDefault("starttls", false)))));
        props.put("mail.smtp.ssl.enable", String.valueOf(Boolean.parseBoolean(String.valueOf(settings.getOrDefault("ssl", false)))));
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
