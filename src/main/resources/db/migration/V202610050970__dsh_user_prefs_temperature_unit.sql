-- DSH-07.04·07.05 내 화면 설정(API-DSH-12): 사용자별 온도 단위(API-DEV-57 "사용자별 단위는 API-DSH-12의 temperatureUnit").
-- expand 전용(ADR-030): NULL 허용 열 추가와 새 열의 CHECK만. NULL이면 조직 단위(org_settings.unit_system)를 따른다.
ALTER TABLE data2flow_core.user_dashboard_prefs
    ADD COLUMN temperature_unit varchar(1),
    ADD CONSTRAINT ck_user_dashboard_prefs_temperature_unit CHECK (temperature_unit IS NULL OR temperature_unit IN ('C', 'F'));

COMMENT ON COLUMN data2flow_core.user_dashboard_prefs.temperature_unit IS '사용자 온도 단위 C/F. NULL이면 조직 단위(DSH-07.04, API-DSH-12)';
