package net.java21.data2flow.core.devicesearch.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 기기 검색식 파서(DEV-13.03, BR-DEV-35). 문법(대소문자 무시):
 * <pre>
 * expr    := and ('or' and)*
 * and     := unary ('and' unary)*
 * unary   := 'not' unary | '(' expr ')' | cmp
 * cmp     := field op value | field 'in' (value | '(' value (',' value)* ')')
 * op      := = | != | &lt; | &lt;= | &gt; | &gt;= | ~
 * value   := "문자열" | 숫자 | true | false | 기간(30m·24h·7d, 지금부터 그만큼 전)
 * field   := name | externalId | status | connectivity | kind | model | space | tag | source | group | battery | rssi | snr
 *          | lastSeen | virtual | metric.&lt;키&gt; | attr.&lt;키&gt;
 * </pre>
 * 오류는 위치(1부터 센 열)와 함께 {@link DeviceQueryException}. 예: {@code battery < } → 열 11(값이 와야 할 자리, AT-DEV-25.2).
 */
public final class DeviceQueryParser {

    public static final int MAX_LENGTH = 2000;
    public static final int MAX_TERMS = 50;

    /** 필드 → 허용 연산자 */
    static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            Map.entry("name", Set.of("=", "!=", "~", "in")),
            Map.entry("externalid", Set.of("=", "!=", "~", "in")),
            Map.entry("status", Set.of("=", "!=", "in")),
            Map.entry("connectivity", Set.of("=", "!=", "in")),
            Map.entry("kind", Set.of("=", "!=", "in")),
            Map.entry("model", Set.of("=", "!=", "in")),
            Map.entry("space", Set.of("=", "!=", "in")),
            Map.entry("tag", Set.of("=", "!=", "in")),
            Map.entry("source", Set.of("=", "!=", "in")),
            Map.entry("group", Set.of("=", "!=", "in")),
            Map.entry("battery", Set.of("=", "!=", "<", "<=", ">", ">=")),
            Map.entry("rssi", Set.of("=", "!=", "<", "<=", ">", ">=")),
            Map.entry("snr", Set.of("=", "!=", "<", "<=", ">", ">=")),
            Map.entry("lastseen", Set.of("<", "<=", ">", ">=")),
            Map.entry("virtual", Set.of("=", "!=")));
    static final Set<String> DYNAMIC_PREFIXES = Set.of("metric.", "attr.");
    private static final Set<String> COMPARE_OPS = Set.of("=", "!=", "<", "<=", ">", ">=", "~");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)?");
    private static final Pattern DURATION = Pattern.compile("\\d{1,6}[smhd]");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,15}(\\.\\d{1,9})?");

    private final String src;
    private final List<Token> tokens;
    private int pos;
    private int terms;

    private DeviceQueryParser(String src) {
        this.src = src;
        this.tokens = tokenize(src);
    }

    /** 검색식으로 보이는가(목록 q가 키워드 검색인지 검색식인지 가른다): 비교 연산자나 and/or/not/in 낱말이 있으면 */
    public static boolean looksLikeExpression(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        String s = q.strip().toLowerCase(Locale.ROOT);
        return s.matches("(?s).*(=|<|>|~|\\(|\\)).*") || s.matches("(?s).*\\s(and|or|in)\\s.*") || s.startsWith("not ");
    }

    public static DeviceQuery parse(String q) {
        if (q == null || q.isBlank()) {
            throw new DeviceQueryException(1, "빈 검색식");
        }
        if (q.length() > MAX_LENGTH) {
            throw new DeviceQueryException(MAX_LENGTH + 1, "검색식이 너무 깁니다");
        }
        DeviceQueryParser p = new DeviceQueryParser(q);
        DeviceQuery result = p.or();
        if (p.peek().type != Type.END) {
            throw p.error(p.peek(), "예상하지 못한 토큰");
        }
        return result;
    }

    private DeviceQuery or() {
        List<DeviceQuery> items = new ArrayList<>(List.of(and()));
        while (peek().isWord("or")) {
            next();
            items.add(and());
        }
        return items.size() == 1 ? items.getFirst() : new DeviceQuery.Or(items);
    }

    private DeviceQuery and() {
        List<DeviceQuery> items = new ArrayList<>(List.of(unary()));
        while (peek().isWord("and")) {
            next();
            items.add(unary());
        }
        return items.size() == 1 ? items.getFirst() : new DeviceQuery.And(items);
    }

    private DeviceQuery unary() {
        Token t = peek();
        if (t.isWord("not")) {
            next();
            return new DeviceQuery.Not(unary());
        }
        if (t.type == Type.LPAREN) {
            next();
            DeviceQuery inner = or();
            expect(Type.RPAREN, "')'가 필요합니다");
            return inner;
        }
        return cmp();
    }

    private DeviceQuery cmp() {
        Token f = next();
        if (f.type != Type.WORD || !IDENT.matcher(f.text).matches() || isKeyword(f.text)) {
            throw error(f, "필드 이름이 필요합니다");
        }
        String field = f.text.toLowerCase(Locale.ROOT);
        boolean dynamic = DYNAMIC_PREFIXES.stream().anyMatch(prefix -> field.startsWith(prefix) && field.length() > prefix.length());
        Set<String> allowed = dynamic ? COMPARE_OPS : FIELDS.get(field);
        if (allowed == null) {
            throw error(f, "모르는 필드: " + f.text);
        }
        if (++terms > MAX_TERMS) {
            throw error(f, "조건이 너무 많습니다");
        }
        Token opTok = next();
        String op = opTok.type == Type.OP ? opTok.text : opTok.isWord("in") ? "in" : null;
        if (op == null) {
            throw error(opTok, "연산자가 필요합니다");
        }
        if (!allowed.contains(op)) {
            throw error(opTok, "이 필드에 쓸 수 없는 연산자: " + op);
        }
        List<String> values = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        if ("in".equals(op) && peek().type == Type.LPAREN) {
            next();
            do {
                Token v = value();
                values.add(v.text);
                kinds.add(v.kind());
            } while (accept(Type.COMMA));
            expect(Type.RPAREN, "')'가 필요합니다");
        } else {
            Token v = value();
            values.add(v.text);
            kinds.add(v.kind());
        }
        return new DeviceQuery.Cmp(dynamic ? field.substring(0, field.indexOf('.') + 1) + f.text.substring(f.text.indexOf('.') + 1) : field,
                op, values, kinds, f.column);
    }

    private Token value() {
        Token v = next();
        if (v.type == Type.STRING || v.type == Type.NUMBER || v.type == Type.DURATION
                || (v.type == Type.WORD && (v.isWord("true") || v.isWord("false")))) {
            return v;
        }
        if (v.type == Type.WORD && !isKeyword(v.text)) {
            return new Token(Type.STRING, v.text, v.column); // 따옴표 없는 낱말 값(예: status = ACTIVE)
        }
        throw error(v, "값이 필요합니다");
    }

    private static boolean isKeyword(String w) {
        return Set.of("and", "or", "not", "in", "true", "false").contains(w.toLowerCase(Locale.ROOT));
    }

    private boolean accept(Type type) {
        if (peek().type == type) {
            next();
            return true;
        }
        return false;
    }

    private void expect(Type type, String message) {
        Token t = next();
        if (t.type != type) {
            throw error(t, message);
        }
    }

    private Token peek() {
        return tokens.get(Math.min(pos, tokens.size() - 1));
    }

    private Token next() {
        Token t = peek();
        if (pos < tokens.size()) {
            pos++;
        }
        return t;
    }

    private DeviceQueryException error(Token t, String message) {
        return new DeviceQueryException(t.column, message);
    }

    // ---- 낱말 나누기

    enum Type { WORD, STRING, NUMBER, DURATION, OP, LPAREN, RPAREN, COMMA, END }

    record Token(Type type, String text, int column) {
        boolean isWord(String w) {
            return type == Type.WORD && text.equalsIgnoreCase(w);
        }

        String kind() {
            return switch (type) {
                case STRING -> "STRING";
                case NUMBER -> "NUMBER";
                case DURATION -> "DURATION";
                default -> "BOOLEAN";
            };
        }
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int col = i + 1;
            if (c == '(') {
                out.add(new Token(Type.LPAREN, "(", col));
                i++;
            } else if (c == ')') {
                out.add(new Token(Type.RPAREN, ")", col));
                i++;
            } else if (c == ',') {
                out.add(new Token(Type.COMMA, ",", col));
                i++;
            } else if (c == '"' || c == '\'') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char ch = s.charAt(j);
                    if (ch == '\\' && j + 1 < n) {
                        b.append(s.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (ch == c) {
                        closed = true;
                        break;
                    }
                    b.append(ch);
                    j++;
                }
                if (!closed) {
                    throw new DeviceQueryException(col, "닫히지 않은 문자열");
                }
                if (b.length() > 200) {
                    throw new DeviceQueryException(col, "값이 너무 깁니다");
                }
                out.add(new Token(Type.STRING, b.toString(), col));
                i = j + 1;
            } else if (c == '=' || c == '!' || c == '<' || c == '>' || c == '~') {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("==")) {
                    out.add(new Token(Type.OP, two.equals("==") ? "=" : two, col));
                    i += 2;
                } else if (c == '!') {
                    throw new DeviceQueryException(col, "모르는 연산자");
                } else {
                    out.add(new Token(Type.OP, String.valueOf(c), col));
                    i++;
                }
            } else {
                int j = i;
                while (j < n && !Character.isWhitespace(s.charAt(j)) && "()=,!<>~\"'".indexOf(s.charAt(j)) < 0) {
                    j++;
                }
                String w = s.substring(i, j);
                if (NUMBER.matcher(w).matches()) {
                    out.add(new Token(Type.NUMBER, w, col));
                } else if (DURATION.matcher(w).matches()) {
                    out.add(new Token(Type.DURATION, w, col));
                } else {
                    out.add(new Token(Type.WORD, w, col));
                }
                i = j;
            }
        }
        out.add(new Token(Type.END, "", n + 1));
        return out;
    }
}
