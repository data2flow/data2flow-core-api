-- API-ENE-70 설정 묶음 버전: 단가·배출계수·원천을 바꿀 때마다(EVT-ENE-07과 같은 트랜잭션) 하나 올린다. analytics는 캐시한 버전과 비교한다
CREATE TABLE data2flow_core.energy_config_state (
    organization_id bigint      NOT NULL,
    config_version  bigint      NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_energy_config_state PRIMARY KEY (organization_id)
);
