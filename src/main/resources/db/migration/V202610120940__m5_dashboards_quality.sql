-- =====================================================================
-- M5 데이터 관리(core-api, 대시보드·공유·브랜딩·IFC·저장 지표):
--   DSH-04.01·04.06·04.07·04.08 사용자 정의 대시보드, DSH-06.03 읽기 전용 공유 링크,
--   DSH-12.04 IFC 건물 모델, DSH-13.01 브랜딩, OPS-01.03 저장 지표 일별 기록.
-- 정본 DDL: data2flow-docs design/erd/ddl/11-core-space-device.sql(dashboards·share_links·branding_settings·
--           building_models·building_model_space_maps).
-- 추가만 한다(ADR-030 expand). user_dashboard_prefs에는 M2에서 미뤄 둔 기본 대시보드 FK만 건다.
-- 문서와 다른 점:
--   * core에 오브젝트 저장소 클라이언트가 아직 없어 브랜딩 자산과 IFC 원본을 DB(bytea)에 둔다(평면도와 같은 방식,
--     V202610050910). object_key에는 'db:branding_assets/<id>'·'db:building_model_files/<id>'를 적는다.
--   * storage_snapshots: 저장 지표의 증가 추세(OPS-01.03 dailyGrowthBytes)를 위한 하루 1회 표 크기 기록(플랫폼 전체, 조직 없음).
-- =====================================================================

-- ---------------------------------------------------------------- 대시보드(DSH-04)
CREATE TABLE data2flow_core.dashboards (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(100) NOT NULL,
    description     varchar(500),
    visibility      varchar(10)  NOT NULL DEFAULT 'PRIVATE',
    owner_user_id   bigint       NOT NULL,
    layout          jsonb        NOT NULL DEFAULT '{"widgets":[]}'::jsonb,
    variables       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    time_range      jsonb        NOT NULL DEFAULT '{"relative":"24h"}'::jsonb,
    resolution      varchar(5)   NOT NULL DEFAULT 'AUTO',
    refresh         varchar(5)   NOT NULL DEFAULT 'LIVE',
    template_source varchar(64),
    status          varchar(10)  NOT NULL DEFAULT 'ACTIVE',
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint,
    updated_by      bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_dashboards PRIMARY KEY (id),
    CONSTRAINT fk_dashboards_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES data2flow_core.app_users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_dashboards_visibility CHECK (visibility IN ('PRIVATE', 'ORG')),
    CONSTRAINT ck_dashboards_resolution CHECK (resolution IN ('AUTO', 'RAW', '1h', '1d')),
    CONSTRAINT ck_dashboards_refresh CHECK (refresh IN ('LIVE', '30s', '1m', '5m', 'OFF')),
    CONSTRAINT ck_dashboards_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);
CREATE INDEX ix_dashboards_organization_id_visibility ON data2flow_core.dashboards (organization_id, visibility, status);
CREATE INDEX ix_dashboards_organization_id_owner_user_id ON data2flow_core.dashboards (organization_id, owner_user_id);
COMMENT ON TABLE data2flow_core.dashboards IS '사용자 정의 대시보드(DSH-04, BR-DSH-07·08·18). 삭제는 status=ARCHIVED';

-- M2는 dashboards가 없어 FK 없이 만들었다(ERD core-space-device.md user_dashboard_prefs). 지금은 가리킬 대시보드가 없으므로 값을 비운다
UPDATE data2flow_core.user_dashboard_prefs SET default_dashboard_id = NULL WHERE default_dashboard_id IS NOT NULL;
ALTER TABLE data2flow_core.user_dashboard_prefs
    ADD CONSTRAINT fk_user_dashboard_prefs_default_dashboard_id FOREIGN KEY (default_dashboard_id)
        REFERENCES data2flow_core.dashboards (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------- 공유 링크(DSH-06.03)
CREATE TABLE data2flow_core.share_links (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    dashboard_id    bigint      NOT NULL,
    token_hash      char(64)    NOT NULL,
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    last_used_at    timestamptz,
    created_by      bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_share_links PRIMARY KEY (id),
    CONSTRAINT uq_share_links_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_share_links_dashboard_id FOREIGN KEY (dashboard_id) REFERENCES data2flow_core.dashboards (id) ON DELETE CASCADE,
    CONSTRAINT ck_share_links_expires CHECK (expires_at > created_at AND expires_at <= created_at + interval '90 days')
);
CREATE INDEX ix_share_links_organization_id_dashboard_id ON data2flow_core.share_links (organization_id, dashboard_id);
COMMENT ON TABLE data2flow_core.share_links IS '읽기 전용 공유 링크(DSH-06.03, BR-DSH-12). 토큰은 SHA-256 해시만';

-- ---------------------------------------------------------------- 브랜딩(DSH-13.01)
CREATE TABLE data2flow_core.branding_settings (
    id                          bigint GENERATED ALWAYS AS IDENTITY,
    organization_id             bigint        NOT NULL,
    logo_light_object_key       varchar(300),
    logo_dark_object_key        varchar(300),
    favicon_object_key          varchar(300),
    primary_color               char(7),
    login_background_object_key varchar(300),
    login_message               varchar(300),
    mail_sender_name            varchar(100),
    mail_signature              varchar(1000),
    public_theme                varchar(6)    NOT NULL DEFAULT 'AUTO',
    app_name                    varchar(30),
    app_short_name              varchar(12),
    contrast_warning_acked      boolean       NOT NULL DEFAULT false,
    version                     integer       NOT NULL DEFAULT 0,
    created_by                  bigint        NOT NULL,
    updated_by                  bigint        NOT NULL,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    updated_at                  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_branding_settings PRIMARY KEY (id),
    CONSTRAINT uq_branding_settings_organization_id UNIQUE (organization_id),
    CONSTRAINT ck_branding_settings_primary_color CHECK (primary_color IS NULL OR primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_branding_settings_public_theme CHECK (public_theme IN ('LIGHT', 'DARK', 'AUTO'))
);
COMMENT ON TABLE data2flow_core.branding_settings IS '조직 브랜딩(DSH-13.01, BR-DSH-20). 조직당 1행';

CREATE TABLE data2flow_core.branding_assets (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    kind            varchar(20)  NOT NULL,
    content_type    varchar(40)  NOT NULL,
    size_bytes      integer      NOT NULL,
    sha256          char(64)     NOT NULL,
    data            bytea        NOT NULL,
    created_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_branding_assets PRIMARY KEY (id),
    CONSTRAINT ck_branding_assets_kind CHECK (kind IN ('LOGO_LIGHT', 'LOGO_DARK', 'FAVICON', 'LOGIN_BACKGROUND')),
    CONSTRAINT ck_branding_assets_content_type CHECK (content_type IN ('image/png', 'image/svg+xml', 'image/x-icon', 'image/jpeg')),
    CONSTRAINT ck_branding_assets_size CHECK (size_bytes BETWEEN 1 AND 1048576)
);
CREATE INDEX ix_branding_assets_organization_id ON data2flow_core.branding_assets (organization_id);
COMMENT ON TABLE data2flow_core.branding_assets IS '브랜딩 자산 원본(PNG·SVG 정화본 ≤1MB). 오브젝트 저장소 도입 전 임시 보관';

-- ---------------------------------------------------------------- IFC 건물 모델(DSH-12.04)
CREATE TABLE data2flow_core.building_models (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    space_id        bigint        NOT NULL,
    name            varchar(100)  NOT NULL,
    object_key      varchar(300)  NOT NULL,
    size_bytes      bigint        NOT NULL,
    ifc_schema      varchar(10),
    status          varchar(10)   NOT NULL DEFAULT 'PROCESSING',
    error           varchar(500),
    element_count   integer,
    version         integer       NOT NULL DEFAULT 0,
    created_by      bigint        NOT NULL,
    updated_by      bigint        NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_building_models PRIMARY KEY (id),
    CONSTRAINT fk_building_models_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE,
    CONSTRAINT ck_building_models_size_bytes CHECK (size_bytes > 0 AND size_bytes <= 209715200),
    CONSTRAINT ck_building_models_ifc_schema CHECK (ifc_schema IS NULL OR ifc_schema IN ('IFC2X3', 'IFC4', 'IFC4X3')),
    CONSTRAINT ck_building_models_status CHECK (status IN ('PROCESSING', 'READY', 'FAILED'))
);
CREATE INDEX ix_building_models_organization_id_space_id ON data2flow_core.building_models (organization_id, space_id);
COMMENT ON TABLE data2flow_core.building_models IS 'IFC 건물 모델(DSH-12.04, BR-DSH-21). 200MB 이하, 열람 전용';

CREATE TABLE data2flow_core.building_model_files (
    building_model_id bigint      NOT NULL,
    organization_id   bigint      NOT NULL,
    sha256            char(64)    NOT NULL,
    data              bytea       NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_building_model_files PRIMARY KEY (building_model_id),
    CONSTRAINT fk_building_model_files_building_model_id FOREIGN KEY (building_model_id)
        REFERENCES data2flow_core.building_models (id) ON DELETE CASCADE
);
CREATE INDEX ix_building_model_files_organization_id ON data2flow_core.building_model_files (organization_id);
COMMENT ON TABLE data2flow_core.building_model_files IS 'IFC 원본. 오브젝트 저장소 도입 전 임시 보관';

CREATE TABLE data2flow_core.building_model_space_maps (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint       NOT NULL,
    building_model_id bigint       NOT NULL,
    ifc_global_id     varchar(22)  NOT NULL,
    space_id          bigint       NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_building_model_space_maps PRIMARY KEY (id),
    CONSTRAINT uq_building_model_space_maps_model_global_id UNIQUE (building_model_id, ifc_global_id),
    CONSTRAINT fk_building_model_space_maps_building_model_id FOREIGN KEY (building_model_id)
        REFERENCES data2flow_core.building_models (id) ON DELETE CASCADE,
    CONSTRAINT fk_building_model_space_maps_space_id FOREIGN KEY (space_id) REFERENCES data2flow_core.spaces (id) ON DELETE CASCADE
);
CREATE INDEX ix_building_model_space_maps_space_id ON data2flow_core.building_model_space_maps (space_id);

-- ---------------------------------------------------------------- 저장 지표(OPS-01.03)
CREATE TABLE data2flow_core.storage_snapshots (
    taken_on     date         NOT NULL,
    schema_name  varchar(63)  NOT NULL,
    table_name   varchar(63)  NOT NULL,
    bytes        bigint       NOT NULL,
    row_estimate bigint       NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_storage_snapshots PRIMARY KEY (taken_on, schema_name, table_name)
);
COMMENT ON TABLE data2flow_core.storage_snapshots IS '표별 크기 하루 1회 기록(OPS-01.03 증가 추세). data2flow_* 스키마만, 플랫폼 전체(조직 없음)';
