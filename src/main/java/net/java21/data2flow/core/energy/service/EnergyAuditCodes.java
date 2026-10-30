package net.java21.data2flow.core.energy.service;

/**
 * 에너지 설정 감사 action 코드(ENE-01). 공통 AuditCodes와 같은 UPPER_SNAKE 형식. {@code ENE_TARIFF_CHANGED}는 UC-ENE-01 사후 조건이고
 * 나머지는 같은 모양으로 더했다. 바뀐 내용은 detail {@code change}(CREATED·UPDATED·DELETED)로 남긴다
 */
public final class EnergyAuditCodes {

    public static final String ENE_TARIFF_CHANGED = "ENE_TARIFF_CHANGED";
    public static final String ENE_EMISSION_FACTOR_CHANGED = "ENE_EMISSION_FACTOR_CHANGED";
    public static final String ENE_SOURCE_CHANGED = "ENE_SOURCE_CHANGED";
    public static final String ENE_BILL_CHANGED = "ENE_BILL_CHANGED";
    /** 기준선 버전 적용(UC-ENE-04) */
    public static final String ENE_BASELINE_ACTIVATED = "ENE_BASELINE_ACTIVATED";

    /** 가상 센서 켜기·끄기·설정(UC-ENE-06~08) */
    public static final String ENE_VIRTUAL_SENSOR_CHANGED = "ENE_VIRTUAL_SENSOR_CHANGED";

    private EnergyAuditCodes() {
    }
}
