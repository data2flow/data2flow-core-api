-- =====================================================================
-- SCR-03.04 스크립트 배포 적용 보고(API-SCR-34 POST /internal/core/scripts/deploy-acks).
-- pipeline 인스턴스가 새 버전(EVT-SCR-01 → API-SCR-32 재적재)을 적용한 뒤 보고한다. 배포 응답·상세의 applied{reported,total,instances}에 쓴다.
-- 문서(design/erd/core-data-automation.md §5)에 없는 테이블(추가). expand 마이그레이션(추가만, ADR-030)
-- =====================================================================

CREATE TABLE data2flow_core.script_deploy_acks (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    script_id       bigint      NOT NULL,
    version_id      bigint      NOT NULL,
    instance        varchar(64) NOT NULL,
    applied_at      timestamptz NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_script_deploy_acks PRIMARY KEY (id),
    CONSTRAINT uq_script_deploy_acks_version_id_instance UNIQUE (version_id, instance),
    CONSTRAINT fk_script_deploy_acks_version_id FOREIGN KEY (version_id)
        REFERENCES data2flow_core.script_versions (id) ON DELETE CASCADE
);

CREATE INDEX ix_script_deploy_acks_organization_id_received_at
    ON data2flow_core.script_deploy_acks (organization_id, received_at);

COMMENT ON TABLE data2flow_core.script_deploy_acks IS '스크립트 버전 인스턴스별 적용 보고(API-SCR-34, SCR-03.04). 버전이 지워지면 함께 지운다';
COMMENT ON COLUMN data2flow_core.script_deploy_acks.instance IS 'pipeline 인스턴스 이름(파드 이름)';
