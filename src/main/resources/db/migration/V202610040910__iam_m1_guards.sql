-- IAM M1 보강(expand 전용, ADR-030).
-- 1) 감사 로그는 INSERT 전용이다(IAM-06.02, BR-IAM-21, NFR-12.02). DB 계정이 서비스 공용 하나라 권한(REVOKE)으로는 막을 수 없으므로
--    트리거로 UPDATE·DELETE·TRUNCATE를 거부한다. 보관 기간 정리는 파티션 DETACH·DROP(DDL)으로만 한다(ERD README §10).
CREATE FUNCTION data2flow_core.reject_audit_log_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'data2flow_core.audit_logs is append-only (%)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
COMMENT ON FUNCTION data2flow_core.reject_audit_log_change() IS '감사 로그 수정·삭제 거부(IAM-06.02, AT-IAM-13.4)';

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON data2flow_core.audit_logs
    FOR EACH ROW EXECUTE FUNCTION data2flow_core.reject_audit_log_change();

CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON data2flow_core.audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION data2flow_core.reject_audit_log_change();

-- 2) 로그인 아이디 조회(API-IAM-30). 로그인 시점에는 조직을 모르므로 lower(login_id) 단독 인덱스를 둔다(v1 단일 조직)
CREATE INDEX ix_app_users_lower_login_id ON data2flow_core.app_users (lower(login_id)) WHERE login_id IS NOT NULL;

-- 3) 폐기 목록 재적재(API-IAM-39a): 최근 폐기된 sid·jti를 시각으로 찾는다
CREATE INDEX ix_refresh_tokens_revoked_at ON data2flow_core.refresh_tokens (revoked_at) WHERE revoked_at IS NOT NULL;

-- 4) 가입 신청 IP 한도(BR-IAM-28)와 재설정 메일 한도(BR-IAM-11)를 DB에서 센다
CREATE INDEX ix_signup_requests_request_ip_created_at ON data2flow_core.signup_requests (request_ip, created_at);
CREATE INDEX ix_password_reset_tokens_user_id_created_at ON data2flow_core.password_reset_tokens (user_id, created_at);
