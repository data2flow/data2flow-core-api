-- M6 분석·AI(core 쪽): 분석 정의 색인과 일정 실행(ANA-04.01, BR-ANA-03, DSH-04.04, DEV-10.02). 추가만(ADR-030 expand).
-- 분석 정의·실행·결과의 원천은 data2flow_analytics(analytics 소유)이고, core는 API로 저장을 중계하면서 권한 판정(공간 범위·소유자)과
-- 일정 실행기, 목록·고정 위젯·사이트 쾌적도에 필요한 최소 정보만 이 색인에 둔다. analytics 스키마는 읽지 않는다(API 경유).

CREATE TABLE data2flow_core.analysis_refs (
    id                     bigint GENERATED ALWAYS AS IDENTITY,
    organization_id        bigint        NOT NULL,
    analysis_id            bigint        NOT NULL,
    name                   varchar(100)  NOT NULL,
    template_key           varchar(64)   NOT NULL,
    template_version       varchar(16),
    owner_user_id          bigint        NOT NULL,
    space_scope_ids        bigint[]      NOT NULL DEFAULT '{}',
    target_summary         varchar(200),
    schedule               jsonb,
    schedule_state         varchar(24),
    next_run_at            timestamptz,
    last_scheduled_at      timestamptz,
    realtime               boolean       NOT NULL DEFAULT false,
    last_run_id            bigint,
    last_run_status        varchar(16),
    last_run_finished_at   timestamptz,
    last_succeeded_run_id  bigint,
    last_succeeded_at      timestamptz,
    status                 varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_analysis_refs PRIMARY KEY (id),
    CONSTRAINT uq_analysis_refs_organization_id_analysis_id UNIQUE (organization_id, analysis_id),
    CONSTRAINT ck_analysis_refs_schedule_state CHECK (schedule_state IS NULL OR schedule_state IN ('ACTIVE', 'PAUSED', 'STOPPED_BY_FAILURE')),
    CONSTRAINT ck_analysis_refs_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_analysis_refs_last_run_status CHECK (last_run_status IS NULL
        OR last_run_status IN ('QUEUED', 'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELLED'))
);

CREATE INDEX ix_analysis_refs_organization_id_template_key ON data2flow_core.analysis_refs (organization_id, template_key);
CREATE INDEX ix_analysis_refs_due ON data2flow_core.analysis_refs (next_run_at)
    WHERE status = 'ACTIVE' AND schedule_state = 'ACTIVE';
CREATE INDEX ix_analysis_refs_space_scope_ids ON data2flow_core.analysis_refs USING gin (space_scope_ids);

COMMENT ON TABLE data2flow_core.analysis_refs IS '분석 정의 색인(원천은 data2flow_analytics.analyses, FK 없음). 권한 판정·목록·일정 실행·고정 위젯용. ANA-04.01, BR-ANA-03';
COMMENT ON COLUMN data2flow_core.analysis_refs.analysis_id IS 'data2flow_analytics.analyses.id';
COMMENT ON COLUMN data2flow_core.analysis_refs.space_scope_ids IS '바인딩이 참조하는 공간(기기의 공간 포함). 이 공간을 모두 볼 수 있어야 분석이 보인다(BR-ANA-03)';
COMMENT ON COLUMN data2flow_core.analysis_refs.schedule IS '{preset: DAILY|WEEKLY, at: "HH:mm", weekday?} 또는 {cron: "분 시 일 월 요일"}. 조직 시간대';
COMMENT ON COLUMN data2flow_core.analysis_refs.next_run_at IS '다음 일정 실행 시각. core 1분 실행기가 행 잠금(SKIP LOCKED)으로 한 파드만 run(trigger=SCHEDULE)을 요청한다';
COMMENT ON COLUMN data2flow_core.analysis_refs.last_succeeded_run_id IS '가장 최근 SUCCEEDED 실행(EVT-ANA-01). 고정 위젯·사이트 쾌적도가 읽는다(BR-DSH-11 실패 실행 무시)';
