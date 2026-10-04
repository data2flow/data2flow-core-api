package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.dataexchange.repository.DataDictionaryRepository;
import net.java21.data2flow.core.dataexchange.repository.DataDictionaryRepository.MetricRow;
import net.java21.data2flow.core.dataexchange.repository.DataDictionaryRepository.SpaceRow;
import net.java21.data2flow.core.dataexchange.repository.DataDictionaryRepository.VersionRow;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 데이터 사전(TSD-07.04, API-TSD-59, BR-TSD-27). 측정 항목(키·이름·단위·값 종류·집계 방식·유효 범위·별칭)과 공간 트리를 모은 "구조"가
 * 바뀌면 판 번호가 오른다(내용 SHA-256 비교, 조회·내보내기 때 확인). 설명 문구(표·열·품질 코드)는 요청 언어로 붙인다(ADR-037).
 * 내보내기 작업·정기 내보내기 파일에 같은 판 번호를 적는다(AT-TSD-19.3).
 */
@Service
public class DataDictionaryService {

    static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    static final List<String> LONG_COLUMNS = List.of("time", "device_id", "device_name", "space_path", "metric", "value", "unit", "quality");
    static final Map<String, String> COLUMN_TYPES = Map.of("time", "timestamptz", "device_id", "bigint", "device_name", "text",
            "space_path", "text", "metric", "text", "value", "double", "unit", "text", "quality", "smallint");

    private final RoleChecker roleChecker;
    private final DataDictionaryRepository repository;
    private final MessageSource messages;
    private final JsonMapper json;
    private final Clock clock;

    public DataDictionaryService(RoleChecker roleChecker, DataDictionaryRepository repository, MessageSource messages, JsonMapper json,
                                 Clock clock) {
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.messages = messages;
        this.json = json;
        this.clock = clock;
    }

    /** API-TSD-59 JSON. version이 없으면 현재 판(구조가 바뀌었으면 새 판을 만든다), 있으면 그 판(없으면 404) */
    @Transactional
    public Map<String, Object> dictionary(Integer version) {
        roleChecker.require(Permission.TS_READ);
        long orgId = roleChecker.currentUser().organizationId();
        VersionRow row = version == null ? current(orgId)
                : repository.findVersion(orgId, version).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return render(row, LocaleContextHolder.getLocale());
    }

    /** API-TSD-59 HTML(사람용) */
    @Transactional
    public String html(Integer version) {
        return toHtml(dictionary(version), LocaleContextHolder.getLocale());
    }

    /** 현재 판 번호(내보내기에 적음). 구조가 바뀌었으면 새 판을 만든다 */
    @Transactional(propagation = Propagation.REQUIRED)
    public int currentVersion(long organizationId) {
        return current(organizationId).version();
    }

    /** 현재 판 사전 JSON 문자열(정기 내보내기 파일 옆 data-dictionary_v{n}.json, AT-TSD-17.1) */
    @Transactional
    public String currentJson(long organizationId, Locale locale) {
        return json.writeValueAsString(render(current(organizationId), locale));
    }

    VersionRow current(long organizationId) {
        Map<String, Object> structure = structure(organizationId);
        String content = json.writeValueAsString(structure);
        String hash = Tokens.sha256Hex(content);
        VersionRow latest = repository.findLatest(organizationId).orElse(null);
        if (latest != null && latest.contentHash().equals(hash)) {
            return latest;
        }
        int next = latest == null ? 1 : latest.version() + 1;
        Instant now = clock.instant();
        if (!repository.insert(organizationId, next, hash, content, now)) {
            return repository.findLatest(organizationId).orElseThrow();
        }
        return new VersionRow(next, hash, content, now);
    }

    private Map<String, Object> structure(long organizationId) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (MetricRow m : repository.findMetrics(organizationId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", m.key());
            item.put("displayName", m.displayName());
            item.put("unit", m.unit());
            item.put("valueType", m.valueType());
            item.put("aggDefault", m.aggDefault() == null ? null : m.aggDefault().toLowerCase(Locale.ROOT));
            item.put("validMin", m.validMin());
            item.put("validMax", m.validMax());
            item.put("stateType", m.stateType());
            item.put("aliases", m.aliases());
            metrics.add(item);
        }
        List<Map<String, Object>> spaces = new ArrayList<>();
        for (SpaceRow s : repository.findSpaces(organizationId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Long.toString(s.id()));
            item.put("parentId", s.parentId() == null ? null : Long.toString(s.parentId()));
            item.put("type", s.type());
            item.put("name", s.name());
            item.put("code", s.code());
            item.put("path", s.path());
            spaces.add(item);
        }
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("metrics", metrics);
        structure.put("spaces", spaces);
        return structure;
    }

    private Map<String, Object> render(VersionRow row, Locale locale) {
        Map<String, Object> structure = json.readValue(row.content(), MAP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", row.version());
        out.put("generatedAt", row.createdAt().toString());
        List<Map<String, Object>> tables = new ArrayList<>();
        for (String table : List.of("telemetry", "telemetry_aggregate")) {
            List<Map<String, Object>> columns = new ArrayList<>();
            for (String c : LONG_COLUMNS) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", c);
                col.put("type", COLUMN_TYPES.get(c));
                col.put("unit", null);
                col.put("description", text("dictionary." + table + ".column." + c, locale));
                columns.add(col);
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", table);
            t.put("description", text("dictionary.table." + table, locale));
            t.put("columns", columns);
            tables.add(t);
        }
        out.put("tables", tables);
        out.put("metrics", structure.get("metrics"));
        List<Map<String, Object>> quality = new ArrayList<>();
        for (int code = 0; code <= 5; code++) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("code", code);
            q.put("meaning", text("dictionary.quality." + code, locale));
            q.put("includedInAggregates", code == 0 || code == 4);
            quality.add(q);
        }
        out.put("qualityCodes", quality);
        List<Map<String, Object>> aggregations = new ArrayList<>();
        for (String a : List.of("avg", "min", "max", "sum", "last", "count", "twa")) {
            aggregations.add(Map.of("key", a, "description", text("dictionary.agg." + a, locale)));
        }
        out.put("aggregations", aggregations);
        out.put("spaces", structure.get("spaces"));
        return out;
    }

    private String text(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }

    @SuppressWarnings("unchecked")
    static String toHtml(Map<String, Object> d, Locale locale) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html lang=\"").append(esc(locale.getLanguage())).append("\"><head><meta charset=\"utf-8\">")
                .append("<title>data2flow data dictionary v").append(d.get("version")).append("</title>")
                .append("<style>body{font-family:sans-serif}table{border-collapse:collapse;margin:8px 0}td,th{border:1px solid #999;padding:4px 8px}</style>")
                .append("</head><body><h1>data2flow data dictionary v").append(d.get("version")).append("</h1><p>")
                .append(esc(String.valueOf(d.get("generatedAt")))).append("</p>");
        for (Map<String, Object> t : (List<Map<String, Object>>) d.get("tables")) {
            b.append("<h2>").append(esc(String.valueOf(t.get("name")))).append("</h2><p>").append(esc(String.valueOf(t.get("description"))))
                    .append("</p><table><tr><th>column</th><th>type</th><th>description</th></tr>");
            for (Map<String, Object> c : (List<Map<String, Object>>) t.get("columns")) {
                b.append("<tr><td>").append(esc(String.valueOf(c.get("name")))).append("</td><td>").append(esc(String.valueOf(c.get("type"))))
                        .append("</td><td>").append(esc(String.valueOf(c.get("description")))).append("</td></tr>");
            }
            b.append("</table>");
        }
        b.append("<h2>metrics</h2><table><tr><th>key</th><th>name</th><th>unit</th><th>valueType</th><th>aggDefault</th><th>aliases</th></tr>");
        for (Map<String, Object> m : (List<Map<String, Object>>) d.get("metrics")) {
            b.append("<tr><td>").append(esc(String.valueOf(m.get("key")))).append("</td><td>").append(esc(String.valueOf(m.get("displayName"))))
                    .append("</td><td>").append(esc(m.get("unit") == null ? "" : String.valueOf(m.get("unit")))).append("</td><td>")
                    .append(esc(String.valueOf(m.get("valueType")))).append("</td><td>").append(esc(String.valueOf(m.get("aggDefault"))))
                    .append("</td><td>").append(esc(String.valueOf(m.get("aliases")))).append("</td></tr>");
        }
        b.append("</table><h2>quality</h2><table><tr><th>code</th><th>meaning</th><th>aggregated</th></tr>");
        for (Map<String, Object> q : (List<Map<String, Object>>) d.get("qualityCodes")) {
            b.append("<tr><td>").append(q.get("code")).append("</td><td>").append(esc(String.valueOf(q.get("meaning")))).append("</td><td>")
                    .append(q.get("includedInAggregates")).append("</td></tr>");
        }
        b.append("</table><h2>spaces</h2><table><tr><th>id</th><th>parent</th><th>type</th><th>name</th><th>code</th></tr>");
        for (Map<String, Object> s : (List<Map<String, Object>>) d.get("spaces")) {
            b.append("<tr><td>").append(esc(String.valueOf(s.get("id")))).append("</td><td>")
                    .append(esc(s.get("parentId") == null ? "" : String.valueOf(s.get("parentId")))).append("</td><td>")
                    .append(esc(String.valueOf(s.get("type")))).append("</td><td>").append(esc(String.valueOf(s.get("name")))).append("</td><td>")
                    .append(esc(s.get("code") == null ? "" : String.valueOf(s.get("code")))).append("</td></tr>");
        }
        return b.append("</table></body></html>").toString();
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
