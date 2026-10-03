-- ADR-042: 플랫폼 브로커 기기 서명 키를 해시만이 아니라 SecretCipher 암호문으로도 저장한다(HMAC 검증에 원문 키가 필요).
-- 추가만(expand, ADR-030). 기존 행은 NULL이고 서명 검증 대상이 아니다(키를 다시 발급해야 한다).
ALTER TABLE data2flow_core.device_credentials ADD COLUMN IF NOT EXISTS signing_key_enc bytea;

COMMENT ON COLUMN data2flow_core.device_credentials.signing_key_enc IS 'payload HMAC 서명 키 암호문(SecretCipher AES-256-GCM, kid 포함, context data2flow_core.device_credentials.signing_key:{조직}:{기기}). 외부 API로 다시 보여 주지 않고 ingress 내부 API(API-DSC-72)로만 복호화해 준다(ADR-042)';
