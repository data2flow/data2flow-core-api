-- CMP-01.01~01.04: 기준 프로필·버전·항목 기준·등급 구간·측정 적정성 규칙·적용(design/erd/core-environment.md §2).
-- 시스템 프로필은 organization_id = 0(모든 조직이 읽음, BR-CMP-01). ERD와 다른 점:
--   * profile_metric_rules.limit_inclusive: 경계 포함 여부(국내법 "이하"는 포함, RESET "<"는 미포함, TC-CMP-010·011·012).
--     등급 구간도 같은 규칙의 포함 여부를 따른다
--   * 연속 측정만 요구하는 항목(CONTINUOUS_MONITORING_ONLY)은 기준값이 없어도 된다(RESET 온습도, WELL A08)

CREATE TABLE data2flow_core.compliance_profiles (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    kind            varchar(16)  NOT NULL,
    code            varchar(64),
    name            varchar(100) NOT NULL,
    current_version integer      NOT NULL DEFAULT 1,
    archived_at     timestamptz,
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint       NOT NULL,
    updated_by      bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_compliance_profiles PRIMARY KEY (id),
    CONSTRAINT uq_compliance_profiles_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_compliance_profiles_current_version CHECK (current_version >= 1),
    CONSTRAINT ck_compliance_profiles_kind CHECK (kind IN ('SYSTEM', 'ORGANIZATION')),
    CONSTRAINT ck_compliance_profiles_system CHECK (kind <> 'SYSTEM' OR (organization_id = 0 AND code IS NOT NULL))
);

CREATE UNIQUE INDEX uq_compliance_profiles_code ON data2flow_core.compliance_profiles (code) WHERE code IS NOT NULL;

COMMENT ON TABLE data2flow_core.compliance_profiles IS '기준 프로필(CMP-01). SYSTEM은 organization_id=0·읽기 전용(BR-CMP-01)';

CREATE TABLE data2flow_core.compliance_profile_versions (
    id                     bigint GENERATED ALWAYS AS IDENTITY,
    organization_id        bigint       NOT NULL,
    profile_id             bigint       NOT NULL,
    version_no             integer      NOT NULL,
    source_title           varchar(200) NOT NULL,
    source_url             varchar(500),
    effective_date         date         NOT NULL,
    description            text,
    streak_days            integer,
    streak_daily_ratio     numeric(5,2),
    facility_type_required boolean      NOT NULL DEFAULT false,
    change_note            varchar(500),
    locked                 boolean      NOT NULL DEFAULT false,
    created_by             bigint       NOT NULL,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_compliance_profile_versions PRIMARY KEY (id),
    CONSTRAINT fk_compliance_profile_versions_profile_id FOREIGN KEY (profile_id)
        REFERENCES data2flow_core.compliance_profiles (id) ON DELETE CASCADE,
    CONSTRAINT uq_compliance_profile_versions_profile_id_version_no UNIQUE (profile_id, version_no),
    CONSTRAINT ck_compliance_profile_versions_change_note CHECK (version_no = 1 OR change_note IS NOT NULL),
    CONSTRAINT ck_compliance_profile_versions_source_url CHECK (source_url IS NULL OR source_url LIKE 'https://%'),
    CONSTRAINT ck_compliance_profile_versions_streak_daily_ratio
        CHECK (streak_daily_ratio IS NULL OR (streak_daily_ratio >= 50 AND streak_daily_ratio <= 100)),
    CONSTRAINT ck_compliance_profile_versions_streak_days CHECK (streak_days IS NULL OR (streak_days >= 1 AND streak_days <= 365)),
    CONSTRAINT ck_compliance_profile_versions_version_no CHECK (version_no >= 1)
);

CREATE INDEX ix_compliance_profile_versions_organization_id_profile_id
    ON data2flow_core.compliance_profile_versions (organization_id, profile_id);

COMMENT ON TABLE data2flow_core.compliance_profile_versions IS '프로필 버전. 적용·리포트에 쓰이면 locked=true(BR-CMP-02)';

CREATE TABLE data2flow_core.profile_metric_rules (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    organization_id    bigint       NOT NULL,
    profile_version_id bigint       NOT NULL,
    metric_key         varchar(64)  NOT NULL,
    facility_type      varchar(64),
    eval_window        varchar(16)  NOT NULL,
    lower_limit        numeric,
    upper_limit        numeric,
    limit_inclusive    boolean      NOT NULL DEFAULT true,
    unit               varchar(16)  NOT NULL,
    requirement        varchar(32)  NOT NULL,
    note               varchar(300),
    sort_order         integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_profile_metric_rules PRIMARY KEY (id),
    CONSTRAINT fk_profile_metric_rules_profile_version_id FOREIGN KEY (profile_version_id)
        REFERENCES data2flow_core.compliance_profile_versions (id) ON DELETE CASCADE,
    CONSTRAINT uq_profile_metric_rules_version_metric_facility UNIQUE NULLS NOT DISTINCT (profile_version_id, metric_key, facility_type),
    CONSTRAINT ck_profile_metric_rules_eval_window CHECK (eval_window IN ('INSTANT', 'HOUR_1', 'HOUR_8', 'DAY_1')),
    CONSTRAINT ck_profile_metric_rules_limits CHECK (
        (requirement = 'CONTINUOUS_MONITORING_ONLY' OR lower_limit IS NOT NULL OR upper_limit IS NOT NULL)
        AND (lower_limit IS NULL OR upper_limit IS NULL OR lower_limit <= upper_limit)),
    CONSTRAINT ck_profile_metric_rules_requirement CHECK (requirement IN ('LIMIT', 'CONTINUOUS_MONITORING_ONLY', 'RECOMMENDED'))
);

CREATE INDEX ix_profile_metric_rules_organization_id_profile_version_id
    ON data2flow_core.profile_metric_rules (organization_id, profile_version_id);

COMMENT ON COLUMN data2flow_core.profile_metric_rules.limit_inclusive IS '경계값을 충족으로 보는가(이하·이상=true, 미만·초과=false)';

CREATE TABLE data2flow_core.profile_grade_bands (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    metric_rule_id  bigint      NOT NULL,
    name            varchar(30) NOT NULL,
    lower_value     numeric,
    upper_value     numeric,
    is_base         boolean     NOT NULL DEFAULT false,
    sort_order      integer     NOT NULL,
    CONSTRAINT pk_profile_grade_bands PRIMARY KEY (id),
    CONSTRAINT fk_profile_grade_bands_metric_rule_id FOREIGN KEY (metric_rule_id)
        REFERENCES data2flow_core.profile_metric_rules (id) ON DELETE CASCADE,
    CONSTRAINT uq_profile_grade_bands_metric_rule_id_name UNIQUE (metric_rule_id, name)
);

CREATE UNIQUE INDEX uq_profile_grade_bands_metric_rule_id_base ON data2flow_core.profile_grade_bands (metric_rule_id) WHERE is_base;
CREATE INDEX ix_profile_grade_bands_organization_id_metric_rule_id ON data2flow_core.profile_grade_bands (organization_id, metric_rule_id);

CREATE TABLE data2flow_core.profile_coverage_rules (
    profile_version_id bigint      NOT NULL,
    kind               varchar(24) NOT NULL,
    organization_id    bigint      NOT NULL,
    params             jsonb       NOT NULL,
    CONSTRAINT pk_profile_coverage_rules PRIMARY KEY (profile_version_id, kind),
    CONSTRAINT fk_profile_coverage_rules_profile_version_id FOREIGN KEY (profile_version_id)
        REFERENCES data2flow_core.compliance_profile_versions (id) ON DELETE CASCADE,
    CONSTRAINT ck_profile_coverage_rules_kind CHECK (kind IN ('DEVICE_DENSITY', 'PUBLIC_DISPLAY_DENSITY', 'CALIBRATION_INTERVAL', 'DATA_QUALITY'))
);

CREATE INDEX ix_profile_coverage_rules_organization_id_profile_version_id
    ON data2flow_core.profile_coverage_rules (organization_id, profile_version_id);

CREATE TABLE data2flow_core.profile_applications (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    organization_id     bigint      NOT NULL,
    profile_id          bigint      NOT NULL,
    pinned_version_no   integer,
    target_type         varchar(16) NOT NULL,
    target_space_id     bigint,
    target_usage        varchar(32),
    include_descendants boolean     NOT NULL DEFAULT true,
    facility_type       varchar(64),
    applied_by          bigint      NOT NULL,
    applied_at          timestamptz NOT NULL DEFAULT now(),
    version             integer     NOT NULL DEFAULT 0,
    CONSTRAINT pk_profile_applications PRIMARY KEY (id),
    CONSTRAINT fk_profile_applications_profile_id FOREIGN KEY (profile_id) REFERENCES data2flow_core.compliance_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_profile_applications_target_space_id FOREIGN KEY (target_space_id) REFERENCES data2flow_core.spaces (id) ON DELETE RESTRICT,
    CONSTRAINT uq_profile_applications_target UNIQUE NULLS NOT DISTINCT (organization_id, profile_id, target_type, target_space_id, target_usage),
    CONSTRAINT ck_profile_applications_target CHECK ((target_type IN ('SPACE', 'SITE') AND target_space_id IS NOT NULL)
        OR (target_type = 'SPACE_USAGE' AND target_usage IS NOT NULL)),
    CONSTRAINT ck_profile_applications_target_type CHECK (target_type IN ('SPACE', 'SPACE_USAGE', 'SITE'))
);

CREATE INDEX ix_profile_applications_organization_id_target_space_id ON data2flow_core.profile_applications (organization_id, target_space_id);

COMMENT ON TABLE data2flow_core.profile_applications IS '프로필 적용(CMP-01.03). pinned_version_no NULL이면 최신 버전을 따름. 시스템 프로필도 조직별로 적용';

CREATE TABLE data2flow_core.profile_rule_links (
    profile_application_id bigint      NOT NULL,
    rule_id                bigint      NOT NULL,
    organization_id        bigint      NOT NULL,
    profile_version_no     integer     NOT NULL,
    grade_band_name        varchar(30),
    update_pending         boolean     NOT NULL DEFAULT false,
    CONSTRAINT pk_profile_rule_links PRIMARY KEY (profile_application_id, rule_id),
    CONSTRAINT fk_profile_rule_links_profile_application_id FOREIGN KEY (profile_application_id)
        REFERENCES data2flow_core.profile_applications (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_rule_links_rule_id FOREIGN KEY (rule_id) REFERENCES data2flow_core.rules (id) ON DELETE RESTRICT
);

CREATE INDEX ix_profile_rule_links_organization_id_profile_application_id
    ON data2flow_core.profile_rule_links (organization_id, profile_application_id);
