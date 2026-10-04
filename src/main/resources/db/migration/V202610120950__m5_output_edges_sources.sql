-- =====================================================================
-- M5 데이터 관리(core-api, DSC): 출력 연결(DSC-04.01), Webhook 수신 소스·인증 방식 매트릭스·TLS(DSC-01.03·09.05·09.06),
-- 무중단 자격증명 교체(DSC-07.02), 커넥터 템플릿(DSC-09.12), 엣지 게이트웨이 원격 관리·업데이트(DSC-08.03·08.04).
-- 정본 DDL: data2flow-docs design/erd/ddl/12-core-data-automation.sql §DSC(output_*, edge_*).
-- 추가만 한다(ADR-030 expand). 기존 표는 열 추가와 CHECK 완화(비밀값 종류 TOKEN·GCP_SERVICE_ACCOUNT 허용)만 한다.
-- 문서와 다른 점(ERD 반영 필요):
--   * output_config_versions: 출력 연결 실행 설정 버전(API-DSC-73 sinceVersion). config_versions의 scope CHECK를 건드리지 않으려고 따로 둔다
--   * source_secrets.pending_kid·pending_fingerprint·cert_not_after, source_secret_rotations: 무중단 교체 진행(BR-DSC-09)·인증서 만료
--   * edge_commands: 재시작·로그 수집 요청(API-DSC-67)을 다음 하트비트로 전달
--   * edge_gateways.desired_agent_version 등 업데이트 상태 열
-- =====================================================================

-- ---------------------------------------------------------------- 출력 연결(DSC-04.01)
CREATE TABLE data2flow_core.output_connections (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    name varchar(100) NOT NULL,
    type varchar(12) NOT NULL,
    target jsonb NOT NULL,
    filter jsonb NOT NULL,
    format varchar(12) NOT NULL DEFAULT 'CANONICAL',
    template text,
    enabled boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 0,
    created_by bigint NOT NULL,
    updated_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_output_connections PRIMARY KEY (id),
    CONSTRAINT uq_output_connections_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_output_connections_type CHECK (type IN ('MQTT_PUBLISH','WEBHOOK')),
    CONSTRAINT ck_output_connections_format CHECK (format IN ('CANONICAL','TEMPLATE')),
    CONSTRAINT ck_output_connections_template CHECK (format <> 'TEMPLATE' OR template IS NOT NULL)
);
COMMENT ON TABLE data2flow_core.output_connections IS '표준 텔레메트리 출력 연결(외부 MQTT·Webhook). 실행은 action의 output 패키지. DSC-04.01';
COMMENT ON COLUMN data2flow_core.output_connections.filter IS '{deviceIds[], groupIds[], spaceIds[], metrics[], qualityMin}';
COMMENT ON COLUMN data2flow_core.output_connections.template IS 'Mustache(로직 없음)';

CREATE TABLE data2flow_core.output_secrets (
    output_id bigint NOT NULL,
    organization_id bigint NOT NULL,
    kind varchar(16) NOT NULL,
    ciphertext bytea NOT NULL,
    kid varchar(20) NOT NULL,
    fingerprint char(8) NOT NULL,
    rotated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_output_secrets PRIMARY KEY (output_id, kind),
    CONSTRAINT ck_output_secrets_kind CHECK (kind IN ('PASSWORD','CA_CERT','HEADER_VALUE','HMAC_KEY')),
    CONSTRAINT fk_output_secrets_output_id FOREIGN KEY (output_id) REFERENCES data2flow_core.output_connections (id) ON DELETE CASCADE
);
CREATE INDEX ix_output_secrets_organization_id_output_id ON data2flow_core.output_secrets (organization_id, output_id);
COMMENT ON TABLE data2flow_core.output_secrets IS '출력 연결 비밀값(source_secrets와 같은 방식). DSC domain-model §2.9';

CREATE TABLE data2flow_core.output_delivery_stats (
    output_id bigint NOT NULL,
    minute timestamptz NOT NULL,
    organization_id bigint NOT NULL,
    sent integer NOT NULL DEFAULT 0,
    failed integer NOT NULL DEFAULT 0,
    retried integer NOT NULL DEFAULT 0,
    lag_ms integer,
    CONSTRAINT pk_output_delivery_stats PRIMARY KEY (output_id, minute),
    CONSTRAINT fk_output_delivery_stats_output_id FOREIGN KEY (output_id) REFERENCES data2flow_core.output_connections (id) ON DELETE CASCADE
);
CREATE INDEX ix_output_delivery_stats_organization_id_output_id ON data2flow_core.output_delivery_stats (organization_id, output_id);
COMMENT ON TABLE data2flow_core.output_delivery_stats IS '출력 연결 1분 발송 지표(action이 API-DSC-75로 보고). 7일 보관';

CREATE TABLE data2flow_core.output_config_versions (
    organization_id bigint NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_output_config_versions PRIMARY KEY (organization_id)
);
COMMENT ON TABLE data2flow_core.output_config_versions IS '조직별 출력 연결 실행 설정 버전(API-DSC-73 sinceVersion). 연결·비밀값이 바뀔 때마다 1 오른다';

-- ---------------------------------------------------------------- 소스 비밀값(DSC-07.02·09.05·09.06)
ALTER TABLE data2flow_core.source_secrets DROP CONSTRAINT ck_source_secrets_kind;
ALTER TABLE data2flow_core.source_secrets ADD CONSTRAINT ck_source_secrets_kind CHECK (kind IN ('PASSWORD','HEADER_VALUE','CLIENT_CERT',
    'CLIENT_KEY','CA_CERT','API_KEY','HMAC_KEY','OAUTH2_CLIENT','SAS_KEY','SASL','AWS_KEYS','TLS_CA_BUNDLE','TLS_CLIENT_CERT','SCHEMA_FILE',
    'TOKEN','GCP_SERVICE_ACCOUNT'));
ALTER TABLE data2flow_core.source_secrets ADD COLUMN pending_kid varchar(20);
ALTER TABLE data2flow_core.source_secrets ADD COLUMN pending_fingerprint char(8);
ALTER TABLE data2flow_core.source_secrets ADD COLUMN cert_not_after timestamptz;
COMMENT ON COLUMN data2flow_core.source_secrets.cert_not_after IS 'PEM 인증서(CA_CERT·CLIENT_CERT)의 가장 이른 만료 시각. 30일 전부터 화면 경고(DSC-09.06)';

CREATE TABLE data2flow_core.source_secret_rotations (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    source_id bigint NOT NULL,
    rotation_id varchar(36) NOT NULL,
    state varchar(10) NOT NULL DEFAULT 'ROTATING',
    kinds text[] NOT NULL,
    instances jsonb NOT NULL DEFAULT '{}'::jsonb,
    error varchar(500),
    started_by bigint NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CONSTRAINT pk_source_secret_rotations PRIMARY KEY (id),
    CONSTRAINT uq_source_secret_rotations_rotation_id UNIQUE (rotation_id),
    CONSTRAINT ck_source_secret_rotations_state CHECK (state IN ('ROTATING','DONE','FAILED')),
    CONSTRAINT fk_source_secret_rotations_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE
);
CREATE INDEX ix_source_secret_rotations_organization_id_source_id ON data2flow_core.source_secret_rotations (organization_id, source_id, started_at DESC);
COMMENT ON TABLE data2flow_core.source_secret_rotations IS '무중단 자격증명 교체(DSC-07.02, BR-DSC-09). instances = {instanceId: {ok, error, at}}(EVT-DSC-08)';

-- Webhook 수신 키는 모든 조직에서 하나뿐(ingress가 경로 키만으로 소스를 찾는다, API-DSC-54, DSC-01.03)
CREATE UNIQUE INDEX uq_data_sources_webhook_source_key ON data2flow_core.data_sources ((connection->>'sourceKey')) WHERE type = 'WEBHOOK';

-- ---------------------------------------------------------------- 엣지 게이트웨이(DSC-08.03·08.04)
CREATE TABLE data2flow_core.edge_gateways (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    name varchar(100) NOT NULL,
    site_id bigint NOT NULL,
    source_id bigint,
    status varchar(15) NOT NULL DEFAULT 'REGISTERING',
    agent_version varchar(20),
    arch varchar(20),
    cert_fingerprint char(64),
    applied_config_version integer,
    desired_config_version integer,
    buffer_used_bytes bigint,
    buffer_items bigint,
    dropped_items bigint NOT NULL DEFAULT 0,
    throughput double precision,
    last_seen_at timestamptz,
    revoked_at timestamptz,
    version integer NOT NULL DEFAULT 0,
    created_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_edge_gateways PRIMARY KEY (id),
    CONSTRAINT uq_edge_gateways_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_edge_gateways_status CHECK (status IN ('REGISTERING','ONLINE','OFFLINE','UPDATING','ERROR','REVOKED')),
    CONSTRAINT fk_edge_gateways_site_id FOREIGN KEY (site_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT fk_edge_gateways_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE RESTRICT
);
COMMENT ON TABLE data2flow_core.edge_gateways IS '엣지 게이트웨이(DSC-08). mTLS 인증서 지문, 버퍼 사용량은 하트비트 보고값';

CREATE TABLE data2flow_core.edge_registration_tokens (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    edge_id bigint NOT NULL,
    token_hash char(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_edge_registration_tokens PRIMARY KEY (id),
    CONSTRAINT uq_edge_registration_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_edge_registration_tokens_edge_id FOREIGN KEY (edge_id) REFERENCES data2flow_core.edge_gateways (id) ON DELETE CASCADE
);
CREATE INDEX ix_edge_registration_tokens_organization_id_edge_id ON data2flow_core.edge_registration_tokens (organization_id, edge_id);
COMMENT ON TABLE data2flow_core.edge_registration_tokens IS '엣지 등록 토큰. 24시간, 1회용(BR-DSC-31). 해시만 저장';

CREATE TABLE data2flow_core.edge_config_versions (
    edge_id bigint NOT NULL,
    version_no integer NOT NULL,
    organization_id bigint NOT NULL,
    targets jsonb NOT NULL,
    decoders jsonb,
    result varchar(20) NOT NULL DEFAULT 'PENDING',
    error varchar(500),
    created_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    deployed_at timestamptz,
    CONSTRAINT pk_edge_config_versions PRIMARY KEY (edge_id, version_no),
    CONSTRAINT ck_edge_config_versions_result CHECK (result IN ('PENDING','APPLIED','FAILED_ROLLED_BACK')),
    CONSTRAINT fk_edge_config_versions_edge_id FOREIGN KEY (edge_id) REFERENCES data2flow_core.edge_gateways (id) ON DELETE CASCADE
);
CREATE INDEX ix_edge_config_versions_organization_id_edge_id ON data2flow_core.edge_config_versions (organization_id, edge_id);
COMMENT ON TABLE data2flow_core.edge_config_versions IS '엣지 수집 설정 판(BR-DSC-32). deployed_at이 있으면 배포 요청됨, result는 에이전트 보고';

CREATE TABLE data2flow_core.edge_updates (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    edge_id bigint NOT NULL,
    from_version varchar(20) NOT NULL,
    to_version varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'APPROVED',
    approved_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT pk_edge_updates PRIMARY KEY (id),
    CONSTRAINT ck_edge_updates_status CHECK (status IN ('APPROVED','IN_PROGRESS','SUCCEEDED','FAILED_ROLLED_BACK')),
    CONSTRAINT fk_edge_updates_edge_id FOREIGN KEY (edge_id) REFERENCES data2flow_core.edge_gateways (id) ON DELETE CASCADE
);
CREATE INDEX ix_edge_updates_organization_id_edge_id ON data2flow_core.edge_updates (organization_id, edge_id);
COMMENT ON TABLE data2flow_core.edge_updates IS '엣지 에이전트 업데이트 이력(BR-DSC-33: 승인한 엣지만, 실패 시 5분 안에 이전 버전)';

CREATE TABLE data2flow_core.edge_commands (
    id bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint NOT NULL,
    edge_id bigint NOT NULL,
    request_id varchar(36) NOT NULL,
    kind varchar(15) NOT NULL,
    args jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(10) NOT NULL DEFAULT 'PENDING',
    requested_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    CONSTRAINT pk_edge_commands PRIMARY KEY (id),
    CONSTRAINT uq_edge_commands_request_id UNIQUE (request_id),
    CONSTRAINT ck_edge_commands_kind CHECK (kind IN ('RESTART','COLLECT_LOGS')),
    CONSTRAINT ck_edge_commands_status CHECK (status IN ('PENDING','SENT')),
    CONSTRAINT fk_edge_commands_edge_id FOREIGN KEY (edge_id) REFERENCES data2flow_core.edge_gateways (id) ON DELETE CASCADE
);
CREATE INDEX ix_edge_commands_organization_id_edge_id ON data2flow_core.edge_commands (organization_id, edge_id, status);
COMMENT ON TABLE data2flow_core.edge_commands IS '엣지 원격 명령(API-DSC-67 재시작·로그 수집). 다음 하트비트(API-DSC-79) 응답으로 전달';

-- ---------------------------------------------------------------- 커넥터 템플릿(DSC-09.12, TC-DSC-308 11종)
-- 템플릿이 가리키는 커넥터가 카탈로그에 없으면 FK를 걸 수 없어, ingress 보고(EVT-DSC-09) 전에도 쓸 수 있게 ingress 스키마로 넣는다.
-- ingress가 같은 키로 보고하면 스키마·버전을 덮어쓴다(BR-DSC-23)
INSERT INTO data2flow_core.connector_catalogs (connector_key, version, category, config_schema, auth_methods, payload_formats, ack_mode,
                                               scaling, supports_send, name, standard, transports)
VALUES
       ('sparkplug-b', '1.0.0', 'MQTT', '{"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://data2flow.java21.net/schemas/connectors/sparkplug-b-1.0.0.json","title":"Sparkplug B","description":"spBv1.0/{groupId}/#을 구독해 NBIRTH·NDATA·NDEATH·DBIRTH·DDATA·DDEATH만 기록한다(STATE·명령 제외). payload(Protobuf)는 그대로 넘기고 pipeline이 해석. 발행하지 않는다.","type":"object","additionalProperties":false,"required":["url"],"properties":{"url":{"type":"string","pattern":"^(tcp|mqtt|ssl|mqtts|ws|wss)://[^\\s/:]+(:\\d{1,5})?(/.*)?$"},"groupId":{"type":"string","default":"+","description":"그룹 ID. +면 모든 그룹"},"username":{"type":"string"},"auth":{"type":"string","enum":["NONE","USERPASS","MTLS"]},"version":{"type":"string","enum":["5.0","3.1.1"],"default":"3.1.1"},"keepaliveSec":{"type":"integer","minimum":10,"maximum":600,"default":60},"sessionExpirySec":{"type":"integer","minimum":0,"maximum":604800,"default":3600},"qos":{"type":"integer","enum":[0,1,2],"default":1},"sharedGroup":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$","description":"MQTT 5 공유 구독(SCALABLE)"},"clientIdBase":{"type":"string","pattern":"^[a-z0-9][a-z0-9-]{0,80}$"},"tlsInsecure":{"type":"boolean","default":false}},"x-ui":{"tabs":[{"name":"connection","fields":["url","groupId","username","auth"]},{"name":"session","fields":["version","keepaliveSec","sessionExpirySec","qos","sharedGroup","clientIdBase","tlsInsecure"]}]}}'::jsonb,
        '{NONE,USER_PASSWORD,MTLS}', '{SPARKPLUG_B}', 'AFTER_WRITE', 'DUAL_ACTIVE', false, 'Sparkplug B', 'Eclipse Sparkplug 3.0', '{tcp,ssl,ws,wss}'),
       ('tts-v3', '1.0.0', 'LORAWAN', '{"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://data2flow.java21.net/schemas/connectors/tts-v3-1.0.0.json","title":"The Things Stack v3","description":"MQTT 통합: 사용자 이름 {applicationId}@{tenantId}, 비밀번호는 API 키(비밀값 PASSWORD), 토픽 v3/{사용자}/devices/+/up, 디코더 tts-v3.","type":"object","additionalProperties":false,"required":["host","applicationId"],"properties":{"host":{"type":"string","description":"예: eu1.cloud.thethings.network"},"url":{"type":"string","pattern":"^(tcp|mqtt|ssl|mqtts|ws|wss)://[^\\s/:]+(:\\d{1,5})?(/.*)?$"},"applicationId":{"type":"string"},"tenantId":{"type":"string","default":"ttn"},"event":{"type":"string","enum":["up","join","down/ack"],"default":"up"},"version":{"type":"string","enum":["5.0","3.1.1"],"default":"3.1.1"},"keepaliveSec":{"type":"integer","minimum":10,"maximum":600,"default":60},"sessionExpirySec":{"type":"integer","minimum":0,"maximum":604800,"default":3600},"qos":{"type":"integer","enum":[0,1,2],"default":1},"sharedGroup":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$","description":"MQTT 5 공유 구독(SCALABLE)"},"clientIdBase":{"type":"string","pattern":"^[a-z0-9][a-z0-9-]{0,80}$"},"tlsInsecure":{"type":"boolean","default":false}},"x-ui":{"tabs":[{"name":"connection","fields":["host","url","applicationId","tenantId","event"]},{"name":"session","fields":["version","keepaliveSec","sessionExpirySec","qos","sharedGroup","clientIdBase","tlsInsecure"]}]}}'::jsonb,
        '{USER_PASSWORD}', '{BINARY,JSON,TEXT}', 'AFTER_WRITE', 'DUAL_ACTIVE', false, 'The Things Stack v3', 'The Things Stack MQTT v3', '{ssl}'),
       ('aws-iot-core', '1.0.0', 'CLOUD_HUB', '{"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://data2flow.java21.net/schemas/connectors/aws-iot-core-1.0.0.json","title":"AWS IoT Core","description":"엔드포인트 8883에 X.509 클라이언트 인증서(mTLS)로 접속해 토픽을 구독한다. 비밀값 CLIENT_CERT·CLIENT_KEY·CA_CERT.","type":"object","additionalProperties":false,"required":["endpoint","topics"],"properties":{"endpoint":{"type":"string","description":"예: abc123-ats.iot.ap-northeast-2.amazonaws.com"},"url":{"type":"string","pattern":"^(tcp|mqtt|ssl|mqtts|ws|wss)://[^\\s/:]+(:\\d{1,5})?(/.*)?$"},"topics":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"string"}},"version":{"type":"string","enum":["5.0","3.1.1"],"default":"3.1.1"},"keepaliveSec":{"type":"integer","minimum":10,"maximum":600,"default":60},"sessionExpirySec":{"type":"integer","minimum":0,"maximum":604800,"default":3600},"qos":{"type":"integer","enum":[0,1,2],"default":1},"sharedGroup":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$","description":"MQTT 5 공유 구독(SCALABLE)"},"clientIdBase":{"type":"string","pattern":"^[a-z0-9][a-z0-9-]{0,80}$"},"tlsInsecure":{"type":"boolean","default":false}},"x-ui":{"tabs":[{"name":"connection","fields":["endpoint","url","topics"]},{"name":"session","fields":["version","keepaliveSec","sessionExpirySec","qos","sharedGroup","clientIdBase","tlsInsecure"]}]}}'::jsonb,
        '{MTLS}', '{BINARY,JSON,TEXT}', 'AFTER_WRITE', 'DUAL_ACTIVE', false, 'AWS IoT Core', 'AWS IoT Core MQTT', '{ssl}'),
       ('azure-iot-hub', '1.0.0', 'CLOUD_HUB', '{"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://data2flow.java21.net/schemas/connectors/azure-iot-hub-1.0.0.json","title":"Azure IoT Hub (MQTT)","description":"장치 엔드포인트에 SAS 토큰(비밀값 SAS_KEY로 생성)으로 접속해 클라우드→장치 토픽을 구독한다. 전체 텔레메트리는 Event Hubs 호환 엔드포인트(amqp10 커넥터)로 받는다.","type":"object","additionalProperties":false,"required":["hubName","deviceId"],"properties":{"hubName":{"type":"string"},"deviceId":{"type":"string"},"url":{"type":"string","pattern":"^(tcp|mqtt|ssl|mqtts|ws|wss)://[^\\s/:]+(:\\d{1,5})?(/.*)?$"},"topics":{"type":"array","items":{"type":"string"}},"sasTtlSec":{"type":"integer","minimum":300,"maximum":2592000,"default":86400},"version":{"type":"string","enum":["5.0","3.1.1"],"default":"3.1.1"},"keepaliveSec":{"type":"integer","minimum":10,"maximum":600,"default":60},"sessionExpirySec":{"type":"integer","minimum":0,"maximum":604800,"default":3600},"qos":{"type":"integer","enum":[0,1,2],"default":1},"sharedGroup":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$","description":"MQTT 5 공유 구독(SCALABLE)"},"clientIdBase":{"type":"string","pattern":"^[a-z0-9][a-z0-9-]{0,80}$"},"tlsInsecure":{"type":"boolean","default":false}},"x-ui":{"tabs":[{"name":"connection","fields":["hubName","deviceId","url","topics","sasTtlSec"]},{"name":"session","fields":["version","keepaliveSec","sessionExpirySec","qos","sharedGroup","clientIdBase","tlsInsecure"]}]}}'::jsonb,
        '{TOKEN}', '{BINARY,JSON,TEXT}', 'AFTER_WRITE', 'DUAL_ACTIVE', false, 'Azure IoT Hub (MQTT)', 'Azure IoT Hub MQTT 3.1.1', '{ssl}'),
       ('amqp091', '1.0.0', 'QUEUE', '{"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://data2flow.java21.net/schemas/connectors/amqp091-1.0.0.json","title":"AMQP 0-9-1 (RabbitMQ)","description":"큐에서 수동 확인으로 가져와 기록(confirm) 뒤 ack(AFTER_WRITE, AT-DSC-20.1). 큐는 만들지 않는다. 비밀값 PASSWORD·CA_CERT·CLIENT_CERT·CLIENT_KEY는 비밀값 저장소에 둔다(BR-DSC-02).","type":"object","additionalProperties":false,"required":["url","queue"],"properties":{"url":{"type":"string","pattern":"^amqps?://[^\\s/:]+(:\\d{1,5})?/?$","description":"예: amqps://broker.example.com:5671"},"vhost":{"type":"string","default":"/"},"queue":{"type":"string","minLength":1,"maxLength":255},"username":{"type":"string","maxLength":256},"batchSize":{"type":"integer","minimum":1,"maximum":1000,"default":100,"description":"한 번에 가져와 함께 확인하는 수"},"tlsInsecure":{"type":"boolean","default":false,"description":"인증서 검증 끄기. 개발 소스만(BR-DSC-29)"}},"x-ui":{"tabs":[{"name":"connection","fields":["url","vhost","queue","batchSize"]},{"name":"security","fields":["username","tlsInsecure"]}]}}'::jsonb,
        '{MTLS,USER_PASSWORD}', '{AVRO,BINARY,CBOR,JSON,MSGPACK,PROTOBUF,TEXT}', 'AFTER_WRITE', 'SCALABLE', false, 'AMQP 0-9-1 (RabbitMQ)', 'AMQP 0-9-1', '{amqp,amqps}'),
       ('kafka', '1.0.0', 'QUEUE', '{"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://data2flow.java21.net/schemas/connectors/kafka-1.0.0.json","title":"Apache Kafka","description":"소비자 그룹으로 받고 기록(confirm) 뒤 오프셋을 커밋한다(AFTER_WRITE, AT-DSC-20.2). SASL 비밀번호는 비밀값 PASSWORD.","type":"object","additionalProperties":false,"required":["bootstrapServers","topics"],"properties":{"bootstrapServers":{"type":"string","pattern":"^[^\\s,]+:\\d{1,5}(,[^\\s,]+:\\d{1,5})*$","description":"예: kafka-1:9092,kafka-2:9092"},"topics":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"string","minLength":1,"maxLength":249}},"groupId":{"type":"string","maxLength":255,"description":"비우면 data2flow-ingress-{소스 ID}"},"securityProtocol":{"type":"string","enum":["PLAINTEXT","SSL","SASL_PLAINTEXT","SASL_SSL"],"default":"PLAINTEXT"},"saslMechanism":{"type":"string","enum":["PLAIN","SCRAM-SHA-256","SCRAM-SHA-512"],"default":"SCRAM-SHA-512"},"username":{"type":"string","maxLength":256},"startFrom":{"type":"string","enum":["earliest","latest"],"default":"earliest","description":"그룹에 커밋 위치가 없을 때"},"batchSize":{"type":"integer","minimum":1,"maximum":10000,"default":500}},"x-ui":{"tabs":[{"name":"connection","fields":["bootstrapServers","topics","groupId","startFrom","batchSize"]},{"name":"security","fields":["securityProtocol","saslMechanism","username"]}]}}'::jsonb,
        '{MTLS,NONE,SASL_PLAIN,SASL_SCRAM_256,SASL_SCRAM_512}', '{AVRO,BINARY,CBOR,JSON,MSGPACK,PROTOBUF,TEXT}', 'AFTER_WRITE', 'SCALABLE', false, 'Apache Kafka', 'Apache Kafka', '{tcp,ssl}')
ON CONFLICT (connector_key) DO NOTHING;

INSERT INTO data2flow_core.connector_templates (organization_id, template_key, connector_key, name, description, preset, decoder_key, docs_url, builtin)
VALUES (0, 'tts-v3', 'tts-v3', 'The Things Stack v3', 'TTS MQTT 통합(v3/{앱}@{테넌트}/devices/+/up), 비밀번호는 API 키',
        '{"connection":{"host":"eu1.cloud.thethings.network","applicationId":"","tenantId":"ttn","event":"up","qos":1},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.end_device_ids.dev_eui","timePath":"$.received_at","timeFormat":"ISO8601","metrics":[{"path":"$.uplink_message.decoded_payload.temperature","key":"temperature","unit":"℃"}]}}'::jsonb,
        'generic-json', 'https://www.thethingsindustries.com/docs/integrations/mqtt/', true),
       (0, 'aws-iot-core', 'aws-iot-core', 'AWS IoT Core', '{id}-ats.iot.{region}.amazonaws.com:8883 + X.509 클라이언트 인증서(mTLS)',
        '{"connection":{"endpoint":"","topics":["dt/+/telemetry"],"qos":1},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","timePath":"$.ts","timeFormat":"AUTO","metrics":[{"path":"$.temperature","key":"temperature","unit":"℃"}]}}'::jsonb,
        'generic-json', 'https://docs.aws.amazon.com/iot/latest/developerguide/mqtt.html', true),
       (0, 'azure-iot-hub', 'azure-iot-hub', 'Azure IoT Hub (MQTT)', '{hub}.azure-devices.net:8883, 장치 SAS 키(SAS_KEY)로 토큰 생성',
        '{"connection":{"hubName":"","deviceId":"","sasTtlSec":86400},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","timePath":"$.ts","timeFormat":"AUTO","metrics":[{"path":"$.temperature","key":"temperature","unit":"℃"}]}}'::jsonb,
        'generic-json', 'https://learn.microsoft.com/azure/iot/iot-mqtt-connect-to-iot-hub', true),
       (0, 'hivemq-cloud', 'mqtt', 'HiveMQ Cloud', 'ssl://{클러스터}.s1.eu.hivemq.cloud:8883, 사용자 이름·비밀번호',
        '{"connection":{"url":"ssl://CLUSTER.s1.eu.hivemq.cloud:8883","protocolVersion":"5.0","qos":1,"keepaliveSec":60,"cleanStart":false,"auth":"USERPASS"},"topics":[{"topic":"devices/+/telemetry","qos":1}],"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","timePath":"$.ts","timeFormat":"AUTO","metrics":[{"path":"$.temperature","key":"temperature","unit":"℃"}]}}'::jsonb,
        'generic-json', 'https://www.hivemq.com/docs/hivemq-cloud/', true),
       (0, 'emqx', 'mqtt', 'EMQX', 'tcp://{호스트}:1883 또는 ssl://{호스트}:8883, 사용자 이름·비밀번호',
        '{"connection":{"url":"tcp://emqx.example.com:1883","protocolVersion":"5.0","qos":1,"keepaliveSec":60,"cleanStart":false,"auth":"USERPASS"},"topics":[{"topic":"sensors/#","qos":1}],"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","timePath":"$.ts","timeFormat":"AUTO","metrics":[{"path":"$.temperature","key":"temperature","unit":"℃"}]}}'::jsonb,
        'generic-json', 'https://docs.emqx.com/', true),
       (0, 'sparkplug-b', 'sparkplug-b', 'Sparkplug B', 'spBv1.0/{groupId}/# 구독(NBIRTH·NDATA·DBIRTH·DDATA), 구독 전용',
        '{"connection":{"url":"tcp://broker.example.com:1883","groupId":"+","qos":1}}'::jsonb,
        NULL, 'https://sparkplug.eclipse.org/', true),
       (0, 'rabbitmq', 'amqp091', 'RabbitMQ (AMQP 0-9-1)', 'amqps://{호스트}:5671 큐 구독(기록 뒤 ack)',
        '{"connection":{"url":"amqps://rabbitmq.example.com:5671","vhost":"/","queue":"telemetry","batchSize":100},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","timePath":"$.ts","timeFormat":"AUTO","metrics":[{"path":"$.temperature","key":"temperature","unit":"℃"}]}}'::jsonb,
        'generic-json', 'https://www.rabbitmq.com/docs/', true),
       (0, 'kafka', 'kafka', 'Apache Kafka', 'SASL_SSL·SCRAM-SHA-512, 처음부터 읽기(earliest)',
        '{"connection":{"bootstrapServers":"kafka.example.com:9093","topics":["iot.telemetry"],"securityProtocol":"SASL_SSL","saslMechanism":"SCRAM-SHA-512","startFrom":"earliest"},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","timePath":"$.ts","timeFormat":"AUTO","metrics":[{"path":"$.temperature","key":"temperature","unit":"℃"}]}}'::jsonb,
        'generic-json', 'https://kafka.apache.org/documentation/', true),
       (0, 'milesight-gateway', 'mqtt', 'Milesight 게이트웨이 (내장 NS MQTT)', 'Milesight UG6x 내장 네트워크 서버의 MQTT 업링크, 사용자 이름·비밀번호',
        '{"connection":{"url":"tcp://GATEWAY_IP:1883","protocolVersion":"3.1.1","qos":1,"keepaliveSec":60,"cleanStart":false,"auth":"USERPASS"},"topics":[{"topic":"/milesight/uplink","qos":1}],"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.devEUI","timePath":"$.time","timeFormat":"AUTO","metrics":[{"path":"$.temperature","key":"temperature","unit":"℃"}]}}'::jsonb,
        'generic-json', 'https://support.milesight-iot.com/', true)
ON CONFLICT (organization_id, template_key) DO NOTHING;
