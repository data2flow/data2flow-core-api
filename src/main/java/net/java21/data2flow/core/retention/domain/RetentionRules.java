package net.java21.data2flow.core.retention.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 보관 정책 규칙(TSD-02.01·05.01·05.03, BR-TSD-02·03): 유효 정책 계산(METRIC > MODEL > ORG, 없으면 시스템 기본값), 변경안 합치기,
 * 줄어드는 항목 찾기, 미리 보기 확인 토큰.
 *
 * <p>변경안(API-TSD-41·42 {@code items}) 해석: ORG 항목은 그 종류만 바꾸고(빠진 종류는 그대로), MODEL·METRIC 재정의는 보낸 목록이
 * 전체다(빠진 재정의는 지운다 — 화면이 재정의 표 전체를 보낸다).
 */
public final class RetentionRules {

    public enum Scope { ORG, MODEL, METRIC }

    /** 정책 한 줄 */
    public record Policy(Scope scope, String scopeRef, DataClass dataClass, int retainDays, Integer compressAfterDays,
                         boolean archiveBeforeDelete, String storeMode) {

        public String key() {
            return scope + "|" + (scopeRef == null ? "" : scopeRef) + "|" + dataClass;
        }
    }

    /** 유효 정책 한 줄(inherited = 저장된 값 없이 시스템 기본값) */
    public record Effective(Policy policy, boolean inherited) {
    }

    private RetentionRules() {
    }

    /** ORG 정책(저장 또는 기본값) — 모든 종류 + 저장된 재정의 */
    public static List<Effective> effective(List<Policy> stored) {
        List<Effective> out = new ArrayList<>();
        for (DataClass dc : DataClass.values()) {
            Optional<Policy> org = stored.stream().filter(p -> p.scope() == Scope.ORG && p.dataClass() == dc).findFirst();
            out.add(org.map(p -> new Effective(p, false)).orElseGet(() -> new Effective(defaultPolicy(dc), true)));
        }
        stored.stream().filter(p -> p.scope() != Scope.ORG)
                .sorted(Comparator.comparing((Policy p) -> p.dataClass().ordinal()).thenComparing(p -> p.scope().ordinal())
                        .thenComparing(Policy::scopeRef))
                .forEach(p -> out.add(new Effective(p, false)));
        return out;
    }

    public static Policy defaultPolicy(DataClass dc) {
        return new Policy(Scope.ORG, null, dc, dc.defaultDays(),
                dc == DataClass.TELEMETRY ? DataClass.DEFAULT_COMPRESS_AFTER_DAYS : null, false, null);
    }

    /** 변경안을 저장된 정책에 합친 새 정책 집합 */
    public static List<Policy> merge(List<Policy> stored, List<Policy> changes) {
        Map<String, Policy> out = new LinkedHashMap<>();
        stored.stream().filter(p -> p.scope() == Scope.ORG).forEach(p -> out.put(p.key(), p));
        changes.stream().filter(p -> p.scope() == Scope.ORG).forEach(p -> out.put(p.key(), p));
        changes.stream().filter(p -> p.scope() != Scope.ORG).forEach(p -> out.put(p.key(), p));
        return List.copyOf(out.values());
    }

    /** ORG 보관 일수(저장 또는 기본값) */
    public static int orgDays(List<Policy> set, DataClass dc) {
        return set.stream().filter(p -> p.scope() == Scope.ORG && p.dataClass() == dc).map(Policy::retainDays).findFirst()
                .orElse(dc.defaultDays());
    }

    /**
     * 바꾸면 보관 기간이 줄어드는 항목(BR-TSD-03). ORG 기간 단축, 재정의 단축·새 재정의가 ORG보다 짧음, 더 긴 재정의를 지워 ORG로 돌아감.
     * 돌려주는 값은 새 유효 기간(지워진 재정의는 같은 범위로 ORG 기간)이다
     */
    public static List<Policy> shortened(List<Policy> before, List<Policy> after) {
        List<Policy> out = new ArrayList<>();
        for (DataClass dc : DataClass.values()) {
            int oldOrg = orgDays(before, dc);
            int newOrg = orgDays(after, dc);
            if (DataClass.shorter(newOrg, oldOrg)) {
                after.stream().filter(p -> p.scope() == Scope.ORG && p.dataClass() == dc).findFirst().ifPresent(out::add);
            }
        }
        for (Policy p : after) {
            if (p.scope() == Scope.ORG) {
                continue;
            }
            int old = before.stream().filter(b -> b.key().equals(p.key())).map(Policy::retainDays).findFirst()
                    .orElse(orgDays(before, p.dataClass()));
            if (DataClass.shorter(p.retainDays(), old)) {
                out.add(p);
            }
        }
        for (Policy b : before) {
            if (b.scope() == Scope.ORG || after.stream().anyMatch(a -> a.key().equals(b.key()))) {
                continue;
            }
            int newDays = orgDays(after, b.dataClass());
            if (DataClass.shorter(newDays, b.retainDays())) {
                out.add(new Policy(b.scope(), b.scopeRef(), b.dataClass(), newDays, null, false, null));
            }
        }
        return out;
    }

    /** 두 정책 집합이 같은가(순서 무관) */
    public static boolean same(List<Policy> a, List<Policy> b) {
        return canonical(a).equals(canonical(b));
    }

    /**
     * 미리 보기 확인 토큰(BR-TSD-03): {@code 만료초.해시}. 해시 = SHA-256(조직, 현재 정책 판, 새 정책 집합, 만료초). 정책이 그 사이 바뀌었거나
     * 다른 변경안이면 맞지 않는다. 보안 토큰이 아니라 "미리 보기를 보고 확인했다"는 표시다(같은 권한이면 미리 보기를 부를 수 있다)
     */
    public static String confirmToken(long organizationId, long policyVersion, List<Policy> next, long expiresEpochSecond) {
        String material = organizationId + "\n" + policyVersion + "\n" + canonical(next) + "\n" + expiresEpochSecond;
        return expiresEpochSecond + "." + sha256(material).substring(0, 40);
    }

    /** 토큰이 맞고 아직 유효한가 */
    public static boolean verify(String token, long organizationId, long policyVersion, List<Policy> next, long nowEpochSecond) {
        if (token == null || !token.matches("\\d{1,12}\\.[0-9a-f]{40}")) {
            return false;
        }
        long expires = Long.parseLong(token.substring(0, token.indexOf('.')));
        return expires >= nowEpochSecond && confirmToken(organizationId, policyVersion, next, expires).equals(token);
    }

    static String canonical(List<Policy> set) {
        return set.stream().sorted(Comparator.comparing(Policy::key))
                .map(p -> p.key() + "=" + p.retainDays() + "/" + Objects.toString(p.compressAfterDays(), "-") + "/"
                        + p.archiveBeforeDelete() + "/" + Objects.toString(p.storeMode(), "-"))
                .reduce((x, y) -> x + ";" + y).orElse("");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
