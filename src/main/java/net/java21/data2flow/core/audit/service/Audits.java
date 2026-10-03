package net.java21.data2flow.core.audit.service;

import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.audit.AuditRecorder;
import net.java21.data2flow.core.common.ClientInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;

/**
 * 감사 기록 도우미. 시각은 주입한 시계로, IP·User-Agent는 현재 요청에서 채운다(IAM-06.01 기록 항목).
 * 내부 API(로그인 확인 등)처럼 사용자 IP가 본문으로 오면 {@link AuditEvent.Builder#ip(String)}로 덮어쓴다.
 */
@Component
public class Audits {

    private final AuditRecorder recorder;
    private final Clock clock;

    public Audits(AuditRecorder recorder, Clock clock) {
        this.recorder = recorder;
        this.clock = clock;
    }

    public AuditEvent.Builder event(long organizationId, String action) {
        AuditEvent.Builder builder = AuditEvent.builder(organizationId, action).occurredAt(clock.instant());
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            ClientInfo client = ClientInfo.from(servlet.getRequest());
            builder.ip(client.ip()).userAgent(client.userAgent());
        }
        return builder;
    }

    public void record(AuditEvent.Builder builder) {
        recorder.record(builder.build());
    }
}
