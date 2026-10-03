-- =====================================================================
-- M2 WP-B2: 기기 발견·승인(ADR-031, ING-03.02·03.03·07.02), 게이트웨이(DEV-05.01), 플랫폼 브로커 기기 자격(DSC-03.02·03.05)
-- expand 마이그레이션(추가만, ADR-030)
--   * devices.suggested_space_id: ChirpStack tags(location·point)로 찾은 추천 공간(ING-03.03). 확정(space_id)은 관리자가 한다
--   * source_autoreg_quotas: 소스별 시간당 자동 등록 한도 창·차단 상태(BR-ING-09). 한도를 넘으면 관리자가 풀 때까지 차단 유지
--   * gateways.offline_after_sec: 게이트웨이 오프라인 판정 기준(API-DEV-62 offlineAfterSec, 기본 10분)
--   * device_credentials.revoked_at: 폐기 시각(API-DSC-22 응답 revokedAt)
-- =====================================================================

ALTER TABLE data2flow_core.devices ADD COLUMN suggested_space_id bigint;
ALTER TABLE data2flow_core.devices
    ADD CONSTRAINT fk_devices_suggested_space_id FOREIGN KEY (suggested_space_id) REFERENCES data2flow_core.spaces (id) ON DELETE SET NULL;
COMMENT ON COLUMN data2flow_core.devices.suggested_space_id IS '자동 등록 때 원본 tags(location·point)로 찾은 추천 공간(ING-03.03). 제안일 뿐 space_id는 관리자가 정한다';

CREATE INDEX ix_devices_organization_id_updated_at ON data2flow_core.devices (organization_id, updated_at);

CREATE TABLE data2flow_core.source_autoreg_quotas (
    source_id         bigint      NOT NULL,
    organization_id   bigint      NOT NULL,
    window_started_at timestamptz NOT NULL,
    used_count        integer     NOT NULL DEFAULT 0,
    rejected_count    integer     NOT NULL DEFAULT 0,
    blocked_at        timestamptz,
    released_at       timestamptz,
    released_by       bigint,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_source_autoreg_quotas PRIMARY KEY (source_id),
    CONSTRAINT fk_source_autoreg_quotas_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE,
    CONSTRAINT ck_source_autoreg_quotas_used_count CHECK (used_count >= 0),
    CONSTRAINT ck_source_autoreg_quotas_rejected_count CHECK (rejected_count >= 0)
);
CREATE INDEX ix_source_autoreg_quotas_organization_id ON data2flow_core.source_autoreg_quotas (organization_id);
COMMENT ON TABLE data2flow_core.source_autoreg_quotas IS '소스별 자동 등록 한도(ING-07.02, BR-ING-09). 고정 1시간 창(UTC 정시), blocked_at이 있으면 관리자가 풀 때까지(API-ING-16) 차단';

ALTER TABLE data2flow_core.gateways ADD COLUMN offline_after_sec integer NOT NULL DEFAULT 600;
ALTER TABLE data2flow_core.gateways
    ADD CONSTRAINT ck_gateways_offline_after_sec CHECK (offline_after_sec BETWEEN 60 AND 86400);
COMMENT ON COLUMN data2flow_core.gateways.offline_after_sec IS '마지막 수신 뒤 이 시간(초)이 지나면 OFFLINE(DEV-05, 기본 600초)';

ALTER TABLE data2flow_core.device_credentials ADD COLUMN revoked_at timestamptz;
COMMENT ON COLUMN data2flow_core.device_credentials.revoked_at IS '폐기 시각(BR-DSC-14)';
