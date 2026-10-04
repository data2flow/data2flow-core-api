-- M5 기기 운영 편의(DEV-08·09.04·13.03~13.06·03.04): 자산 정보, 작업 지시·정기 점검, 현장 설치, 저장된 검색, 표준 형식 내보내기.
-- 정본: design/erd/core-space-device.md §3·§4·§6, ddl/11-core-space-device.sql. 추가만(ADR-030).
-- ERD와 다른 점(문서에 반영 요청):
--   * file_blobs: core에 오브젝트 저장소 클라이언트가 아직 없어(평면도와 같은 이유, V202610050910) 작업 지시 첨부·자산 사진·설치 사진·
--     표준 내보내기 결과를 DB(bytea)에 둔다. 각 표의 object_key·photo_keys·photo_refs·file_ref에는 'db:file_blobs/<id>'를 적는다.
--   * work_orders.linked_origins: 같은 기기·유형의 열린 작업에 자동 생성 요청이 다시 오면 출처를 덧붙인다(BR-DEV-21, AT-DEV-16.2).
--   * asset_info.warranty_notified_for: 보증 만료 30일 전 알림을 한 번만 보내려고 알린 만료일을 적는다(AT-DEV-16.5).
--   * device_commissionings.checklist·checked_at: 10분 무수신 때 계산한 점검 체크리스트(BR-DEV-38).
--   * ngsi_pushes: ERD에 없음("확인 필요"). API-DEV-136 응답 필드대로 최소 열.

CREATE TABLE data2flow_core.file_blobs (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    purpose         varchar(20)   NOT NULL,
    file_name       varchar(200)  NOT NULL,
    content_type    varchar(100)  NOT NULL,
    size_bytes      bigint        NOT NULL,
    data            bytea         NOT NULL,
    created_by      bigint,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_file_blobs PRIMARY KEY (id),
    CONSTRAINT ck_file_blobs_purpose CHECK (purpose IN ('WORK_ORDER', 'ASSET_PHOTO', 'COMMISSIONING', 'DEVICE_EXPORT')),
    CONSTRAINT ck_file_blobs_size CHECK (size_bytes BETWEEN 0 AND 104857600)
);
CREATE INDEX ix_file_blobs_organization_id ON data2flow_core.file_blobs (organization_id, purpose);
COMMENT ON TABLE data2flow_core.file_blobs IS '첨부·사진·내보내기 파일(오브젝트 저장소 도입 전 임시 보관, DEV-08·13). 키는 db:file_blobs/<id>';

CREATE TABLE data2flow_core.asset_info (
    organization_id       bigint       NOT NULL,
    device_id             bigint       NOT NULL,
    serial_no             varchar(100),
    purchased_on          date,
    installed_on          date,
    warranty_until        date,
    supplier              varchar(100),
    installer             varchar(100),
    photo_keys            text[]       NOT NULL DEFAULT '{}',
    warranty_notified_for date,
    version               integer      NOT NULL DEFAULT 0,
    updated_by            bigint,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_asset_info PRIMARY KEY (device_id),
    CONSTRAINT fk_asset_info_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE
);
CREATE INDEX ix_asset_info_organization_id_warranty_until ON data2flow_core.asset_info (organization_id, warranty_until);
COMMENT ON TABLE data2flow_core.asset_info IS '자산 정보(1:1, DEV-08.01)';

CREATE TABLE data2flow_core.device_commissionings (
    organization_id  bigint      NOT NULL,
    device_id        bigint      NOT NULL,
    status           varchar(10) NOT NULL DEFAULT 'PLANNED',
    planned_space_id bigint,
    installed_by     bigint,
    installed_at     timestamptz,
    photo_refs       text[]      NOT NULL DEFAULT '{}',
    x                numeric(7,4),
    y                numeric(7,4),
    first_seen_at    timestamptz,
    client_op_id     uuid,
    checklist        jsonb,
    checked_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_commissionings PRIMARY KEY (device_id),
    CONSTRAINT uq_device_commissionings_client_op_id UNIQUE (client_op_id),
    CONSTRAINT fk_device_commissionings_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_device_commissionings_planned_space_id FOREIGN KEY (planned_space_id) REFERENCES data2flow_core.spaces (id) ON DELETE SET NULL,
    CONSTRAINT ck_device_commissionings_status CHECK (status IN ('PLANNED', 'INSTALLED', 'VERIFIED', 'PROBLEM')),
    CONSTRAINT ck_device_commissionings_xy CHECK ((x IS NULL OR x BETWEEN 0 AND 1) AND (y IS NULL OR y BETWEEN 0 AND 1))
);
CREATE INDEX ix_device_commissionings_organization_id_status ON data2flow_core.device_commissionings (organization_id, status);
COMMENT ON TABLE data2flow_core.device_commissionings IS '현장 설치 확인(1:1). client_op_id는 오프라인 재전송 멱등 키(DEV-13.05, BR-DEV-37·38)';

CREATE TABLE data2flow_core.saved_searches (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    owner_id        bigint        NOT NULL,
    name            varchar(100)  NOT NULL,
    query           text          NOT NULL,
    ast             jsonb         NOT NULL,
    shared          boolean       NOT NULL DEFAULT false,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_saved_searches PRIMARY KEY (id),
    CONSTRAINT uq_saved_searches_owner_id_name UNIQUE (owner_id, name),
    CONSTRAINT fk_saved_searches_owner_id FOREIGN KEY (owner_id) REFERENCES data2flow_core.app_users (id) ON DELETE CASCADE
);
CREATE INDEX ix_saved_searches_organization_id_shared ON data2flow_core.saved_searches (organization_id, shared);
COMMENT ON TABLE data2flow_core.saved_searches IS '저장한 기기 검색(DEV-13.03)';

CREATE TABLE data2flow_core.device_export_jobs (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    format          varchar(15)  NOT NULL,
    scope           jsonb        NOT NULL,
    include_values  boolean      NOT NULL DEFAULT false,
    status          varchar(10)  NOT NULL DEFAULT 'QUEUED',
    file_ref        varchar(300),
    report          jsonb,
    created_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_device_export_jobs PRIMARY KEY (id),
    CONSTRAINT ck_device_export_jobs_format CHECK (format IN ('DTDL', 'NGSI_LD', 'BRICK_TTL', 'BRICK_JSONLD')),
    CONSTRAINT ck_device_export_jobs_status CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED'))
);
CREATE INDEX ix_device_export_jobs_organization_id_created_at ON data2flow_core.device_export_jobs (organization_id, created_at DESC);
COMMENT ON TABLE data2flow_core.device_export_jobs IS '표준 모델 내보내기(DEV-13.04)';

CREATE TABLE data2flow_core.ngsi_pushes (
    id                   bigint GENERATED ALWAYS AS IDENTITY,
    organization_id      bigint       NOT NULL,
    output_connection_id bigint       NOT NULL,
    scope                jsonb        NOT NULL DEFAULT '{"spaceIds":[],"deviceIds":[]}'::jsonb,
    interval_sec         integer      NOT NULL,
    enabled              boolean      NOT NULL DEFAULT true,
    last_sent_at         timestamptz,
    last_error           varchar(500),
    created_by           bigint       NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ngsi_pushes PRIMARY KEY (id),
    CONSTRAINT ck_ngsi_pushes_interval CHECK (interval_sec BETWEEN 60 AND 86400)
);
CREATE INDEX ix_ngsi_pushes_organization_id ON data2flow_core.ngsi_pushes (organization_id);
COMMENT ON TABLE data2flow_core.ngsi_pushes IS 'NGSI-LD 주기 전송 설정(DEV-13.04, API-DEV-136). 대상은 출력 연결(output_connections, FK 없음)';

-- 작업 지시·정기 점검
CREATE TABLE data2flow_core.maintenance_plans (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    organization_id     bigint       NOT NULL,
    name                varchar(100) NOT NULL,
    target_group_id     bigint       NOT NULL,
    work_type           varchar(15)  NOT NULL,
    interval_days       integer      NOT NULL,
    lead_days           integer      NOT NULL DEFAULT 7,
    next_due_on         date         NOT NULL,
    default_assignee_id bigint,
    checklist_template  jsonb        NOT NULL DEFAULT '[]'::jsonb,
    enabled             boolean      NOT NULL DEFAULT true,
    version             integer      NOT NULL DEFAULT 0,
    created_by          bigint,
    updated_by          bigint,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_maintenance_plans PRIMARY KEY (id),
    CONSTRAINT fk_maintenance_plans_target_group_id FOREIGN KEY (target_group_id) REFERENCES data2flow_core.device_groups (id) ON DELETE RESTRICT,
    CONSTRAINT fk_maintenance_plans_default_assignee_id FOREIGN KEY (default_assignee_id) REFERENCES data2flow_core.app_users (id) ON DELETE SET NULL,
    CONSTRAINT ck_maintenance_plans_work_type CHECK (work_type IN ('BATTERY', 'CALIBRATION', 'INSPECTION', 'REPAIR', 'REPLACE', 'RELOCATE', 'OTHER')),
    CONSTRAINT ck_maintenance_plans_interval CHECK (interval_days BETWEEN 7 AND 1095),
    CONSTRAINT ck_maintenance_plans_lead CHECK (lead_days BETWEEN 0 AND 60)
);
CREATE INDEX ix_maintenance_plans_organization_id_next_due_on ON data2flow_core.maintenance_plans (organization_id, next_due_on) WHERE enabled;
COMMENT ON TABLE data2flow_core.maintenance_plans IS '정기 점검 계획 → 작업 지시 자동 생성(DEV-08.05, BR-DEV-28)';

CREATE TABLE data2flow_core.work_orders (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    organization_id     bigint       NOT NULL,
    title               varchar(150) NOT NULL,
    type                varchar(15)  NOT NULL,
    status              varchar(15)  NOT NULL DEFAULT 'OPEN',
    priority            varchar(10)  NOT NULL DEFAULT 'NORMAL',
    assignee_id         bigint,
    requester_id        bigint,
    due_at              timestamptz,
    origin              varchar(10)  NOT NULL DEFAULT 'MANUAL',
    origin_ref          varchar(100),
    linked_origins      jsonb        NOT NULL DEFAULT '[]'::jsonb,
    maintenance_plan_id bigint,
    result              jsonb,
    completed_at        timestamptz,
    version             integer      NOT NULL DEFAULT 0,
    created_by          bigint,
    updated_by          bigint,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_work_orders PRIMARY KEY (id),
    CONSTRAINT fk_work_orders_assignee_id FOREIGN KEY (assignee_id) REFERENCES data2flow_core.app_users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_orders_requester_id FOREIGN KEY (requester_id) REFERENCES data2flow_core.app_users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_orders_maintenance_plan_id FOREIGN KEY (maintenance_plan_id) REFERENCES data2flow_core.maintenance_plans (id) ON DELETE SET NULL,
    CONSTRAINT ck_work_orders_type CHECK (type IN ('BATTERY', 'CALIBRATION', 'INSPECTION', 'REPAIR', 'REPLACE', 'RELOCATE', 'OTHER')),
    CONSTRAINT ck_work_orders_status CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_work_orders_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_work_orders_origin CHECK (origin IN ('MANUAL', 'ALARM', 'ANALYSIS', 'FLOW', 'SCHEDULE'))
);
CREATE INDEX ix_work_orders_organization_id_status ON data2flow_core.work_orders (organization_id, status, due_at);
CREATE INDEX ix_work_orders_organization_id_assignee_id ON data2flow_core.work_orders (organization_id, assignee_id, status);
CREATE UNIQUE INDEX uq_work_orders_organization_id_origin_ref_open ON data2flow_core.work_orders (organization_id, origin, origin_ref)
    WHERE origin_ref IS NOT NULL AND status NOT IN ('DONE', 'CANCELLED');
COMMENT ON TABLE data2flow_core.work_orders IS '작업 지시(DEV-08.02, BR-DEV-21). 같은 원인의 열린 작업은 하나만';
COMMENT ON COLUMN data2flow_core.work_orders.maintenance_plan_id IS 'origin=SCHEDULE일 때 계획 연결(origin_ref=plan:<id>:<due>)';
COMMENT ON COLUMN data2flow_core.work_orders.linked_origins IS '덧붙인 출처 [{origin, originRef, at}] (BR-DEV-21, AT-DEV-16.2)';

CREATE TABLE data2flow_core.work_order_targets (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    work_order_id   bigint      NOT NULL,
    device_id       bigint,
    space_id        bigint,
    CONSTRAINT pk_work_order_targets PRIMARY KEY (id),
    CONSTRAINT fk_work_order_targets_work_order_id FOREIGN KEY (work_order_id) REFERENCES data2flow_core.work_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_work_order_targets_device_id FOREIGN KEY (device_id) REFERENCES data2flow_core.devices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_order_targets_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT ck_work_order_targets_one CHECK (num_nonnulls(device_id, space_id) = 1)
);
CREATE INDEX ix_work_order_targets_organization_id_device_id ON data2flow_core.work_order_targets (organization_id, device_id);
CREATE INDEX ix_work_order_targets_work_order_id ON data2flow_core.work_order_targets (work_order_id);

CREATE TABLE data2flow_core.work_order_checklists (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    work_order_id   bigint       NOT NULL,
    sort_order      integer      NOT NULL DEFAULT 0,
    text            varchar(200) NOT NULL,
    done            boolean      NOT NULL DEFAULT false,
    done_by         bigint,
    done_at         timestamptz,
    CONSTRAINT pk_work_order_checklists PRIMARY KEY (id),
    CONSTRAINT fk_work_order_checklists_work_order_id FOREIGN KEY (work_order_id) REFERENCES data2flow_core.work_orders (id) ON DELETE CASCADE
);
CREATE INDEX ix_work_order_checklists_organization_id_work_order_id ON data2flow_core.work_order_checklists (organization_id, work_order_id);

CREATE TABLE data2flow_core.work_order_attachments (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    work_order_id   bigint       NOT NULL,
    object_key      varchar(300) NOT NULL,
    kind            varchar(10)  NOT NULL,
    uploaded_by     bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_work_order_attachments PRIMARY KEY (id),
    CONSTRAINT fk_work_order_attachments_work_order_id FOREIGN KEY (work_order_id) REFERENCES data2flow_core.work_orders (id) ON DELETE CASCADE,
    CONSTRAINT ck_work_order_attachments_kind CHECK (kind IN ('PHOTO', 'FILE'))
);
CREATE INDEX ix_work_order_attachments_organization_id_work_order_id ON data2flow_core.work_order_attachments (organization_id, work_order_id);

CREATE TABLE data2flow_core.work_order_comments (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    work_order_id   bigint        NOT NULL,
    author_id       bigint        NOT NULL,
    body            varchar(2000) NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_work_order_comments PRIMARY KEY (id),
    CONSTRAINT fk_work_order_comments_work_order_id FOREIGN KEY (work_order_id) REFERENCES data2flow_core.work_orders (id) ON DELETE CASCADE
);
CREATE INDEX ix_work_order_comments_organization_id_work_order_id ON data2flow_core.work_order_comments (organization_id, work_order_id, created_at);
