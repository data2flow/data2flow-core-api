-- ENE-02.02 재실 추정 가상 센서
--   * 가상 센서 계산값(occupancy_est 등)은 공간마다 하나인 "공간 가상 기기"의 telemetry로 저장한다(pipeline API-ING-25).
--     그 기기는 조직마다 하나인 시스템 소스(type DERIVED, code virtual-sensors)에 붙는다. ingress는 DERIVED를 구독하지 않고,
--     소스 무수신 점검(DSC)에서도 뺀다.
ALTER TABLE data2flow_core.data_sources DROP CONSTRAINT ck_data_sources_type;
ALTER TABLE data2flow_core.data_sources ADD CONSTRAINT ck_data_sources_type CHECK (type IN ('MQTT_SUBSCRIBE','PLATFORM_BROKER','SIMULATION','WEBHOOK',
    'CONNECTOR','EDGE','KMA_WEATHER','AIRKOREA','HOLIDAY','ICAL','ONEM2M','OPCUA','MODBUS_TCP','DERIVED'));
COMMENT ON COLUMN data2flow_core.data_sources.type IS 'DERIVED: 가상 센서 계산값 시스템 소스(조직마다 하나, ENE-02.02). 사용자가 만들 수 없다';
