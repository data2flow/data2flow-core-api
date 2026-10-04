-- =====================================================================
-- M5 데이터 관리(core-api): 보관 정책·콜드 보관 파일(TSD-02.01·05.01·05.02·05.03), 스크립트 테스트 케이스·공유 모듈·
-- 수식 파생 항목·설정값 판(SCR-03.03·04.01·04.02·01.06).
-- 정본 DDL: data2flow-docs design/erd/ddl/12-core-data-automation.sql. 추가만 한다(ADR-030 expand).
-- 문서와 다른 점:
--   * archive_files.restored_job_id: import_jobs(가져오기, 별도 마이그레이션)보다 먼저 만들어질 수 있어 FK 없이 ID만 둔다
--   * archive_files UNIQUE(organization_id, object_key): pipeline이 등록(API-TSD-61)을 다시 시도해도 한 행(멱등)
--   * scripts.config_revision: 설정값 판(SCR-04.02, API-SCR-32 configRevision). 설정값이 바뀔 때만 1 오른다
-- =====================================================================

-- ---------------------------------------------------------------- 보관 정책(TSD-02.01·05.01·05.03, NFR-04.03)
CREATE TABLE data2flow_core.retention_policies (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    organization_id       bigint      NOT NULL,
    scope                 varchar(8)  NOT NULL DEFAULT 'ORG',
    scope_ref             varchar(64),
    data_class            varchar(24) NOT NULL,
    retain_days           integer     NOT NULL,
    compress_after_days   integer,
    archive_before_delete boolean     NOT NULL DEFAULT false,
    store_mode            varchar(12),
    version               integer     NOT NULL DEFAULT 0,
    updated_by            bigint      NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_retention_policies PRIMARY KEY (id),
    CONSTRAINT uq_retention_policies_scope_data_class UNIQUE NULLS NOT DISTINCT (organization_id, scope, scope_ref, data_class),
    CONSTRAINT ck_retention_policies_scope CHECK (scope IN ('ORG','MODEL','METRIC')),
    CONSTRAINT ck_retention_policies_scope_ref CHECK (scope = 'ORG' OR scope_ref IS NOT NULL),
    CONSTRAINT ck_retention_policies_data_class CHECK (data_class IN ('RAW_MESSAGE','TELEMETRY','LINK','AGG_1M','AGG_1H','AGG_1D',
        'FLOW_EXECUTION','ANALYSIS_RESULT','AUDIT_LOG','NOTIFICATION_DELIVERY','COMMAND','DEVICE_STATE_HISTORY','WEBHOOK_DELIVERY')),
    CONSTRAINT ck_retention_policies_retain_days CHECK (retain_days >= 0),
    CONSTRAINT ck_retention_policies_store_mode CHECK (store_mode IS NULL OR (scope = 'METRIC' AND store_mode IN ('ALL','ON_CHANGE')))
);
COMMENT ON TABLE data2flow_core.retention_policies IS '보관 정책. 우선순위 METRIC > MODEL > ORG. TSD-02.01·05.01, OPS-04, NFR-04.03. 정본은 TSD 도메인 모델 §2.7';

-- ---------------------------------------------------------------- 콜드 보관 파일(TSD-05.02, API-TSD-61·33)
CREATE TABLE data2flow_core.archive_files (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    data_class      varchar(24)  NOT NULL,
    range_from      timestamptz  NOT NULL,
    range_to        timestamptz  NOT NULL,
    object_key      varchar(300) NOT NULL,
    format          varchar(8)   NOT NULL DEFAULT 'PARQUET',
    rows_count      bigint       NOT NULL,
    bytes           bigint       NOT NULL,
    checksum        char(64)     NOT NULL,
    restored_job_id bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_archive_files PRIMARY KEY (id),
    CONSTRAINT uq_archive_files_organization_id_object_key UNIQUE (organization_id, object_key),
    CONSTRAINT ck_archive_files_format CHECK (format IN ('PARQUET'))
);
CREATE INDEX ix_archive_files_organization_id_data_class_range_from ON data2flow_core.archive_files (organization_id, data_class, range_from);
COMMENT ON TABLE data2flow_core.archive_files IS '콜드 보관 파일(Parquet, TSD-05.02). pipeline이 업로드·체크섬 확인 뒤 등록(API-TSD-61)';

-- ---------------------------------------------------------------- 스크립트 설정값 판(SCR-04.02)
ALTER TABLE data2flow_core.scripts ADD COLUMN config_revision integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN data2flow_core.scripts.config_revision IS '설정값 판(API-SCR-22로 바뀔 때마다 1 오름, API-SCR-32 configRevision)';

-- ---------------------------------------------------------------- 테스트 케이스(SCR-03.03)
CREATE TABLE data2flow_core.script_test_cases (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    script_id       bigint      NOT NULL,
    name            varchar(80) NOT NULL,
    input           jsonb       NOT NULL,
    context         jsonb,
    expected        jsonb       NOT NULL,
    compare_mode    varchar(16) NOT NULL DEFAULT 'EXACT',
    compare_fields  text[],
    tolerance       double precision,
    last_result     jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_script_test_cases PRIMARY KEY (id),
    CONSTRAINT uq_script_test_cases_script_id_name UNIQUE (script_id, name),
    CONSTRAINT ck_script_test_cases_compare_mode CHECK (compare_mode IN ('EXACT','FIELDS','TOLERANCE')),
    CONSTRAINT fk_script_test_cases_script_id FOREIGN KEY (script_id) REFERENCES data2flow_core.scripts (id) ON DELETE CASCADE
);
CREATE INDEX ix_script_test_cases_organization_id_script_id ON data2flow_core.script_test_cases (organization_id, script_id);
COMMENT ON TABLE data2flow_core.script_test_cases IS '스크립트 테스트 케이스. 스크립트당 50개, input·context·expected 각 256KB(SCR-03.03)';

-- ---------------------------------------------------------------- 공유 모듈(SCR-04.01)
CREATE TABLE data2flow_core.script_modules (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    name            varchar(40) NOT NULL,
    description     varchar(500),
    created_by      bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_script_modules PRIMARY KEY (id),
    CONSTRAINT uq_script_modules_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_script_modules_name CHECK (name ~ '^[a-z0-9-]{3,40}$')
);
COMMENT ON TABLE data2flow_core.script_modules IS '공유 모듈(SCR-04.01)';

CREATE TABLE data2flow_core.script_module_versions (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    module_id       bigint      NOT NULL,
    version_no      integer     NOT NULL,
    code            text        NOT NULL,
    status          varchar(10) NOT NULL DEFAULT 'DRAFT',
    released_at     timestamptz,
    created_by      bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_script_module_versions PRIMARY KEY (id),
    CONSTRAINT uq_script_module_versions_module_id_version_no UNIQUE (module_id, version_no),
    CONSTRAINT ck_script_module_versions_status CHECK (status IN ('DRAFT','RELEASED')),
    CONSTRAINT fk_script_module_versions_module_id FOREIGN KEY (module_id) REFERENCES data2flow_core.script_modules (id) ON DELETE CASCADE
);
CREATE INDEX ix_script_module_versions_organization_id_status ON data2flow_core.script_module_versions (organization_id, status);
COMMENT ON TABLE data2flow_core.script_module_versions IS '공유 모듈 버전. RELEASED는 불변(BR-SCR-13)';

-- ---------------------------------------------------------------- 수식 파생 항목(SCR-01.06)
CREATE TABLE data2flow_core.formula_metrics (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    result_key      varchar(64)  NOT NULL,
    display_name    varchar(100) NOT NULL,
    unit            varchar(16),
    expression      varchar(500) NOT NULL,
    compiled_js     text         NOT NULL,
    target_type     varchar(8)   NOT NULL,
    target_id       varchar(64)  NOT NULL,
    status          varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint       NOT NULL,
    updated_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_formula_metrics PRIMARY KEY (id),
    CONSTRAINT uq_formula_metrics_organization_id_result_key UNIQUE (organization_id, result_key),
    CONSTRAINT ck_formula_metrics_target_type CHECK (target_type IN ('MODEL','DEVICE','SPACE')),
    CONSTRAINT ck_formula_metrics_status CHECK (status IN ('ACTIVE','DISABLED'))
);
COMMENT ON TABLE data2flow_core.formula_metrics IS '수식 파생 측정 항목. TRANSFORM 단계 마지막에 실행. result_key는 표준 측정 항목(metrics)으로도 등록(SCR-01.06)';
