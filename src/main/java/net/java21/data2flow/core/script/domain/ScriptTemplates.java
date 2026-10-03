package net.java21.data2flow.core.script.domain;

import net.java21.data2flow.core.script.domain.ScriptModels.ScriptKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기본 코드와 기본 제공 템플릿(API-SCR-15, SCR-01.05). 템플릿 기능 자체는 M5지만 M2 화면(새 스크립트 대화상자)이 목록을 부르고
 * 생성 요청이 {@code templateKey}를 보낼 수 있어 코드에 고정 목록으로 둔다(DB 테이블 없음).
 */
public final class ScriptTemplates {

    /** 새 TRANSFORM 스크립트 기본 코드(UC-SCR-01 3단계) */
    public static final String DEFAULT_TRANSFORM = """
            /**
             * 표준 메시지를 받아 고친 메시지를 돌려줍니다. null을 돌려주면 저장하지 않습니다(원본은 남습니다).
             * @param {CanonicalTelemetry} msg
             * @param {TransformContext} ctx
             */
            function transform(msg, ctx) {
              return msg;
            }
            """;

    /** 새 DECODE 스크립트 기본 코드(UC-SCR-05) */
    public static final String DEFAULT_DECODE = """
            /**
             * 원본 하나를 표준 메시지 하나로 바꿉니다. externalId와 metrics는 필수입니다.
             * @param {DecodeInput} input
             * @param {DecodeContext} ctx
             */
            function decode(input, ctx) {
              const data = typeof input.payload === 'string' ? JSON.parse(input.payload) : input.payload;
              return { externalId: String(data.id), measuredAt: input.receivedAt, metrics: [] };
            }
            """;

    private static final List<Template> ALL = List.of(
            new Template("calibration-offset", ScriptKind.TRANSFORM, "보정 오프셋", "기기 속성 tempOffset(없으면 설정값)을 온도에 더합니다", """
                    function transform(msg, ctx) {
                      const offset = ctx.device.attributes.tempOffset ?? ctx.config.tempOffset ?? 0;
                      const t = ctx.util.metric(msg, 'temperature');
                      if (t) ctx.util.setMetric(msg, 'temperature', ctx.util.round(t.value + offset, 2), t.unit);
                      return msg;
                    }
                    """, Map.of("tempOffset", 0)),
            new Template("unit-convert", ScriptKind.TRANSFORM, "단위 변환", "화씨 온도를 섭씨로 바꿉니다", """
                    function transform(msg, ctx) {
                      const t = ctx.util.metric(msg, ctx.config.metric);
                      if (t) ctx.util.setMetric(msg, ctx.config.metric, ctx.util.round(ctx.util.f2c(t.value), 2), '°C');
                      return msg;
                    }
                    """, Map.of("metric", "temperature")),
            new Template("moving-average", ScriptKind.TRANSFORM, "이동평균 필터", "직전 n개 평균으로 값을 고릅니다", """
                    function transform(msg, ctx) {
                      const m = ctx.util.metric(msg, ctx.config.metric);
                      if (m) ctx.util.setMetric(msg, ctx.config.metric, ctx.util.movingAvg(ctx.config.metric, m.value, ctx.config.n), m.unit);
                      return msg;
                    }
                    """, Map.of("metric", "co2", "n", 5)),
            new Template("drop-zero", ScriptKind.TRANSFORM, "0값 제거", "값이 0인 측정 항목을 버립니다", """
                    function transform(msg, ctx) {
                      msg.metrics = msg.metrics.filter((m) => m.value !== 0);
                      return msg.metrics.length === 0 ? null : msg;
                    }
                    """, Map.of()),
            new Template("dew-point", ScriptKind.TRANSFORM, "이슬점", "온도·습도로 dew_point를 만듭니다", """
                    function transform(msg, ctx) {
                      const t = ctx.util.metric(msg, 'temperature');
                      const h = ctx.util.metric(msg, 'humidity');
                      if (t && h) ctx.util.setMetric(msg, 'dew_point', ctx.util.round(ctx.util.dewPoint(t.value, h.value), 1), '°C');
                      return msg;
                    }
                    """, Map.of()),
            new Template("milesight-decoder", ScriptKind.DECODE, "Milesight 디코더", "Milesight 채널 바이트(온도·습도)를 해석합니다", """
                    function decode(input, ctx) {
                      const b = ctx.util.bytes.fromBase64(input.payload.data ?? input.payload);
                      const metrics = [];
                      for (let i = 0; i < b.length;) {
                        const channel = b[i++], type = b[i++];
                        if (channel === 0x03 && type === 0x67) { metrics.push({ key: 'temperature', value: ctx.util.bytes.readInt16LE(b, i) / 10, unit: '°C' }); i += 2; }
                        else if (channel === 0x04 && type === 0x68) { metrics.push({ key: 'humidity', value: b[i] / 2, unit: '%' }); i += 1; }
                        else break;
                      }
                      return { externalId: String(input.payload.devEui ?? input.topic.split('/')[3]), measuredAt: input.receivedAt, metrics };
                    }
                    """, Map.of()));

    private ScriptTemplates() {
    }

    public static List<Template> all() {
        return ALL;
    }

    public static Optional<Template> find(String key) {
        return ALL.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    public static String defaultCode(ScriptKind kind) {
        return kind == ScriptKind.DECODE ? DEFAULT_DECODE : DEFAULT_TRANSFORM;
    }

    /** 템플릿 하나(API-SCR-15 응답 항목) */
    public record Template(String key, ScriptKind kind, String name, String description, String code,
                           Map<String, Object> configDefaults) {
        public Template {
            configDefaults = new LinkedHashMap<>(configDefaults);
        }
    }
}
