-- M5 남은 것(DSC-09.07·09.08, ADR-056): payload 스키마 보관과 토픽 템플릿. 추가만(ADR-030 expand).
-- data_sources.topic_template 열은 M2부터 있었고 이제 저장한다. 스키마는 비밀값이 아니라서 source_secrets.SCHEMA_FILE을 쓰지 않고 따로 둔다. 참조(schema_ref)는 불변이다(내용이 바뀌면 새 참조).

COMMENT ON COLUMN data2flow_core.data_sources.topic_template IS 'DSC-09.08 토픽 템플릿. API-DSC-50 config.topicTemplate로 ingress에 간다(ADR-056)';

CREATE TABLE data2flow_core.payload_schemas (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    source_id       bigint        NOT NULL,
    schema_ref      varchar(40)   NOT NULL,
    format          varchar(10)   NOT NULL,
    file_name       varchar(200)  NOT NULL,
    content         bytea         NOT NULL,
    content_sha256  char(64)      NOT NULL,
    message_types   text[]        NOT NULL DEFAULT '{}',
    message_type    varchar(200),
    created_by      bigint        NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_payload_schemas PRIMARY KEY (id),
    CONSTRAINT uq_payload_schemas_schema_ref UNIQUE (schema_ref),
    CONSTRAINT fk_payload_schemas_source_id FOREIGN KEY (source_id) REFERENCES data2flow_core.data_sources (id) ON DELETE CASCADE,
    CONSTRAINT ck_payload_schemas_format CHECK (format IN ('PROTOBUF', 'AVRO')),
    CONSTRAINT ck_payload_schemas_content_size CHECK (octet_length(content) <= 1048576)
);
CREATE INDEX ix_payload_schemas_organization_id_source_id ON data2flow_core.payload_schemas (organization_id, source_id);
COMMENT ON TABLE data2flow_core.payload_schemas IS 'API-DSC-59로 올린 .proto·FileDescriptorSet·.avsc 원문(1MiB 이하). ingress가 API-DSC-81로 읽어 영구 캐시. DSC-09.07, ADR-056';
COMMENT ON COLUMN data2flow_core.payload_schemas.schema_ref IS '불변 참조(ps_ + 난수). 소스 payload.schemaRef가 가리킨다';
