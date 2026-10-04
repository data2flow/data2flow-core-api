-- =====================================================================
-- M4 자동화 완성(core-api): 규칙·알람·알림 정책·무음·당직·사용자 수신 설정·메신저 연결,
-- 인터락·비상 정지·장면·예약 제어, Sink 연결.
-- 정본 DDL: data2flow-docs design/erd/ddl/12-core-data-automation.sql §RUL·§ACT·sink_connections.
-- 추가만 한다(ADR-030 expand): 기존 표는 FK 하나(flows.source_rule_id)와 색인만 더한다.
-- 문서와 다른 점(design/erd/core-data-automation.md §11에 적음):
--   * alarms.last_notified_at·notify_seq: 재알림 간격(BR-RUL-13)과 알림 멱등 키 eventSeq
--   * user_notify_prefs.channels·min_severity: 사용자별 수신 채널·최소 심각도(OPS-06.05)
--   * messenger_link_codes: 메신저 계정 연결 일회용 코드(API-RUL-30, 10분, 해시만 저장)
-- =====================================================================

-- ---------------------------------------------------------------- 규칙(RUL-01)
CREATE TABLE data2flow_core.rule_templates (
    id                        bigint GENERATED ALWAYS AS IDENTITY,
    organization_id           bigint       NOT NULL DEFAULT 0,
    template_key              varchar(64)  NOT NULL,
    name                      varchar(100) NOT NULL,
    description               varchar(500),
    category                  varchar(16)  NOT NULL,
    params_schema             jsonb        NOT NULL,
    condition_template        jsonb        NOT NULL,
    default_severity          varchar(10)  NOT NULL,
    source_profile_version_id bigint,
    builtin                   boolean      NOT NULL DEFAULT false,
    status                    varchar(10)  NOT NULL DEFAULT 'ACTIVE',
    version                   integer      NOT NULL DEFAULT 0,
    created_at                timestamptz  NOT NULL DEFAULT now(),
    updated_at                timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_rule_templates PRIMARY KEY (id),
    CONSTRAINT uq_rule_templates_organization_id_template_key UNIQUE (organization_id, template_key),
    CONSTRAINT ck_rule_templates_category CHECK (category IN ('COMFORT','SAFETY','ENERGY','EQUIPMENT','COMPLIANCE')),
    CONSTRAINT ck_rule_templates_default_severity CHECK (default_severity IN ('CRITICAL','MAJOR','MINOR','WARNING','INFO')),
    CONSTRAINT ck_rule_templates_status CHECK (status IN ('ACTIVE','DEPRECATED'))
);
COMMENT ON TABLE data2flow_core.rule_templates IS '규칙 템플릿(RUL-01.10). 시스템 제공은 organization_id=0·builtin';

CREATE TABLE data2flow_core.notification_policies (
    id                   bigint GENERATED ALWAYS AS IDENTITY,
    organization_id      bigint       NOT NULL,
    name                 varchar(100) NOT NULL,
    min_severity         varchar(10)  NOT NULL,
    space_id             bigint,
    include_children     boolean      NOT NULL DEFAULT true,
    rule_ids             bigint[],
    time_window          jsonb,
    recipients           jsonb        NOT NULL,
    channels             text[]       NOT NULL,
    templates            jsonb,
    renotify_minutes     integer      NOT NULL DEFAULT 30,
    aggregate_window_sec integer      NOT NULL DEFAULT 0,
    notify_on_clear      boolean      NOT NULL DEFAULT true,
    version              integer      NOT NULL DEFAULT 0,
    created_by           bigint       NOT NULL,
    updated_by           bigint       NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_notification_policies PRIMARY KEY (id),
    CONSTRAINT uq_notification_policies_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_notification_policies_min_severity CHECK (min_severity IN ('CRITICAL','MAJOR','MINOR','WARNING','INFO')),
    CONSTRAINT ck_notification_policies_recipients CHECK (jsonb_typeof(recipients) = 'array' AND jsonb_array_length(recipients) >= 1),
    CONSTRAINT ck_notification_policies_renotify_minutes CHECK (renotify_minutes BETWEEN 10 AND 1440),
    CONSTRAINT ck_notification_policies_aggregate_window_sec CHECK (aggregate_window_sec = 0 OR aggregate_window_sec BETWEEN 60 AND 600),
    CONSTRAINT fk_notification_policies_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT
);
COMMENT ON TABLE data2flow_core.notification_policies IS '알림 정책(RUL-03). channels는 WEB + 채널 SPI 키(현재 TELEGRAM, ADR-033). recipients = [{type: USER|ROLE|ON_CALL, id}]';

CREATE TABLE data2flow_core.policy_steps (
    policy_id       bigint   NOT NULL,
    step_no         smallint NOT NULL,
    organization_id bigint   NOT NULL,
    wait_minutes    integer  NOT NULL,
    recipients      jsonb    NOT NULL,
    CONSTRAINT pk_policy_steps PRIMARY KEY (policy_id, step_no),
    CONSTRAINT ck_policy_steps_step_no CHECK (step_no BETWEEN 1 AND 3),
    CONSTRAINT ck_policy_steps_wait_minutes CHECK (wait_minutes > 0),
    CONSTRAINT fk_policy_steps_policy_id FOREIGN KEY (policy_id) REFERENCES data2flow_core.notification_policies (id) ON DELETE CASCADE
);
COMMENT ON TABLE data2flow_core.policy_steps IS '에스컬레이션 단계(1~3). 확인(ACK)되면 남은 단계 취소(RUL-03.03, 실행은 action)';

CREATE TABLE data2flow_core.rules (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint       NOT NULL,
    name             varchar(100) NOT NULL,
    template_key     varchar(64),
    status           varchar(12)  NOT NULL DEFAULT 'ACTIVE',
    error_reason     varchar(32),
    scope_type       varchar(12)  NOT NULL,
    scope_ids        jsonb        NOT NULL,
    include_children boolean      NOT NULL DEFAULT true,
    condition        jsonb        NOT NULL,
    time_condition   jsonb,
    severity         varchar(10)  NOT NULL,
    title_template   varchar(200) NOT NULL,
    auto_clear       boolean      NOT NULL DEFAULT true,
    policy_id        bigint,
    flow_id          uuid         NOT NULL,
    target_count     integer      NOT NULL DEFAULT 0,
    version          integer      NOT NULL DEFAULT 0,
    created_by       bigint       NOT NULL,
    updated_by       bigint       NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_rules PRIMARY KEY (id),
    CONSTRAINT ck_rules_status CHECK (status IN ('ACTIVE','INACTIVE','ERROR','CONVERTED','DELETED')),
    CONSTRAINT ck_rules_error_reason CHECK (error_reason IS NULL OR error_reason IN ('NO_TARGET','METRIC_DELETED','FLOW_ERROR')),
    CONSTRAINT ck_rules_scope_type CHECK (scope_type IN ('DEVICE','SPACE','MODEL','TAG')),
    CONSTRAINT ck_rules_scope_ids CHECK (jsonb_typeof(scope_ids) = 'array' AND jsonb_array_length(scope_ids) > 0),
    CONSTRAINT ck_rules_severity CHECK (severity IN ('CRITICAL','MAJOR','MINOR','WARNING','INFO')),
    CONSTRAINT ck_rules_target_count CHECK (target_count BETWEEN 0 AND 5000),
    CONSTRAINT fk_rules_flow_id FOREIGN KEY (flow_id) REFERENCES data2flow_core.flows (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rules_policy_id FOREIGN KEY (policy_id) REFERENCES data2flow_core.notification_policies (id) ON DELETE SET NULL
);
-- 삭제(DELETED)한 규칙 이름은 다시 쓸 수 있다(문서의 UNIQUE(organization_id, name)를 부분 색인으로)
CREATE UNIQUE INDEX uq_rules_organization_id_name ON data2flow_core.rules (organization_id, name) WHERE status <> 'DELETED';
CREATE INDEX ix_rules_organization_id_status ON data2flow_core.rules (organization_id, status);
COMMENT ON TABLE data2flow_core.rules IS '규칙. 저장하면 내부 플로우(kind=RULE)로 컴파일(ADR-005). version은 저장마다 증가(낙관적 잠금 겸 플로우 버전 대응)';

ALTER TABLE data2flow_core.flows
    ADD CONSTRAINT fk_flows_source_rule_id FOREIGN KEY (source_rule_id) REFERENCES data2flow_core.rules (id) ON DELETE SET NULL;

CREATE TABLE data2flow_core.rule_tuning_suggestions (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    rule_id         bigint      NOT NULL,
    problem         varchar(16) NOT NULL,
    current         jsonb       NOT NULL,
    proposed        jsonb       NOT NULL,
    simulation      jsonb,
    status          varchar(10) NOT NULL DEFAULT 'OPEN',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_rule_tuning_suggestions PRIMARY KEY (id),
    CONSTRAINT ck_rule_tuning_suggestions_problem CHECK (problem IN ('TOO_FREQUENT','FLAPPING','UNACKNOWLEDGED')),
    CONSTRAINT ck_rule_tuning_suggestions_status CHECK (status IN ('OPEN','APPLIED','DISMISSED')),
    CONSTRAINT fk_rule_tuning_suggestions_rule_id FOREIGN KEY (rule_id) REFERENCES data2flow_core.rules (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_rule_tuning_suggestions_open ON data2flow_core.rule_tuning_suggestions (rule_id, problem) WHERE status = 'OPEN';
COMMENT ON TABLE data2flow_core.rule_tuning_suggestions IS '규칙 튜닝 제안(RUL-06.02). 적용 전까지 규칙을 바꾸지 않는다(BR-RUL-22)';

-- ---------------------------------------------------------------- 알람(RUL-02·04)
CREATE TABLE data2flow_core.space_events (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    space_id        bigint      NOT NULL,
    opened_at       timestamptz NOT NULL,
    closed_at       timestamptz,
    alarm_count     integer     NOT NULL DEFAULT 0,
    CONSTRAINT pk_space_events PRIMARY KEY (id),
    CONSTRAINT fk_space_events_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE
);
CREATE INDEX ix_space_events_organization_id_space_id_opened_at ON data2flow_core.space_events (organization_id, space_id, opened_at DESC);
COMMENT ON TABLE data2flow_core.space_events IS '공간 이벤트(5분 창의 알람 묶음, RUL-04.03·BR-RUL-11)';

CREATE TABLE data2flow_core.alarms (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint           NOT NULL,
    alarm_key         varchar(200)     NOT NULL,
    source_type       varchar(8)       NOT NULL,
    rule_id           bigint,
    flow_id           uuid,
    node_id           varchar(40),
    severity          varchar(10)      NOT NULL,
    title             varchar(200)     NOT NULL,
    status            varchar(14)      NOT NULL DEFAULT 'ACTIVE',
    flapping          boolean          NOT NULL DEFAULT false,
    device_id         bigint,
    space_id          bigint,
    metric_key        varchar(64),
    trigger_value     double precision,
    peak_value        double precision,
    last_value        double precision,
    threshold         jsonb,
    occurrence_count  integer          NOT NULL DEFAULT 1,
    raised_at         timestamptz      NOT NULL,
    last_raised_at    timestamptz      NOT NULL,
    acked_by          bigint,
    acked_at          timestamptz,
    cleared_at        timestamptz,
    clear_reason      varchar(20),
    assignee_id       bigint,
    parent_alarm_id   bigint,
    space_event_id    bigint,
    suppressed_reason varchar(20),
    last_notified_at  timestamptz,
    notify_seq        integer          NOT NULL DEFAULT 0,
    version           integer          NOT NULL DEFAULT 0,
    created_at        timestamptz      NOT NULL DEFAULT now(),
    updated_at        timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT pk_alarms PRIMARY KEY (id),
    CONSTRAINT ck_alarms_source_type CHECK (source_type IN ('RULE','FLOW','SYSTEM')),
    CONSTRAINT ck_alarms_severity CHECK (severity IN ('CRITICAL','MAJOR','MINOR','WARNING','INFO')),
    CONSTRAINT ck_alarms_status CHECK (status IN ('ACTIVE','ACKNOWLEDGED','SUPPRESSED','CLEARED')),
    CONSTRAINT ck_alarms_clear_reason CHECK (clear_reason IS NULL OR clear_reason IN ('AUTO','MANUAL','PARENT_CLEARED','RULE_DELETED','RULE_SCOPE_CHANGED')),
    CONSTRAINT ck_alarms_suppressed_reason CHECK (suppressed_reason IS NULL OR suppressed_reason IN ('MAINTENANCE','PARENT','DEVICE_OFFLINE')),
    CONSTRAINT fk_alarms_rule_id FOREIGN KEY (rule_id) REFERENCES data2flow_core.rules (id) ON DELETE SET NULL,
    CONSTRAINT fk_alarms_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alarms_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alarms_parent_alarm_id FOREIGN KEY (parent_alarm_id) REFERENCES data2flow_core.alarms (id) ON DELETE SET NULL,
    CONSTRAINT fk_alarms_space_event_id FOREIGN KEY (space_event_id) REFERENCES data2flow_core.space_events (id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX uq_alarms_alarm_key_open ON data2flow_core.alarms (organization_id, alarm_key) WHERE status IN ('ACTIVE','ACKNOWLEDGED','SUPPRESSED');
CREATE INDEX ix_alarms_organization_id_status_raised_at ON data2flow_core.alarms (organization_id, status, raised_at DESC);
CREATE INDEX ix_alarms_organization_id_space_id_status ON data2flow_core.alarms (organization_id, space_id, status);
CREATE INDEX ix_alarms_organization_id_alarm_key_raised_at ON data2flow_core.alarms (organization_id, alarm_key, raised_at DESC);
CREATE INDEX ix_alarms_organization_id_device_id ON data2flow_core.alarms (organization_id, device_id, raised_at DESC) WHERE device_id IS NOT NULL;
CREATE INDEX ix_alarms_parent_alarm_id ON data2flow_core.alarms (parent_alarm_id) WHERE parent_alarm_id IS NOT NULL;
COMMENT ON TABLE data2flow_core.alarms IS '알람. 열린 알람은 alarm_key당 하나(BR-RUL-02). CLEARED는 다시 열지 않고 새 행. 하위 알람은 parent_alarm_id(BR-RUL-08·09)';
COMMENT ON COLUMN data2flow_core.alarms.alarm_key IS 'rule:{ruleId}:{targetKey} / flow:{flowId}:{nodeId}:{targetKey} / system:{code}:{refId} (contracts AlarmKeys)';
COMMENT ON COLUMN data2flow_core.alarms.last_notified_at IS '마지막 알림 요청 시각(재알림 간격 BR-RUL-13)';
COMMENT ON COLUMN data2flow_core.alarms.notify_seq IS '알림 요청 순번(NotificationRequest.eventSeq, 멱등 키)';

CREATE TABLE data2flow_core.alarm_events (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    alarm_id        bigint      NOT NULL,
    at              timestamptz NOT NULL DEFAULT now(),
    type            varchar(14) NOT NULL,
    actor_type      varchar(10) NOT NULL,
    actor_id        bigint,
    data            jsonb,
    CONSTRAINT pk_alarm_events PRIMARY KEY (id),
    CONSTRAINT ck_alarm_events_type CHECK (type IN ('RAISED','RERAISED','ACKED','CLEARED','ASSIGNED','NOTE','ACTION','ESCALATED','NOTIFIED','SILENCED','FLAPPING_ON','FLAPPING_OFF','SUPPRESSED')),
    CONSTRAINT ck_alarm_events_actor_type CHECK (actor_type IN ('USER','SYSTEM','FLOW','MESSENGER')),
    CONSTRAINT fk_alarm_events_alarm_id FOREIGN KEY (alarm_id) REFERENCES data2flow_core.alarms (id) ON DELETE CASCADE
);
CREATE INDEX ix_alarm_events_alarm_id_at ON data2flow_core.alarm_events (alarm_id, at);
COMMENT ON TABLE data2flow_core.alarm_events IS '알람 타임라인(INSERT 전용). data: 값, 메모(≤2,000자), 조치 종류, 발송 ID';

CREATE TABLE data2flow_core.silences (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    kind            varchar(10)  NOT NULL,
    target_type     varchar(8)   NOT NULL,
    target_id       bigint       NOT NULL,
    starts_at       timestamptz,
    ends_at         timestamptz,
    recurrence      jsonb,
    reason          varchar(200),
    created_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_silences PRIMARY KEY (id),
    CONSTRAINT ck_silences_kind CHECK (kind IN ('ONE_TIME','RECURRING')),
    CONSTRAINT ck_silences_target_type CHECK (target_type IN ('RULE','DEVICE','SPACE','ALARM')),
    CONSTRAINT ck_silences_one_time CHECK (kind <> 'ONE_TIME' OR (starts_at IS NOT NULL AND ends_at > starts_at)),
    CONSTRAINT ck_silences_recurring CHECK (kind <> 'RECURRING' OR recurrence IS NOT NULL)
);
CREATE INDEX ix_silences_organization_id_target ON data2flow_core.silences (organization_id, target_type, target_id);
COMMENT ON TABLE data2flow_core.silences IS '무음(RUL-02.07). 끝 시각은 미포함, 반복은 요일·시간대 또는 날짜 구간';

-- ---------------------------------------------------------------- 알림 템플릿·당직·사용자 설정(RUL-03·05, OPS-06.05)
CREATE TABLE data2flow_core.notification_templates (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL DEFAULT 0,
    template_key    varchar(64)  NOT NULL,
    channel         varchar(20)  NOT NULL,
    locale          varchar(5)   NOT NULL DEFAULT 'ko',
    subject         varchar(200),
    body            text         NOT NULL,
    builtin         boolean      NOT NULL DEFAULT false,
    version         integer      NOT NULL DEFAULT 0,
    updated_by      bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_notification_templates PRIMARY KEY (id),
    CONSTRAINT uq_notification_templates_key_channel_locale UNIQUE (organization_id, template_key, channel, locale),
    CONSTRAINT ck_notification_templates_locale CHECK (locale IN ('ko','en','ja','zh')),
    CONSTRAINT ck_notification_templates_body CHECK (channel = 'WEB' OR char_length(body) <= 4000)
);
COMMENT ON TABLE data2flow_core.notification_templates IS '알림 템플릿. channel = WEB 또는 채널 SPI 키(현재 TELEGRAM). 시스템 기본은 organization_id=0·builtin(되돌리기 기준)';

CREATE TABLE data2flow_core.on_call_schedules (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(100) NOT NULL,
    timezone        varchar(40)  NOT NULL DEFAULT 'Asia/Seoul',
    version         integer      NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_on_call_schedules PRIMARY KEY (id),
    CONSTRAINT uq_on_call_schedules_organization_id_name UNIQUE (organization_id, name)
);
COMMENT ON TABLE data2flow_core.on_call_schedules IS '당직 일정(RUL-05.03). v1은 조직당 1개(API-RUL-26)';

CREATE TABLE data2flow_core.on_call_shifts (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint   NOT NULL,
    schedule_id     bigint   NOT NULL,
    day_of_week     smallint NOT NULL,
    from_time       time     NOT NULL,
    to_time         time     NOT NULL,
    user_id         bigint   NOT NULL,
    CONSTRAINT pk_on_call_shifts PRIMARY KEY (id),
    CONSTRAINT ck_on_call_shifts_day_of_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT fk_on_call_shifts_schedule_id FOREIGN KEY (schedule_id) REFERENCES data2flow_core.on_call_schedules (id) ON DELETE CASCADE
);
COMMENT ON TABLE data2flow_core.on_call_shifts IS '당직 교대(요일 1=월~7=일, 시간대, 사용자). to < from이면 자정을 넘는다';

CREATE TABLE data2flow_core.on_call_overrides (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    organization_id    bigint      NOT NULL,
    schedule_id        bigint      NOT NULL,
    starts_at          timestamptz NOT NULL,
    ends_at            timestamptz NOT NULL,
    original_user_id   bigint      NOT NULL,
    substitute_user_id bigint      NOT NULL,
    created_by         bigint      NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_on_call_overrides PRIMARY KEY (id),
    CONSTRAINT ck_on_call_overrides_period CHECK (ends_at > starts_at),
    CONSTRAINT fk_on_call_overrides_schedule_id FOREIGN KEY (schedule_id) REFERENCES data2flow_core.on_call_schedules (id) ON DELETE CASCADE
);
COMMENT ON TABLE data2flow_core.on_call_overrides IS '당직 대체(대체 근무 > 주간 교대, BR-RUL-19)';

CREATE TABLE data2flow_core.user_messenger_links (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint       NOT NULL,
    user_id          bigint       NOT NULL,
    channel          varchar(20)  NOT NULL,
    external_user_id varchar(128) NOT NULL,
    linked_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_messenger_links PRIMARY KEY (id),
    CONSTRAINT uq_user_messenger_links_channel_external_user_id UNIQUE (channel, external_user_id),
    CONSTRAINT uq_user_messenger_links_user_id_channel UNIQUE (user_id, channel)
);
COMMENT ON TABLE data2flow_core.user_messenger_links IS '메신저 계정 연결(외부 계정 1 ↔ 사용자 1). 메신저 응답은 연결 계정 권한으로(BR-RUL-18)';

CREATE TABLE data2flow_core.messenger_link_codes (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    user_id         bigint      NOT NULL,
    channel         varchar(20) NOT NULL,
    code_hash       char(64)    NOT NULL,
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_messenger_link_codes PRIMARY KEY (id),
    CONSTRAINT uq_messenger_link_codes_code_hash UNIQUE (code_hash)
);
CREATE INDEX ix_messenger_link_codes_organization_id_user_id ON data2flow_core.messenger_link_codes (organization_id, user_id);
COMMENT ON TABLE data2flow_core.messenger_link_codes IS '메신저 계정 연결 일회용 코드(API-RUL-30, 10분 유효, ADR-033). 원문은 저장하지 않는다';

CREATE TABLE data2flow_core.user_notify_prefs (
    user_id            bigint      NOT NULL,
    organization_id    bigint      NOT NULL,
    dnd_from           time,
    dnd_to             time,
    dnd_allow_critical boolean     NOT NULL DEFAULT true,
    locale             varchar(5)  NOT NULL DEFAULT 'ko',
    channels           text[]      NOT NULL DEFAULT '{WEB,TELEGRAM}',
    min_severity       varchar(10) NOT NULL DEFAULT 'INFO',
    push_categories    jsonb       NOT NULL DEFAULT '{"alarm":true,"workOrderAssigned":true,"approvalRequest":true}',
    push_min_severity  varchar(10) NOT NULL DEFAULT 'MAJOR',
    version            integer     NOT NULL DEFAULT 0,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_notify_prefs PRIMARY KEY (user_id),
    CONSTRAINT ck_user_notify_prefs_locale CHECK (locale IN ('ko','en','ja','zh')),
    CONSTRAINT ck_user_notify_prefs_min_severity CHECK (min_severity IN ('CRITICAL','MAJOR','MINOR','WARNING','INFO')),
    CONSTRAINT ck_user_notify_prefs_push_min_severity CHECK (push_min_severity IN ('CRITICAL','MAJOR','MINOR','WARNING','INFO')),
    CONSTRAINT ck_user_notify_prefs_dnd CHECK ((dnd_from IS NULL) = (dnd_to IS NULL))
);
COMMENT ON TABLE data2flow_core.user_notify_prefs IS '사용자 알림 수신 설정(OPS-06.05): 받을 채널, 최소 심각도, 방해 금지 시간과 CRITICAL 예외(BR-RUL-14)';

-- ---------------------------------------------------------------- 제어 정의(ACT-02.07·02.08·05.01·06.02·06.03)
CREATE TABLE data2flow_core.scenes (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(60)  NOT NULL,
    description     varchar(500),
    space_id        bigint,
    item_count      integer      NOT NULL DEFAULT 0,
    managed_by_role varchar(20),
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint       NOT NULL,
    updated_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_scenes PRIMARY KEY (id),
    CONSTRAINT uq_scenes_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_scenes_item_count CHECK (item_count BETWEEN 0 AND 100),
    CONSTRAINT fk_scenes_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT
);
COMMENT ON TABLE data2flow_core.scenes IS '장면(ACT-05). 실행 기록(scene_runs)은 data2flow_action';

CREATE TABLE data2flow_core.scene_items (
    scene_id        bigint      NOT NULL,
    seq             smallint    NOT NULL,
    organization_id bigint      NOT NULL,
    target          jsonb       NOT NULL,
    capability      varchar(64) NOT NULL,
    desired         jsonb       NOT NULL,
    CONSTRAINT pk_scene_items PRIMARY KEY (scene_id, seq),
    CONSTRAINT fk_scene_items_scene_id FOREIGN KEY (scene_id) REFERENCES data2flow_core.scenes (id) ON DELETE CASCADE
);
COMMENT ON TABLE data2flow_core.scene_items IS '장면 항목. target = {deviceId} 또는 {spaceId, relation:controls, capability, includeChildren}';

CREATE TABLE data2flow_core.control_schedules (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(100) NOT NULL,
    target          jsonb        NOT NULL,
    kind            varchar(12)  NOT NULL,
    run_at          timestamptz,
    cron            varchar(64),
    space_hours     jsonb,
    valid_from      date,
    valid_to        date,
    skip_holidays   boolean      NOT NULL DEFAULT false,
    timezone        varchar(40)  NOT NULL DEFAULT 'Asia/Seoul',
    enabled         boolean      NOT NULL DEFAULT true,
    next_run_at     timestamptz,
    last_run        jsonb,
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint       NOT NULL,
    updated_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_control_schedules PRIMARY KEY (id),
    CONSTRAINT uq_control_schedules_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_control_schedules_kind CHECK (kind IN ('ONCE','RECURRING','SPACE_HOURS')),
    CONSTRAINT ck_control_schedules_once CHECK (kind <> 'ONCE' OR run_at IS NOT NULL),
    CONSTRAINT ck_control_schedules_recurring CHECK (kind <> 'RECURRING' OR cron IS NOT NULL),
    CONSTRAINT ck_control_schedules_space_hours CHECK (kind <> 'SPACE_HOURS' OR space_hours IS NOT NULL),
    CONSTRAINT ck_control_schedules_valid CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_control_schedules_next_run_at_enabled ON data2flow_core.control_schedules (next_run_at) WHERE enabled;
COMMENT ON TABLE data2flow_core.control_schedules IS '예약 제어(ACT-02.07). target = {sceneId} 또는 {deviceId, capability, command, args}';

CREATE TABLE data2flow_core.interlock_rules (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint       NOT NULL,
    name             varchar(100) NOT NULL,
    space_id         bigint,
    include_children boolean      NOT NULL DEFAULT true,
    enabled          boolean      NOT NULL DEFAULT true,
    condition        jsonb        NOT NULL,
    forbid           jsonb        NOT NULL,
    message          varchar(200) NOT NULL,
    version          integer      NOT NULL DEFAULT 0,
    created_by       bigint       NOT NULL,
    updated_by       bigint       NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_interlock_rules PRIMARY KEY (id),
    CONSTRAINT uq_interlock_rules_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT fk_interlock_rules_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT
);
COMMENT ON TABLE data2flow_core.interlock_rules IS '인터락(ACT-02.08·06.02). 판정은 action 제어 창구(내부 API-ACT-44로 읽음)';

CREATE TABLE data2flow_core.emergency_stops (
    id           bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    scope        jsonb        NOT NULL,
    reason       varchar(200) NOT NULL,
    started_by   bigint       NOT NULL,
    started_at   timestamptz  NOT NULL DEFAULT now(),
    released_by  bigint,
    released_at  timestamptz,
    release_note varchar(500),
    CONSTRAINT pk_emergency_stops PRIMARY KEY (id)
);
CREATE INDEX ix_emergency_stops_organization_id_open ON data2flow_core.emergency_stops (organization_id) WHERE released_at IS NULL;
COMMENT ON TABLE data2flow_core.emergency_stops IS '자동화 비상 정지(ACT-06.03). 열려 있으면 released_at NULL. EVT-ACT-03 control.emergency.*';

-- ---------------------------------------------------------------- Sink 연결(FLW-04.01)
CREATE TABLE data2flow_core.sink_connections (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(100) NOT NULL,
    type            varchar(16)  NOT NULL,
    config          jsonb        NOT NULL,
    secret_enc      bytea        NOT NULL,
    status          varchar(12)  NOT NULL DEFAULT 'UNTESTED',
    last_error      varchar(500),
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint       NOT NULL,
    updated_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sink_connections PRIMARY KEY (id),
    CONSTRAINT uq_sink_connections_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_sink_connections_type CHECK (type IN ('POSTGRESQL','MYSQL','INFLUXDB')),
    CONSTRAINT ck_sink_connections_status CHECK (status IN ('OK','ERROR','UNTESTED'))
);
COMMENT ON TABLE data2flow_core.sink_connections IS 'Sink 연결(외부 DB 저장, FLW-04). 실행은 action. 비밀값 AES-256-GCM, 응답에서 가림';

-- ---------------------------------------------------------------- 시스템 기본 규칙 템플릿 7종(RUL-01.10, API-RUL-05)
INSERT INTO data2flow_core.rule_templates (organization_id, template_key, name, description, category, params_schema, condition_template,
                                           default_severity, builtin)
VALUES
 (0, 'high-co2', '고CO2', 'CO2가 1,000ppm을 넘은 상태가 5분 이어지면 발생, 900ppm 아래에서 해제', 'COMFORT',
  '{"type":"object","properties":{"value":{"type":"number","default":1000},"clear":{"type":"number","default":900},"for":{"type":"string","format":"duration","default":"PT5M"}}}',
  '{"kind":"threshold","metric":"co2","op":">","value":1000,"for":"PT5M","clear":900}', 'MAJOR', true),
 (0, 'high-temp', '고온', '온도가 28℃를 넘은 상태가 10분 이어지면 발생, 27℃ 아래에서 해제', 'COMFORT',
  '{"type":"object","properties":{"value":{"type":"number","default":28},"clear":{"type":"number","default":27},"for":{"type":"string","format":"duration","default":"PT10M"}}}',
  '{"kind":"threshold","metric":"temperature","op":">","value":28,"for":"PT10M","clear":27}', 'MAJOR', true),
 (0, 'low-temp', '저온', '온도가 18℃보다 낮은 상태가 10분 이어지면 발생, 19℃ 이상에서 해제', 'COMFORT',
  '{"type":"object","properties":{"value":{"type":"number","default":18},"clear":{"type":"number","default":19},"for":{"type":"string","format":"duration","default":"PT10M"}}}',
  '{"kind":"threshold","metric":"temperature","op":"<","value":18,"for":"PT10M","clear":19}', 'MINOR', true),
 (0, 'high-humidity', '고습', '습도가 70%를 넘은 상태가 15분 이어지면 발생, 65% 아래에서 해제', 'COMFORT',
  '{"type":"object","properties":{"value":{"type":"number","default":70},"clear":{"type":"number","default":65},"for":{"type":"string","format":"duration","default":"PT15M"}}}',
  '{"kind":"threshold","metric":"humidity","op":">","value":70,"for":"PT15M","clear":65}', 'MINOR', true),
 (0, 'low-battery', '배터리 20% 미만', '배터리가 20%보다 낮으면 발생, 25% 이상에서 해제', 'EQUIPMENT',
  '{"type":"object","properties":{"value":{"type":"number","default":20},"clear":{"type":"number","default":25}}}',
  '{"kind":"threshold","metric":"battery","op":"<","value":20,"clear":25}', 'WARNING', true),
 (0, 'no-data-30m', '무수신 30분', '30분 동안 데이터가 없으면 발생, 다시 들어오면 해제', 'EQUIPMENT',
  '{"type":"object","properties":{"window":{"type":"string","format":"duration","default":"PT30M"}}}',
  '{"kind":"noData","window":"PT30M"}', 'MAJOR', true),
 (0, 'door-open-off-hours', '운영 시간 외 문 열림', '공간 운영 시간표 밖에서 문이 열리면 발생', 'SAFETY',
  '{"type":"object","properties":{}}',
  '{"kind":"threshold","metric":"door","op":"==","value":1}', 'CRITICAL', true);

-- ---------------------------------------------------------------- 시스템 기본 알림 템플릿(RUL-05.01: 4개 언어 × WEB·TELEGRAM)
INSERT INTO data2flow_core.notification_templates (organization_id, template_key, channel, locale, subject, body, builtin)
SELECT 0, t.key, c.channel, t.locale, NULL, t.body, true
  FROM (VALUES
   ('alarm.raised.default', 'ko', '[{{alarm.severity}}] {{alarm.title}}' || chr(10) || '{{space.path}} · {{device.name}}' || chr(10) || '값 {{value}} (기준 {{threshold}})' || chr(10) || '{{link}}'),
   ('alarm.raised.default', 'en', '[{{alarm.severity}}] {{alarm.title}}' || chr(10) || '{{space.path}} · {{device.name}}' || chr(10) || 'Value {{value}} (threshold {{threshold}})' || chr(10) || '{{link}}'),
   ('alarm.raised.default', 'ja', '[{{alarm.severity}}] {{alarm.title}}' || chr(10) || '{{space.path}} · {{device.name}}' || chr(10) || '値 {{value}} (基準 {{threshold}})' || chr(10) || '{{link}}'),
   ('alarm.raised.default', 'zh', '[{{alarm.severity}}] {{alarm.title}}' || chr(10) || '{{space.path}} · {{device.name}}' || chr(10) || '数值 {{value}} (阈值 {{threshold}})' || chr(10) || '{{link}}'),
   ('alarm.reraised.default', 'ko', '[{{alarm.severity}}] 다시 발생: {{alarm.title}}' || chr(10) || '값 {{value}}' || chr(10) || '{{link}}'),
   ('alarm.reraised.default', 'en', '[{{alarm.severity}}] Raised again: {{alarm.title}}' || chr(10) || 'Value {{value}}' || chr(10) || '{{link}}'),
   ('alarm.reraised.default', 'ja', '[{{alarm.severity}}] 再発生: {{alarm.title}}' || chr(10) || '値 {{value}}' || chr(10) || '{{link}}'),
   ('alarm.reraised.default', 'zh', '[{{alarm.severity}}] 再次发生: {{alarm.title}}' || chr(10) || '数值 {{value}}' || chr(10) || '{{link}}'),
   ('alarm.cleared.default', 'ko', '[해제] {{alarm.title}}' || chr(10) || '{{space.path}} · {{device.name}}' || chr(10) || '{{link}}'),
   ('alarm.cleared.default', 'en', '[Cleared] {{alarm.title}}' || chr(10) || '{{space.path}} · {{device.name}}' || chr(10) || '{{link}}'),
   ('alarm.cleared.default', 'ja', '[解除] {{alarm.title}}' || chr(10) || '{{space.path}} · {{device.name}}' || chr(10) || '{{link}}'),
   ('alarm.cleared.default', 'zh', '[已解除] {{alarm.title}}' || chr(10) || '{{space.path}} · {{device.name}}' || chr(10) || '{{link}}'),
   ('alarm.escalated.default', 'ko', '[에스컬레이션] [{{alarm.severity}}] {{alarm.title}} — 아직 확인되지 않았습니다' || chr(10) || '{{link}}'),
   ('alarm.escalated.default', 'en', '[Escalated] [{{alarm.severity}}] {{alarm.title}} — not acknowledged yet' || chr(10) || '{{link}}'),
   ('alarm.escalated.default', 'ja', '[エスカレーション] [{{alarm.severity}}] {{alarm.title}} — まだ確認されていません' || chr(10) || '{{link}}'),
   ('alarm.escalated.default', 'zh', '[升级] [{{alarm.severity}}] {{alarm.title}} — 尚未确认' || chr(10) || '{{link}}')
  ) AS t(key, locale, body)
 CROSS JOIN (VALUES ('WEB'), ('TELEGRAM')) AS c(channel);
