-- ENE-01.01·01.02 에너지 설정: 요금 단가, 배출계수, 에너지 원천(계량·추정), 고지서(design/erd/core-environment.md §4.1~4.4)
--   * energy_tariffs의 끝은 같은 범위 다음 행 valid_from − 1일이다(시작일만 겹치지 않으면 기간이 겹치지 않는다). TOU 24시간 커버는 애플리케이션이 검사한다
--   * energy_emission_factors의 organization_id 0 행은 시스템 기본값(수정·삭제 불가, 조직은 새 기간 행만 더한다)
--   * 기본 배출계수 0.4567 tCO2eq/MWh는 화면 설계(UI-ENE-01) 예시 값이다. 고시 문서명·기준 연도는 출시 전에 확인한다(go-live-checklist)

CREATE TABLE data2flow_core.energy_tariffs (
    id                        bigint GENERATED ALWAYS AS IDENTITY,
    organization_id           bigint        NOT NULL,
    scope_space_id            bigint,
    valid_from                date          NOT NULL,
    mode                      varchar(8)    NOT NULL,
    flat_rate_krw_per_kwh     numeric(10,2),
    tou_schedule              jsonb,
    base_charge_krw_per_month numeric(12,0),
    version                   integer       NOT NULL DEFAULT 0,
    updated_by                bigint        NOT NULL,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_energy_tariffs PRIMARY KEY (id),
    CONSTRAINT fk_energy_tariffs_scope_space_id FOREIGN KEY (scope_space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT uq_energy_tariffs_organization_id_scope_space_id_valid_from UNIQUE NULLS NOT DISTINCT (organization_id, scope_space_id, valid_from),
    CONSTRAINT ck_energy_tariffs_mode CHECK (mode IN ('FLAT', 'TOU')),
    CONSTRAINT ck_energy_tariffs_flat CHECK (mode <> 'FLAT' OR flat_rate_krw_per_kwh > 0),
    CONSTRAINT ck_energy_tariffs_tou CHECK (mode <> 'TOU' OR tou_schedule IS NOT NULL)
);

CREATE TABLE data2flow_core.energy_emission_factors (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    organization_id     bigint       NOT NULL DEFAULT 0,
    valid_from          date         NOT NULL,
    factor_tco2_per_mwh numeric(8,4) NOT NULL,
    source_title        varchar(200) NOT NULL,
    source_year         smallint     NOT NULL,
    created_by          bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_energy_emission_factors PRIMARY KEY (id),
    CONSTRAINT uq_energy_emission_factors_organization_id_valid_from UNIQUE (organization_id, valid_from),
    CONSTRAINT ck_energy_emission_factors_factor CHECK (factor_tco2_per_mwh >= 0 AND factor_tco2_per_mwh <= 2)
);

INSERT INTO data2flow_core.energy_emission_factors (organization_id, valid_from, factor_tco2_per_mwh, source_title, source_year, created_by)
VALUES (0, DATE '2020-01-01', 0.4567, '국가 온실가스 배출·흡수계수 — 전력 배출계수(온실가스종합정보센터 고시)', 2023, 0);

CREATE TABLE data2flow_core.energy_sources (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    organization_id    bigint      NOT NULL,
    space_id           bigint      NOT NULL,
    kind               varchar(10) NOT NULL,
    device_id          bigint      NOT NULL,
    metric_key         varchar(64),
    cumulative         boolean,
    covers_descendants boolean     NOT NULL DEFAULT false,
    rated_power_w      integer,
    standby_power_w    integer,
    enabled            boolean     NOT NULL DEFAULT true,
    version            integer     NOT NULL DEFAULT 0,
    created_by         bigint      NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_energy_sources PRIMARY KEY (id),
    CONSTRAINT fk_energy_sources_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_energy_sources_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT ck_energy_sources_kind CHECK (kind IN ('METERED', 'ESTIMATED')),
    CONSTRAINT ck_energy_sources_metered CHECK (kind <> 'METERED' OR metric_key IN ('energy', 'power')),
    CONSTRAINT ck_energy_sources_estimated CHECK (kind <> 'ESTIMATED' OR rated_power_w IS NOT NULL)
);

CREATE UNIQUE INDEX uq_energy_sources_device_id_estimated ON data2flow_core.energy_sources (device_id) WHERE kind = 'ESTIMATED';
CREATE UNIQUE INDEX uq_energy_sources_device_id_metric_key_metered ON data2flow_core.energy_sources (device_id, metric_key) WHERE kind = 'METERED';
CREATE INDEX ix_energy_sources_organization_id_space_id ON data2flow_core.energy_sources (organization_id, space_id);

CREATE TABLE data2flow_core.energy_bills (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    organization_id       bigint        NOT NULL,
    space_id              bigint        NOT NULL,
    month                 date          NOT NULL,
    kwh                   numeric(12,1) NOT NULL,
    amount_krw            numeric(14,0),
    attachment_object_key varchar(300),
    created_by            bigint        NOT NULL,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_energy_bills PRIMARY KEY (id),
    CONSTRAINT fk_energy_bills_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT uq_energy_bills_space_id_month UNIQUE (space_id, month),
    CONSTRAINT ck_energy_bills_month CHECK (EXTRACT(day FROM month) = 1)
);

CREATE INDEX ix_energy_bills_organization_id_month ON data2flow_core.energy_bills (organization_id, month);
