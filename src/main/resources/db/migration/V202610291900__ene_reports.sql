-- ENE-01.05 월간 에너지 리포트(API-ENE-13, UC-ENE-05). 요청은 GENERATING 행만 넣고 생성 작업이 하나씩 잡아(SKIP LOCKED) analytics 지표(API-ENE-85)로
-- 모델을 만들고 PDF를 그린다. 작업에는 요청자 권한 문맥이 없어서 요청할 때 만든 API-ENE-85 본문(권한 범위 트리·기준선 키)을 request에 둔다.
-- 파일은 오브젝트 저장소 도입 전까지 file_blobs(purpose ENE_REPORT)에 두고 file_object_key에 'db:file_blobs/<id>'를 적는다
ALTER TABLE data2flow_core.file_blobs DROP CONSTRAINT ck_file_blobs_purpose;
ALTER TABLE data2flow_core.file_blobs ADD CONSTRAINT ck_file_blobs_purpose
    CHECK (purpose IN ('WORK_ORDER', 'ASSET_PHOTO', 'COMMISSIONING', 'DEVICE_EXPORT', 'CMP_REPORT', 'ENE_REPORT'));

CREATE TABLE data2flow_core.energy_reports (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint       NOT NULL,
    scope_space_id   bigint,
    month            date         NOT NULL,
    format           varchar(8)   NOT NULL,
    status           varchar(12)  NOT NULL DEFAULT 'GENERATING',
    progress         smallint     NOT NULL DEFAULT 0,
    request          jsonb        NOT NULL,
    model            jsonb,
    file_object_key  varchar(300),
    sha256           char(64),
    size_bytes       bigint,
    attempts         smallint     NOT NULL DEFAULT 0,
    claimed_at       timestamptz,
    failure_reason   varchar(300),
    created_by       bigint       NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_energy_reports PRIMARY KEY (id),
    CONSTRAINT fk_energy_reports_scope_space_id FOREIGN KEY (scope_space_id) REFERENCES data2flow_core.spaces (id) ON DELETE SET NULL,
    CONSTRAINT ck_energy_reports_month CHECK (EXTRACT(day FROM month) = 1),
    CONSTRAINT ck_energy_reports_format CHECK (format IN ('PDF', 'JSON')),
    CONSTRAINT ck_energy_reports_status CHECK (status IN ('GENERATING', 'READY', 'FAILED')),
    CONSTRAINT ck_energy_reports_progress CHECK (progress BETWEEN 0 AND 100)
);
CREATE INDEX ix_energy_reports_organization_id_created_at ON data2flow_core.energy_reports (organization_id, created_at DESC);
CREATE INDEX ix_energy_reports_generating ON data2flow_core.energy_reports (id) WHERE status = 'GENERATING';
COMMENT ON TABLE data2flow_core.energy_reports IS '월간 에너지 리포트(ENE-01.05). 장: 지표, 기준선 대비, 상위 사용 공간, 절감 조치 결과(VERIFIED만)';
COMMENT ON COLUMN data2flow_core.energy_reports.request IS '요청할 때 만든 analytics API-ENE-85 본문(권한 범위로 자른 공간 트리, baselineKey)과 baselineVersionId·scopeName';
