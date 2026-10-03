-- data2flow_core 초기 스키마: 신원·접근(IAM)·운영(OPS). 정본 data2flow-docs/design/erd/ddl/10-core-identity-ops.sql과 같다(ERD README §12).
-- 공간·기기(11)·데이터·자동화(12)·환경(13) 파일은 해당 마일스톤에서 다음 마이그레이션으로 더한다.

-- =====================================================================
-- data2flow_core · 신원·접근(IAM) · 운영(OPS)
-- 초기 Flyway 마이그레이션 초안 (data2flow-core-api)
-- 근거: design/erd/core-identity-ops.md, spec/detail/IAM·OPS/domain-model.md
-- 규칙: design/erd/README.md (테이블 복수형, BIGINT IDENTITY, timestamptz UTC,
--       열거값 varchar+CHECK, 같은 스키마 안에서만 FK)
-- organization_id는 모든 업무 테이블에 두지만 FK는 걸지 않는다(v1 단일 조직,
-- core의 다른 ERD 파일과 적용 순서를 묶지 않기 위해). 조회 조건은 BR-IAM-01.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS data2flow_core;

-- ---------------------------------------------------------------------
-- 1. 조직
-- ---------------------------------------------------------------------
CREATE TABLE data2flow_core.organizations (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    code        varchar(40)  NOT NULL,
    name        varchar(100) NOT NULL,
    timezone    varchar(40)  NOT NULL DEFAULT 'Asia/Seoul',
    locale      varchar(10)  NOT NULL DEFAULT 'ko',
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_organizations PRIMARY KEY (id),
    CONSTRAINT uq_organizations_code UNIQUE (code),
    CONSTRAINT ck_organizations_code CHECK (code ~ '^[a-z0-9-]{3,40}$'),
    CONSTRAINT ck_organizations_locale CHECK (locale IN ('ko', 'en', 'ja', 'zh')),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
COMMENT ON TABLE data2flow_core.organizations IS '조직. v1은 1행(ADR-004)';

CREATE TABLE data2flow_core.org_security_policies (
    organization_id         bigint      NOT NULL,
    session_idle_minutes    smallint    NOT NULL DEFAULT 30,
    session_absolute_hours  smallint    NOT NULL DEFAULT 12,
    access_ttl_minutes      smallint    NOT NULL DEFAULT 60,
    refresh_ttl_hours       smallint    NOT NULL DEFAULT 6,
    login_max_failures      smallint    NOT NULL DEFAULT 5,
    lockout_minutes         smallint    NOT NULL DEFAULT 15,
    mfa_required_roles      varchar(20)[] NOT NULL DEFAULT '{}',
    signup_request_enabled  boolean     NOT NULL DEFAULT false,
    signup_allowed_domains  varchar(253)[] NOT NULL DEFAULT '{}',
    audit_retention_days    integer     NOT NULL DEFAULT 365,
    version                 integer     NOT NULL DEFAULT 0,
    updated_by              bigint,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_org_security_policies PRIMARY KEY (organization_id),
    CONSTRAINT fk_org_security_policies_organization_id FOREIGN KEY (organization_id)
        REFERENCES data2flow_core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_org_security_policies_session_idle CHECK (session_idle_minutes BETWEEN 5 AND 240),
    CONSTRAINT ck_org_security_policies_session_absolute CHECK (session_absolute_hours BETWEEN 1 AND 24),
    CONSTRAINT ck_org_security_policies_access_ttl CHECK (access_ttl_minutes BETWEEN 5 AND 120),
    CONSTRAINT ck_org_security_policies_refresh_ttl CHECK (refresh_ttl_hours BETWEEN 1 AND 24),
    CONSTRAINT ck_org_security_policies_login_max_failures CHECK (login_max_failures BETWEEN 3 AND 10),
    CONSTRAINT ck_org_security_policies_lockout CHECK (lockout_minutes BETWEEN 5 AND 120),
    CONSTRAINT ck_org_security_policies_audit_retention CHECK (audit_retention_days BETWEEN 365 AND 3650)
);
COMMENT ON TABLE data2flow_core.org_security_policies IS '조직 보안 정책(1:1). IAM-02.03·02.05·03.01·07.01, IAM-01.08';

-- ---------------------------------------------------------------------
-- 2. 사용자·역할
-- ---------------------------------------------------------------------
CREATE TABLE data2flow_core.app_users (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    organization_id       bigint       NOT NULL,
    login_id              varchar(30),
    email                 varchar(254) NOT NULL,
    name                  varchar(50)  NOT NULL,
    phone                 varchar(20),
    locale                varchar(10)  NOT NULL DEFAULT 'ko',
    timezone              varchar(40)  NOT NULL DEFAULT 'Asia/Seoul',
    status                varchar(20)  NOT NULL DEFAULT 'INVITED',
    auth_source           varchar(10)  NOT NULL DEFAULT 'LOCAL',
    password_hash         varchar(255),
    password_changed_at   timestamptz,
    must_change_password  boolean      NOT NULL DEFAULT false,
    failed_login_count    smallint     NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    last_login_ip         inet,
    totp_secret_enc       bytea,
    totp_enabled          boolean      NOT NULL DEFAULT false,
    notification_pref     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    anonymized_at         timestamptz,
    version               integer      NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_app_users PRIMARY KEY (id),
    CONSTRAINT ck_app_users_login_id CHECK (login_id IS NULL OR login_id ~ '^[a-z0-9._-]{4,30}$' OR login_id LIKE 'deleted-%'),
    CONSTRAINT ck_app_users_login_id_required CHECK (status = 'INVITED' OR login_id IS NOT NULL),
    CONSTRAINT ck_app_users_status CHECK (status IN ('INVITED', 'PENDING_APPROVAL', 'ACTIVE', 'LOCKED', 'DISABLED', 'DELETED')),
    CONSTRAINT ck_app_users_auth_source CHECK (auth_source IN ('LOCAL')),
    CONSTRAINT ck_app_users_failed_login_count CHECK (failed_login_count BETWEEN 0 AND 10),
    CONSTRAINT ck_app_users_locale CHECK (locale IN ('ko', 'en', 'ja', 'zh'))
);
CREATE UNIQUE INDEX uq_app_users_organization_id_login_id ON data2flow_core.app_users (organization_id, lower(login_id)) WHERE login_id IS NOT NULL;
CREATE UNIQUE INDEX uq_app_users_organization_id_email ON data2flow_core.app_users (organization_id, lower(email));
CREATE INDEX ix_app_users_organization_id_status ON data2flow_core.app_users (organization_id, status);
COMMENT ON TABLE data2flow_core.app_users IS '회원. user는 예약어라 app_users. 삭제는 익명화(anonymized_at), 행 유지(IAM-01.10)';
COMMENT ON COLUMN data2flow_core.app_users.password_hash IS 'Argon2id 인코딩 문자열. 평문·가역 저장 금지(IAM-02.01)';
COMMENT ON COLUMN data2flow_core.app_users.totp_secret_enc IS 'AES-GCM 암호문(IAM-02.05)';

CREATE TABLE data2flow_core.custom_roles (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(40)  NOT NULL,
    description     varchar(200),
    permissions     varchar(40)[] NOT NULL,
    based_on        varchar(20),
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint       NOT NULL,
    updated_by      bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_custom_roles PRIMARY KEY (id),
    CONSTRAINT uq_custom_roles_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_custom_roles_permissions CHECK (cardinality(permissions) >= 1),
    CONSTRAINT ck_custom_roles_name CHECK (upper(name) NOT IN ('ADMIN', 'INTEGRATOR', 'OPERATOR', 'ANALYST', 'VIEWER')),
    CONSTRAINT ck_custom_roles_based_on CHECK (based_on IS NULL OR based_on IN ('ADMIN', 'INTEGRATOR', 'OPERATOR', 'ANALYST', 'VIEWER'))
);
COMMENT ON TABLE data2flow_core.custom_roles IS '사용자 정의 역할(IAM-04.03, BR-IAM-30)';

CREATE TABLE data2flow_core.user_roles (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    user_id         bigint      NOT NULL,
    role            varchar(20) NOT NULL,
    custom_role_id  bigint,
    space_scope     bigint[]    NOT NULL DEFAULT '{}',
    granted_by      bigint      NOT NULL,
    granted_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_roles PRIMARY KEY (id),
    CONSTRAINT uq_user_roles_user_id UNIQUE (user_id),
    CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) REFERENCES data2flow_core.app_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_custom_role_id FOREIGN KEY (custom_role_id) REFERENCES data2flow_core.custom_roles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_roles_granted_by FOREIGN KEY (granted_by) REFERENCES data2flow_core.app_users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('ADMIN', 'INTEGRATOR', 'OPERATOR', 'ANALYST', 'VIEWER', 'CUSTOM')),
    CONSTRAINT ck_user_roles_custom CHECK ((role = 'CUSTOM') = (custom_role_id IS NOT NULL))
);
CREATE INDEX ix_user_roles_organization_id_role ON data2flow_core.user_roles (organization_id, role);
COMMENT ON COLUMN data2flow_core.user_roles.space_scope IS '비어 있으면 전체 공간, 값이 있으면 그 공간과 하위만(IAM-04.02)';

CREATE TABLE data2flow_core.password_history (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    user_id         bigint       NOT NULL,
    password_hash   varchar(255) NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_password_history PRIMARY KEY (id),
    CONSTRAINT fk_password_history_user_id FOREIGN KEY (user_id) REFERENCES data2flow_core.app_users (id) ON DELETE CASCADE
);
CREATE INDEX ix_password_history_user_id_created_at ON data2flow_core.password_history (user_id, created_at DESC);
COMMENT ON TABLE data2flow_core.password_history IS '사용자당 최근 3개만 유지(IAM-02.02, BR-IAM-03)';

CREATE TABLE data2flow_core.password_reset_tokens (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    user_id         bigint      NOT NULL,
    token_hash      char(64)    NOT NULL,
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    requested_ip    inet,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_password_reset_tokens PRIMARY KEY (id),
    CONSTRAINT uq_password_reset_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_tokens_user_id FOREIGN KEY (user_id) REFERENCES data2flow_core.app_users (id) ON DELETE CASCADE
);
CREATE INDEX ix_password_reset_tokens_user_id_unused ON data2flow_core.password_reset_tokens (user_id) WHERE used_at IS NULL;
COMMENT ON TABLE data2flow_core.password_reset_tokens IS '1회용, 30분. 새 요청 시 이전 미사용 토큰 무효(IAM-02.04, BR-IAM-11)';

CREATE TABLE data2flow_core.mfa_recovery_codes (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint      NOT NULL,
    user_id         bigint      NOT NULL,
    code_hash       char(64)    NOT NULL,
    used_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_mfa_recovery_codes PRIMARY KEY (id),
    CONSTRAINT uq_mfa_recovery_codes_user_id_code_hash UNIQUE (user_id, code_hash),
    CONSTRAINT fk_mfa_recovery_codes_user_id FOREIGN KEY (user_id) REFERENCES data2flow_core.app_users (id) ON DELETE CASCADE
);
COMMENT ON TABLE data2flow_core.mfa_recovery_codes IS '2단계 인증 복구 코드 10개, 1회용(IAM-02.05, BR-IAM-26)';

CREATE TABLE data2flow_core.invitations (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    user_id         bigint       NOT NULL,
    email           varchar(254) NOT NULL,
    role            varchar(20)  NOT NULL,
    custom_role_id  bigint,
    space_scope     bigint[]     NOT NULL DEFAULT '{}',
    token_hash      char(64)     NOT NULL,
    status          varchar(20)  NOT NULL DEFAULT 'PENDING',
    expires_at      timestamptz  NOT NULL,
    sent_count      smallint     NOT NULL DEFAULT 1,
    invited_by      bigint       NOT NULL,
    accepted_at     timestamptz,
    canceled_at     timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_invitations PRIMARY KEY (id),
    CONSTRAINT uq_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_invitations_user_id FOREIGN KEY (user_id) REFERENCES data2flow_core.app_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_invitations_custom_role_id FOREIGN KEY (custom_role_id) REFERENCES data2flow_core.custom_roles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_invitations_role CHECK (role IN ('ADMIN', 'INTEGRATOR', 'OPERATOR', 'ANALYST', 'VIEWER', 'CUSTOM')),
    CONSTRAINT ck_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT ck_invitations_sent_count CHECK (sent_count BETWEEN 1 AND 6)
);
CREATE UNIQUE INDEX uq_invitations_organization_id_email_pending ON data2flow_core.invitations (organization_id, lower(email)) WHERE status = 'PENDING';
CREATE INDEX ix_invitations_expires_at_pending ON data2flow_core.invitations (expires_at) WHERE status = 'PENDING';
COMMENT ON TABLE data2flow_core.invitations IS '초대. 72시간·1회용, 재발송 최대 5회(IAM-01.03, BR-IAM-09·10)';

CREATE TABLE data2flow_core.signup_requests (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint       NOT NULL,
    email             varchar(254) NOT NULL,
    name              varchar(50)  NOT NULL,
    login_id          varchar(30)  NOT NULL,
    password_hash     varchar(255) NOT NULL,
    message           varchar(500),
    email_verify_token_hash char(64),
    email_verified_at timestamptz,
    status            varchar(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    user_id           bigint,
    decided_by        bigint,
    decided_at        timestamptz,
    reject_reason     varchar(500),
    request_ip        inet,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_signup_requests PRIMARY KEY (id),
    CONSTRAINT uq_signup_requests_email_verify_token_hash UNIQUE (email_verify_token_hash),
    CONSTRAINT fk_signup_requests_user_id FOREIGN KEY (user_id) REFERENCES data2flow_core.app_users (id) ON DELETE SET NULL,
    CONSTRAINT ck_signup_requests_login_id CHECK (login_id ~ '^[a-z0-9._-]{4,30}$'),
    CONSTRAINT ck_signup_requests_status CHECK (status IN ('PENDING_VERIFICATION', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_signup_requests_reject_reason CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL)
);
CREATE UNIQUE INDEX uq_signup_requests_organization_id_login_id_open ON data2flow_core.signup_requests (organization_id, lower(login_id))
    WHERE status IN ('PENDING_VERIFICATION', 'PENDING_APPROVAL');
CREATE INDEX ix_signup_requests_organization_id_status ON data2flow_core.signup_requests (organization_id, status, created_at);
COMMENT ON TABLE data2flow_core.signup_requests IS '관리자 승인형 가입 신청(IAM-01.08, ADR-032, BR-IAM-28·29)';

-- ---------------------------------------------------------------------
-- 3. 세션(Refresh 계보)·장기 토큰
-- ---------------------------------------------------------------------
CREATE TABLE data2flow_core.refresh_tokens (
    jti                 uuid         NOT NULL,
    session_id          uuid         NOT NULL,
    organization_id     bigint       NOT NULL,
    user_id             bigint       NOT NULL,
    token_hash          char(64)     NOT NULL,
    issued_at           timestamptz  NOT NULL DEFAULT now(),
    last_used_at        timestamptz  NOT NULL DEFAULT now(),
    expires_at          timestamptz  NOT NULL,
    absolute_expires_at timestamptz  NOT NULL,
    rotated_at          timestamptz,
    revoked_at          timestamptz,
    revoke_reason       varchar(30),
    ip                  inet,
    user_agent          varchar(300),
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (jti),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES data2flow_core.app_users (id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_tokens_revoke_reason CHECK (revoke_reason IS NULL OR revoke_reason IN
        ('LOGOUT', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'USER_DISABLED', 'FORCED', 'ROLE_CHANGED', 'EXPIRED'))
);
CREATE INDEX ix_refresh_tokens_session_id ON data2flow_core.refresh_tokens (session_id);
CREATE INDEX ix_refresh_tokens_user_id_active ON data2flow_core.refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_expires_at ON data2flow_core.refresh_tokens (expires_at);
COMMENT ON TABLE data2flow_core.refresh_tokens IS 'Refresh 계보(crowfoot refresh_tokens 준용). Access 6시간 슬라이딩·절대 12시간(IAM-03.01, IAM-07.03)';

CREATE TABLE data2flow_core.service_accounts (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    name            varchar(50)  NOT NULL,
    description     varchar(200),
    status          varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    version         integer      NOT NULL DEFAULT 0,
    created_by      bigint       NOT NULL,
    updated_by      bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_service_accounts PRIMARY KEY (id),
    CONSTRAINT uq_service_accounts_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_service_accounts_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
COMMENT ON TABLE data2flow_core.service_accounts IS 'MCP 장기 토큰 소유 계정. 로그인 불가(IAM-05.01, BR-IAM-35)';

CREATE TABLE data2flow_core.api_tokens (
    id                  bigint GENERATED ALWAYS AS IDENTITY,
    organization_id     bigint        NOT NULL,
    owner_type          varchar(20)   NOT NULL,
    owner_id            bigint        NOT NULL,
    kind                varchar(20)   NOT NULL,
    name                varchar(50)   NOT NULL,
    token_prefix        char(12)      NOT NULL,
    token_hash          char(64)      NOT NULL,
    scopes              varchar(40)[] NOT NULL,
    space_scope         bigint[]      NOT NULL DEFAULT '{}',
    expires_at          timestamptz   NOT NULL,
    rate_limit_per_min  integer       NOT NULL DEFAULT 600,
    status              varchar(20)   NOT NULL DEFAULT 'ACTIVE',
    last_used_at        timestamptz,
    last_used_ip        inet,
    rotated_from_id     bigint,
    grace_until         timestamptz,
    approved_by         bigint,
    created_by          bigint        NOT NULL,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_api_tokens PRIMARY KEY (id),
    CONSTRAINT uq_api_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT uq_api_tokens_owner_type_owner_id_name UNIQUE (owner_type, owner_id, name),
    CONSTRAINT fk_api_tokens_rotated_from_id FOREIGN KEY (rotated_from_id) REFERENCES data2flow_core.api_tokens (id) ON DELETE SET NULL,
    CONSTRAINT ck_api_tokens_owner_type CHECK (owner_type IN ('USER', 'SERVICE_ACCOUNT')),
    CONSTRAINT ck_api_tokens_kind CHECK (kind IN ('API_KEY', 'MCP')),
    CONSTRAINT ck_api_tokens_status CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'ROTATING', 'REVOKED', 'EXPIRED', 'REJECTED')),
    CONSTRAINT ck_api_tokens_rate_limit CHECK (rate_limit_per_min BETWEEN 1 AND 6000),
    CONSTRAINT ck_api_tokens_expires_at CHECK (expires_at <= created_at + interval '1 year')
);
CREATE INDEX ix_api_tokens_organization_id_owner ON data2flow_core.api_tokens (organization_id, owner_type, owner_id);
COMMENT ON TABLE data2flow_core.api_tokens IS '장기 토큰(MCP). 원문 저장 금지, SHA-256만(IAM-04.07, IAM-05, BR-IAM-18·19·34)';

-- ---------------------------------------------------------------------
-- 4. 감사 로그 (INSERT 전용, 월 파티션)
-- ---------------------------------------------------------------------
CREATE TABLE data2flow_core.audit_logs (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    occurred_at     timestamptz  NOT NULL DEFAULT now(),
    actor_type      varchar(20)  NOT NULL,
    actor_id        varchar(64),
    actor_name      varchar(100),
    action          varchar(60)  NOT NULL,
    target_type     varchar(40),
    target_id       varchar(64),
    result          varchar(10)  NOT NULL,
    detail          jsonb,
    cause           jsonb,
    ip              inet,
    user_agent      varchar(300),
    request_id      varchar(64),
    CONSTRAINT pk_audit_logs PRIMARY KEY (id, occurred_at),
    CONSTRAINT ck_audit_logs_actor_type CHECK (actor_type IN ('USER', 'SERVICE_ACCOUNT', 'FLOW', 'SERVICE', 'SYSTEM')),
    CONSTRAINT ck_audit_logs_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED'))
) PARTITION BY RANGE (occurred_at);
CREATE INDEX ix_audit_logs_organization_id_occurred_at ON data2flow_core.audit_logs (organization_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_organization_id_target ON data2flow_core.audit_logs (organization_id, target_type, target_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_organization_id_actor ON data2flow_core.audit_logs (organization_id, actor_type, actor_id, occurred_at DESC);
CREATE TABLE data2flow_core.audit_logs_default PARTITION OF data2flow_core.audit_logs DEFAULT;
-- 예시: 첫 달 파티션. 이후 파티션은 core-api 스케줄 작업이 3개월 앞까지 만든다(README §10).
CREATE TABLE data2flow_core.audit_logs_y2026m10 PARTITION OF data2flow_core.audit_logs
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
COMMENT ON TABLE data2flow_core.audit_logs IS '감사 로그. INSERT 전용, 앱 계정에 UPDATE·DELETE 권한 없음(IAM-06.02, BR-IAM-21)';
COMMENT ON COLUMN data2flow_core.audit_logs.cause IS '자동 제어 원인: flowId, flowVersion, nodeId, triggerMessageId(IAM-06.04)';

CREATE TABLE data2flow_core.audit_forwarders (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint       NOT NULL,
    type              varchar(20)  NOT NULL,
    endpoint          varchar(300) NOT NULL,
    credential_enc    bytea,
    filter            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    last_forwarded_id bigint       NOT NULL DEFAULT 0,
    status            varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    last_error        varchar(500),
    last_success_at   timestamptz,
    version           integer      NOT NULL DEFAULT 0,
    created_by        bigint       NOT NULL,
    updated_by        bigint,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_forwarders PRIMARY KEY (id),
    CONSTRAINT ck_audit_forwarders_type CHECK (type IN ('SYSLOG_TLS', 'HTTPS_JSON', 'S3_PARQUET')),
    CONSTRAINT ck_audit_forwarders_status CHECK (status IN ('ACTIVE', 'FAILING', 'DISABLED'))
);
CREATE INDEX ix_audit_forwarders_organization_id ON data2flow_core.audit_forwarders (organization_id);
COMMENT ON TABLE data2flow_core.audit_forwarders IS '감사 로그 외부 전달, 최소 1회(IAM-06.05, BR-IAM-33)';

-- ---------------------------------------------------------------------
-- 5. 운영 설정
-- ---------------------------------------------------------------------
CREATE TABLE data2flow_core.org_settings (
    organization_id bigint       NOT NULL,
    display_name    varchar(100) NOT NULL,
    logo_object_key varchar(300),
    timezone        varchar(40)  NOT NULL DEFAULT 'Asia/Seoul',
    locale          varchar(10)  NOT NULL DEFAULT 'ko',
    unit_system     varchar(10)  NOT NULL DEFAULT 'METRIC',
    date_format     varchar(20)  NOT NULL DEFAULT 'YYYY-MM-DD HH:mm',
    version         integer      NOT NULL DEFAULT 0,
    updated_by      bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_org_settings PRIMARY KEY (organization_id),
    CONSTRAINT fk_org_settings_organization_id FOREIGN KEY (organization_id)
        REFERENCES data2flow_core.organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_org_settings_locale CHECK (locale IN ('ko', 'en', 'ja', 'zh')),
    CONSTRAINT ck_org_settings_unit_system CHECK (unit_system IN ('METRIC', 'IMPERIAL'))
);
COMMENT ON TABLE data2flow_core.org_settings IS '조직 기본 설정(1:1, OPS-07.01)';

CREATE TABLE data2flow_core.external_service_config (
    id               bigint GENERATED ALWAYS AS IDENTITY,
    organization_id  bigint      NOT NULL,
    kind             varchar(20) NOT NULL,
    provider         varchar(40) NOT NULL,
    settings         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    secret_enc       bytea,
    enabled          boolean     NOT NULL DEFAULT true,
    last_test_at     timestamptz,
    last_test_result varchar(500),
    version          integer     NOT NULL DEFAULT 0,
    created_by       bigint      NOT NULL,
    updated_by       bigint,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_external_service_config PRIMARY KEY (id),
    CONSTRAINT uq_external_service_config_organization_id_kind UNIQUE (organization_id, kind),
    CONSTRAINT ck_external_service_config_kind CHECK (kind IN ('LLM', 'MAIL', 'WEATHER', 'MAP', 'AIRQUALITY'))
);
COMMENT ON TABLE data2flow_core.external_service_config IS '외부 서비스 연결(OPS-07.02). 비밀값은 secret_enc(AES-256-GCM, 키 ID 포함), 응답은 *** (BR-OPS-05)';

CREATE TABLE data2flow_core.feature_flags (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    key             varchar(80)  NOT NULL,
    description     varchar(300),
    enabled_default boolean      NOT NULL DEFAULT false,
    overrides       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    version         integer      NOT NULL DEFAULT 0,
    updated_by      bigint,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_feature_flags PRIMARY KEY (id),
    CONSTRAINT uq_feature_flags_organization_id_key UNIQUE (organization_id, key)
);
COMMENT ON TABLE data2flow_core.feature_flags IS '기능 플래그(OPS-11.03). overrides = {users:[id], roles:[...]}';

CREATE TABLE data2flow_core.notification_channels (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint      NOT NULL,
    name              varchar(50) NOT NULL,
    type              varchar(20) NOT NULL,
    config            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    secret_enc        bytea,
    rate_limit_per_min integer    NOT NULL DEFAULT 20,
    digest_window_sec integer     NOT NULL DEFAULT 60,
    enabled           boolean     NOT NULL DEFAULT true,
    status            varchar(20) NOT NULL DEFAULT 'OK',
    version           integer     NOT NULL DEFAULT 0,
    created_by        bigint      NOT NULL,
    updated_by        bigint,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_notification_channels PRIMARY KEY (id),
    CONSTRAINT uq_notification_channels_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_notification_channels_type CHECK (type ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    CONSTRAINT ck_notification_channels_digest_window CHECK (digest_window_sec BETWEEN 0 AND 900),
    CONSTRAINT ck_notification_channels_status CHECK (status IN ('OK', 'DEGRADED', 'FAILING'))
);
COMMENT ON TABLE data2flow_core.notification_channels IS '알림 채널(OPS-06). type은 등록된 SPI 키 — 현재 TELEGRAM만(ADR-033). 값 검증은 애플리케이션이 등록 목록으로(OPS-06.06, BR-OPS-32)';
COMMENT ON COLUMN data2flow_core.notification_channels.config IS 'SPI 설정 스키마(JSON Schema)로 검증. TELEGRAM: chat_id 목록, 형식';

CREATE TABLE data2flow_core.outgoing_webhooks (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint        NOT NULL,
    name              varchar(50)   NOT NULL,
    url               varchar(500)  NOT NULL,
    event_types       varchar(60)[] NOT NULL,
    secret_enc        bytea         NOT NULL,
    active_secret_ids varchar(40)[] NOT NULL DEFAULT '{}',
    enabled           boolean       NOT NULL DEFAULT true,
    timeout_ms        integer       NOT NULL DEFAULT 5000,
    disabled_reason   varchar(200),
    version           integer       NOT NULL DEFAULT 0,
    created_by        bigint        NOT NULL,
    updated_by        bigint,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_outgoing_webhooks PRIMARY KEY (id),
    CONSTRAINT uq_outgoing_webhooks_organization_id_name UNIQUE (organization_id, name),
    CONSTRAINT ck_outgoing_webhooks_url CHECK (url LIKE 'https://%'),
    CONSTRAINT ck_outgoing_webhooks_event_types CHECK (cardinality(event_types) >= 1),
    CONSTRAINT ck_outgoing_webhooks_timeout CHECK (timeout_ms BETWEEN 1000 AND 10000)
);
COMMENT ON TABLE data2flow_core.outgoing_webhooks IS '보내는 Webhook 정의(OPS-09.03, BR-OPS-17·18). 발송 기록은 data2flow_action.webhook_deliveries';

CREATE TABLE data2flow_core.maintenance_windows (
    id                     bigint GENERATED ALWAYS AS IDENTITY,
    organization_id        bigint       NOT NULL,
    target_type            varchar(10)  NOT NULL,
    target_id              bigint       NOT NULL,
    starts_at              timestamptz  NOT NULL,
    ends_at                timestamptz,
    pause_automation       boolean      NOT NULL DEFAULT true,
    exclude_from_analytics boolean      NOT NULL DEFAULT true,
    reason                 varchar(200) NOT NULL,
    status                 varchar(20)  NOT NULL DEFAULT 'SCHEDULED',
    version                integer      NOT NULL DEFAULT 0,
    created_by             bigint       NOT NULL,
    ended_by               bigint,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_maintenance_windows PRIMARY KEY (id),
    CONSTRAINT ck_maintenance_windows_target_type CHECK (target_type IN ('SPACE', 'DEVICE')),
    CONSTRAINT ck_maintenance_windows_status CHECK (status IN ('SCHEDULED', 'ACTIVE', 'ENDED', 'CANCELED')),
    CONSTRAINT ck_maintenance_windows_range CHECK (ends_at IS NULL OR (ends_at > starts_at AND ends_at <= starts_at + interval '30 days'))
);
CREATE INDEX ix_maintenance_windows_organization_id_target ON data2flow_core.maintenance_windows (organization_id, target_type, target_id, starts_at);
CREATE INDEX ix_maintenance_windows_status_open ON data2flow_core.maintenance_windows (organization_id, starts_at) WHERE status IN ('SCHEDULED', 'ACTIVE');
COMMENT ON TABLE data2flow_core.maintenance_windows IS '유지보수 구간(OPS-05, BR-OPS-10·11). target_id는 spaces/devices.id(다형 참조, FK 없음). 같은 대상 겹침은 애플리케이션이 막음(MAINTENANCE_OVERLAP)';

-- ---------------------------------------------------------------------
-- 6. 백업·설정 이동
-- ---------------------------------------------------------------------
CREATE TABLE data2flow_core.backup_records (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint       NOT NULL,
    kind              varchar(15)  NOT NULL,
    started_at        timestamptz  NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    status            varchar(20)  NOT NULL DEFAULT 'RUNNING',
    contents          jsonb        NOT NULL DEFAULT '{}'::jsonb,
    size_bytes        bigint,
    location          varchar(300),
    encryption_key_id varchar(40)  NOT NULL,
    checksum_sha256   char(64),
    error             varchar(500),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_backup_records PRIMARY KEY (id),
    CONSTRAINT ck_backup_records_kind CHECK (kind IN ('FULL', 'PRE_UPGRADE', 'MANUAL')),
    CONSTRAINT ck_backup_records_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED'))
);
CREATE INDEX ix_backup_records_organization_id_started_at ON data2flow_core.backup_records (organization_id, started_at DESC);
COMMENT ON TABLE data2flow_core.backup_records IS '백업 이력(OPS-03, BR-OPS-12·13)';

CREATE TABLE data2flow_core.restore_drills (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    organization_id    bigint       NOT NULL,
    backup_id          bigint       NOT NULL,
    target_environment varchar(60)  NOT NULL,
    started_at         timestamptz  NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    rto_minutes        integer,
    checks             jsonb        NOT NULL DEFAULT '{}'::jsonb,
    result             varchar(10),
    performed_by       bigint       NOT NULL,
    memo               varchar(500),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_restore_drills PRIMARY KEY (id),
    CONSTRAINT fk_restore_drills_backup_id FOREIGN KEY (backup_id) REFERENCES data2flow_core.backup_records (id) ON DELETE RESTRICT,
    CONSTRAINT ck_restore_drills_result CHECK (result IS NULL OR result IN ('PASSED', 'FAILED')),
    CONSTRAINT ck_restore_drills_target_environment CHECK (target_environment <> 'data2flow')
);
COMMENT ON TABLE data2flow_core.restore_drills IS '복구 훈련, 운영 네임스페이스 금지(OPS-03.03, BR-OPS-14)';

CREATE TABLE data2flow_core.config_transfer_jobs (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    organization_id   bigint       NOT NULL,
    kind              varchar(10)  NOT NULL,
    scope             jsonb        NOT NULL,
    conflict_mode     varchar(10),
    target_organization_id bigint,
    bundle_object_key varchar(300),
    format_version    integer,
    preview           jsonb,
    status            varchar(20)  NOT NULL DEFAULT 'PENDING',
    error             jsonb,
    requested_by      bigint       NOT NULL,
    approved_by       bigint,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_config_transfer_jobs PRIMARY KEY (id),
    CONSTRAINT ck_config_transfer_jobs_kind CHECK (kind IN ('EXPORT', 'IMPORT', 'PROMOTE')),
    CONSTRAINT ck_config_transfer_jobs_conflict_mode CHECK (conflict_mode IS NULL OR conflict_mode IN ('SKIP', 'OVERWRITE')),
    CONSTRAINT ck_config_transfer_jobs_status CHECK (status IN ('PENDING', 'PREVIEWED', 'APPLYING', 'APPLIED', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT ck_config_transfer_jobs_promote CHECK (kind <> 'PROMOTE' OR target_organization_id IS NOT NULL)
);
CREATE INDEX ix_config_transfer_jobs_organization_id_created_at ON data2flow_core.config_transfer_jobs (organization_id, created_at DESC);
COMMENT ON TABLE data2flow_core.config_transfer_jobs IS '설정 내보내기·가져오기·승격(OPS-07.03, OPS-11.02, ADR-030 staging 조직→운영 조직, BR-OPS-15·16)';

-- ---------------------------------------------------------------------
-- 7. 라이선스·사용량
-- ---------------------------------------------------------------------
CREATE TABLE data2flow_core.licenses (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    organization_id       bigint       NOT NULL,
    license_key_hash      char(64)     NOT NULL,
    edition               varchar(10)  NOT NULL,
    device_limit          integer      NOT NULL,
    feature_bundle        varchar(40)[] NOT NULL DEFAULT '{}',
    valid_from            date         NOT NULL,
    valid_until           date         NOT NULL,
    signature_verified_at timestamptz,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_licenses PRIMARY KEY (id),
    CONSTRAINT uq_licenses_organization_id UNIQUE (organization_id),
    CONSTRAINT ck_licenses_edition CHECK (edition IN ('STANDARD', 'PRO')),
    CONSTRAINT ck_licenses_valid CHECK (valid_until >= valid_from)
);
COMMENT ON TABLE data2flow_core.licenses IS '라이선스(조직당 1, OPS-10)';

CREATE TABLE data2flow_core.usage_snapshots (
    id                    bigint GENERATED ALWAYS AS IDENTITY,
    organization_id       bigint        NOT NULL,
    taken_on              date          NOT NULL,
    taken_at              timestamptz   NOT NULL DEFAULT now(),
    devices_active        integer       NOT NULL,
    devices_virtual       integer       NOT NULL,
    messages_month        bigint        NOT NULL,
    storage_bytes         bigint        NOT NULL,
    llm_calls_month       integer       NOT NULL DEFAULT 0,
    llm_tokens_month      bigint        NOT NULL DEFAULT 0,
    llm_cost_estimate_krw numeric(14,2) NOT NULL DEFAULT 0,
    limits                jsonb         NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_usage_snapshots PRIMARY KEY (id),
    CONSTRAINT uq_usage_snapshots_organization_id_taken_on UNIQUE (organization_id, taken_on)
);
COMMENT ON TABLE data2flow_core.usage_snapshots IS '사용량 일 1회 스냅샷(OPS-10, NFR-11.01)';

-- ---------------------------------------------------------------------
-- 8. 운영 지원
-- ---------------------------------------------------------------------
CREATE TABLE data2flow_core.system_notices (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    severity        varchar(10)   NOT NULL,
    title           varchar(100)  NOT NULL,
    body            varchar(2000) NOT NULL,
    starts_at       timestamptz   NOT NULL,
    ends_at         timestamptz,
    dismissible     boolean       NOT NULL DEFAULT true,
    version         integer       NOT NULL DEFAULT 0,
    created_by      bigint        NOT NULL,
    updated_by      bigint,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_system_notices PRIMARY KEY (id),
    CONSTRAINT ck_system_notices_severity CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    CONSTRAINT ck_system_notices_range CHECK (ends_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX ix_system_notices_organization_id_starts_at ON data2flow_core.system_notices (organization_id, starts_at);
COMMENT ON TABLE data2flow_core.system_notices IS '시스템 공지(OPS-13.02)';

CREATE TABLE data2flow_core.release_notes (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL DEFAULT 0,
    version         varchar(20)   NOT NULL,
    released_on     date          NOT NULL,
    status          varchar(10)   NOT NULL DEFAULT 'DRAFT',
    title_i18n      jsonb         NOT NULL,
    summary_i18n    jsonb         NOT NULL DEFAULT '{}',
    body_i18n       jsonb         NOT NULL,
    published_at    timestamptz,
    published_by    bigint,
    version_no      integer       NOT NULL DEFAULT 0,
    created_by      bigint        NOT NULL,
    updated_by      bigint,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_release_notes PRIMARY KEY (id),
    CONSTRAINT uq_release_notes_version UNIQUE (version),
    CONSTRAINT ck_release_notes_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_release_notes_version_format CHECK (version ~ '^v[0-9]+\.[0-9]+(\.[0-9]+)?$'),
    CONSTRAINT ck_release_notes_ko_required CHECK (title_i18n ? 'ko' AND body_i18n ? 'ko')
);
CREATE INDEX ix_release_notes_status_released_on ON data2flow_core.release_notes (status, released_on DESC);
COMMENT ON TABLE data2flow_core.release_notes IS '릴리스 노트(OPS-14, ADR-038). 플랫폼 공통(organization_id=0)';

CREATE TABLE data2flow_core.release_note_assets (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL DEFAULT 0,
    release_note_id bigint        NOT NULL,
    object_key      varchar(300)  NOT NULL,
    content_type    varchar(40)   NOT NULL,
    size_bytes      integer       NOT NULL,
    created_by      bigint        NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_release_note_assets PRIMARY KEY (id),
    CONSTRAINT fk_release_note_assets_release_note_id FOREIGN KEY (release_note_id) REFERENCES data2flow_core.release_notes (id) ON DELETE CASCADE,
    CONSTRAINT ck_release_note_assets_size CHECK (size_bytes BETWEEN 1 AND 2097152)
);
CREATE INDEX ix_release_note_assets_release_note_id ON data2flow_core.release_note_assets (release_note_id);
COMMENT ON TABLE data2flow_core.release_note_assets IS '릴리스 노트 이미지(OPS-14.01)';

CREATE TABLE data2flow_core.diagnostic_bundles (
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    organization_id    bigint       NOT NULL,
    requested_by       bigint       NOT NULL,
    status             varchar(20)  NOT NULL DEFAULT 'PENDING',
    object_key         varchar(300),
    size_bytes         bigint,
    contents_manifest  jsonb,
    secret_scan_result varchar(10),
    expires_at         timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_diagnostic_bundles PRIMARY KEY (id),
    CONSTRAINT ck_diagnostic_bundles_status CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_diagnostic_bundles_secret_scan CHECK (secret_scan_result IS NULL OR secret_scan_result IN ('PASSED', 'FAILED'))
);
COMMENT ON TABLE data2flow_core.diagnostic_bundles IS '진단 묶음, 7일 보관(OPS-13.01). status: PENDING → RUNNING → READY | FAILED, READY는 7일 뒤 EXPIRED(2026-10-03 제안값)';

CREATE TABLE data2flow_core.log_level_overrides (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    service     varchar(40)  NOT NULL,
    logger      varchar(200) NOT NULL,
    level       varchar(5)   NOT NULL,
    expires_at  timestamptz  NOT NULL,
    set_by      bigint       NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_log_level_overrides PRIMARY KEY (id),
    CONSTRAINT uq_log_level_overrides_service_logger UNIQUE (service, logger),
    CONSTRAINT ck_log_level_overrides_level CHECK (level IN ('TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR')),
    CONSTRAINT ck_log_level_overrides_expires CHECK (expires_at <= created_at + interval '24 hours')
);
COMMENT ON TABLE data2flow_core.log_level_overrides IS '서비스별 로그 레벨 임시 변경, 조직 무관(OPS-02.04, BR-OPS-23)';

CREATE TABLE data2flow_core.ops_thresholds (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint           NOT NULL,
    key             varchar(40)      NOT NULL,
    value           double precision NOT NULL,
    enabled         boolean          NOT NULL DEFAULT true,
    version         integer          NOT NULL DEFAULT 0,
    updated_by      bigint,
    created_at      timestamptz      NOT NULL DEFAULT now(),
    updated_at      timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT pk_ops_thresholds PRIMARY KEY (id),
    CONSTRAINT uq_ops_thresholds_organization_id_key UNIQUE (organization_id, key),
    CONSTRAINT ck_ops_thresholds_key CHECK (key IN ('INGEST_ZERO_MINUTES', 'DISK_FREE_PERCENT', 'HEARTBEAT_DELAY_SEC',
        'STREAM_LAG_MESSAGES', 'DLQ_GROWTH_PER_10MIN', 'BACKUP_FAILED'))
);
COMMENT ON TABLE data2flow_core.ops_thresholds IS '운영 알람 기준(OPS-01.05, NFR-02.10, BR-OPS-02·03)';

-- 표준 테이블: API 멱등 키 (README §11.2, BR-OPS-20). domain-map 소유 목록에 없어 새로 추가함.
CREATE TABLE data2flow_core.idempotency_keys (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint       NOT NULL,
    user_id         bigint       NOT NULL,
    route           varchar(200) NOT NULL,
    idempotency_key varchar(64)  NOT NULL,
    request_hash    char(64)     NOT NULL,
    response_status integer,
    response_body   jsonb,
    expires_at      timestamptz  NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_keys_scope UNIQUE (organization_id, user_id, route, idempotency_key)
);
CREATE INDEX ix_idempotency_keys_expires_at ON data2flow_core.idempotency_keys (expires_at);
COMMENT ON TABLE data2flow_core.idempotency_keys IS 'Idempotency-Key 24시간 보관. 같은 키·다른 본문이면 409 IDEMPOTENCY_KEY_REUSED(OPS-12.03, BR-OPS-20)';

-- 표준 테이블: 아웃박스 (README §11.1, ADR-020·reliability-and-ha.md — 이벤트 무손실).
-- core-api가 업무 트랜잭션과 함께 이벤트(data2flow.events), 설정 변경(data2flow.config),
-- 화면·예약 제어 명령(data2flow.actions)을 기록하고, 릴레이가 publisher confirm 뒤 sent_at을 채운다.
CREATE TABLE data2flow_core.outboxes (
    id              bigint GENERATED ALWAYS AS IDENTITY,
    organization_id bigint        NOT NULL,
    idempotency_key char(64)      NOT NULL,
    kind            varchar(12)   NOT NULL,
    exchange        varchar(64)   NOT NULL,
    routing_key     varchar(128)  NOT NULL,
    payload         jsonb         NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    attempts        smallint      NOT NULL DEFAULT 0,
    last_error      varchar(500),
    CONSTRAINT pk_outboxes PRIMARY KEY (id),
    CONSTRAINT uq_outboxes_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_outboxes_kind CHECK (kind IN ('EVENT', 'CONFIG', 'COMMAND', 'NOTIFY'))
);
CREATE INDEX ix_outboxes_unsent ON data2flow_core.outboxes (created_at) WHERE sent_at IS NULL;
CREATE INDEX ix_outboxes_sent_at ON data2flow_core.outboxes (sent_at) WHERE sent_at IS NOT NULL;
COMMENT ON TABLE data2flow_core.outboxes IS 'core-api 아웃박스. 릴레이는 FOR UPDATE SKIP LOCKED로 가져가고, 보낸 행은 7일 뒤 삭제(2026-10-03 기본값)';

-- 표준 테이블: 소비한 메시지 기록 (README §11.2 마지막 문단). 자연 키로 멱등 저장할 수 없는 소비자가 쓴다.
CREATE TABLE data2flow_core.processed_messages (
    consumer     varchar(64)  NOT NULL,
    message_id   varchar(64)  NOT NULL,
    processed_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_messages PRIMARY KEY (consumer, message_id)
);
CREATE INDEX ix_processed_messages_processed_at ON data2flow_core.processed_messages (processed_at);
COMMENT ON TABLE data2flow_core.processed_messages IS '소비자별 처리한 messageId. INSERT … ON CONFLICT DO NOTHING으로 중복 판정, 7일 보관(2026-10-03 기본값)';
