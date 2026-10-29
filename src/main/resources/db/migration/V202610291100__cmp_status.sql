-- CMP-01.05·02.01: 종합 점수·일 집계·이탈 구간·연속 달성(design/erd/core-environment.md §3, spec/detail/CMP/domain-model.md).
-- ERD와 다른 점:
--   * compliance_daily: 오늘 행은 진행 중이라 분 합계가 하루 분보다 작다. 하루 분은 보통 1440이고 서머타임 날은 1380·1500이라
--     CHECK는 1500 이하로 둔다. daily_ratio는 % 값(0~100)이고 충족·이탈 분이 모두 0이면 null
--   * compliance_streaks.evaluated_on: 마지막으로 반영한 날(같은 날을 두 번 세지 않게)
--   * compliance_excursions: (공간, 버전, 항목, 시작 시각) 유일. 같은 키의 진행 중 구간은 하나만
--   * compliance_score_weights: ERD에 없던 조직별 점수 가중치(BR-CMP-08 "조직 설정에서 변경 가능"). 변경 이력은 감사 로그에 남긴다
--   * 월 파티션은 기본 파티션만 만든다(파티션 관리 작업이 붙으면 월 파티션을 미리 만든다)

CREATE TABLE data2flow_core.compliance_daily (
    space_id           bigint       NOT NULL,
    profile_version_id bigint       NOT NULL,
    metric_key         varchar(64)  NOT NULL,
    day                date         NOT NULL,
    organization_id    bigint       NOT NULL,
    met_minutes        integer      NOT NULL,
    exceeded_minutes   integer      NOT NULL,
    missing_minutes    integer      NOT NULL,
    daily_ratio        numeric(5,2),
    max_value          numeric,
    avg_value          numeric,
    quality_warning    boolean      NOT NULL DEFAULT false,
    computed_at        timestamptz  NOT NULL,
    CONSTRAINT pk_compliance_daily PRIMARY KEY (space_id, profile_version_id, metric_key, day),
    CONSTRAINT ck_compliance_daily_minutes CHECK (met_minutes >= 0 AND exceeded_minutes >= 0 AND missing_minutes >= 0
        AND met_minutes + exceeded_minutes + missing_minutes <= 1500),
    CONSTRAINT ck_compliance_daily_ratio CHECK (daily_ratio IS NULL OR (daily_ratio >= 0 AND daily_ratio <= 100))
) PARTITION BY RANGE (day);

CREATE TABLE data2flow_core.compliance_daily_default PARTITION OF data2flow_core.compliance_daily DEFAULT;

CREATE INDEX ix_compliance_daily_organization_id_day ON data2flow_core.compliance_daily (organization_id, day);

COMMENT ON TABLE data2flow_core.compliance_daily IS '공간·프로필 버전·항목별 일 집계(조직 시간대 일, BR-CMP-07·10)';
COMMENT ON COLUMN data2flow_core.compliance_daily.daily_ratio IS 'met / (met + exceeded) × 100. 결측은 분자·분모 어디에도 넣지 않는다';
COMMENT ON COLUMN data2flow_core.compliance_daily.quality_warning IS '결측 분 / 하루 분 > 10%(데이터 품질 기준)';

CREATE TABLE data2flow_core.compliance_streaks (
    space_id        bigint      NOT NULL,
    profile_id      bigint      NOT NULL,
    organization_id bigint      NOT NULL,
    current_days    integer     NOT NULL DEFAULT 0,
    started_on      date,
    broken_on       date,
    broken_metric   varchar(64),
    evaluated_on    date        NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_compliance_streaks PRIMARY KEY (space_id, profile_id),
    CONSTRAINT ck_compliance_streaks_current_days CHECK (current_days >= 0)
);

CREATE INDEX ix_compliance_streaks_organization_id ON data2flow_core.compliance_streaks (organization_id);

COMMENT ON TABLE data2flow_core.compliance_streaks IS '연속 달성(BR-CMP-11). 닫힌 날마다 하루씩 반영한다';

CREATE TABLE data2flow_core.compliance_excursions (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    organization_id    bigint      NOT NULL,
    space_id           bigint      NOT NULL,
    profile_version_id bigint      NOT NULL,
    metric_key         varchar(64) NOT NULL,
    started_at         timestamptz NOT NULL,
    ended_at           timestamptz,
    peak_value         numeric     NOT NULL,
    alarm_id           bigint,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT pk_compliance_excursions PRIMARY KEY (id),
    CONSTRAINT uq_compliance_excursions_space_version_metric_started UNIQUE (space_id, profile_version_id, metric_key, started_at),
    CONSTRAINT ck_compliance_excursions_period CHECK (ended_at IS NULL OR ended_at > started_at)
);

CREATE UNIQUE INDEX uq_compliance_excursions_open ON data2flow_core.compliance_excursions (space_id, profile_version_id, metric_key)
    WHERE ended_at IS NULL;
CREATE INDEX ix_compliance_excursions_organization_id_space_id_started_at
    ON data2flow_core.compliance_excursions (organization_id, space_id, started_at);

COMMENT ON TABLE data2flow_core.compliance_excursions IS '기준 이탈 구간(EVT-CMP-04). 진행 중이면 ended_at null';

CREATE TABLE data2flow_core.ieq_scores (
    space_id            bigint      NOT NULL,
    time                timestamptz NOT NULL,
    organization_id     bigint      NOT NULL,
    score               smallint,
    indices             jsonb       NOT NULL,
    top_penalty_metric  varchar(64),
    basis               varchar(16) NOT NULL,
    excluded_metrics    varchar(64)[] NOT NULL DEFAULT '{}',
    profile_version_ids bigint[]    NOT NULL DEFAULT '{}',
    computed_at         timestamptz NOT NULL,
    CONSTRAINT pk_ieq_scores PRIMARY KEY (space_id, time),
    CONSTRAINT ck_ieq_scores_score CHECK (score IS NULL OR (score >= 0 AND score <= 100)),
    CONSTRAINT ck_ieq_scores_basis CHECK (basis IN ('PROFILE', 'DEFAULT'))
) PARTITION BY RANGE (time);

CREATE TABLE data2flow_core.ieq_scores_default PARTITION OF data2flow_core.ieq_scores DEFAULT;

CREATE INDEX ix_ieq_scores_organization_id_time ON data2flow_core.ieq_scores (organization_id, time);

COMMENT ON TABLE data2flow_core.ieq_scores IS '종합 실내 환경 점수(5분, BR-CMP-08). 보관 2년';

CREATE TABLE data2flow_core.compliance_score_weights (
    organization_id bigint      NOT NULL,
    weights         jsonb       NOT NULL,
    version         integer     NOT NULL DEFAULT 0,
    updated_by      bigint      NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_compliance_score_weights PRIMARY KEY (organization_id)
);

COMMENT ON TABLE data2flow_core.compliance_score_weights IS '조직 점수 가중치(항목 키 → 0 이상 숫자). 행이 없으면 기본 가중치';
