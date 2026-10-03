-- 테스트 전용: data2flow-action이 소유하는 data2flow_action 스키마 중 core-api가 읽는 표(design/erd/ddl/22-action.sql 그대로).
-- core-api는 기기 상태·명령 이력을 읽기만 한다(conventions §6). 통합 테스트가 Testcontainers PostgreSQL에 한 번 만든다.
CREATE SCHEMA IF NOT EXISTS data2flow_action;
SET search_path TO data2flow_action;

-- -----------------------------------------------------------------------------
-- Command
-- -----------------------------------------------------------------------------
CREATE TABLE commands (
    id                    uuid          NOT NULL,
    organization_id       bigint        NOT NULL,
    idempotency_key       char(64)      NOT NULL,
    device_id             bigint        NOT NULL,
    capability            varchar(64)   NOT NULL,
    command               varchar(64)   NOT NULL,
    args                  jsonb         NOT NULL DEFAULT '{}'::jsonb,
    priority              varchar(10)   NOT NULL,
    source                jsonb         NOT NULL,
    source_type           varchar(12)   GENERATED ALWAYS AS ((source ->> 'type')::varchar(12)) STORED,
    source_ref            varchar(64)   GENERATED ALWAYS AS (
                              (COALESCE(source ->> 'sceneRunId', source ->> 'bulkJobId', source ->> 'flowId'))::varchar(64)
                          ) STORED,
    status                varchar(24)   NOT NULL DEFAULT 'REQUESTED',
    status_reason         varchar(32),
    valid_until           timestamptz   NOT NULL,
    execute_after         timestamptz,
    expected_delivery_at  timestamptz,
    attempts              integer       NOT NULL DEFAULT 0,
    driver_response       jsonb,
    requested_at          timestamptz   NOT NULL DEFAULT now(),
    sent_at               timestamptz,
    acked_at              timestamptz,
    applied_at            timestamptz,
    finished_at           timestamptz,
    CONSTRAINT pk_commands PRIMARY KEY (id),
    CONSTRAINT uq_commands_org_idempotency_key UNIQUE (organization_id, idempotency_key),
    CONSTRAINT ck_commands_priority CHECK (priority IN ('MANUAL','SAFETY','SCHEDULE','AUTO','AI')),
    CONSTRAINT ck_commands_source_type CHECK (source_type IN ('USER','FLOW','RULE','AI','SCHEDULE','SCENE','BULK','SYSTEM')),
    CONSTRAINT ck_commands_status CHECK (status IN (
        'REQUESTED','REJECTED','BLOCKED','SKIPPED','DELAYED','QUEUED','QUEUED_FOR_DOWNLINK',
        'SENT','ACKED','APPLIED','TIMEOUT','FAILED','SUPERSEDED','CANCELLED'))
);
CREATE INDEX ix_commands_org_device_requested ON commands (organization_id, device_id, requested_at DESC, id);
CREATE INDEX ix_commands_org_requested ON commands (organization_id, requested_at DESC, id);
CREATE INDEX ix_commands_active ON commands (device_id, capability)
    WHERE status IN ('REQUESTED','DELAYED','QUEUED','QUEUED_FOR_DOWNLINK','SENT','ACKED');
CREATE INDEX ix_commands_due ON commands (execute_after) WHERE status = 'DELAYED';
CREATE INDEX ix_commands_source_ref ON commands (source_type, source_ref) WHERE source_ref IS NOT NULL;
COMMENT ON TABLE commands IS '제어 창구(Control Facade)를 거친 명령. 화면·플로우·AI·예약 모두 여기로 모임(ADR-009)';
COMMENT ON COLUMN commands.idempotency_key IS '사용자: Idempotency-Key, 플로우: sha256(flowId,nodeId,triggerMessageId)(BR-FLW-13)';
COMMENT ON COLUMN commands.priority IS '출처가 정하는 우선순위(BR-ACT-24)';
COMMENT ON COLUMN commands.device_id IS 'data2flow_core.devices.id (스키마 간 FK 없음)';
COMMENT ON COLUMN commands.valid_until IS '대기열 유효 시간. 지나면 FAILED(EXPIRED)';

CREATE TABLE command_events (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    command_id       uuid          NOT NULL,
    organization_id  bigint        NOT NULL,
    at               timestamptz   NOT NULL DEFAULT now(),
    from_status      varchar(24),
    to_status        varchar(24)   NOT NULL,
    reason           varchar(32),
    detail           jsonb,
    CONSTRAINT pk_command_events PRIMARY KEY (id),
    CONSTRAINT fk_command_events_command FOREIGN KEY (command_id) REFERENCES commands (id) ON DELETE CASCADE
);
CREATE INDEX ix_command_events_command_at ON command_events (command_id, at);
COMMENT ON TABLE command_events IS '명령 상태 전이 타임라인';

CREATE TABLE device_shadows (
    device_id           bigint        NOT NULL,
    organization_id     bigint        NOT NULL,
    desired             jsonb         NOT NULL DEFAULT '{}'::jsonb,
    desired_version     bigint        NOT NULL DEFAULT 0,
    desired_updated_at  timestamptz,
    desired_source      jsonb,
    reported            jsonb         NOT NULL DEFAULT '{}'::jsonb,
    reported_version    bigint        NOT NULL DEFAULT 0,
    reported_at         timestamptz,
    delta               jsonb         NOT NULL DEFAULT '{}'::jsonb,
    connectivity        varchar(8)    NOT NULL DEFAULT 'UNKNOWN',
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_shadows PRIMARY KEY (device_id),
    CONSTRAINT ck_device_shadows_connectivity CHECK (connectivity IN ('UNKNOWN','ONLINE','OFFLINE'))
);
CREATE INDEX ix_device_shadows_org ON device_shadows (organization_id);
COMMENT ON TABLE device_shadows IS '기기의 원하는 상태(desired)·보고된 상태(reported)·차이(delta)';
COMMENT ON COLUMN device_shadows.reported_version IS '이보다 작은 보고는 버림(BR-ACT-05)';

CREATE TABLE device_state_history (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint        NOT NULL,
    device_id        bigint        NOT NULL,
    capability       varchar(64)   NOT NULL,
    attribute        varchar(64)   NOT NULL,
    value            jsonb         NOT NULL,
    label            varchar(64),
    valid_from       timestamptz   NOT NULL,
    valid_to         timestamptz,
    source           jsonb,
    command_id       uuid,
    CONSTRAINT pk_device_state_history PRIMARY KEY (id, valid_from),
    CONSTRAINT ck_device_state_history_range CHECK (valid_to IS NULL OR valid_to >= valid_from)
) PARTITION BY RANGE (valid_from);
CREATE TABLE device_state_history_default PARTITION OF device_state_history DEFAULT;
CREATE TABLE device_state_history_y2026m10 PARTITION OF device_state_history
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
CREATE INDEX ix_device_state_history_device_from ON device_state_history (device_id, capability, valid_from DESC);
COMMENT ON TABLE device_state_history IS '액추에이터 상태 구간(API-TSD-05 actuator). 월 파티션, 스케줄러가 생성·정리. 컬럼은 API 응답에서 도출(확인 필요)';

RESET search_path;
