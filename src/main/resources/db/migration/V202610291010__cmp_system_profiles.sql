-- CMP-01.02: 기본 제공(시스템) 프로필 5종 v1 시드. organization_id = 0, 읽기 전용(BR-CMP-01), 적용하면 그 버전이 잠긴다.
-- 법령·인증 기준이 바뀌면 이 파일을 고치지 않고 새 마이그레이션으로 v2를 더한다(BR-CMP-03).
-- 국내법 시설 유형(facility_type)은 별표 2의 시설 묶음이다: GENERAL(학원·도서관·지하역사 등), SENSITIVE(의료기관·산후조리원·
-- 노인요양시설·어린이집·실내 어린이놀이시설), INDOOR_PARKING(실내주차장).

DO $$
DECLARE
    p bigint;
    v bigint;
    r bigint;
BEGIN
    -- ① 실내공기질관리법 다중이용시설(유지기준 LIMIT·권고기준 RECOMMENDED, "이하" = 경계 포함)
    INSERT INTO data2flow_core.compliance_profiles (organization_id, kind, code, name, current_version, created_by, updated_by)
    VALUES (0, 'SYSTEM', 'KR_IAQ_PUBLIC_FACILITY', '실내공기질관리법 다중이용시설', 1, 0, 0) RETURNING id INTO p;
    INSERT INTO data2flow_core.compliance_profile_versions (organization_id, profile_id, version_no, source_title, source_url, effective_date,
                                                            description, facility_type_required, created_by)
    VALUES (0, p, 1, '실내공기질 관리법 시행규칙 별표 2(실내공기질 유지기준)·별표 3(권고기준)', 'https://www.law.go.kr/법령/실내공기질관리법시행규칙',
            DATE '2024-12-23', '다중이용시설 유지기준(위반 시 개선명령)과 권고기준. 시설 유형을 골라 적용한다. 자가 점검용이며 법정 측정을 대체하지 않는다',
            true, 0) RETURNING id INTO v;
    INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, facility_type, eval_window, lower_limit,
                                                     upper_limit, limit_inclusive, unit, requirement, note, sort_order)
    VALUES
        (0, v, 'pm10',              'GENERAL',        'DAY_1',  NULL, 100,  true, 'μg/m³',  'LIMIT',       '24시간 평균', 1),
        (0, v, 'pm2_5',             'GENERAL',        'DAY_1',  NULL, 50,   true, 'μg/m³',  'LIMIT',       '24시간 평균', 2),
        (0, v, 'co2',               'GENERAL',        'HOUR_1', NULL, 1000, true, 'ppm',    'LIMIT',       NULL, 3),
        (0, v, 'hcho',              'GENERAL',        'HOUR_1', NULL, 100,  true, 'μg/m³',  'LIMIT',       NULL, 4),
        (0, v, 'co',                'GENERAL',        'HOUR_1', NULL, 10,   true, 'ppm',    'LIMIT',       NULL, 5),
        (0, v, 'no2',               'GENERAL',        'HOUR_1', NULL, 0.1,  true, 'ppm',    'RECOMMENDED', NULL, 6),
        (0, v, 'radon',             'GENERAL',        'DAY_1',  NULL, 148,  true, 'Bq/m³',  'RECOMMENDED', NULL, 7),
        (0, v, 'tvoc',              'GENERAL',        'HOUR_1', NULL, 500,  true, 'μg/m³',  'RECOMMENDED', NULL, 8),
        (0, v, 'pm10',              'SENSITIVE',      'DAY_1',  NULL, 75,   true, 'μg/m³',  'LIMIT',       '24시간 평균', 1),
        (0, v, 'pm2_5',             'SENSITIVE',      'DAY_1',  NULL, 35,   true, 'μg/m³',  'LIMIT',       '24시간 평균', 2),
        (0, v, 'co2',               'SENSITIVE',      'HOUR_1', NULL, 1000, true, 'ppm',    'LIMIT',       NULL, 3),
        (0, v, 'hcho',              'SENSITIVE',      'HOUR_1', NULL, 80,   true, 'μg/m³',  'LIMIT',       NULL, 4),
        (0, v, 'airborne_bacteria', 'SENSITIVE',      'HOUR_1', NULL, 800,  true, 'CFU/m³', 'LIMIT',       '총부유세균', 5),
        (0, v, 'co',                'SENSITIVE',      'HOUR_1', NULL, 10,   true, 'ppm',    'LIMIT',       NULL, 6),
        (0, v, 'no2',               'SENSITIVE',      'HOUR_1', NULL, 0.05, true, 'ppm',    'RECOMMENDED', NULL, 7),
        (0, v, 'radon',             'SENSITIVE',      'DAY_1',  NULL, 148,  true, 'Bq/m³',  'RECOMMENDED', NULL, 8),
        (0, v, 'tvoc',              'SENSITIVE',      'HOUR_1', NULL, 400,  true, 'μg/m³',  'RECOMMENDED', NULL, 9),
        (0, v, 'mold',              'SENSITIVE',      'HOUR_1', NULL, 500,  true, 'CFU/m³', 'RECOMMENDED', '곰팡이', 10),
        (0, v, 'pm10',              'INDOOR_PARKING', 'DAY_1',  NULL, 200,  true, 'μg/m³',  'LIMIT',       '24시간 평균', 1),
        (0, v, 'co2',               'INDOOR_PARKING', 'HOUR_1', NULL, 1000, true, 'ppm',    'LIMIT',       NULL, 2),
        (0, v, 'hcho',              'INDOOR_PARKING', 'HOUR_1', NULL, 100,  true, 'μg/m³',  'LIMIT',       NULL, 3),
        (0, v, 'co',                'INDOOR_PARKING', 'HOUR_1', NULL, 25,   true, 'ppm',    'LIMIT',       NULL, 4),
        (0, v, 'no2',               'INDOOR_PARKING', 'HOUR_1', NULL, 0.30, true, 'ppm',    'RECOMMENDED', NULL, 5),
        (0, v, 'radon',             'INDOOR_PARKING', 'DAY_1',  NULL, 148,  true, 'Bq/m³',  'RECOMMENDED', NULL, 6),
        (0, v, 'tvoc',              'INDOOR_PARKING', 'HOUR_1', NULL, 1000, true, 'μg/m³',  'RECOMMENDED', NULL, 7);
    INSERT INTO data2flow_core.profile_grade_bands (organization_id, metric_rule_id, name, lower_value, upper_value, is_base, sort_order)
    SELECT 0, id, CASE requirement WHEN 'LIMIT' THEN '유지기준' ELSE '권고기준' END, NULL, upper_limit, true, 1
      FROM data2flow_core.profile_metric_rules WHERE profile_version_id = v;
    INSERT INTO data2flow_core.profile_coverage_rules (organization_id, profile_version_id, kind, params)
    VALUES (0, v, 'CALIBRATION_INTERVAL', '{"defaultIntervalDays":365}'),
           (0, v, 'DATA_QUALITY', '{"maxMissingRatio":0.10,"maxReportIntervalSec":900}');

    -- ② RESET Air Commercial Interiors("<" = 경계 미포함). 연속 달성 3개월, 일 충족률 95% 이상
    INSERT INTO data2flow_core.compliance_profiles (organization_id, kind, code, name, current_version, created_by, updated_by)
    VALUES (0, 'SYSTEM', 'RESET_AIR_CI', 'RESET Air(Commercial Interiors)', 1, 0, 0) RETURNING id INTO p;
    INSERT INTO data2flow_core.compliance_profile_versions (organization_id, profile_id, version_no, source_title, source_url, effective_date,
                                                            description, streak_days, streak_daily_ratio, facility_type_required, created_by)
    VALUES (0, p, 1, 'RESET Air Standard for Commercial Interiors v2.1', 'https://www.reset.build/standard/air', DATE '2023-01-01',
            '연속 측정 기반 실내 공기질 인증. 인증 신청 보조 자료이며 인증기관 심사를 대체하지 않는다', 90, 95.00, false, 0) RETURNING id INTO v;
    INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, eval_window, lower_limit, upper_limit,
                                                     limit_inclusive, unit, requirement, sort_order)
    VALUES (0, v, 'pm2_5', 'HOUR_1', NULL, 35, false, 'μg/m³', 'LIMIT', 1) RETURNING id INTO r;
    INSERT INTO data2flow_core.profile_grade_bands (organization_id, metric_rule_id, name, lower_value, upper_value, is_base, sort_order)
    VALUES (0, r, '허용', NULL, 35, true, 1), (0, r, 'v2.1 기본', NULL, 25, false, 2), (0, r, '고성능', NULL, 12, false, 3);
    INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, eval_window, lower_limit, upper_limit,
                                                     limit_inclusive, unit, requirement, sort_order)
    VALUES (0, v, 'tvoc', 'HOUR_1', NULL, 500, false, 'μg/m³', 'LIMIT', 2) RETURNING id INTO r;
    INSERT INTO data2flow_core.profile_grade_bands (organization_id, metric_rule_id, name, lower_value, upper_value, is_base, sort_order)
    VALUES (0, r, '허용', NULL, 500, true, 1), (0, r, '고성능', NULL, 400, false, 2);
    INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, eval_window, lower_limit, upper_limit,
                                                     limit_inclusive, unit, requirement, sort_order)
    VALUES (0, v, 'co2', 'HOUR_1', NULL, 1000, false, 'ppm', 'LIMIT', 3) RETURNING id INTO r;
    INSERT INTO data2flow_core.profile_grade_bands (organization_id, metric_rule_id, name, lower_value, upper_value, is_base, sort_order)
    VALUES (0, r, '허용', NULL, 1000, true, 1), (0, r, '고성능', NULL, 600, false, 2);
    INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, eval_window, unit, requirement, note, sort_order)
    VALUES (0, v, 'temperature', 'INSTANT', '℃', 'CONTINUOUS_MONITORING_ONLY', '연속 측정만 요구', 4),
           (0, v, 'humidity', 'INSTANT', '%', 'CONTINUOUS_MONITORING_ONLY', '연속 측정만 요구', 5);
    INSERT INTO data2flow_core.profile_coverage_rules (organization_id, profile_version_id, kind, params)
    VALUES (0, v, 'DEVICE_DENSITY', '{"maxAreaPerDeviceM2":500,"spaceUsages":["OFFICE","CLASSROOM","LAB","MEETING"]}'),
           (0, v, 'CALIBRATION_INTERVAL', '{"defaultIntervalDays":365}'),
           (0, v, 'DATA_QUALITY', '{"maxMissingRatio":0.10,"maxReportIntervalSec":900}');

    -- ③ WELL v2 A08 공기질 모니터링·인식: PM2.5·CO2·TVOC 중 3개 이상 연속 측정, 재실자 공개 표시
    INSERT INTO data2flow_core.compliance_profiles (organization_id, kind, code, name, current_version, created_by, updated_by)
    VALUES (0, 'SYSTEM', 'WELL_V2_A08', 'WELL v2 A08 공기질 모니터링', 1, 0, 0) RETURNING id INTO p;
    INSERT INTO data2flow_core.compliance_profile_versions (organization_id, profile_id, version_no, source_title, source_url, effective_date,
                                                            description, facility_type_required, created_by)
    VALUES (0, p, 1, 'WELL Building Standard v2, Feature A08 Air Quality Monitoring and Awareness', 'https://v2.wellcertified.com/en/wellv2/air/feature/8',
            DATE '2020-09-01', '연속 측정 항목과 재실자 공개 표시(Part 2). 인증 신청 보조 자료이며 인증기관 심사를 대체하지 않는다', false, 0)
    RETURNING id INTO v;
    INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, eval_window, unit, requirement, note, sort_order)
    VALUES (0, v, 'pm2_5', 'INSTANT', 'μg/m³', 'CONTINUOUS_MONITORING_ONLY', '세 항목 중 3개 이상 연속 측정', 1),
           (0, v, 'co2', 'INSTANT', 'ppm', 'CONTINUOUS_MONITORING_ONLY', '세 항목 중 3개 이상 연속 측정', 2),
           (0, v, 'tvoc', 'INSTANT', 'μg/m³', 'CONTINUOUS_MONITORING_ONLY', '세 항목 중 3개 이상 연속 측정', 3);
    INSERT INTO data2flow_core.profile_coverage_rules (organization_id, profile_version_id, kind, params)
    VALUES (0, v, 'PUBLIC_DISPLAY_DENSITY', '{"areaPerDisplayM2":500}'),
           (0, v, 'DATA_QUALITY', '{"maxMissingRatio":0.10,"maxReportIntervalSec":600}');

    -- ④ ASHRAE 55 열쾌적: PMV −0.5 ~ +0.5(경계 포함). PMV는 가상 센서(ENE-02.03)가 만든다
    INSERT INTO data2flow_core.compliance_profiles (organization_id, kind, code, name, current_version, created_by, updated_by)
    VALUES (0, 'SYSTEM', 'ASHRAE_55', 'ASHRAE 55 열쾌적', 1, 0, 0) RETURNING id INTO p;
    INSERT INTO data2flow_core.compliance_profile_versions (organization_id, profile_id, version_no, source_title, source_url, effective_date,
                                                            description, facility_type_required, created_by)
    VALUES (0, p, 1, 'ANSI/ASHRAE Standard 55-2023 Thermal Environmental Conditions for Human Occupancy', 'https://www.ashrae.org/technical-resources/bookstore/standard-55-thermal-environmental-conditions-for-human-occupancy',
            DATE '2023-01-01', 'PMV 방식. 적응형 모델은 조직 기준으로 복제해 정한다', false, 0) RETURNING id INTO v;
    INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, eval_window, lower_limit, upper_limit,
                                                     limit_inclusive, unit, requirement, sort_order)
    VALUES (0, v, 'pmv', 'HOUR_1', -0.5, 0.5, true, 'PMV', 'LIMIT', 1) RETURNING id INTO r;
    INSERT INTO data2flow_core.profile_grade_bands (organization_id, metric_rule_id, name, lower_value, upper_value, is_base, sort_order)
    VALUES (0, r, '쾌적', -0.5, 0.5, true, 1);

    -- ⑤ 조직 자체 기준 템플릿: 복제해서 쓴다(시스템 기본 기준, BR-CMP-09)
    INSERT INTO data2flow_core.compliance_profiles (organization_id, kind, code, name, current_version, created_by, updated_by)
    VALUES (0, 'SYSTEM', 'ORG_TEMPLATE', '조직 자체 기준(템플릿)', 1, 0, 0) RETURNING id INTO p;
    INSERT INTO data2flow_core.compliance_profile_versions (organization_id, profile_id, version_no, source_title, effective_date, description,
                                                            facility_type_required, created_by)
    VALUES (0, p, 1, 'data2flow 시스템 기본 기준', DATE '2026-10-29', '조직 기준을 만들 때 복제해 고치는 출발점', false, 0) RETURNING id INTO v;
    INSERT INTO data2flow_core.profile_metric_rules (organization_id, profile_version_id, metric_key, eval_window, lower_limit, upper_limit,
                                                     limit_inclusive, unit, requirement, sort_order)
    VALUES (0, v, 'co2', 'HOUR_1', NULL, 1000, true, 'ppm', 'LIMIT', 1),
           (0, v, 'pm2_5', 'HOUR_1', NULL, 35, true, 'μg/m³', 'LIMIT', 2),
           (0, v, 'temperature', 'INSTANT', 20, 26, true, '℃', 'LIMIT', 3),
           (0, v, 'humidity', 'INSTANT', 40, 60, true, '%', 'LIMIT', 4);
    INSERT INTO data2flow_core.profile_grade_bands (organization_id, metric_rule_id, name, lower_value, upper_value, is_base, sort_order)
    SELECT 0, id, '기준', lower_limit, upper_limit, true, 1 FROM data2flow_core.profile_metric_rules WHERE profile_version_id = v;
END $$;
