-- CMP-02.02·02.06: 준수 리포트와 판(design/erd/core-environment.md §3.6·3.7, spec/detail/CMP/domain-model.md).
-- ERD와 다른 점:
--   * compliance_report_revisions.progress: 생성 진행률 0~100(API-CMP-17 progress)
--   * compliance_report_revisions.model: 리포트 모델(수치·목록) JSON. 발행할 때 같은 수치로 다시 그리고(초안 워터마크만 뺀다),
--     같은 조건의 재생성과 비교한다(BR-CMP-12)
--   * compliance_report_revisions.size_bytes·attempts·claimed_at: 파일 크기(API-CMP-19 sizeBytes), 생성 작업 시도 수와 잡은 시각
--     (여러 파드 중 하나만 생성, 멈춘 작업은 15분 뒤 다시 잡는다)
--   * 파일은 오브젝트 저장소 도입 전까지 file_blobs(purpose CMP_REPORT)에 두고 pdf_object_key에 'db:file_blobs/<id>'를 적는다
--   * period CHECK는 400일 이하(시작·끝 포함)로 ERD보다 하루 좁다(API-CMP-16 "최대 400일"과 같게)

ALTER TABLE data2flow_core.file_blobs DROP CONSTRAINT ck_file_blobs_purpose;
ALTER TABLE data2flow_core.file_blobs ADD CONSTRAINT ck_file_blobs_purpose
    CHECK (purpose IN ('WORK_ORDER', 'ASSET_PHOTO', 'COMMISSIONING', 'DEVICE_EXPORT', 'CMP_REPORT'));

CREATE TABLE data2flow_core.compliance_reports (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    type            varchar(24)   NOT NULL,
    title           varchar(200)  NOT NULL,
    space_ids       bigint[]      NOT NULL,
    facility_type   varchar(64),
    period_from     date          NOT NULL,
    period_to       date          NOT NULL,
    options         jsonb         NOT NULL DEFAULT '{}',
    dedup_key       char(64)      NOT NULL,
    created_by      bigint        NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_compliance_reports PRIMARY KEY (id),
    CONSTRAINT uq_compliance_reports_organization_id_dedup_key UNIQUE (organization_id, dedup_key),
    CONSTRAINT ck_compliance_reports_type CHECK (type IN ('KR_SELF_CHECK', 'RESET_SUPPORT', 'WELL_A08_SUPPORT')),
    CONSTRAINT ck_compliance_reports_period CHECK (period_to >= period_from AND period_to - period_from < 400),
    CONSTRAINT ck_compliance_reports_space_ids CHECK (cardinality(space_ids) >= 1)
);

COMMENT ON TABLE data2flow_core.compliance_reports IS '준수 리포트(CMP-02.02). 같은 조건(dedup_key = sha256(type, spaces, period, facility))은 한 리포트에 판을 쌓는다';

CREATE TABLE data2flow_core.compliance_report_revisions (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    organization_id     bigint        NOT NULL,
    report_id           bigint        NOT NULL,
    revision_no         integer       NOT NULL,
    status              varchar(16)   NOT NULL DEFAULT 'GENERATING',
    progress            smallint      NOT NULL DEFAULT 0,
    pdf_object_key      varchar(300),
    csv_object_key      varchar(300),
    sha256              char(64),
    size_bytes          bigint,
    data_range          jsonb,
    profile_version_ids bigint[]      NOT NULL DEFAULT '{}',
    model               jsonb,
    attempts            smallint      NOT NULL DEFAULT 0,
    claimed_at          timestamptz,
    generated_by        bigint,
    published_by        bigint,
    published_at        timestamptz,
    failure_reason      varchar(300),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_compliance_report_revisions PRIMARY KEY (id),
    CONSTRAINT fk_compliance_report_revisions_report_id FOREIGN KEY (report_id) REFERENCES data2flow_core.compliance_reports (id) ON DELETE CASCADE,
    CONSTRAINT uq_compliance_report_revisions_report_id_revision_no UNIQUE (report_id, revision_no),
    CONSTRAINT ck_compliance_report_revisions_status CHECK (status IN ('GENERATING', 'DRAFT', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_compliance_report_revisions_data_range CHECK (status IN ('GENERATING', 'FAILED') OR data_range IS NOT NULL),
    CONSTRAINT ck_compliance_report_revisions_progress CHECK (progress BETWEEN 0 AND 100)
);

CREATE INDEX ix_compliance_report_revisions_organization_id_report_id ON data2flow_core.compliance_report_revisions (organization_id, report_id);
CREATE INDEX ix_compliance_report_revisions_generating ON data2flow_core.compliance_report_revisions (id) WHERE status = 'GENERATING';
CREATE INDEX ix_compliance_report_revisions_sha256 ON data2flow_core.compliance_report_revisions (sha256) WHERE sha256 IS NOT NULL;

COMMENT ON TABLE data2flow_core.compliance_report_revisions IS '리포트 판. PUBLISHED 판은 불변, sha256은 PDF 해시(해시 표시 영역을 0으로 채운 파일의 SHA-256, BR-CMP-16)';
COMMENT ON COLUMN data2flow_core.compliance_report_revisions.model IS '리포트 모델(수치·목록). 발행은 이 값으로 다시 그린다';
