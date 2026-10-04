package net.java21.data2flow.core.script.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스크립트 M5 규칙(SCR-03.03·04.01·04.02·01.06, BR-SCR-12·13·14): 설정값 검사, 모듈 참조 추출, 한도.
 */
public final class ScriptM5Rules {

    /** 스크립트당 테스트 케이스(BR-SCR-14) */
    public static final int MAX_TEST_CASES = 50;
    /** 조직당 공유 모듈(BR-SCR-14) */
    public static final int MAX_MODULES = 100;
    /** 조직당 수식 항목(BR-SCR-14) */
    public static final int MAX_FORMULAS = 200;
    /** 설정값 항목 수·값 크기(API-SCR-22) */
    public static final int MAX_CONFIG_ENTRIES = 50;
    public static final int MAX_CONFIG_VALUE_BYTES = 1024;
    /** 운영 로그 수집 시간(BR-SCR-17) */
    public static final java.time.Duration LOG_CAPTURE = java.time.Duration.ofMinutes(30);

    private static final Pattern MODULE_NAME = Pattern.compile("^[a-z0-9-]{3,40}$");
    private static final Pattern CONFIG_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    /** {@code import … from 'module:이름@버전'}(SCR-api §3.5) */
    private static final Pattern IMPORT = Pattern.compile("from\\s*['\"]module:([a-z0-9-]{3,40})@(\\d{1,6})['\"]");
    private static final Pattern RESULT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private ScriptM5Rules() {
    }

    /** 비밀값처럼 보이는 설정 이름(BR-SCR-12): password·secret·token이 들어가거나 key로 끝남(apiKey·api_key), 대소문자 무시 */
    public static boolean secretLike(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("password") || n.contains("passwd") || n.contains("secret") || n.contains("token") || n.endsWith("key");
    }

    /**
     * API-SCR-22 설정값 검사: 50개 이하, 이름 형식, 비밀값 이름 거부(400 SCRIPT_CONFIG_SECRET_FORBIDDEN), 값은 문자열·숫자·불리언(1KB 이하).
     */
    public static void validateConfig(JsonNode config) {
        if (config == null || !config.isObject()) {
            throw ScriptModels.invalid("config", "NotNull");
        }
        if (config.size() > MAX_CONFIG_ENTRIES) {
            throw ScriptModels.invalid("config", "Size");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        List<FieldErrorDetail> secrets = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : config.properties()) {
            String name = e.getKey();
            JsonNode v = e.getValue();
            if (!CONFIG_NAME.matcher(name).matches()) {
                errors.add(new FieldErrorDetail("config." + name, "Pattern", null));
            } else if (secretLike(name)) {
                secrets.add(new FieldErrorDetail("config." + name, "SECRET_FORBIDDEN", null));
            } else if (!(v.isString() || v.isNumber() || v.isBoolean())) {
                errors.add(new FieldErrorDetail("config." + name, "TYPE", "string|number|boolean"));
            } else if (v.isString() && v.stringValue().getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_VALUE_BYTES) {
                errors.add(new FieldErrorDetail("config." + name, "Size", "1KB"));
            }
        }
        if (!secrets.isEmpty()) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_CONFIG_SECRET_FORBIDDEN, secrets);
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(net.java21.data2flow.contracts.error.CommonErrorCode.INVALID_REQUEST, errors);
        }
    }

    /** 코드가 가져오는 모듈 {@code 이름@버전}(정렬·중복 제거) */
    public static List<String> moduleRefs(String code) {
        Set<String> refs = new LinkedHashSet<>();
        if (code != null) {
            Matcher m = IMPORT.matcher(code);
            while (m.find()) {
                refs.add(m.group(1) + "@" + Integer.parseInt(m.group(2)));
            }
        }
        return refs.stream().sorted().toList();
    }

    public static boolean validModuleName(String name) {
        return name != null && MODULE_NAME.matcher(name).matches();
    }

    public static boolean validResultKey(String key) {
        return key != null && RESULT_KEY.matcher(key).matches();
    }
}
