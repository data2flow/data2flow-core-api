package net.java21.data2flow.core.external.provider;

/**
 * 외부 연동 파사드 구현 하나의 설명(design/external-integrations.md §2 "등록"). {@code available=false}는 화면에 "준비 중".
 *
 * @param key       구현 키(KMA·SIMULATED_WEATHER·AIRKOREA·SIMULATED·DATA_GO_KR_HOLIDAY·FIXED_HOLIDAY)
 * @param available 실제로 쓸 수 있는가(서비스 키가 있어야 true)
 * @param simulated 가짜 구현(시뮬레이터·고정 값)인가
 */
public record ProviderDescriptor(String key, boolean available, boolean simulated) {
}
