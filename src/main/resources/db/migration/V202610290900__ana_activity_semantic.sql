-- ANA-01.05: 기본 측정 항목 activity(활동량, PIR)의 의미 태그를 analytics 재실 추정 역할(activity)과 맞춘다.
-- 시드는 없을 때만 넣으므로 이미 만든 조직의 행은 여기서 고친다. 사용자가 바꾼 값은 그대로 둔다(기본값 occupancy일 때만).
UPDATE data2flow_core.metrics
   SET semantic = 'activity', version = version + 1, updated_at = now()
 WHERE key = 'activity' AND builtin AND semantic = 'occupancy';
