-- ENE-02.01 가상 센서 설정(API-ENE-20). 공간 부피(면적 × 층고)가 있어야 환기율(ACH)을 켤 수 있다(BR-ENE-09)
ALTER TABLE data2flow_core.spaces ADD COLUMN ceiling_height_m numeric(5,2);
ALTER TABLE data2flow_core.spaces ADD CONSTRAINT ck_spaces_ceiling_height_m
    CHECK (ceiling_height_m IS NULL OR (ceiling_height_m > 0 AND ceiling_height_m <= 100));
COMMENT ON COLUMN data2flow_core.spaces.ceiling_height_m IS '층고(m). 공간 부피 = area_m2 × ceiling_height_m(ENE-02.01 환기량)';

CREATE TABLE data2flow_core.virtual_sensor_config (
    space_id bigint NOT NULL,
    kind varchar(16) NOT NULL,
    organization_id bigint NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    params jsonb NOT NULL,
    output_metrics varchar(64)[] NOT NULL,
    last_status varchar(16),
    last_missing varchar(64)[],
    last_computed_at timestamptz,
    version integer NOT NULL DEFAULT 0,
    updated_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_virtual_sensor_config PRIMARY KEY (space_id, kind),
    CONSTRAINT ck_virtual_sensor_config_kind CHECK (kind IN ('ACH','OCCUPANCY','THERMAL_COMFORT')),
    CONSTRAINT ck_virtual_sensor_config_last_status CHECK (last_status IS NULL OR last_status IN ('OK','INPUT_MISSING')),
    CONSTRAINT fk_virtual_sensor_config_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE
);
CREATE INDEX ix_virtual_sensor_config_organization_id ON data2flow_core.virtual_sensor_config (organization_id) WHERE enabled;
COMMENT ON TABLE data2flow_core.virtual_sensor_config IS '가상 센서 설정(ENE-02). 출력: ACH → ach·ventilation_m3h, OCCUPANCY → occupancy_est, THERMAL_COMFORT → pmv·ppd·discomfort_index';
COMMENT ON COLUMN data2flow_core.virtual_sensor_config.last_status IS '마지막 계산 결과(EVT-ENE-03). 계산 전이면 null';
