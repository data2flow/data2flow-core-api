-- =====================================================================
-- M5 데이터 꺼내 쓰기(TSD-04.01·04.02·04.03·07.02·07.04, NFR-04.02): 내보내기 작업·정기 내보내기·가져오기·데이터 사전.
-- 정본: design/erd/core-data-automation.md §4(export_schedules, export_jobs, import_jobs, import_errors, data_dictionary_versions).
-- ERD와 다른 점(오브젝트 저장소 도입 전, floorplan_images와 같은 방식):
--   * 파일 본문은 exchange_file_chunks(1MiB 조각)에 둔다. export_jobs.object_key = 'db:exchange_file_chunks/EXPORT/<id>'
--   * export_jobs.plan: 요청 시점에 권한 범위로 확정한 대상·집계 단위·시간대·표시 단위(비동기 실행기가 그대로 쓴다)
--   * export_schedules.credential_enc: S3·SFTP 자격(SecretCipher 암호문). credential_ref는 'db:export_schedules.credential_enc'
--   * export_schedule_runs: 기간별 판 번호·재시도(BR-TSD-28, AT-TSD-17.2·17.3)
--   * import_jobs.file_object_key·sample·error, data_dictionary_versions.content(판별 내용 보관, API-TSD-59 version)
-- import_jobs·import_errors는 archive_files(보관, 다른 마이그레이션)의 FK 대상이라 IF NOT EXISTS로 만든다.
-- expand 마이그레이션(추가만, ADR-030)
-- =====================================================================

CREATE TABLE data2flow_core.export_schedules (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint        NOT NULL,
    name             varchar(100)  NOT NULL,
    query            jsonb         NOT NULL,
    format           varchar(8)    NOT NULL,
    cron             varchar(64)   NOT NULL,
    relative_period  varchar(20)   NOT NULL,
    delivery         varchar(8)    NOT NULL,
    recipients       text[],
    target_type      varchar(8),
    target           jsonb,
    credential_ref   varchar(100),
    credential_enc   bytea,
    last_version     integer       NOT NULL DEFAULT 0,
    last_status      varchar(12),
    last_error       varchar(500),
    enabled          boolean       NOT NULL DEFAULT true,
    last_run_at      timestamptz,
    next_run_at      timestamptz,
    version          integer       NOT NULL DEFAULT 0,
    created_by       bigint        NOT NULL,
    updated_by       bigint        NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_export_schedules PRIMARY KEY (id),
    CONSTRAINT uq_export_schedules_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_export_schedules_format CHECK (format IN ('CSV','XLSX','PARQUET')),
    CONSTRAINT ck_export_schedules_delivery CHECK (delivery IN ('EMAIL','STORAGE')),
    CONSTRAINT ck_export_schedules_target_type CHECK (target_type IS NULL OR target_type IN ('S3','SFTP'))
);
CREATE INDEX ix_export_schedules_next_run_at_enabled ON data2flow_core.export_schedules (next_run_at) WHERE enabled;
COMMENT ON TABLE data2flow_core.export_schedules IS '정기 내보내기(TSD-04.03, TSD-07.02, API-TSD-23). 파일명 {조직}_{범위}_{시작}_{끝}_v{판}(BR-TSD-28)';
COMMENT ON COLUMN data2flow_core.export_schedules.cron IS '조직 시간대 기준(5필드 또는 6필드)';
COMMENT ON COLUMN data2flow_core.export_schedules.credential_enc IS 'S3 {accessKey, secretKey}·SFTP {password|privateKey} JSON의 SecretCipher 암호문(쓰기 전용)';

CREATE TABLE data2flow_core.export_jobs (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    organization_id    bigint        NOT NULL,
    requested_by       bigint        NOT NULL,
    schedule_id        bigint,
    query              jsonb         NOT NULL,
    plan               jsonb         NOT NULL DEFAULT '{}'::jsonb,
    format             varchar(8)    NOT NULL,
    status             varchar(10)   NOT NULL DEFAULT 'QUEUED',
    rows_count         bigint,
    bytes              bigint,
    estimated_rows     bigint,
    object_key         varchar(300),
    dictionary_version integer,
    expires_at         timestamptz,
    error              varchar(500),
    started_at         timestamptz,
    finished_at        timestamptz,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_export_jobs PRIMARY KEY (id),
    CONSTRAINT ck_export_jobs_format CHECK (format IN ('CSV','XLSX','PARQUET')),
    CONSTRAINT ck_export_jobs_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','EXPIRED','CANCELLED')),
    CONSTRAINT fk_export_jobs_schedule_id FOREIGN KEY (schedule_id) REFERENCES data2flow_core.export_schedules (id) ON DELETE SET NULL
);
CREATE INDEX ix_export_jobs_organization_id_requested_by_created_at ON data2flow_core.export_jobs (organization_id, requested_by, created_at DESC);
CREATE INDEX ix_export_jobs_status_created_at ON data2flow_core.export_jobs (status, created_at) WHERE status IN ('QUEUED','RUNNING');
COMMENT ON TABLE data2flow_core.export_jobs IS '내보내기 작업(TSD-04.01, BR-TSD-14). 파일 7일 후 만료';
COMMENT ON COLUMN data2flow_core.export_jobs.rows_count IS '문서의 rows(예약어 피함)';
COMMENT ON COLUMN data2flow_core.export_jobs.plan IS '요청 시점에 권한 범위로 확정한 계열·집계 단위·시간대·표시 단위';

CREATE TABLE data2flow_core.export_schedule_runs (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint        NOT NULL,
    schedule_id      bigint        NOT NULL,
    period_from      timestamptz   NOT NULL,
    period_to        timestamptz   NOT NULL,
    file_version     integer       NOT NULL,
    job_id           bigint,
    status           varchar(10)   NOT NULL DEFAULT 'PENDING',
    attempts         integer       NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    file_name        varchar(300),
    error            varchar(500),
    stale            boolean       NOT NULL DEFAULT false,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_export_schedule_runs PRIMARY KEY (id),
    CONSTRAINT uq_export_schedule_runs_schedule_period_version UNIQUE (schedule_id, period_from, file_version),
    CONSTRAINT ck_export_schedule_runs_status CHECK (status IN ('PENDING','RETRYING','SUCCEEDED','FAILED')),
    CONSTRAINT fk_export_schedule_runs_schedule_id FOREIGN KEY (schedule_id) REFERENCES data2flow_core.export_schedules (id) ON DELETE CASCADE
);
CREATE INDEX ix_export_schedule_runs_organization_id_schedule_id ON data2flow_core.export_schedule_runs (organization_id, schedule_id, period_from);
CREATE INDEX ix_export_schedule_runs_retry ON data2flow_core.export_schedule_runs (next_attempt_at) WHERE status IN ('PENDING','RETRYING');
COMMENT ON TABLE data2flow_core.export_schedule_runs IS '정기 내보내기 실행(기간·판). 3회 재시도 후 FAILED(BR-TSD-28), 재계산된 기간은 stale → 다음 실행에 새 판(AT-TSD-17.2)';

CREATE TABLE IF NOT EXISTS data2flow_core.import_jobs (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    requested_by bigint NOT NULL,
    source_kind varchar(10) NOT NULL,
    config jsonb NOT NULL,
    mapping jsonb NOT NULL,
    secret_enc bytea,
    dry_run boolean NOT NULL DEFAULT false,
    status varchar(18) NOT NULL DEFAULT 'QUEUED',
    total bigint,
    inserted bigint,
    skipped_duplicate bigint,
    failed bigint,
    origin_label varchar(100),
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_import_jobs PRIMARY KEY (id),
    CONSTRAINT ck_import_jobs_source_kind CHECK (source_kind IN ('CSV','INFLUXDB')),
    CONSTRAINT ck_import_jobs_status CHECK (status IN ('QUEUED','VALIDATING','DRY_RUN_DONE','RUNNING','SUCCEEDED','PARTIALLY_FAILED','FAILED'))
);
CREATE INDEX IF NOT EXISTS ix_import_jobs_organization_id_created_at ON data2flow_core.import_jobs (organization_id, created_at DESC);
COMMENT ON TABLE data2flow_core.import_jobs IS '가져오기 작업(TSD-04.02). Influx 토큰은 secret_enc(AES-GCM)';

ALTER TABLE data2flow_core.import_jobs
    ADD COLUMN IF NOT EXISTS file_object_key varchar(300),
    ADD COLUMN IF NOT EXISTS file_bytes bigint,
    ADD COLUMN IF NOT EXISTS sample jsonb,
    ADD COLUMN IF NOT EXISTS error varchar(500),
    ADD COLUMN IF NOT EXISTS range_from timestamptz,
    ADD COLUMN IF NOT EXISTS range_to timestamptz;
COMMENT ON COLUMN data2flow_core.import_jobs.file_object_key IS 'CSV 파일 위치(db:exchange_file_chunks/IMPORT/<id>)';
COMMENT ON COLUMN data2flow_core.import_jobs.sample IS '미리 실행 결과 앞 10행 [{line, deviceId, metricKey, measuredAt, value}]';

CREATE TABLE IF NOT EXISTS data2flow_core.import_errors (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    job_id bigint NOT NULL,
    line_or_point varchar(100) NOT NULL,
    error_code varchar(64) NOT NULL,
    message varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_import_errors PRIMARY KEY (id),
    CONSTRAINT fk_import_errors_job_id FOREIGN KEY (job_id) REFERENCES data2flow_core.import_jobs (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_import_errors_job_id ON data2flow_core.import_errors (job_id);
COMMENT ON TABLE data2flow_core.import_errors IS '가져오기 오류. 작업당 최대 1,000건';

CREATE TABLE data2flow_core.exchange_file_chunks (
    owner_kind       varchar(8)    NOT NULL,
    owner_id         bigint        NOT NULL,
    chunk_no         integer       NOT NULL,
    organization_id  bigint        NOT NULL,
    data             bytea         NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_exchange_file_chunks PRIMARY KEY (owner_kind, owner_id, chunk_no),
    CONSTRAINT ck_exchange_file_chunks_owner_kind CHECK (owner_kind IN ('EXPORT','IMPORT')),
    CONSTRAINT ck_exchange_file_chunks_size CHECK (octet_length(data) BETWEEN 1 AND 1048576)
);
CREATE INDEX ix_exchange_file_chunks_organization_id ON data2flow_core.exchange_file_chunks (organization_id);
COMMENT ON TABLE data2flow_core.exchange_file_chunks IS '내보내기 결과·가져오기 원본 파일(1MiB 조각). 오브젝트 저장소 도입 전 임시 보관, 7일 뒤 삭제';

CREATE TABLE data2flow_core.data_dictionary_versions (
    organization_id bigint      NOT NULL,
    version_no      integer     NOT NULL,
    content_hash    char(64)    NOT NULL,
    content         jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_data_dictionary_versions PRIMARY KEY (organization_id, version_no)
);
COMMENT ON TABLE data2flow_core.data_dictionary_versions IS '데이터 사전 판. 측정 항목·별칭·공간 구조가 바뀌면 오름(BR-TSD-27). content는 그 판의 사전 JSON';
