-- =====================================================================
-- M2 수집 경로(core-api): 공간·기기·모델·측정 항목·그룹·시맨틱·목표(DEV), 데이터 소스·커넥터 카탈로그·연결 상태·지표(DSC),
-- 시계열 주석(TSD), 스크립트·버전·연결(SCR), 사용자 화면 설정(DSH 즐겨찾기·시간대).
-- 근거: design/erd/ddl/11-core-space-device.sql, 12-core-data-automation.sql, 19-core-deferred-fks.sql (해당 테이블만 옮김)
-- 문서와 다른 점:
--   * metrics.key는 대소문자를 섞을 수 있다(^[A-Za-z][A-Za-z0-9_]{0,63}$, 2026-10-04 결정: 센서가 보내는 키 그대로, 예: pir_trigger·TVOC)
--   * user_dashboard_prefs.default_dashboard_id의 FK는 dashboards 테이블이 생기는 M5 마이그레이션에서 건다
--   * config_versions: 내부 전체 조회(API-DEV-123·API-DSC-50·API-SCR-32)의 sinceVersion 비교용 단조 증가 버전(추가)
-- expand 마이그레이션(추가만, ADR-030)
-- =====================================================================

CREATE TABLE data2flow_core.spaces (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    organization_id     bigint        NOT NULL,
    parent_id           bigint,
    type                varchar(10)   NOT NULL,
    name                varchar(100)  NOT NULL,
    code                varchar(50),
    path                text          NOT NULL,
    depth               smallint      NOT NULL,
    sort_order          integer       NOT NULL DEFAULT 0,
    usage               varchar(20),
    area_m2             numeric(10,2),
    capacity            integer,
    timezone            varchar(40),
    address             varchar(300),
    latitude            numeric(9,6),
    longitude           numeric(9,6),
    kma_nx              smallint,
    kma_ny              smallint,
    mode_override       varchar(15),
    mode_override_until timestamptz,
    schedule_inherit    boolean       NOT NULL DEFAULT true,
    status              varchar(10)   NOT NULL DEFAULT 'ACTIVE',
    version             integer       NOT NULL DEFAULT 0,
    created_by          bigint,
    updated_by          bigint,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_spaces PRIMARY KEY (id),
    CONSTRAINT fk_spaces_parent_id FOREIGN KEY (parent_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT ck_spaces_type CHECK (type IN ('SITE', 'BUILDING', 'FLOOR', 'ROOM', 'ZONE')),
    CONSTRAINT ck_spaces_depth CHECK (depth BETWEEN 1 AND 6),
    CONSTRAINT ck_spaces_root CHECK ((parent_id IS NULL) = (type = 'SITE')),
    CONSTRAINT ck_spaces_usage CHECK (usage IS NULL OR usage IN ('CLASSROOM', 'OFFICE', 'MEETING', 'LAB', 'CORRIDOR', 'OTHER')),
    CONSTRAINT ck_spaces_timezone_site CHECK (type = 'SITE' OR timezone IS NULL),
    CONSTRAINT ck_spaces_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_spaces_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_spaces_mode_override CHECK (mode_override IS NULL OR mode_override IN ('OCCUPIED', 'UNOCCUPIED', 'HOLIDAY', 'MAINTENANCE')),
    CONSTRAINT ck_spaces_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE UNIQUE INDEX uq_spaces_organization_id_parent_id_name ON data2flow_core.spaces (organization_id, coalesce(parent_id, 0), name) WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_spaces_organization_id_code ON data2flow_core.spaces (organization_id, code) WHERE code IS NOT NULL;

CREATE INDEX ix_spaces_organization_id_path ON data2flow_core.spaces (organization_id, path text_pattern_ops);

CREATE INDEX ix_spaces_parent_id ON data2flow_core.spaces (parent_id);

COMMENT ON TABLE data2flow_core.spaces IS '공간 트리, 최대 6단계(DEV-01, BR-DEV-01~05). 삭제 대신 status=ARCHIVED (문서의 deleted_at 대체)';

COMMENT ON COLUMN data2flow_core.spaces.path IS '조상 id를 / 로 이은 경로(예: /1/4/9/). 하위 조회·권한 범위 필터(IAM-04.05)';

CREATE TABLE data2flow_core.space_schedules (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    space_id        bigint      NOT NULL,
    day_of_week     smallint    NOT NULL,
    start_time      time        NOT NULL,
    end_time        time        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_space_schedules PRIMARY KEY (id),
    CONSTRAINT fk_space_schedules_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE,
    CONSTRAINT ck_space_schedules_day_of_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_space_schedules_range CHECK (end_time > start_time)
);

CREATE INDEX ix_space_schedules_organization_id_space_id ON data2flow_core.space_schedules (organization_id, space_id, day_of_week);

COMMENT ON TABLE data2flow_core.space_schedules IS '공간 운영 시간(재실 모드). 상속 여부는 spaces.schedule_inherit (DEV-01.06)';

CREATE TABLE data2flow_core.metrics (
    id                   bigint GENERATED ALWAYS AS IDENTITY,
    organization_id      bigint       NOT NULL,
    key                  varchar(64)  NOT NULL,
    display_name         varchar(50)  NOT NULL,
    unit                 varchar(16),
    value_type           varchar(10)  NOT NULL DEFAULT 'NUMBER',
    enum_map             jsonb,
    valid_min            double precision,
    valid_max            double precision,
    precision            smallint     NOT NULL DEFAULT 1,
    agg_default          varchar(10)  NOT NULL DEFAULT 'AVG',
    state_type           boolean      NOT NULL DEFAULT false,
    semantic             varchar(32),
    status               varchar(12)  NOT NULL DEFAULT 'VERIFIED',
    first_seen_at        timestamptz,
    first_seen_device_id bigint,
    builtin              boolean      NOT NULL DEFAULT false,
    version              integer      NOT NULL DEFAULT 0,
    created_by           bigint,
    updated_by           bigint,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_metrics PRIMARY KEY (id),
    CONSTRAINT uq_metrics_organization_id_key UNIQUE (organization_id, key),
    CONSTRAINT ck_metrics_key CHECK (key ~ '^[A-Za-z][A-Za-z0-9_]{0,63}$'),
    CONSTRAINT ck_metrics_value_type CHECK (value_type IN ('NUMBER', 'BOOLEAN', 'ENUM')),
    CONSTRAINT ck_metrics_enum_map CHECK (value_type <> 'ENUM' OR enum_map IS NOT NULL),
    CONSTRAINT ck_metrics_valid_range CHECK (valid_min IS NULL OR valid_max IS NULL OR valid_min < valid_max),
    CONSTRAINT ck_metrics_precision CHECK (precision BETWEEN 0 AND 6),
    CONSTRAINT ck_metrics_agg_default CHECK (agg_default IN ('AVG', 'SUM', 'MAX', 'MIN', 'LAST', 'COUNT')),
    CONSTRAINT ck_metrics_status CHECK (status IN ('VERIFIED', 'UNVERIFIED', 'IGNORED'))
);

CREATE INDEX ix_metrics_organization_id_status ON data2flow_core.metrics (organization_id, status);

COMMENT ON TABLE data2flow_core.metrics IS '측정 항목 사전(DEV-05, BR-DEV-17~20). 문서는 key가 PK — 규칙에 따라 id PK + UNIQUE(organization_id, key)';

COMMENT ON COLUMN data2flow_core.metrics.state_type IS '상태형 항목 여부(door 등). true면 값이 바뀔 때만 저장 가능(TSD-05.03). DEV 문서대로 boolean(2026-10-03)';

CREATE TABLE data2flow_core.metric_aliases (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    alias           varchar(64) NOT NULL,
    metric_key      varchar(64) NOT NULL,
    created_by      bigint,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_metric_aliases PRIMARY KEY (id),
    CONSTRAINT uq_metric_aliases_organization_id_alias UNIQUE (organization_id, alias),
    CONSTRAINT fk_metric_aliases_metric_key FOREIGN KEY (organization_id, metric_key)
        REFERENCES data2flow_core.metrics (organization_id, key) ON DELETE CASCADE ON UPDATE CASCADE
);

COMMENT ON TABLE data2flow_core.metric_aliases IS '수신 키 → 표준 metric 키 별칭(DEV-05.03, BR-DEV-19)';

CREATE TABLE data2flow_core.device_models (
    id                         bigint GENERATED ALWAYS AS IDENTITY,
    organization_id            bigint       NOT NULL,
    code                       varchar(50)  NOT NULL,
    vendor                     varchar(100),
    name                       varchar(100) NOT NULL,
    protocol                   varchar(10)  NOT NULL,
    kind                       varchar(10)  NOT NULL,
    default_interval_sec       integer      NOT NULL DEFAULT 600,
    default_offline_multiplier numeric(3,1) NOT NULL DEFAULT 3.0,
    description                varchar(500),
    image_object_key           varchar(300),
    builtin                    boolean      NOT NULL DEFAULT false,
    transform_script_id        bigint,
    decode_script_id           bigint,
    driver_key                 varchar(60),
    default_dashboard_id       bigint,
    default_rule_template_ids  bigint[]     NOT NULL DEFAULT '{}',
    attribute_schema           jsonb,
    semantic_template          jsonb,
    status                     varchar(12)  NOT NULL DEFAULT 'ACTIVE',
    version                    integer      NOT NULL DEFAULT 0,
    created_by                 bigint,
    updated_by                 bigint,
    created_at                 timestamptz  NOT NULL DEFAULT now(),
    updated_at                 timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_models PRIMARY KEY (id),
    CONSTRAINT uq_device_models_organization_id_code UNIQUE (organization_id, code),
    CONSTRAINT ck_device_models_code CHECK (code ~ '^[A-Z0-9][A-Z0-9._-]{1,49}$'),
    CONSTRAINT ck_device_models_protocol CHECK (protocol IN ('LORAWAN', 'MQTT', 'HTTP', 'VIRTUAL', 'OTHER')),
    CONSTRAINT ck_device_models_kind CHECK (kind IN ('SENSOR', 'ACTUATOR', 'GATEWAY', 'HYBRID')),
    CONSTRAINT ck_device_models_interval CHECK (default_interval_sec BETWEEN 10 AND 86400),
    CONSTRAINT ck_device_models_multiplier CHECK (default_offline_multiplier BETWEEN 1.5 AND 10),
    CONSTRAINT ck_device_models_status CHECK (status IN ('ACTIVE', 'DEPRECATED'))
);

COMMENT ON TABLE data2flow_core.device_models IS '기기 모델 = 장치 패키지(DEV-04, BR-DEV-13~16). 문서는 code가 PK — 규칙에 따라 id PK + UNIQUE(organization_id, code)';

COMMENT ON COLUMN data2flow_core.device_models.transform_script_id IS '참조(FK는 19-core-deferred-fks.sql): data2flow_core.scripts.id';

COMMENT ON COLUMN data2flow_core.device_models.default_rule_template_ids IS '참조(FK 없음, 배열, 12번 파일): data2flow_core.rule_templates.id. 승인 시 적용할 기본 규칙 템플릿(DEV-03.05)';

CREATE TABLE data2flow_core.model_metrics (
    organization_id bigint      NOT NULL,
    model_id        bigint      NOT NULL,
    metric_key      varchar(64) NOT NULL,
    required        boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_model_metrics PRIMARY KEY (model_id, metric_key),
    CONSTRAINT fk_model_metrics_model_id FOREIGN KEY (model_id) REFERENCES data2flow_core.device_models (id) ON DELETE CASCADE,
    CONSTRAINT fk_model_metrics_metric_key FOREIGN KEY (organization_id, metric_key)
        REFERENCES data2flow_core.metrics (organization_id, key) ON DELETE RESTRICT ON UPDATE CASCADE
);

CREATE INDEX ix_model_metrics_organization_id_metric_key ON data2flow_core.model_metrics (organization_id, metric_key);

COMMENT ON TABLE data2flow_core.model_metrics IS '모델이 내는 측정 항목(DEV-04.02). 테이블 이름 매핑에 없어 추가함';

CREATE TABLE data2flow_core.devices (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    organization_id       bigint       NOT NULL,
    source_id             bigint       NOT NULL,
    external_id           varchar(128) NOT NULL,
    name                  varchar(100) NOT NULL,
    kind                  varchar(10)  NOT NULL DEFAULT 'SENSOR',
    model_id              bigint,
    space_id              bigint,
    status                varchar(10)  NOT NULL DEFAULT 'PENDING',
    is_virtual            boolean      NOT NULL DEFAULT false,
    expected_interval_sec integer,
    offline_multiplier    numeric(3,1),
    approved_at           timestamptz,
    approved_by           bigint,
    auto_registered       boolean      NOT NULL DEFAULT false,
    first_seen_at         timestamptz,
    source_meta           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    logical_device_id     bigint,
    replaced_by_device_id bigint,
    replaced_at           timestamptz,
    qr_token              varchar(24),
    version               integer      NOT NULL DEFAULT 0,
    created_by            bigint,
    updated_by            bigint,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_devices PRIMARY KEY (id),
    CONSTRAINT uq_devices_source_id_external_id UNIQUE (source_id, external_id),
    CONSTRAINT uq_devices_qr_token UNIQUE (qr_token),
    CONSTRAINT fk_devices_model_id FOREIGN KEY (model_id) REFERENCES data2flow_core.device_models (id) ON DELETE RESTRICT,
    CONSTRAINT fk_devices_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT fk_devices_logical_device_id FOREIGN KEY (logical_device_id) REFERENCES data2flow_core.devices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_devices_replaced_by_device_id FOREIGN KEY (replaced_by_device_id) REFERENCES data2flow_core.devices (id) ON DELETE RESTRICT,
    CONSTRAINT ck_devices_kind CHECK (kind IN ('SENSOR', 'ACTUATOR', 'GATEWAY', 'HYBRID')),
    CONSTRAINT ck_devices_status CHECK (status IN ('PENDING', 'ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_devices_interval CHECK (expected_interval_sec IS NULL OR expected_interval_sec BETWEEN 10 AND 86400),
    CONSTRAINT ck_devices_multiplier CHECK (offline_multiplier IS NULL OR offline_multiplier BETWEEN 1.5 AND 10)
);

CREATE INDEX ix_devices_organization_id_status ON data2flow_core.devices (organization_id, status);

CREATE INDEX ix_devices_organization_id_space_id ON data2flow_core.devices (organization_id, space_id);

CREATE INDEX ix_devices_organization_id_model_id ON data2flow_core.devices (organization_id, model_id);

CREATE INDEX ix_devices_logical_device_id ON data2flow_core.devices (logical_device_id);

COMMENT ON TABLE data2flow_core.devices IS '기기(DEV-02·03, BR-DEV-06~12). 삭제는 status=DELETED (문서의 deleted_at 대체). 문서의 model_code는 model_id로 바꿈';

COMMENT ON COLUMN data2flow_core.devices.source_id IS '참조(FK는 19-core-deferred-fks.sql): data2flow_core.data_sources.id';

CREATE TABLE data2flow_core.device_tags (
    organization_id bigint      NOT NULL,
    device_id       bigint      NOT NULL,
    tag             varchar(40) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_tags PRIMARY KEY (device_id, tag),
    CONSTRAINT fk_device_tags_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE
);

CREATE INDEX ix_device_tags_organization_id_tag ON data2flow_core.device_tags (organization_id, tag);

COMMENT ON TABLE data2flow_core.device_tags IS '기기 태그(DEV-02.05)';

CREATE TABLE data2flow_core.device_space_relations (
    organization_id bigint      NOT NULL,
    device_id       bigint      NOT NULL,
    space_id        bigint      NOT NULL,
    relation        varchar(10) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_space_relations PRIMARY KEY (device_id, space_id, relation),
    CONSTRAINT fk_device_space_relations_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_device_space_relations_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE,
    CONSTRAINT ck_device_space_relations_relation CHECK (relation IN ('MEASURES', 'CONTROLS'))
);

CREATE INDEX ix_device_space_relations_organization_id_space_id ON data2flow_core.device_space_relations (organization_id, space_id, relation);

COMMENT ON TABLE data2flow_core.device_space_relations IS '설치 공간 외 측정·제어 대상 공간(DEV-01.07)';

CREATE TABLE data2flow_core.device_attributes (
    organization_id bigint      NOT NULL,
    device_id       bigint      NOT NULL,
    scope           varchar(10) NOT NULL,
    key             varchar(64) NOT NULL,
    value           jsonb,
    desired_value   jsonb,
    reported_value  jsonb,
    updated_by      bigint,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_attributes PRIMARY KEY (device_id, scope, key),
    CONSTRAINT fk_device_attributes_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE,
    CONSTRAINT ck_device_attributes_scope CHECK (scope IN ('SERVER', 'SHARED', 'CLIENT'))
);

CREATE INDEX ix_device_attributes_organization_id_key ON data2flow_core.device_attributes (organization_id, key);

COMMENT ON TABLE data2flow_core.device_attributes IS '기기 속성(DEV-02.08, BR-DEV-21) (참고: ThingsBoard)';

CREATE TABLE data2flow_core.device_attribute_history (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    device_id       bigint      NOT NULL,
    scope           varchar(10) NOT NULL,
    key             varchar(64) NOT NULL,
    old_value       jsonb,
    new_value       jsonb,
    changed_by      bigint,
    changed_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_attribute_history PRIMARY KEY (id),
    CONSTRAINT fk_device_attribute_history_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE
);

CREATE INDEX ix_device_attribute_history_organization_id_device_id ON data2flow_core.device_attribute_history (organization_id, device_id, changed_at DESC);

COMMENT ON TABLE data2flow_core.device_attribute_history IS '속성 변경 이력(DEV-02.08)';

CREATE TABLE data2flow_core.gateways (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    source_id       bigint       NOT NULL,
    gateway_eui     varchar(32)  NOT NULL,
    name            varchar(100),
    space_id        bigint,
    last_seen_at    timestamptz,
    status          varchar(10)  NOT NULL DEFAULT 'UNKNOWN',
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_gateways PRIMARY KEY (id),
    CONSTRAINT uq_gateways_source_id_gateway_eui UNIQUE (source_id, gateway_eui),
    CONSTRAINT fk_gateways_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE SET NULL,
    CONSTRAINT ck_gateways_status CHECK (status IN ('ONLINE', 'OFFLINE', 'UNKNOWN'))
);

CREATE INDEX ix_gateways_organization_id_status ON data2flow_core.gateways (organization_id, status);

COMMENT ON TABLE data2flow_core.gateways IS 'LoRaWAN 게이트웨이 조회 정보(DEV-02.09). 관리는 ChirpStack(ADR-001)';

COMMENT ON COLUMN data2flow_core.gateways.source_id IS '참조(FK는 19-core-deferred-fks.sql): data2flow_core.data_sources.id';

CREATE TABLE data2flow_core.device_groups (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(100) NOT NULL,
    type            varchar(10)  NOT NULL,
    criteria        jsonb,
    member_count    integer      NOT NULL DEFAULT 0,
    description     varchar(500),
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint,
    updated_by      bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_groups PRIMARY KEY (id),
    CONSTRAINT uq_device_groups_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_device_groups_type CHECK (type IN ('STATIC', 'DYNAMIC')),
    CONSTRAINT ck_device_groups_criteria CHECK (type <> 'DYNAMIC' OR criteria IS NOT NULL)
);

COMMENT ON TABLE data2flow_core.device_groups IS '기기 그룹, 정적·동적(DEV-06, BR-DEV-22·23)';

CREATE TABLE data2flow_core.device_group_members (
    organization_id bigint      NOT NULL,
    group_id        bigint      NOT NULL,
    device_id       bigint      NOT NULL,
    source          varchar(10) NOT NULL,
    added_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_group_members PRIMARY KEY (group_id, device_id),
    CONSTRAINT fk_device_group_members_group_id FOREIGN KEY (group_id) REFERENCES data2flow_core.device_groups (id) ON DELETE CASCADE,
    CONSTRAINT fk_device_group_members_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE,
    CONSTRAINT ck_device_group_members_source CHECK (source IN ('STATIC', 'DYNAMIC'))
);

CREATE INDEX ix_device_group_members_organization_id_device_id ON data2flow_core.device_group_members (organization_id, device_id);

CREATE TABLE data2flow_core.equipment (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    device_id       bigint,
    space_id        bigint,
    equip_class     varchar(80)  NOT NULL,
    name            varchar(100) NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_equipment PRIMARY KEY (id),
    CONSTRAINT fk_equipment_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_equipment_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE SET NULL
);

CREATE INDEX ix_equipment_organization_id_device_id ON data2flow_core.equipment (organization_id, device_id);

COMMENT ON TABLE data2flow_core.equipment IS '시맨틱 장비, Brick 클래스(DEV-12) (참고: Brick Schema)';

CREATE TABLE data2flow_core.points (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    equipment_id    bigint      NOT NULL,
    metric_key      varchar(64) NOT NULL,
    point_type      varchar(12) NOT NULL,
    quantity        varchar(80),
    tags            text[]      NOT NULL DEFAULT '{}',
    source          varchar(10) NOT NULL DEFAULT 'MODEL',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_points PRIMARY KEY (id),
    CONSTRAINT uq_points_equipment_id_metric_key UNIQUE (equipment_id, metric_key),
    CONSTRAINT fk_points_equipment_id FOREIGN KEY (equipment_id) REFERENCES data2flow_core.equipment (id) ON DELETE CASCADE,
    CONSTRAINT fk_points_metric_key FOREIGN KEY (organization_id, metric_key)
        REFERENCES data2flow_core.metrics (organization_id, key) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT ck_points_point_type CHECK (point_type IN ('MEASUREMENT', 'CONTROL', 'STATUS', 'SETPOINT')),
    CONSTRAINT ck_points_source CHECK (source IN ('MODEL', 'USER'))
);

CREATE INDEX ix_points_organization_id_metric_key ON data2flow_core.points (organization_id, metric_key);

CREATE TABLE data2flow_core.space_targets (
    organization_id bigint           NOT NULL,
    space_id        bigint           NOT NULL,
    metric_key      varchar(64)      NOT NULL,
    min_value       double precision,
    max_value       double precision,
    version         integer          NOT NULL DEFAULT 0,
    updated_by      bigint,
    created_at      timestamptz      NOT NULL DEFAULT now(),
    updated_at      timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT pk_space_targets PRIMARY KEY (space_id, metric_key),
    CONSTRAINT fk_space_targets_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_space_targets_metric_key FOREIGN KEY (organization_id, metric_key)
        REFERENCES data2flow_core.metrics (organization_id, key) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT ck_space_targets_range CHECK (min_value IS NULL OR max_value IS NULL OR min_value < max_value)
);

CREATE INDEX ix_space_targets_organization_id_metric_key ON data2flow_core.space_targets (organization_id, metric_key);

COMMENT ON TABLE data2flow_core.space_targets IS '공간 목표 범위. 행이 없으면 상위 공간 값 상속(DEV-01.05)';

CREATE TABLE data2flow_core.floorplans (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    space_id        bigint        NOT NULL,
    object_key      varchar(300)  NOT NULL,
    width_px        integer       NOT NULL,
    height_px       integer       NOT NULL,
    scale_m_per_px  numeric(10,6),
    version         integer       NOT NULL DEFAULT 0,
    updated_by      bigint,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_floorplans PRIMARY KEY (id),
    CONSTRAINT uq_floorplans_space_id UNIQUE (space_id),
    CONSTRAINT fk_floorplans_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE,
    CONSTRAINT ck_floorplans_size CHECK (width_px > 0 AND height_px > 0)
);

CREATE INDEX ix_floorplans_organization_id ON data2flow_core.floorplans (organization_id);

COMMENT ON TABLE data2flow_core.floorplans IS '공간 도면(DEV-09, DSH-12)';

CREATE TABLE data2flow_core.floorplan_markers (
    organization_id bigint       NOT NULL,
    floorplan_id    bigint       NOT NULL,
    device_id       bigint       NOT NULL,
    x               numeric(7,4) NOT NULL,
    y               numeric(7,4) NOT NULL,
    rotation        smallint     NOT NULL DEFAULT 0,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_floorplan_markers PRIMARY KEY (floorplan_id, device_id),
    CONSTRAINT fk_floorplan_markers_floorplan_id FOREIGN KEY (floorplan_id) REFERENCES data2flow_core.floorplans (id) ON DELETE CASCADE,
    CONSTRAINT fk_floorplan_markers_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE,
    CONSTRAINT ck_floorplan_markers_xy CHECK (x BETWEEN 0 AND 1 AND y BETWEEN 0 AND 1),
    CONSTRAINT ck_floorplan_markers_rotation CHECK (rotation BETWEEN 0 AND 359)
);

CREATE INDEX ix_floorplan_markers_organization_id_device_id ON data2flow_core.floorplan_markers (organization_id, device_id);

CREATE TABLE data2flow_core.user_dashboard_prefs (
    organization_id      bigint      NOT NULL,
    user_id              bigint      NOT NULL,
    default_dashboard_id bigint,
    home                 varchar(10) NOT NULL DEFAULT 'HOME',
    theme                varchar(10) NOT NULL DEFAULT 'SYSTEM',
    locale               varchar(10),
    time_zone            varchar(64),
    favorites            jsonb       NOT NULL DEFAULT '[]'::jsonb,
    recent               jsonb       NOT NULL DEFAULT '[]'::jsonb,
    tours_dismissed      text[]      NOT NULL DEFAULT '{}',
    version              integer     NOT NULL DEFAULT 0,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_dashboard_prefs PRIMARY KEY (user_id),
    CONSTRAINT fk_user_dashboard_prefs_user_id FOREIGN KEY (user_id) REFERENCES data2flow_core.app_users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_dashboard_prefs_home CHECK (home IN ('HOME', 'DASHBOARD')),
    CONSTRAINT ck_user_dashboard_prefs_theme CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM'))
);

COMMENT ON TABLE data2flow_core.user_dashboard_prefs IS '사용자 화면 설정(1:1, DSH-03·14)';

CREATE TABLE data2flow_core.connector_catalogs (
    connector_key varchar(40) NOT NULL,
    version varchar(20) NOT NULL,
    category varchar(20) NOT NULL,
    config_schema jsonb NOT NULL,
    auth_methods text[] NOT NULL,
    payload_formats text[] NOT NULL,
    ack_mode varchar(15) NOT NULL,
    scaling varchar(12) NOT NULL,
    supports_send boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    disabled_reason varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_connector_catalogs PRIMARY KEY (connector_key),
    CONSTRAINT ck_connector_catalogs_category CHECK (category IN ('MQTT','LORAWAN','CLOUD_HUB','QUEUE','HTTP','LIGHTWEIGHT','INDUSTRIAL','FILE','PLATFORM')),
    CONSTRAINT ck_connector_catalogs_ack_mode CHECK (ack_mode IN ('AFTER_WRITE','AUTO','CURSOR','NONE')),
    CONSTRAINT ck_connector_catalogs_scaling CHECK (scaling IN ('SCALABLE','DUAL_ACTIVE','SINGLETON'))
);

COMMENT ON TABLE data2flow_core.connector_catalogs IS '커넥터 카탈로그(시스템 공용, 조직 구분 없음). ingress 보고로 갱신. DSC-09';

COMMENT ON COLUMN data2flow_core.connector_catalogs.connector_key IS '예: mqtt, sparkplug-b, amqp091, kafka, nats, opcua, modbus, bacnet, onem2m, file-import';

COMMENT ON COLUMN data2flow_core.connector_catalogs.config_schema IS 'JSON Schema 2020-12. 소스 설정 폼 자동 생성';

COMMENT ON COLUMN data2flow_core.connector_catalogs.ack_mode IS '무손실 확인 시점(DSC-09.03)';

COMMENT ON COLUMN data2flow_core.connector_catalogs.disabled_reason IS '예: BACnet4J GPL-3.0 검토 중';

CREATE TABLE data2flow_core.connector_templates (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL DEFAULT 0,
    template_key varchar(64) NOT NULL,
    connector_key varchar(40) NOT NULL,
    name varchar(100) NOT NULL,
    description varchar(500),
    preset jsonb NOT NULL,
    decoder_key varchar(30),
    docs_url varchar(500),
    builtin boolean NOT NULL DEFAULT false,
    version integer NOT NULL DEFAULT 0,
    created_by bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_connector_templates PRIMARY KEY (id),
    CONSTRAINT uq_connector_templates_organization_id_template_key UNIQUE (organization_id, template_key),
    CONSTRAINT fk_connector_templates_connector_key FOREIGN KEY (connector_key) REFERENCES data2flow_core.connector_catalogs (connector_key) ON DELETE RESTRICT
);

COMMENT ON TABLE data2flow_core.connector_templates IS '커넥터 설정 템플릿. 플랫폼 기본(organization_id=0, builtin)은 읽기 전용, 조직 템플릿은 INTEGRATOR가 만든다. DSC-09';

CREATE TABLE data2flow_core.data_sources (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    code varchar(50) NOT NULL,
    name varchar(100) NOT NULL,
    type varchar(20) NOT NULL,
    connector_key varchar(40),
    connector_version varchar(20),
    lifecycle varchar(10) NOT NULL DEFAULT 'DRAFT',
    connection jsonb NOT NULL,
    tls jsonb,
    payload jsonb,
    topic_template varchar(256),
    send_enabled boolean NOT NULL DEFAULT false,
    is_dev boolean NOT NULL DEFAULT false,
    decoder_key varchar(30) NOT NULL,
    decoder_config jsonb,
    decode_script_id bigint,
    unknown_device_policy varchar(12) NOT NULL DEFAULT 'AUTO_REGISTER',
    default_model_id bigint,
    default_space_id bigint,
    autoreg_limit_per_hour integer NOT NULL DEFAULT 100,
    no_data_alarm_after_sec integer NOT NULL DEFAULT 600,
    site_id bigint,
    archived_at timestamptz,
    version integer NOT NULL DEFAULT 0,
    created_by bigint NOT NULL,
    updated_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_data_sources PRIMARY KEY (id),
    CONSTRAINT uq_data_sources_organization_id_code UNIQUE (organization_id, code),
    CONSTRAINT ck_data_sources_code CHECK (code ~ '^[a-z][a-z0-9-]{1,49}$'),
    CONSTRAINT ck_data_sources_type CHECK (type IN ('MQTT_SUBSCRIBE','PLATFORM_BROKER','SIMULATION','WEBHOOK','CONNECTOR','EDGE','KMA_WEATHER','AIRKOREA','HOLIDAY','ICAL','ONEM2M','OPCUA','MODBUS_TCP')),
    CONSTRAINT ck_data_sources_lifecycle CHECK (lifecycle IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
    CONSTRAINT ck_data_sources_unknown_device_policy CHECK (unknown_device_policy IN ('AUTO_REGISTER','REJECT')),
    CONSTRAINT ck_data_sources_autoreg_limit_per_hour CHECK (autoreg_limit_per_hour BETWEEN 0 AND 10000),
    CONSTRAINT ck_data_sources_no_data_alarm_after_sec CHECK (no_data_alarm_after_sec BETWEEN 60 AND 86400),
    CONSTRAINT ck_data_sources_connector_key CHECK (type <> 'CONNECTOR' OR connector_key IS NOT NULL),
    CONSTRAINT fk_data_sources_connector_key FOREIGN KEY (connector_key) REFERENCES data2flow_core.connector_catalogs (connector_key) ON DELETE RESTRICT,
    CONSTRAINT fk_data_sources_default_model_id FOREIGN KEY (default_model_id) REFERENCES data2flow_core.device_models (id) ON DELETE RESTRICT,
    CONSTRAINT fk_data_sources_default_space_id FOREIGN KEY (default_space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT fk_data_sources_site_id FOREIGN KEY (site_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT
);

CREATE INDEX ix_data_sources_organization_id_lifecycle ON data2flow_core.data_sources (organization_id, lifecycle);

COMMENT ON TABLE data2flow_core.data_sources IS '데이터 소스(DataSource 집합체 루트). 비밀값은 source_secrets. DSC-01·02·06·07·09';

COMMENT ON COLUMN data2flow_core.data_sources.code IS 'client-id·로그 표시용. 생성 후 변경 불가';

COMMENT ON COLUMN data2flow_core.data_sources.type IS '생성 후 변경 불가. CONNECTOR면 connector_key로 세부 프로토콜';

COMMENT ON COLUMN data2flow_core.data_sources.connection IS '유형별 JSON Schema 검증(비밀값 제외)';

COMMENT ON COLUMN data2flow_core.data_sources.is_dev IS 'TLS 검증 끄기 허용(BR-DSC-29)';

COMMENT ON COLUMN data2flow_core.data_sources.decoder_config IS '32KB 이하';

COMMENT ON COLUMN data2flow_core.data_sources.decode_script_id IS 'decoder_key=script일 때 필수. FK는 scripts 생성 후 아래 ALTER로 건다';

COMMENT ON COLUMN data2flow_core.data_sources.site_id IS '외부 맥락 유형(KMA_WEATHER·AIRKOREA·HOLIDAY·ICAL)일 때 필수, spaces(SITE)';

CREATE TABLE data2flow_core.source_topics (
    source_id bigint NOT NULL,
    organization_id bigint NOT NULL,
    topic varchar(256) NOT NULL,
    qos smallint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_source_topics PRIMARY KEY (source_id, topic),
    CONSTRAINT ck_source_topics_qos CHECK (qos IN (0, 1, 2)),
    CONSTRAINT fk_source_topics_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);

COMMENT ON TABLE data2flow_core.source_topics IS '구독 토픽. 소스당 최대 20개, 와일드카드 # + 허용(BR-DSC-06)';

CREATE TABLE data2flow_core.source_secrets (
    source_id bigint NOT NULL,
    organization_id bigint NOT NULL,
    kind varchar(16) NOT NULL,
    ciphertext bytea NOT NULL,
    kid varchar(20) NOT NULL,
    fingerprint char(8) NOT NULL,
    pending_ciphertext bytea,
    rotated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_source_secrets PRIMARY KEY (source_id, kind),
    CONSTRAINT ck_source_secrets_kind CHECK (kind IN ('PASSWORD','HEADER_VALUE','CLIENT_CERT','CLIENT_KEY','CA_CERT','API_KEY','HMAC_KEY','OAUTH2_CLIENT','SAS_KEY','SASL','AWS_KEYS','TLS_CA_BUNDLE','TLS_CLIENT_CERT','SCHEMA_FILE')),
    CONSTRAINT fk_source_secrets_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);

COMMENT ON TABLE data2flow_core.source_secrets IS '소스 비밀값. AES-256-GCM 암호문, 키는 k8s Secret(kid). 교체 중 새 값은 pending_ciphertext(DSC-07.02)';

COMMENT ON COLUMN data2flow_core.source_secrets.fingerprint IS '화면 표시용 끝 4자리 대체(••••3f2a)';

CREATE TABLE data2flow_core.source_runtimes (
    source_id bigint NOT NULL,
    instance_id varchar(64) NOT NULL,
    organization_id bigint NOT NULL,
    state varchar(12) NOT NULL,
    error_kind varchar(16),
    error_message varchar(500),
    client_id varchar(128),
    connected_since timestamptz,
    reconnects_24h integer NOT NULL DEFAULT 0,
    reported_at timestamptz NOT NULL,
    CONSTRAINT pk_source_runtimes PRIMARY KEY (source_id, instance_id),
    CONSTRAINT ck_source_runtimes_state CHECK (state IN ('CONNECTED','CONNECTING','DISCONNECTED','ERROR','DISABLED')),
    CONSTRAINT ck_source_runtimes_error_kind CHECK (error_kind IS NULL OR error_kind IN ('AUTH','DNS','TLS','TIMEOUT','REFUSED','PROTOCOL','QUOTA','OTHER')),
    CONSTRAINT fk_source_runtimes_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);

COMMENT ON TABLE data2flow_core.source_runtimes IS 'ingress 인스턴스별 연결 상태(EVT-DSC-02 보고를 core가 저장, 30초마다). 90초 넘게 보고 없는 인스턴스는 대표 상태 계산에서 제외';

COMMENT ON COLUMN data2flow_core.source_runtimes.instance_id IS 'ingress 파드 이름';

CREATE TABLE data2flow_core.source_stat_1m (
    source_id bigint NOT NULL,
    minute timestamptz NOT NULL,
    organization_id bigint NOT NULL,
    received integer NOT NULL DEFAULT 0,
    accepted integer NOT NULL DEFAULT 0,
    decode_errors integer NOT NULL DEFAULT 0,
    script_errors integer NOT NULL DEFAULT 0,
    rejected_unknown integer NOT NULL DEFAULT 0,
    invalid integer NOT NULL DEFAULT 0,
    dup integer NOT NULL DEFAULT 0,
    bytes bigint NOT NULL DEFAULT 0,
    reconnects integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_source_stat_1m PRIMARY KEY (source_id, minute),
    CONSTRAINT fk_source_stat_1m_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);

COMMENT ON TABLE data2flow_core.source_stat_1m IS '소스별 1분 수집 지표. 7일 보관(스케줄 작업이 지난 행 삭제). DSC-02';

CREATE TABLE data2flow_core.source_ignore_entries (
    source_id bigint NOT NULL,
    external_id varchar(128) NOT NULL,
    organization_id bigint NOT NULL,
    reason varchar(10) NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_source_ignore_entries PRIMARY KEY (source_id, external_id),
    CONSTRAINT ck_source_ignore_entries_reason CHECK (reason IN ('REJECTED','MANUAL')),
    CONSTRAINT fk_source_ignore_entries_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);

COMMENT ON TABLE data2flow_core.source_ignore_entries IS '자동 등록 무시 목록. 거부한 PENDING 기기의 외부 ID(BR-DEV-07)';

CREATE TABLE data2flow_core.device_credentials (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    device_id bigint NOT NULL,
    type varchar(8) NOT NULL DEFAULT 'PASSWORD',
    username varchar(128) NOT NULL,
    password_hash varchar(200),
    signing_key_hash char(64),
    status varchar(8) NOT NULL DEFAULT 'ACTIVE',
    expires_at timestamptz,
    last_used_at timestamptz,
    created_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_credentials PRIMARY KEY (id),
    CONSTRAINT ck_device_credentials_type CHECK (type IN ('PASSWORD')),
    CONSTRAINT ck_device_credentials_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT fk_device_credentials_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_device_credentials_username_active ON data2flow_core.device_credentials (username) WHERE status = 'ACTIVE';

CREATE INDEX ix_device_credentials_organization_id_device_id ON data2flow_core.device_credentials (organization_id, device_id);

COMMENT ON TABLE data2flow_core.device_credentials IS '플랫폼 브로커 직결 기기 자격(nginx Basic 계정 + 기기별 HMAC 서명 키). 승인 시 서명 키 1회 발급(DSC-03.05, ADR-029·031). 공용 브로커라 인증서 방식 없음';

COMMENT ON COLUMN data2flow_core.device_credentials.signing_key_hash IS 'payload HMAC 서명 키의 해시. 원문은 발급 응답에만';

CREATE TABLE data2flow_core.annotations (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    time_from timestamptz NOT NULL,
    time_to timestamptz,
    device_id bigint,
    space_id bigint,
    metric_key varchar(64),
    type varchar(20) NOT NULL,
    title varchar(150) NOT NULL,
    ref varchar(100),
    created_by bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_annotations PRIMARY KEY (id),
    CONSTRAINT ck_annotations_type CHECK (type IN ('ALARM','OFFLINE','SCRIPT_ERROR','ANOMALY','WORK_ORDER','REPLACEMENT','CALENDAR','MANUAL')),
    CONSTRAINT ck_annotations_time CHECK (time_to IS NULL OR time_to >= time_from),
    CONSTRAINT fk_annotations_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_annotations_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE
);

CREATE INDEX ix_annotations_organization_id_device_id_time_from ON data2flow_core.annotations (organization_id, device_id, time_from);

CREATE INDEX ix_annotations_organization_id_space_id_time_from ON data2flow_core.annotations (organization_id, space_id, time_from);

COMMENT ON TABLE data2flow_core.annotations IS '차트 주석. 기기·공간 둘 다 없으면 조직 전체. TSD-01.04, EVT-TSD-05';

CREATE TABLE data2flow_core.scripts (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    name varchar(80) NOT NULL,
    kind varchar(16) NOT NULL,
    description varchar(500),
    active_version_id bigint,
    status varchar(24) NOT NULL DEFAULT 'ENABLED',
    auto_disabled_at timestamptz,
    auto_disabled_reason varchar(200),
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    log_capture_until timestamptz,
    version integer NOT NULL DEFAULT 0,
    created_by bigint NOT NULL,
    updated_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_scripts PRIMARY KEY (id),
    CONSTRAINT uq_scripts_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_scripts_kind CHECK (kind IN ('DECODE','TRANSFORM')),
    CONSTRAINT ck_scripts_status CHECK (status IN ('ENABLED','DISABLED','AUTO_DISABLED'))
);

COMMENT ON TABLE data2flow_core.scripts IS '스크립트(Script 집합체 루트). config는 ctx.config 설정값(항목 50개·값 1KB, 비밀값 금지). SCR-01·02·05';

CREATE TABLE data2flow_core.script_versions (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    script_id bigint NOT NULL,
    version_no integer NOT NULL,
    code text NOT NULL,
    code_sha256 char(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    static_check jsonb NOT NULL,
    module_refs jsonb,
    deploy_memo varchar(200),
    forced boolean NOT NULL DEFAULT false,
    force_reason varchar(200),
    test_result jsonb,
    deployed_by bigint,
    deployed_at timestamptz,
    created_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_script_versions PRIMARY KEY (id),
    CONSTRAINT uq_script_versions_script_id_version_no UNIQUE (script_id, version_no),
    CONSTRAINT ck_script_versions_status CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    CONSTRAINT ck_script_versions_code CHECK (octet_length(code) <= 65536),
    CONSTRAINT fk_script_versions_script_id FOREIGN KEY (script_id) REFERENCES data2flow_core.scripts (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_script_versions_script_id_active ON data2flow_core.script_versions (script_id) WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_script_versions_script_id_draft ON data2flow_core.script_versions (script_id) WHERE status = 'DRAFT';

COMMENT ON TABLE data2flow_core.script_versions IS '스크립트 버전. 스크립트당 ACTIVE·DRAFT 각 1개, 최근 50개 보관(BR-SCR-09·10)';

ALTER TABLE data2flow_core.scripts ADD CONSTRAINT fk_scripts_active_version_id FOREIGN KEY (active_version_id) REFERENCES data2flow_core.script_versions (id) ON DELETE SET NULL;

ALTER TABLE data2flow_core.data_sources ADD CONSTRAINT fk_data_sources_decode_script_id FOREIGN KEY (decode_script_id) REFERENCES data2flow_core.scripts (id) ON DELETE RESTRICT;

CREATE TABLE data2flow_core.script_bindings (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    script_id bigint NOT NULL,
    kind varchar(16) NOT NULL,
    target_type varchar(16) NOT NULL,
    target_id varchar(64) NOT NULL,
    failure_policy varchar(16) NOT NULL DEFAULT 'FAIL_OPEN',
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_script_bindings PRIMARY KEY (id),
    CONSTRAINT uq_script_bindings_organization_id_target_type_target_id_kind UNIQUE (organization_id, target_type, target_id, kind),
    CONSTRAINT ck_script_bindings_kind CHECK (kind IN ('DECODE','TRANSFORM')),
    CONSTRAINT ck_script_bindings_target_type CHECK (target_type IN ('SOURCE','MODEL','DEVICE')),
    CONSTRAINT ck_script_bindings_decode_source CHECK (kind <> 'DECODE' OR target_type = 'SOURCE'),
    CONSTRAINT ck_script_bindings_failure_policy CHECK (failure_policy IN ('FAIL_OPEN','FAIL_CLOSED')),
    CONSTRAINT fk_script_bindings_script_id FOREIGN KEY (script_id) REFERENCES data2flow_core.scripts (id) ON DELETE CASCADE
);

COMMENT ON TABLE data2flow_core.script_bindings IS '스크립트 연결. 대상마다 종류별 1개, 실행 순서 모델 → 기기(SCR-02.03)';

COMMENT ON COLUMN data2flow_core.script_bindings.kind IS '스크립트 kind 사본(UNIQUE 판정용)';

COMMENT ON COLUMN data2flow_core.script_bindings.target_id IS '소스 ID, 모델 코드, 기기 ID(문자열)';

CREATE INDEX ix_script_versions_organization_id_script_id ON data2flow_core.script_versions (organization_id, script_id);

CREATE INDEX ix_source_ignore_entries_organization_id_source_id ON data2flow_core.source_ignore_entries (organization_id, source_id);

CREATE INDEX ix_source_runtimes_organization_id_source_id ON data2flow_core.source_runtimes (organization_id, source_id);

CREATE INDEX ix_source_secrets_organization_id_source_id ON data2flow_core.source_secrets (organization_id, source_id);

CREATE INDEX ix_source_stat_1m_organization_id_source_id ON data2flow_core.source_stat_1m (organization_id, source_id);

CREATE INDEX ix_source_topics_organization_id_source_id ON data2flow_core.source_topics (organization_id, source_id);

-- 파일 순서 때문에 미뤄 둔 외래 키(19-core-deferred-fks.sql)
ALTER TABLE data2flow_core.devices
    ADD CONSTRAINT fk_devices_source_id FOREIGN KEY (source_id)
        REFERENCES data2flow_core.data_sources (id) ON DELETE RESTRICT;
ALTER TABLE data2flow_core.gateways
    ADD CONSTRAINT fk_gateways_source_id FOREIGN KEY (source_id)
        REFERENCES data2flow_core.data_sources (id) ON DELETE RESTRICT;
ALTER TABLE data2flow_core.device_models
    ADD CONSTRAINT fk_device_models_transform_script_id FOREIGN KEY (transform_script_id)
        REFERENCES data2flow_core.scripts (id) ON DELETE RESTRICT;
ALTER TABLE data2flow_core.device_models
    ADD CONSTRAINT fk_device_models_decode_script_id FOREIGN KEY (decode_script_id)
        REFERENCES data2flow_core.scripts (id) ON DELETE RESTRICT;

-- 내부 전체 조회의 설정 버전(sinceVersion과 같으면 204). 바꾸는 트랜잭션 안에서 1 올린다
CREATE TABLE data2flow_core.config_versions (
    organization_id bigint      NOT NULL,
    scope           varchar(20) NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_config_versions PRIMARY KEY (organization_id, scope),
    CONSTRAINT ck_config_versions_scope CHECK (scope IN ('METRICS', 'SOURCES', 'SCRIPTS', 'DEVICES', 'MODELS', 'SPACES', 'GROUPS'))
);
COMMENT ON TABLE data2flow_core.config_versions IS '조직·범위별 설정 버전(API-DEV-123 metrics, API-DSC-50 sources, API-SCR-32 scripts 등 sinceVersion)';
