package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.core.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.core.catalog.domain.BuiltinCatalog.MetricDef;
import net.java21.data2flow.core.catalog.domain.BuiltinCatalog.ModelDef;
import net.java21.data2flow.core.catalog.domain.CatalogModels;
import net.java21.data2flow.core.catalog.domain.CatalogModels.MetricFields;
import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelMetric;
import net.java21.data2flow.core.catalog.event.CatalogEvents;
import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import net.java21.data2flow.core.catalog.repository.MetricRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 기본 제공 카탈로그 시드(DEV-03.02, BR-DEV-14, AT-DEV-09.1 "새 조직에는 기본 모델 6종이 측정 항목·단위가 채워진 채로 있다").
 *
 * <p>측정 항목과 모델은 조직 단위라({@code metrics.organization_id}) Flyway 시드 한 번으로 끝나지 않는다. 그래서 애플리케이션이
 * (a) 조직을 만들 때(BootstrapService), (b) 시작할 때 이 배포가 맡는 ACTIVE 조직마다({@link BuiltinCatalogStartupRunner}) 넣는다.
 * 넣기는 멱등이다(ON CONFLICT DO NOTHING): 이미 있는 키·코드는 건드리지 않고(사용자가 고친 정의 값 유지), 빠진 것만 채운다.
 * 같은 코드의 사용자 모델이 이미 있으면 그 모델은 건드리지 않는다. 새로 넣은 행만 설정 변경(METRIC·MODEL)을 보낸다.
 */
@Service
public class BuiltinCatalogSeeder {

    private final DeviceModelRepository models;
    private final MetricRepository metrics;
    private final CatalogEvents events;
    private final Clock clock;

    public BuiltinCatalogSeeder(DeviceModelRepository models, MetricRepository metrics, CatalogEvents events, Clock clock) {
        this.models = models;
        this.metrics = metrics;
        this.events = events;
        this.clock = clock;
    }

    /** 시드 결과(새로 넣은 수) */
    public record SeedResult(int metricsCreated, int modelsCreated) {
    }

    @Transactional
    public SeedResult seed(long organizationId) {
        Instant now = clock.instant();
        int metricsCreated = 0;
        for (MetricDef def : BuiltinCatalog.METRICS) {
            MetricFields fields = new MetricFields(def.displayName(), def.unit(), def.valueType(), null, def.validMin(), def.validMax(),
                    def.precision(), def.aggDefault(), def.stateType(), def.semantic());
            Optional<Long> id = metrics.insert(organizationId, def.key(), fields, CatalogModels.VERIFIED, true, null, now);
            if (id.isPresent()) {
                metricsCreated++;
                events.metricChanged(organizationId, id.get(), 0);
            }
        }
        int modelsCreated = 0;
        for (ModelDef def : BuiltinCatalog.MODELS) {
            Optional<Long> inserted = models.insertBuiltin(organizationId, def.code(), def.vendor(), def.name(), def.protocol(),
                    def.kind(), def.defaultIntervalSec(), def.description(), now);
            Long modelId = inserted.orElseGet(() -> models.findByCode(organizationId, def.code())
                    .filter(CatalogModels.DeviceModel::builtin).map(CatalogModels.DeviceModel::id).orElse(null));
            if (modelId == null) {
                continue;
            }
            List<ModelMetric> metricList = def.metrics();
            for (int i = 0; i < metricList.size(); i++) {
                models.insertMetricIfAbsent(organizationId, modelId, metricList.get(i), now.plusMillis(i));
            }
            if (inserted.isPresent()) {
                modelsCreated++;
                events.modelChanged(organizationId, modelId, 0);
            }
        }
        return new SeedResult(metricsCreated, modelsCreated);
    }
}
