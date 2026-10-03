package net.java21.data2flow.core.source.domain;

import net.java21.data2flow.core.source.domain.SourceModels.RuntimeRow;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 소스 대표 연결 상태(DSC domain-model §2.5, DSC-02.01) 계산. 순수 함수라 단위 테스트로 규칙을 확인한다.
 *
 * <ol>
 *   <li>lifecycle이 ACTIVE가 아니면 DISABLED</li>
 *   <li>보고가 {@link #STALE_AFTER}(90초) 넘게 끊긴 인스턴스는 뺀다</li>
 *   <li>하나라도 CONNECTED면 CONNECTED(나머지가 끊겼으면 화면이 "일부 연결" 경고 — connected &lt; total)</li>
 *   <li>CONNECTING이 하나라도 있으면 CONNECTING(아직 연결을 시도하는 중)</li>
 *   <li>남은 인스턴스가 모두 ERROR면 ERROR(가장 최근 보고의 오류 종류)</li>
 *   <li>그 밖(DISCONNECTED·DISABLED가 섞임)은 DISCONNECTED</li>
 *   <li>살아 있는 인스턴스가 없으면: ACTIVE가 된 지 90초 안이면 CONNECTING(ingress가 1분 안에 연결을 시도한다, BR-DSC-05), 지나면 DISCONNECTED</li>
 * </ol>
 * 문서는 섞인 경우(4·6)를 정하지 않아 위처럼 정했다.
 */
public final class ConnectionStates {

    public static final String CONNECTED = "CONNECTED";
    public static final String CONNECTING = "CONNECTING";
    public static final String DISCONNECTED = "DISCONNECTED";
    public static final String ERROR = "ERROR";
    public static final String DISABLED = "DISABLED";

    /** 이보다 오래 보고가 없는 인스턴스는 대표 상태 계산에서 뺀다(source_runtimes 주석, domain-model §2.5) */
    public static final Duration STALE_AFTER = Duration.ofSeconds(90);

    private ConnectionStates() {
    }

    /** 계산 결과: 대표 상태, 오류 종류(ERROR일 때), 살아 있는 인스턴스 수, 그중 연결된 수 */
    public record Representative(String state, String errorKind, int totalInstances, int connectedInstances) {
    }

    public static boolean fresh(RuntimeRow row, Instant now) {
        return row.reportedAt() != null && !row.reportedAt().isBefore(now.minus(STALE_AFTER));
    }

    public static Representative representative(String lifecycle, List<RuntimeRow> instances, Instant activatedAt, Instant now) {
        List<RuntimeRow> live = instances.stream().filter(r -> fresh(r, now)).toList();
        int connected = (int) live.stream().filter(r -> CONNECTED.equals(r.state())).count();
        if (!SourceModels.ACTIVE.equals(lifecycle)) {
            return new Representative(DISABLED, null, live.size(), connected);
        }
        if (live.isEmpty()) {
            boolean starting = activatedAt != null && !activatedAt.isBefore(now.minus(STALE_AFTER));
            return new Representative(starting ? CONNECTING : DISCONNECTED, null, 0, 0);
        }
        if (connected > 0) {
            return new Representative(CONNECTED, null, live.size(), connected);
        }
        if (live.stream().anyMatch(r -> CONNECTING.equals(r.state()))) {
            return new Representative(CONNECTING, null, live.size(), 0);
        }
        if (live.stream().allMatch(r -> ERROR.equals(r.state()))) {
            String kind = live.stream().max(Comparator.comparing(RuntimeRow::reportedAt)).map(RuntimeRow::errorKind).orElse(null);
            return new Representative(ERROR, kind == null ? "OTHER" : kind, live.size(), 0);
        }
        return new Representative(DISCONNECTED, null, live.size(), 0);
    }
}
