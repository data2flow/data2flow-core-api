-- =====================================================================
-- M2 WP-E: 시계열 조회·수집 모니터·실패 메시지(core-api)
--   * ingest_alert_thresholds: 수집 지연·하트비트 알람 기준(API-ING-04, ING-07.04·07.05). 조직당 1행.
--     ops_thresholds(key CHECK 6종)와 단위·검증이 달라 따로 둔다.
--   * dlq_item_claims: 실패 메시지 재처리·폐기 동시 요청 막기(TC-ING-089, 잠금 5분). dlq_items는 pipeline 소유라
--     core가 쓸 수 없으므로 core 쪽에서 먼저 항목을 잡고(INSERT … ON CONFLICT) pipeline 내부 API를 부른 뒤 놓는다.
--   * ops_thresholds.severity·channel_ids: API-OPS-05 항목 {key, enabled, value, severity, channelIds[]}에 맞춤.
-- expand 마이그레이션(추가만, ADR-030)
-- =====================================================================

CREATE TABLE data2flow_core.ingest_alert_thresholds (
    organization_id        bigint      NOT NULL,
    lag_warn_sec           integer     NOT NULL DEFAULT 60,
    lag_critical_sec       integer     NOT NULL DEFAULT 300,
    heartbeat_critical_sec integer     NOT NULL DEFAULT 30,
    version                integer     NOT NULL DEFAULT 0,
    updated_by             bigint,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_ingest_alert_thresholds PRIMARY KEY (organization_id),
    CONSTRAINT ck_ingest_alert_thresholds_lag_warn CHECK (lag_warn_sec BETWEEN 10 AND 3600),
    CONSTRAINT ck_ingest_alert_thresholds_lag_critical CHECK (lag_critical_sec > lag_warn_sec AND lag_critical_sec <= 7200),
    CONSTRAINT ck_ingest_alert_thresholds_heartbeat CHECK (heartbeat_critical_sec BETWEEN 10 AND 600)
);

COMMENT ON TABLE data2flow_core.ingest_alert_thresholds IS '수집 알람 기준(API-ING-04, BR-ING-16 기본 60/300초, ING-07.05 하트비트 30초). 행이 없으면 기본값';

CREATE TABLE data2flow_core.dlq_item_claims (
    dlq_item_id     bigint      NOT NULL,
    organization_id bigint      NOT NULL,
    claimed_by      bigint      NOT NULL,
    claimed_until   timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_dlq_item_claims PRIMARY KEY (dlq_item_id)
);

CREATE INDEX ix_dlq_item_claims_organization_id_claimed_until ON data2flow_core.dlq_item_claims (organization_id, claimed_until);

COMMENT ON TABLE data2flow_core.dlq_item_claims IS '실패 메시지(data2flow_pipeline.dlq_items) 재처리·폐기 중 표시. 5분 뒤 만료(TC-ING-086·089). FK 없음(다른 스키마)';

ALTER TABLE data2flow_core.ops_thresholds
    ADD COLUMN severity    varchar(10) NOT NULL DEFAULT 'CRITICAL',
    ADD COLUMN channel_ids bigint[]    NOT NULL DEFAULT '{}';

ALTER TABLE data2flow_core.ops_thresholds
    ADD CONSTRAINT ck_ops_thresholds_severity CHECK (severity IN ('INFO', 'WARNING', 'MAJOR', 'CRITICAL'));

COMMENT ON COLUMN data2flow_core.ops_thresholds.channel_ids IS '운영 알림을 보낼 알림 채널 ID(API-OPS-05). 비면 조직 기본 운영 채널';

-- 기반 마이그레이션 보정: data_sources.unknown_device_policy가 varchar(12)인데 기본값 'AUTO_REGISTER'는 13자라 기본값으로 넣을 수 없다.
-- 길이만 넓힌다(varchar 확장은 PostgreSQL에서 테이블을 다시 쓰지 않는 메타데이터 변경, expand-only). 다른 묶음이 같은 변경을 해도 무해하다.
ALTER TABLE data2flow_core.data_sources ALTER COLUMN unknown_device_policy TYPE varchar(16);
