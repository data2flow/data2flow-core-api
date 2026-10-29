-- CMP-02.04: 준수 리포트 정기 발행(design/erd/core-environment.md, design/erd/ddl/13-core-environment.sql).
-- ERD와 다른 점:
--   * compliance_report_schedule_runs.trigger: SCHEDULED(예약) / MANUAL(지금 실행, API-CMP-21 run-now)
--   * compliance_report_schedule_runs.report_id·revision_no: API-CMP-22 응답(reportId, revisionNo)
--   * compliance_report_schedule_runs.deliveries: 수신자별 요청 결과 [{recipient, status}] (API-CMP-22 deliveries)
--   * 실행 상태는 PENDING(생성 요청 전) → RUNNING(생성 중) → SUCCEEDED / FAILED. 생성 실패는 3회까지 다시 요청한다(UC-CMP-09 3a)
CREATE TABLE data2flow_core.compliance_report_schedules (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(100) NOT NULL,
    report_template jsonb        NOT NULL,
    cadence         varchar(16)  NOT NULL,
    day_of_period   smallint     NOT NULL,
    publish_time    time         NOT NULL,
    period_rule     varchar(24)  NOT NULL,
    recipients      jsonb        NOT NULL,
    format          varchar(8)   NOT NULL DEFAULT 'PDF',
    enabled         boolean      NOT NULL DEFAULT true,
    next_run_at     timestamptz,
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint       NOT NULL,
    updated_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_compliance_report_schedules PRIMARY KEY (id),
    CONSTRAINT ck_compliance_report_schedules_cadence CHECK (cadence IN ('MONTHLY','QUARTERLY')),
    CONSTRAINT ck_compliance_report_schedules_day_of_period CHECK (day_of_period BETWEEN 1 AND 28),
    CONSTRAINT ck_compliance_report_schedules_period_rule CHECK (period_rule IN ('PREVIOUS_MONTH','PREVIOUS_QUARTER')),
    CONSTRAINT ck_compliance_report_schedules_format CHECK (format IN ('PDF','PDF_CSV'))
);
CREATE INDEX ix_compliance_report_schedules_next_run_at_enabled ON data2flow_core.compliance_report_schedules (next_run_at) WHERE enabled;
CREATE INDEX ix_compliance_report_schedules_organization_id ON data2flow_core.compliance_report_schedules (organization_id);
COMMENT ON TABLE data2flow_core.compliance_report_schedules IS '준수 리포트 정기 발행(CMP-02.04). publish_time은 조직 시간대';
COMMENT ON COLUMN data2flow_core.compliance_report_schedules.report_template IS '{type, title?, spaceIds, facilityType, options}';
COMMENT ON COLUMN data2flow_core.compliance_report_schedules.recipients IS '{"userIds":[..],"emails":[..]}';

CREATE TABLE data2flow_core.compliance_report_schedule_runs (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    schedule_id     bigint       NOT NULL,
    scheduled_for   timestamptz  NOT NULL,
    trigger         varchar(12)  NOT NULL DEFAULT 'SCHEDULED',
    status          varchar(12)  NOT NULL DEFAULT 'PENDING',
    attempts        integer      NOT NULL DEFAULT 0,
    report_id       bigint,
    revision_id     bigint,
    revision_no     integer,
    deliveries      jsonb        NOT NULL DEFAULT '[]',
    error           varchar(500),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_compliance_report_schedule_runs PRIMARY KEY (id),
    CONSTRAINT uq_compliance_report_schedule_runs_schedule_id_scheduled_for UNIQUE (schedule_id, scheduled_for),
    CONSTRAINT ck_compliance_report_schedule_runs_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_compliance_report_schedule_runs_trigger CHECK (trigger IN ('SCHEDULED','MANUAL')),
    CONSTRAINT fk_compliance_report_schedule_runs_schedule_id FOREIGN KEY (schedule_id) REFERENCES data2flow_core.compliance_report_schedules (id) ON DELETE CASCADE,
    CONSTRAINT fk_compliance_report_schedule_runs_revision_id FOREIGN KEY (revision_id) REFERENCES data2flow_core.compliance_report_revisions (id) ON DELETE SET NULL
);
CREATE INDEX ix_compliance_report_schedule_runs_active ON data2flow_core.compliance_report_schedule_runs (organization_id, status)
    WHERE status IN ('PENDING', 'RUNNING');
COMMENT ON TABLE data2flow_core.compliance_report_schedule_runs IS '정기 발행 실행 기록(API-CMP-22). (schedule_id, scheduled_for)로 같은 회차를 두 번 만들지 않는다';
