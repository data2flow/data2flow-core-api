-- =====================================================================
-- M3 가상 폐루프(FLW·ACT·SIM·DEV-03.03) core-api 테이블. 추가만(expand, ADR-030): staging migrate, prod validate.
--   * 제어 정의(ACT-01·03·06.04, DEV-03.03): capabilities(사용자 정의 custom.*), drivers, driver_bindings, control_settings
--     표준 기능 7종은 contracts JSON이 정본이라 행으로 넣지 않는다(StandardCapabilities). 모델의 지원 기능·제약은 M2의
--     device_models.capabilities jsonb [{capability, constraints}]를 그대로 쓰고 M3부터 검증한다(ERD model_capabilities는 미룸)
--   * 플로우 정의(FLW-01·05.06): flows, flow_versions, flow_overlays, flow_version_approvals, flow_apply_reports(엔진 적용 보고 사본)
--   * 가상 환경(SIM-01.01·07.03·07.05): spaces.is_virtual·sandbox, sim_purge_jobs
-- flow_id는 UUID 허용 목록(ERD README §4: 조직 사이 승격·Git 동기화에서 같은 ID)
-- =====================================================================

-- ---------------------------------------------------------------- 가상 공간·샌드박스(SIM-01.01, SIM-07.03)
ALTER TABLE data2flow_core.spaces ADD COLUMN IF NOT EXISTS is_virtual boolean NOT NULL DEFAULT false;
ALTER TABLE data2flow_core.spaces ADD COLUMN IF NOT EXISTS sandbox boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN data2flow_core.spaces.is_virtual IS '가상 공간(SIM-01.01). 가상 공간 아래에는 실제 공간을 둘 수 없다(TC-SIM-001). 물리 설정은 data2flow_sim.space_physics';
COMMENT ON COLUMN data2flow_core.spaces.sandbox IS '샌드박스(SIM-07.03, API-SIM-24) 사본. action이 내부 API로 읽어 실제 기기 명령을 거부한다(BR-SIM-12)';

-- ---------------------------------------------------------------- 사용자 정의 기능(ACT-01.01, API-ACT-25)
CREATE TABLE data2flow_core.capabilities (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint       NOT NULL,
    name             varchar(64)  NOT NULL,
    version_no       integer      NOT NULL DEFAULT 1,
    standard         boolean      NOT NULL DEFAULT false,
    attributes       jsonb        NOT NULL,
    commands         jsonb        NOT NULL,
    expected_effects jsonb,
    matter_cluster   varchar(64),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_capabilities PRIMARY KEY (id),
    CONSTRAINT uq_capabilities_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_capabilities_name CHECK (standard OR name LIKE 'custom.%')
);
COMMENT ON TABLE data2flow_core.capabilities IS '조직의 사용자 정의 기능 custom.*(BR-ACT-22). 표준 7종은 contracts capabilities/*.json(행 없음)';

-- ---------------------------------------------------------------- 드라이버(ACT-03, API-ACT-30)
CREATE TABLE data2flow_core.drivers (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint       NOT NULL,
    name              varchar(100) NOT NULL,
    type              varchar(20)  NOT NULL,
    config            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    secret_enc        bytea,
    polling_sec       integer      NOT NULL DEFAULT 60,
    ack_timeout_sec   integer      NOT NULL DEFAULT 30,
    apply_timeout_sec integer      NOT NULL DEFAULT 60,
    retry             jsonb        NOT NULL DEFAULT '{"maxAttempts":3,"initialMs":1000,"multiplier":2,"maxMs":10000}'::jsonb,
    circuit           jsonb        NOT NULL DEFAULT '{"failureRate":0.5,"windowSec":60,"openSec":30}'::jsonb,
    status            varchar(12)  NOT NULL DEFAULT 'UNTESTED',
    capabilities      text[]       NOT NULL DEFAULT '{}',
    version           integer      NOT NULL DEFAULT 0,
    created_by        bigint       NOT NULL,
    updated_by        bigint       NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_drivers PRIMARY KEY (id),
    CONSTRAINT uq_drivers_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_drivers_type CHECK (type IN ('VIRTUAL', 'MQTT', 'LORAWAN', 'LG_THINQ', 'SMARTTHINGS')),
    CONSTRAINT ck_drivers_status CHECK (status IN ('OK', 'CIRCUIT_OPEN', 'ERROR', 'UNTESTED')),
    CONSTRAINT ck_drivers_polling_sec CHECK (polling_sec >= 0),
    CONSTRAINT ck_drivers_timeouts CHECK (ack_timeout_sec BETWEEN 1 AND 3600 AND apply_timeout_sec BETWEEN 1 AND 3600)
);
COMMENT ON TABLE data2flow_core.drivers IS '제어 드라이버 정의(ACT-03.01·03.02). 실행은 action. secret_enc는 SecretCipher(context data2flow_core.drivers.secret:{id})';

-- 모델 ↔ 드라이버 연결(DEV-03.03, API-ACT-31 PUT /device-models/{id}/driver). 모델당 하나
CREATE TABLE data2flow_core.driver_bindings (
    model_id          bigint      NOT NULL,
    driver_id         bigint      NOT NULL,
    organization_id   bigint      NOT NULL,
    encoder_script_id bigint,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_driver_bindings PRIMARY KEY (model_id, driver_id),
    CONSTRAINT uq_driver_bindings_model_id UNIQUE (model_id),
    CONSTRAINT fk_driver_bindings_model_id FOREIGN KEY (model_id) REFERENCES data2flow_core.device_models (id) ON DELETE CASCADE,
    CONSTRAINT fk_driver_bindings_driver_id FOREIGN KEY (driver_id) REFERENCES data2flow_core.drivers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_driver_bindings_encoder_script_id FOREIGN KEY (encoder_script_id) REFERENCES data2flow_core.scripts (id) ON DELETE RESTRICT
);
CREATE INDEX ix_driver_bindings_organization_id_driver_id ON data2flow_core.driver_bindings (organization_id, driver_id);

-- ---------------------------------------------------------------- 조직 제어 설정(ACT-06.04 절대 한계, API-ACT-17). 조직당 한 행
CREATE TABLE data2flow_core.control_settings (
    organization_id                    bigint      NOT NULL,
    absolute_limits                    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    manual_override_minutes            integer     NOT NULL DEFAULT 30,
    min_interval_sec                   integer     NOT NULL DEFAULT 10,
    oscillation                        jsonb       NOT NULL DEFAULT '{"windowSec":60,"flips":3}'::jsonb,
    default_validity_sec               integer     NOT NULL DEFAULT 600,
    schedule_respects_manual_override  boolean     NOT NULL DEFAULT true,
    require_approval_for_control_nodes boolean     NOT NULL DEFAULT false,
    version                            integer     NOT NULL DEFAULT 0,
    updated_by                         bigint      NOT NULL DEFAULT 0,
    updated_at                         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_control_settings PRIMARY KEY (organization_id),
    CONSTRAINT ck_control_settings_manual_override_minutes CHECK (manual_override_minutes >= 0),
    CONSTRAINT ck_control_settings_min_interval_sec CHECK (min_interval_sec >= 0),
    CONSTRAINT ck_control_settings_default_validity_sec CHECK (default_validity_sec BETWEEN 60 AND 3600)
);
COMMENT ON COLUMN data2flow_core.control_settings.absolute_limits IS '{기능: {속성: {min, max, enum}}} (BR-ACT-09). 모델 범위보다 넓으면 LIMIT_WIDER_THAN_MODEL';

-- ---------------------------------------------------------------- 플로우(FLW-01, API-FLW-01~11·24)
CREATE TABLE data2flow_core.flows (
    id                     uuid          NOT NULL,
    organization_id        bigint        NOT NULL,
    name                   varchar(100)  NOT NULL,
    purpose                varchar(200),
    description            text,
    kind                   varchar(16)   NOT NULL DEFAULT 'FLOW',
    source_rule_id         bigint,
    status                 varchar(20)   NOT NULL DEFAULT 'DRAFT',
    status_reason          varchar(32),
    environment            varchar(8)    NOT NULL DEFAULT 'PROD',
    active_version         integer,
    draft_version          integer,
    pause_mode             varchar(8)    NOT NULL DEFAULT 'DROP',
    auto_pause_on_degraded boolean       NOT NULL DEFAULT false,
    error_rate_threshold   numeric(5,2)  NOT NULL DEFAULT 10.00,
    rate_limit_per_sec     integer       NOT NULL DEFAULT 100,
    catch_flow_id          uuid,
    promoted_from_flow_id  uuid,
    owner_user_id          bigint,
    related_space_ids      bigint[]      NOT NULL DEFAULT '{}',
    tags                   text[]        NOT NULL DEFAULT '{}',
    version                integer       NOT NULL DEFAULT 0,
    created_by             bigint        NOT NULL,
    updated_by             bigint        NOT NULL,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_flows PRIMARY KEY (id),
    CONSTRAINT ck_flows_kind CHECK (kind IN ('FLOW', 'RULE', 'CATCH')),
    CONSTRAINT ck_flows_source_rule_id CHECK (kind <> 'RULE' OR source_rule_id IS NOT NULL),
    CONSTRAINT ck_flows_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'DEGRADED', 'DISABLED', 'DELETED')),
    CONSTRAINT ck_flows_environment CHECK (environment IN ('PROD', 'TEST')),
    CONSTRAINT ck_flows_pause_mode CHECK (pause_mode IN ('DROP', 'BUFFER')),
    CONSTRAINT ck_flows_rate_limit_per_sec CHECK (rate_limit_per_sec BETWEEN 1 AND 1000),
    CONSTRAINT ck_flows_description CHECK (description IS NULL OR char_length(description) <= 4000),
    CONSTRAINT fk_flows_catch_flow_id FOREIGN KEY (catch_flow_id) REFERENCES data2flow_core.flows (id) ON DELETE SET NULL
);
-- 삭제(DELETED)한 플로우 이름은 다시 쓸 수 있다
CREATE UNIQUE INDEX uq_flows_organization_id_name ON data2flow_core.flows (organization_id, name) WHERE status <> 'DELETED';
CREATE INDEX ix_flows_organization_id_status ON data2flow_core.flows (organization_id, status);
COMMENT ON TABLE data2flow_core.flows IS '플로우 정의 머리(FLW-01). 실행 상태는 data2flow_flow(flow-engine). version은 낙관적 잠금(설정 PATCH)';

CREATE TABLE data2flow_core.flow_versions (
    flow_id            uuid         NOT NULL,
    version_no         integer      NOT NULL,
    organization_id    bigint       NOT NULL,
    state              varchar(20)  NOT NULL DEFAULT 'DRAFT',
    base_version       integer,
    definition         jsonb        NOT NULL,
    definition_hash    char(64)     NOT NULL,
    rate_limit_per_sec integer      NOT NULL DEFAULT 100,
    validation         jsonb,
    change_summary     jsonb,
    has_control_node   boolean      NOT NULL DEFAULT false,
    memo               varchar(500),
    imported_from      jsonb,
    applied_by         bigint,
    applied_at         timestamptz,
    created_by         bigint       NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_flow_versions PRIMARY KEY (flow_id, version_no),
    CONSTRAINT ck_flow_versions_state CHECK (state IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'ARCHIVED', 'REJECTED')),
    CONSTRAINT ck_flow_versions_rate_limit_per_sec CHECK (rate_limit_per_sec BETWEEN 1 AND 1000),
    CONSTRAINT ck_flow_versions_definition_size CHECK (pg_column_size(definition) <= 2097152),
    CONSTRAINT fk_flow_versions_flow_id FOREIGN KEY (flow_id) REFERENCES data2flow_core.flows (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_flow_versions_flow_id_active ON data2flow_core.flow_versions (flow_id) WHERE state = 'ACTIVE';
CREATE INDEX ix_flow_versions_organization_id_flow_id ON data2flow_core.flow_versions (organization_id, flow_id);

CREATE TABLE data2flow_core.flow_overlays (
    flow_id         uuid        NOT NULL,
    organization_id bigint      NOT NULL,
    bypass          text[]      NOT NULL DEFAULT '{}',
    debug           text[]      NOT NULL DEFAULT '{}',
    revision        integer     NOT NULL DEFAULT 0,
    updated_by      bigint      NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_flow_overlays PRIMARY KEY (flow_id),
    CONSTRAINT fk_flow_overlays_flow_id FOREIGN KEY (flow_id) REFERENCES data2flow_core.flows (id) ON DELETE CASCADE
);
CREATE INDEX ix_flow_overlays_organization_id_flow_id ON data2flow_core.flow_overlays (organization_id, flow_id);

CREATE TABLE data2flow_core.flow_version_approvals (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    flow_id         uuid         NOT NULL,
    version_no      integer      NOT NULL,
    kind            varchar(8)   NOT NULL,
    memo            varchar(500),
    requested_by    bigint       NOT NULL,
    requested_at    timestamptz  NOT NULL DEFAULT now(),
    decision        varchar(8),
    decided_by      bigint,
    decided_at      timestamptz,
    reason          varchar(500),
    CONSTRAINT pk_flow_version_approvals PRIMARY KEY (id),
    CONSTRAINT ck_flow_version_approvals_kind CHECK (kind IN ('APPLY', 'PROMOTE')),
    CONSTRAINT ck_flow_version_approvals_decision CHECK (decision IS NULL OR decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_flow_version_approvals_reason CHECK (decision IS DISTINCT FROM 'REJECTED' OR reason IS NOT NULL),
    CONSTRAINT fk_flow_version_approvals_flow_version FOREIGN KEY (flow_id, version_no)
        REFERENCES data2flow_core.flow_versions (flow_id, version_no) ON DELETE CASCADE
);
CREATE INDEX ix_flow_version_approvals_organization_id_pending ON data2flow_core.flow_version_approvals (organization_id, requested_at)
    WHERE decision IS NULL;
CREATE INDEX ix_flow_version_approvals_organization_id_flow_id ON data2flow_core.flow_version_approvals (organization_id, flow_id);

-- 엔진 인스턴스 적용 보고(EVT-FLW-02) 사본: API-FLW-02 applyStatus. 원천은 data2flow_flow.flow_instance_versions(core는 읽지 않음)
CREATE TABLE data2flow_core.flow_apply_reports (
    flow_id          uuid         NOT NULL,
    instance_id      varchar(100) NOT NULL,
    organization_id  bigint       NOT NULL,
    applied_version  integer      NOT NULL,
    overlay_revision integer,
    compile_ms       integer,
    error            varchar(500),
    reported_at      timestamptz  NOT NULL,
    CONSTRAINT pk_flow_apply_reports PRIMARY KEY (flow_id, instance_id),
    CONSTRAINT fk_flow_apply_reports_flow_id FOREIGN KEY (flow_id) REFERENCES data2flow_core.flows (id) ON DELETE CASCADE
);
CREATE INDEX ix_flow_apply_reports_organization_id_flow_id ON data2flow_core.flow_apply_reports (organization_id, flow_id);

-- ---------------------------------------------------------------- 가상 데이터 정리 작업(SIM-07.05, API-SIM-25)
CREATE TABLE data2flow_core.sim_purge_jobs (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    origin          varchar(10)  NOT NULL,
    run_ids         text[]       NOT NULL DEFAULT '{}',
    range_from      timestamptz,
    range_to        timestamptz,
    space_ids       bigint[]     NOT NULL DEFAULT '{}',
    status          varchar(20)  NOT NULL DEFAULT 'ACCEPTED',
    requested_by    bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sim_purge_jobs PRIMARY KEY (id),
    CONSTRAINT ck_sim_purge_jobs_origin CHECK (origin IN ('USER', 'RETENTION')),
    CONSTRAINT ck_sim_purge_jobs_status CHECK (status IN ('ACCEPTED', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_sim_purge_jobs_range CHECK (range_from IS NULL OR range_to IS NULL OR range_from < range_to)
);
CREATE INDEX ix_sim_purge_jobs_organization_id_created_at ON data2flow_core.sim_purge_jobs (organization_id, created_at DESC);
COMMENT ON TABLE data2flow_core.sim_purge_jobs IS '가상 데이터 정리 요청 기록(API-SIM-25 사용자, 내부 API simulator 보관 기한). 실제 행 삭제는 소유 서비스(pipeline·action)의 정리 API가 생기면 넘긴다';
