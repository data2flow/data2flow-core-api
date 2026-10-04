-- M5 외부 맥락·조직 달력·운영 모드(DSC-06.01~06.05, DEV-11.02, DEV-12.01). 추가만(ADR-030).
-- calendar_events·source_api_usage_daily는 ERD(ddl/11·12) 정의를 따르고, BR-DSC-18("원본 삭제됨" 표시)·AT-DSC-09.4(경고 1회)·
-- EVT-DEV-06(바뀔 때만 발행)에 필요한 열·표를 더했다(ERD 반영 필요).

CREATE TABLE data2flow_core.calendar_events (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint       NOT NULL,
    title             varchar(150) NOT NULL,
    type              varchar(10)  NOT NULL,
    starts_on         date         NOT NULL,
    ends_on           date         NOT NULL,
    start_time        time,
    end_time          time,
    scope_space_ids   bigint[]     NOT NULL DEFAULT '{}',
    origin            varchar(12)  NOT NULL DEFAULT 'MANUAL',
    origin_uid        varchar(200),
    affects_mode      varchar(12)  NOT NULL DEFAULT 'NONE',
    source_id         bigint,
    locally_modified  boolean      NOT NULL DEFAULT false,
    origin_deleted_at timestamptz,
    version           integer      NOT NULL DEFAULT 0,
    created_by        bigint,
    updated_by        bigint,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_calendar_events PRIMARY KEY (id),
    CONSTRAINT ck_calendar_events_type CHECK (type IN ('HOLIDAY', 'CLOSURE', 'EVENT', 'VACATION', 'EXAM', 'OTHER')),
    CONSTRAINT ck_calendar_events_range CHECK (ends_on >= starts_on),
    CONSTRAINT ck_calendar_events_origin CHECK (origin IN ('MANUAL', 'HOLIDAY_API', 'ICAL')),
    CONSTRAINT ck_calendar_events_affects_mode CHECK (affects_mode IN ('HOLIDAY', 'UNOCCUPIED', 'NONE')),
    CONSTRAINT fk_calendar_events_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX uq_calendar_events_organization_id_origin_uid ON data2flow_core.calendar_events (organization_id, origin, origin_uid)
    WHERE origin_uid IS NOT NULL;
CREATE INDEX ix_calendar_events_organization_id_starts_on ON data2flow_core.calendar_events (organization_id, starts_on, ends_on);
COMMENT ON TABLE data2flow_core.calendar_events IS '조직 달력(공휴일·가져온 iCal·직접 등록, DEV-12.01). 비어 있는 scope_space_ids = 조직 전체';
COMMENT ON COLUMN data2flow_core.calendar_events.origin_uid IS '자동 생성 일정의 원본 UID. s{source_id}:{UID}(공휴일은 UID holiday:yyyy-MM-dd)';
COMMENT ON COLUMN data2flow_core.calendar_events.locally_modified IS '자동 생성 일정을 사람이 고쳤다(BR-DSC-18: 원본에서 사라져도 남김)';
COMMENT ON COLUMN data2flow_core.calendar_events.origin_deleted_at IS '원본에서 사라진 시각(수동 수정 일정만, "원본 삭제됨" 표시)';

CREATE TABLE data2flow_core.source_api_usage_daily (
    source_id       bigint      NOT NULL,
    day             date        NOT NULL,
    organization_id bigint      NOT NULL,
    calls           integer     NOT NULL DEFAULT 0,
    failures        integer     NOT NULL DEFAULT 0,
    quota           integer,
    warned_at       timestamptz,
    CONSTRAINT pk_source_api_usage_daily PRIMARY KEY (source_id, day),
    CONSTRAINT fk_source_api_usage_daily_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);
CREATE INDEX ix_source_api_usage_daily_organization_id ON data2flow_core.source_api_usage_daily (organization_id, day);
COMMENT ON TABLE data2flow_core.source_api_usage_daily IS '공공 API 일일 호출량과 한도(DSC-06.05). day는 KST 날짜';
COMMENT ON COLUMN data2flow_core.source_api_usage_daily.warned_at IS '그날 80% 경고 알람을 낸 시각(하루 1회, AT-DSC-09.4)';

-- core가 직접 갱신하는 외부 맥락 소스(HOLIDAY·ICAL)의 동기화 상태와 다음 실행(BR-DSC-17 재시도 30초·2분·10분)
CREATE TABLE data2flow_core.context_source_syncs (
    source_id        bigint       NOT NULL,
    organization_id  bigint       NOT NULL,
    next_due_at      timestamptz,
    retry_count      smallint     NOT NULL DEFAULT 0,
    last_attempt_at  timestamptz,
    last_success_at  timestamptz,
    last_status      varchar(10),
    last_error       varchar(500),
    added            integer      NOT NULL DEFAULT 0,
    updated          integer      NOT NULL DEFAULT 0,
    removed          integer      NOT NULL DEFAULT 0,
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_context_source_syncs PRIMARY KEY (source_id),
    CONSTRAINT ck_context_source_syncs_last_status CHECK (last_status IS NULL OR last_status IN ('SUCCEEDED', 'FAILED', 'REQUESTED')),
    CONSTRAINT fk_context_source_syncs_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);
CREATE INDEX ix_context_source_syncs_next_due_at ON data2flow_core.context_source_syncs (next_due_at);
COMMENT ON TABLE data2flow_core.context_source_syncs IS '외부 맥락 소스 동기화 상태(DSC-06.03·06.04, 마지막 결과 +추가 −삭제)';

-- iCal 파일 업로드(API-DSC-43, ≤2MB). 오브젝트 저장소가 생기기 전까지 DB에 두고 키는 db:ical_files/<id>
CREATE TABLE data2flow_core.ical_files (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    file_name       varchar(200) NOT NULL,
    data            bytea        NOT NULL,
    event_count     integer      NOT NULL,
    uploaded_by     bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ical_files PRIMARY KEY (id),
    CONSTRAINT ck_ical_files_size CHECK (octet_length(data) BETWEEN 1 AND 2097152)
);
CREATE INDEX ix_ical_files_organization_id ON data2flow_core.ical_files (organization_id, created_at);
COMMENT ON TABLE data2flow_core.ical_files IS '업로드한 iCal 파일(DSC-06.04). ICAL 소스 connection.fileObjectKey가 가리킨다';

-- 공간 운영 모드 마지막 계산값(DEV-11.02). 1분 작업이 바뀐 것만 EVT-DEV-06으로 낸다
CREATE TABLE data2flow_core.space_mode_states (
    space_id        bigint      NOT NULL,
    organization_id bigint      NOT NULL,
    mode            varchar(12) NOT NULL,
    source          varchar(12) NOT NULL,
    changed_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_space_mode_states PRIMARY KEY (space_id),
    CONSTRAINT ck_space_mode_states_mode CHECK (mode IN ('OCCUPIED', 'UNOCCUPIED', 'HOLIDAY', 'MAINTENANCE')),
    CONSTRAINT fk_space_mode_states_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE
);
CREATE INDEX ix_space_mode_states_organization_id ON data2flow_core.space_mode_states (organization_id);
COMMENT ON TABLE data2flow_core.space_mode_states IS '공간 운영 모드 마지막 계산값(DEV-11.02, BR-DEV-23). 바뀌면 space.mode.changed';
