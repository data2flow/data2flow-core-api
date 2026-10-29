-- CMP-02.05: 개선 조치 기록(design/erd/core-environment.md §3.4, spec/detail/CMP/domain-model.md).
-- ERD와 다른 점:
--   * result_checked_at: 자동 확인(AUTO)을 계산한 시각. 계산했는데 result_ratio_24h가 null이면 "확인 불가"(그 사이 결측률이 품질 기준을 넘음, BR-CMP-18)
--   * created_by: 기록한 사용자(조치자 actor_user_id와 다를 수 있다)
--   * 원인 메모 200자, 조치 1000자, 결과 메모 500자는 CHECK로도 막는다(1자 이상)
CREATE TABLE data2flow_core.corrective_actions (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    organization_id       bigint        NOT NULL,
    excursion_id          bigint        NOT NULL,
    cause                 varchar(24)   NOT NULL,
    cause_note            varchar(200),
    action_text           varchar(1000) NOT NULL,
    actor_user_id         bigint        NOT NULL,
    acted_at              timestamptz   NOT NULL,
    linked_alarm_ids      bigint[],
    linked_work_order_ids bigint[],
    result_mode           varchar(8)    NOT NULL,
    result_ratio_24h      numeric(5,2),
    result_note           varchar(500),
    result_checked_at     timestamptz,
    created_by            bigint        NOT NULL,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_corrective_actions PRIMARY KEY (id),
    CONSTRAINT ck_corrective_actions_cause CHECK (cause IN ('VENTILATION','EQUIPMENT','OUTDOOR_INFLOW','CROWDING','SENSOR','OTHER')),
    CONSTRAINT ck_corrective_actions_cause_note CHECK (cause <> 'OTHER' OR cause_note IS NOT NULL),
    CONSTRAINT ck_corrective_actions_action_text CHECK (length(action_text) >= 1),
    CONSTRAINT ck_corrective_actions_result_mode CHECK (result_mode IN ('AUTO','MANUAL')),
    CONSTRAINT ck_corrective_actions_result_note CHECK (result_mode <> 'MANUAL' OR result_note IS NOT NULL),
    CONSTRAINT fk_corrective_actions_excursion_id FOREIGN KEY (excursion_id) REFERENCES data2flow_core.compliance_excursions (id) ON DELETE CASCADE
);
CREATE INDEX ix_corrective_actions_organization_id_excursion_id ON data2flow_core.corrective_actions (organization_id, excursion_id);
CREATE INDEX ix_corrective_actions_auto_pending ON data2flow_core.corrective_actions (acted_at)
    WHERE result_mode = 'AUTO' AND result_checked_at IS NULL;
COMMENT ON TABLE data2flow_core.corrective_actions IS '개선 조치 기록(CMP-02.05). acted_at ≥ 이탈 시작(애플리케이션 검사). AUTO는 24시간 뒤 result_ratio_24h 채움';
