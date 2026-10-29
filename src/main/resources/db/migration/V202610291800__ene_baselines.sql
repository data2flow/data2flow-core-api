-- ENE-01.04 기준선(API-ENE-12, BR-ENE-08): 범위 공간마다 기준선 하나, 버전은 analytics가 학습(API-ENE-81 → EVT-ENE-02)
-- 학습 기간은 30~730일(끝 날 포함). 실패 사유는 error_code(INSUFFICIENT_DATA·NO_WEATHER·INTERNAL_ERROR)와 needed_days
CREATE TABLE data2flow_core.energy_baselines (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint      NOT NULL,
    scope_space_id    bigint      NOT NULL,
    active_version_id bigint,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_energy_baselines PRIMARY KEY (id),
    CONSTRAINT fk_energy_baselines_scope_space_id FOREIGN KEY (scope_space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT uq_energy_baselines_organization_id_scope_space_id UNIQUE (organization_id, scope_space_id)
);
COMMENT ON TABLE data2flow_core.energy_baselines IS '에너지 기준선(범위 공간·건물·사이트당 1개, 활성 버전 하나)';

CREATE TABLE data2flow_core.energy_baseline_versions (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    organization_id    bigint       NOT NULL,
    baseline_id        bigint       NOT NULL,
    method             varchar(24)  NOT NULL,
    train_from         date         NOT NULL,
    train_to           date         NOT NULL,
    params             jsonb        NOT NULL,
    status             varchar(12)  NOT NULL DEFAULT 'TRAINING',
    r2                 numeric(4,3),
    coefficients       jsonb,
    analytics_model_id varchar(64),
    warning            varchar(200),
    error_code         varchar(32),
    needed_days        integer,
    created_by         bigint       NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_energy_baseline_versions PRIMARY KEY (id),
    CONSTRAINT fk_energy_baseline_versions_baseline_id FOREIGN KEY (baseline_id) REFERENCES data2flow_core.energy_baselines (id) ON DELETE CASCADE,
    CONSTRAINT ck_energy_baseline_versions_method CHECK (method IN ('PERIOD_AVERAGE','DEGREE_DAY_REGRESSION')),
    CONSTRAINT ck_energy_baseline_versions_status CHECK (status IN ('TRAINING','READY','FAILED','ACTIVE','RETIRED')),
    CONSTRAINT ck_energy_baseline_versions_train CHECK ((train_to - train_from) >= 29 AND (train_to - train_from) <= 729)
);
CREATE UNIQUE INDEX uq_energy_baseline_versions_baseline_id_active ON data2flow_core.energy_baseline_versions (baseline_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_energy_baseline_versions_organization_id_baseline_id ON data2flow_core.energy_baseline_versions (organization_id, baseline_id);
COMMENT ON TABLE data2flow_core.energy_baseline_versions IS '기준선 버전. 학습은 analytics, 결과 키는 analytics_model_id(참조(FK 없음): data2flow_analytics.baseline_models.key)';
COMMENT ON COLUMN data2flow_core.energy_baseline_versions.params IS '{coolingBaseC, heatingBaseC, splitWeekday, temperatureSource{deviceId, metricKey}}';

ALTER TABLE data2flow_core.energy_baselines ADD CONSTRAINT fk_energy_baselines_active_version_id
    FOREIGN KEY (active_version_id) REFERENCES data2flow_core.energy_baseline_versions (id) ON DELETE SET NULL;
