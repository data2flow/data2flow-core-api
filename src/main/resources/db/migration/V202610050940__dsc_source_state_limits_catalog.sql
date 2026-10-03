-- =====================================================================
-- M2 데이터 소스(DSC) 보강: 대표 연결 상태·무수신 판정 상태, 조직별 소스 한도, 커넥터 카탈로그 표시 열, 기본 유형·템플릿 시드.
-- expand 마이그레이션(추가만, ADR-030). 문서(design/erd/core-data-automation.md §1)에 없는 것이라 ERD 반영이 필요하다.
-- =====================================================================

-- 소스별 대표 연결 상태(DSC-02.01, EVT-DSC-04)와 무수신 판정(EVT-DSC-05) 상태.
-- data_sources는 사용자가 고치는 설정(낙관적 잠금 version)이라, ingress 보고로 자주 바뀌는 값은 따로 둔다.
CREATE TABLE data2flow_core.source_states (
    source_id        bigint      NOT NULL,
    organization_id  bigint      NOT NULL,
    connection_state varchar(12) NOT NULL DEFAULT 'DISABLED',
    error_kind       varchar(16),
    state_changed_at timestamptz,
    last_received_at timestamptz,
    activated_at     timestamptz,
    no_data          boolean     NOT NULL DEFAULT false,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_source_states PRIMARY KEY (source_id),
    CONSTRAINT ck_source_states_connection_state CHECK (connection_state IN ('CONNECTED','CONNECTING','DISCONNECTED','ERROR','DISABLED')),
    CONSTRAINT ck_source_states_error_kind CHECK (error_kind IS NULL OR error_kind IN ('AUTH','DNS','TLS','TIMEOUT','REFUSED','PROTOCOL','QUOTA','OTHER')),
    CONSTRAINT fk_source_states_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);

CREATE INDEX ix_source_states_organization_id ON data2flow_core.source_states (organization_id);

COMMENT ON TABLE data2flow_core.source_states IS '소스 대표 연결 상태(인스턴스 보고를 모은 값, DSC domain-model §2.5)와 무수신 판정 상태(EVT-DSC-05). core가 계산해 저장';

COMMENT ON COLUMN data2flow_core.source_states.last_received_at IS '마지막 수신이 있었던 분(source.stats.1m의 minute, 분 단위 정밀도)';

COMMENT ON COLUMN data2flow_core.source_states.activated_at IS '마지막으로 ACTIVE가 된 시각. 한 번도 받지 못한 소스의 무수신 기준점';

-- 조직별 소스 한도(DSC-07.03, BR-DSC-06). 행이 없으면 기본값(50개·토픽 20·256KB·초당 500건)
CREATE TABLE data2flow_core.source_limits (
    organization_id       bigint      NOT NULL,
    max_sources           integer     NOT NULL DEFAULT 50,
    max_topics_per_source integer     NOT NULL DEFAULT 20,
    max_message_bytes     integer     NOT NULL DEFAULT 262144,
    max_messages_per_sec  integer     NOT NULL DEFAULT 500,
    version               integer     NOT NULL DEFAULT 0,
    updated_by            bigint,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_source_limits PRIMARY KEY (organization_id),
    CONSTRAINT ck_source_limits_max_sources CHECK (max_sources BETWEEN 1 AND 10000),
    CONSTRAINT ck_source_limits_max_topics_per_source CHECK (max_topics_per_source BETWEEN 1 AND 200),
    CONSTRAINT ck_source_limits_max_message_bytes CHECK (max_message_bytes BETWEEN 1024 AND 16777216),
    CONSTRAINT ck_source_limits_max_messages_per_sec CHECK (max_messages_per_sec BETWEEN 1 AND 100000)
);

COMMENT ON TABLE data2flow_core.source_limits IS '조직별 소스 한도(DSC-07.03 "한도는 라이선스·설정으로 바꿀 수 있다", AT-DSC-21.4). ingress는 API-DSC-50 rateLimit으로 받는다';

-- 커넥터 카탈로그 표시 열(API-DSC-55 name·standard·transports). ingress 보고(EVT-DSC-09)는 name만 준다
ALTER TABLE data2flow_core.connector_catalogs ADD COLUMN name varchar(100);
ALTER TABLE data2flow_core.connector_catalogs ADD COLUMN standard varchar(100);
ALTER TABLE data2flow_core.connector_catalogs ADD COLUMN transports text[] NOT NULL DEFAULT '{}';
ALTER TABLE data2flow_core.connector_catalogs ADD COLUMN reported_by varchar(64);
ALTER TABLE data2flow_core.connector_catalogs ADD COLUMN reported_at timestamptz;

COMMENT ON COLUMN data2flow_core.connector_catalogs.reported_by IS '마지막으로 보고한 ingress 인스턴스(EVT-DSC-09 instanceId). 시드 행은 null';

-- M2 기본 유형 3개(DSC-01.02). ingress가 같은 키로 보고하면 스키마·버전을 덮어쓴다(BR-DSC-23)
INSERT INTO data2flow_core.connector_catalogs (connector_key, version, category, config_schema, auth_methods, payload_formats, ack_mode,
                                               scaling, supports_send, name, standard, transports)
VALUES ('mqtt', '1.0.0', 'MQTT',
        '{"$schema":"https://json-schema.org/draft/2020-12/schema","title":"MQTT 3.1.1 / 5.0","type":"object","additionalProperties":false,"required":["url"],"properties":{"url":{"type":"string","pattern":"^(tcp|ssl|ws|wss)://[^\\s/:]+(:\\d{1,5})?(/.*)?$"},"protocolVersion":{"type":"string","enum":["5.0","3.1.1"],"default":"5.0"},"clientIdBase":{"type":"string","pattern":"^[a-z0-9][a-z0-9-]{0,80}$"},"qos":{"type":"integer","enum":[0,1,2],"default":1},"keepaliveSec":{"type":"integer","minimum":10,"maximum":600,"default":60},"cleanStart":{"type":"boolean","default":false},"sessionExpirySec":{"type":"integer","minimum":0,"maximum":4294967295,"default":3600},"receiveMaximum":{"type":"integer","minimum":1,"maximum":65535},"sharedGroup":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},"retainHandling":{"type":"string","enum":["SEND","SEND_IF_SUBSCRIPTION_DOES_NOT_EXIST","DO_NOT_SEND"]},"auth":{"type":"string","enum":["NONE","USERPASS","HEADER","MTLS"],"default":"NONE"},"username":{"type":"string","maxLength":256},"headerName":{"type":"string","maxLength":64,"default":"Authorization"},"headerScheme":{"type":"string","maxLength":32},"tlsInsecure":{"type":"boolean","default":false}},"x-ui":{"tabs":[{"name":"connection","fields":["url","protocolVersion","clientIdBase","keepaliveSec","cleanStart","sessionExpirySec","receiveMaximum"]},{"name":"subscription","fields":["qos","sharedGroup","retainHandling"]},{"name":"security","fields":["auth","username","headerName","headerScheme","tlsInsecure"]}]}}'::jsonb,
        '{NONE,USER_PASSWORD,WS_HEADER,MTLS}', '{JSON,TEXT,BINARY}', 'AFTER_WRITE', 'DUAL_ACTIVE', false,
        'MQTT 3.1.1/5.0', 'OASIS MQTT 3.1.1, 5.0', '{tcp,ssl,ws,wss}'),
       ('platform-broker', '1.0.0', 'PLATFORM',
        '{"$schema":"https://json-schema.org/draft/2020-12/schema","title":"플랫폼 브로커","type":"object","additionalProperties":false,"properties":{"deviceKeyPattern":{"type":"string","maxLength":64,"default":"{externalId}"},"allowedFormats":{"type":"array","items":{"type":"string","enum":["CANONICAL","GENERIC_JSON"]}}}}'::jsonb,
        '{USER_PASSWORD}', '{JSON}', 'AFTER_WRITE', 'DUAL_ACTIVE', false,
        '플랫폼 브로커', 'MQTT over WSS (iot-data.java21.net, ADR-029)', '{wss}'),
       ('simulation', '1.0.0', 'PLATFORM',
        '{"$schema":"https://json-schema.org/draft/2020-12/schema","title":"가상 환경","type":"object","additionalProperties":false,"properties":{"scenarioId":{"type":["string","integer"]}}}'::jsonb,
        '{NONE}', '{JSON}', 'AFTER_WRITE', 'SCALABLE', false,
        '가상 환경(SIM)', 'data2flow SIM', '{internal}')
ON CONFLICT (connector_key) DO NOTHING;

-- 플랫폼 기본 템플릿(DSC-09.12 일부, connectors.md §4). organization_id 0 = 플랫폼 공용, 읽기 전용
INSERT INTO data2flow_core.connector_templates (organization_id, template_key, connector_key, name, description, preset, decoder_key,
                                                builtin)
VALUES (0, 'chirpstack-v4', 'mqtt', 'ChirpStack v4 (MQTT)', 'application/+/device/+/event/up 구독, chirpstack-v4 디코더',
        '{"connection":{"protocolVersion":"5.0","qos":1,"keepaliveSec":60,"cleanStart":false,"auth":"USERPASS"},"topics":[{"topic":"application/+/device/+/event/up","qos":1}],"decoderKey":"chirpstack-v4"}'::jsonb,
        'chirpstack-v4', true),
       (0, 'academy-iot-data', 'mqtt', '아카데미 iot-data (WSS)', 'wss://iot-data.java21.net/mqtt + HTTP Basic 헤더, ChirpStack 디코더(ADR-029)',
        '{"connection":{"url":"wss://iot-data.java21.net:443/mqtt","protocolVersion":"5.0","qos":1,"keepaliveSec":60,"cleanStart":false,"auth":"HEADER","headerName":"Authorization","headerScheme":"Basic"},"topics":[{"topic":"application/+/device/+/event/up","qos":1}],"decoderKey":"chirpstack-v4"}'::jsonb,
        'chirpstack-v4', true)
ON CONFLICT (organization_id, template_key) DO NOTHING;
