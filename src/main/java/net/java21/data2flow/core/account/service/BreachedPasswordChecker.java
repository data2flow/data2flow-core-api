package net.java21.data2flow.core.account.service;

import net.java21.data2flow.core.config.CoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * 유출 비밀번호 대조(IAM-02.02, BR-IAM-03 "로컬 사전 + k-익명성 조회").
 *
 * <ul>
 *   <li>로컬 사전({@code breached-passwords.txt}, 흔한 유출 비밀번호): 항상 쓴다. 대소문자를 무시한다.</li>
 *   <li>k-익명성 조회(Pwned Passwords range API): SHA-1 앞 5자만 보내고 응답의 나머지 해시와 비교한다. 외부 호출이라 설정으로 켠다
 *       ({@code data2flow.core.password.hibp-enabled}). 조회가 실패하면 로컬 사전 결과만 쓴다(가입·변경을 막지 않음).</li>
 * </ul>
 */
@Component
public class BreachedPasswordChecker {

    private static final Logger log = LoggerFactory.getLogger(BreachedPasswordChecker.class);
    static final String DICTIONARY = "security/breached-passwords.txt";

    private final Set<String> dictionary;
    private final boolean hibpEnabled;
    private final RestClient hibp;

    public BreachedPasswordChecker(CoreProperties properties) {
        this.dictionary = load();
        this.hibpEnabled = properties.password().hibpEnabled();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.hibp = RestClient.builder().baseUrl(properties.password().hibpBaseUrl()).requestFactory(factory).build();
    }

    public boolean isBreached(String password) {
        if (dictionary.contains(password.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return hibpEnabled && inRangeApi(password);
    }

    boolean inRangeApi(String password) {
        String sha1 = sha1Hex(password).toUpperCase(Locale.ROOT);
        String prefix = sha1.substring(0, 5);
        String suffix = sha1.substring(5);
        try {
            String body = hibp.get().uri("/range/{prefix}", prefix).header("Add-Padding", "true").retrieve().body(String.class);
            if (body == null) {
                return false;
            }
            for (String line : body.split("\r?\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(suffix) && !line.endsWith(":0")) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ex) {
            log.warn("유출 비밀번호 조회 실패 — 로컬 사전 결과만 사용: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    private static Set<String> load() {
        Set<String> words = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(DICTIONARY).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.strip();
                if (!word.isEmpty() && !word.startsWith("#")) {
                    words.add(word.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("유출 비밀번호 사전을 읽지 못했습니다", ex);
        }
        return Set.copyOf(words);
    }

    private static String sha1Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 사전 단어 수(테스트·진단용) */
    int dictionarySize() {
        return dictionary.size();
    }
}
