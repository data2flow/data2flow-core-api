-- =====================================================================
-- WP-B1 기기 모델·측정 항목(DEV-03.01, DEV-04.02·04.03) 보강. expand 마이그레이션(추가만, ADR-030)
--   * device_models.capabilities: 모델이 지원하는 기능(Capability) 이름 목록 [{capability, constraints}].
--     M2는 이름만 저장하고 검증은 M3(ACT-01.03). ERD의 model_capabilities(ACT 소유)가 생기면 그쪽으로 옮긴다
--   * metric_remap_jobs: 별칭 연결 뒤 과거 시계열 키 바꾸기(BR-DEV-16) 작업의 core 쪽 기록(API-DEV-53 remapJobId,
--     API-DEV-56 진행률). 실제 변환은 pipeline(API-TSD-51)이 하고, 진행률은 남은 별칭 키 행 수로 계산한다
-- 기본 모델 6종·측정 항목 시드는 조직마다 필요해(metrics.organization_id) 마이그레이션이 아니라
-- 애플리케이션 시더(BuiltinCatalogSeeder: 조직 생성 시 + 시작 시 ACTIVE 조직, ON CONFLICT DO NOTHING)가 넣는다
-- =====================================================================

ALTER TABLE data2flow_core.device_models
    ADD COLUMN capabilities jsonb NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN data2flow_core.device_models.capabilities IS '지원 기능(Capability) 이름과 제약 [{capability, constraints}] (DEV-03.01). 검증은 M3 ACT-01.03';

CREATE TABLE data2flow_core.metric_remap_jobs (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    alias           varchar(64)  NOT NULL,
    target_key      varchar(64)  NOT NULL,
    from_ts         timestamptz,
    status          varchar(12)  NOT NULL DEFAULT 'PENDING',
    total           bigint       NOT NULL DEFAULT 0,
    processed       bigint       NOT NULL DEFAULT 0,
    pipeline_job_id varchar(64),
    error           varchar(300),
    requested_by    bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_metric_remap_jobs PRIMARY KEY (id),
    CONSTRAINT ck_metric_remap_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX ix_metric_remap_jobs_organization_id_created_at ON data2flow_core.metric_remap_jobs (organization_id, created_at DESC);

COMMENT ON TABLE data2flow_core.metric_remap_jobs IS '별칭 연결 후 과거 시계열 키 재매핑 작업(BR-DEV-16, API-DEV-53·56). 변환은 pipeline API-TSD-51';

COMMENT ON COLUMN data2flow_core.metric_remap_jobs.from_ts IS '진행률 계산에서 남은 행을 셀 시작 시각(미검증 항목 첫 수신 - 31일). 시계열 전체를 훑지 않게 한다';
