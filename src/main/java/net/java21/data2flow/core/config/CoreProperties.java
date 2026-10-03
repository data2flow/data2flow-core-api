package net.java21.data2flow.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * core-api 설정({@code data2flow.core.*}, conventions.md §4). 기간 기본값은 스펙 값이다.
 *
 * @param webBaseUrl   메일 링크의 웹 주소(초대·재설정·가입 확인). 기본 {@code https://data2flow.java21.net}
 * @param authBaseUrl  data2flow-auth 내부 주소(블랙리스트 등록 API-IAM-37b). 기본 {@code http://data2flow-auth}
 * @param tokens       초대·재설정·가입 확인·세션 유예 기간
 * @param outbox       아웃박스 릴레이
 * @param jobs         정리 작업(만료 초대·가입 신청, 멱등 키, 감사 파티션)
 * @param password     비밀번호 정책 보조 설정
 * @param bootstrap    최초 관리자 생성 Job(IAM-01.02)
 * @param flywayMode   {@code validate}(기본·prod·local) 또는 {@code migrate}(staging 배포·테스트). ADR-030: DB 하나를 함께 쓰므로
 *                     migrate는 staging 배포 때만 한다
 * @param organizationCode 이 배포가 맡는 조직 코드({@code DATA2FLOW_ORGANIZATION_CODE}). staging과 prod가 DB 하나를 함께 쓰고
 *                     staging은 전용 조직을 쓰므로(ADR-030) "단일 조직"을 찾는 공개 경로·로그인·아웃박스 릴레이·조직 전체 내부 조회가
 *                     이 값으로 조직을 정한다. 비우면 ACTIVE 조직이 하나일 때 그 조직(v1 단일 조직, ADR-004)
 * @param ingressBaseUrl  data2flow-ingress 내부 주소(연결 테스트 API-DSC-51, 원본 샘플 API-DSC-52). 기본 {@code http://data2flow-ingress}
 * @param pipelineBaseUrl data2flow-pipeline 내부 주소(재처리 API-ING-22·23, 스크립트 검사·테스트 API-SCR-30·31). 기본 {@code http://data2flow-pipeline}
 * @param events       도메인 이벤트 소비({@code core.events})
 * @param live         실시간 화면(SSE, API-DSH-20)
 */
@ConfigurationProperties(prefix = "data2flow.core")
public record CoreProperties(String webBaseUrl, String authBaseUrl, Tokens tokens, Outbox outbox, Jobs jobs,
                             Password password, Bootstrap bootstrap, String flywayMode, String organizationCode,
                             String ingressBaseUrl, String pipelineBaseUrl, Events events, Live live) {

    public CoreProperties {
        webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? "https://data2flow.java21.net" : stripSlash(webBaseUrl);
        authBaseUrl = authBaseUrl == null || authBaseUrl.isBlank() ? "http://data2flow-auth" : stripSlash(authBaseUrl);
        tokens = tokens == null ? new Tokens(null, null, null, null, null, null) : tokens;
        outbox = outbox == null ? new Outbox(null, null, null, null) : outbox;
        jobs = jobs == null ? new Jobs(null) : jobs;
        password = password == null ? new Password(null, null) : password;
        bootstrap = bootstrap == null ? new Bootstrap(null, null, null, null, null, null, null, null, null) : bootstrap;
        flywayMode = flywayMode == null || flywayMode.isBlank() ? "validate" : flywayMode;
        organizationCode = organizationCode == null || organizationCode.isBlank() ? null : organizationCode.strip();
        ingressBaseUrl = ingressBaseUrl == null || ingressBaseUrl.isBlank() ? "http://data2flow-ingress" : stripSlash(ingressBaseUrl);
        pipelineBaseUrl = pipelineBaseUrl == null || pipelineBaseUrl.isBlank() ? "http://data2flow-pipeline" : stripSlash(pipelineBaseUrl);
        events = events == null ? new Events(null, null) : events;
        live = live == null ? new Live(null, null) : live;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * @param invitation      초대 유효 기간(IAM-01.03, 72시간)
     * @param passwordReset   재설정 링크 유효 기간(IAM-02.04, 30분)
     * @param signupVerify    가입 신청 이메일 확인 기간(domain-model §2.12, 24시간)
     * @param signupApproval  승인 대기 기간(§3.4, 14일)
     * @param refreshGrace    Refresh 회전 유예(IAM-07.03, 30초)
     * @param mfaSetup        TOTP 등록 임시 비밀 유효 기간(API-IAM-60, 5분)
     */
    public record Tokens(Duration invitation, Duration passwordReset, Duration signupVerify, Duration signupApproval,
                         Duration refreshGrace, Duration mfaSetup) {
        public Tokens {
            invitation = invitation == null ? Duration.ofHours(72) : invitation;
            passwordReset = passwordReset == null ? Duration.ofMinutes(30) : passwordReset;
            signupVerify = signupVerify == null ? Duration.ofHours(24) : signupVerify;
            signupApproval = signupApproval == null ? Duration.ofDays(14) : signupApproval;
            refreshGrace = refreshGrace == null ? Duration.ofSeconds(30) : refreshGrace;
            mfaSetup = mfaSetup == null ? Duration.ofMinutes(5) : mfaSetup;
        }
    }

    /**
     * @param relayEnabled  릴레이 스케줄 실행(테스트에서는 끄고 직접 부른다)
     * @param relayInterval 릴레이 주기(기본 1초)
     * @param batchSize     한 번에 보낼 행 수
     * @param retention     보낸 행 보관(ERD README §14, 7일)
     */
    public record Outbox(Boolean relayEnabled, Duration relayInterval, Integer batchSize, Duration retention) {
        public Outbox {
            relayEnabled = relayEnabled == null || relayEnabled;
            relayInterval = relayInterval == null ? Duration.ofSeconds(1) : relayInterval;
            batchSize = batchSize == null ? 100 : batchSize;
            retention = retention == null ? Duration.ofDays(7) : retention;
        }
    }

    /** @param enabled 정리 작업 스케줄 실행(테스트에서는 끄고 직접 부른다) */
    public record Jobs(Boolean enabled) {
        public Jobs {
            enabled = enabled == null || enabled;
        }
    }

    /**
     * @param hibpEnabled 유출 비밀번호 k-익명성 조회(BR-IAM-03). 외부 호출이라 기본 꺼짐, 로컬 사전은 항상 쓴다
     * @param hibpBaseUrl k-익명성 조회 주소(기본 {@code https://api.pwnedpasswords.com})
     */
    public record Password(Boolean hibpEnabled, String hibpBaseUrl) {
        public Password {
            hibpEnabled = hibpEnabled != null && hibpEnabled;
            hibpBaseUrl = hibpBaseUrl == null || hibpBaseUrl.isBlank() ? "https://api.pwnedpasswords.com" : stripSlash(hibpBaseUrl);
        }
    }

    /**
     * 최초 관리자 생성 Job(design/auth.md §3.4). Secret의 값으로 조직과 ADMIN 계정을 만든다.
     *
     * @param enabled             Job 모드로 실행(웹 없이 한 번 돌고 끝난다)
     * @param exitAfterRun        끝나면 프로세스를 종료한다(k8s Job)
     * @param organizationCode    조직 코드(기본 {@code default})
     * @param organizationName    조직 이름
     * @param adminLoginId        DATA2FLOW_BOOTSTRAP_ADMIN_LOGIN_ID
     * @param adminEmail          DATA2FLOW_BOOTSTRAP_ADMIN_EMAIL
     * @param adminName           표시 이름
     * @param adminInitialPassword 운영자가 Secret에 미리 넣은 일회용 초기 비밀번호(없으면 생성)
     * @param passwordOutputFile  생성한 초기 비밀번호를 쓸 파일(로그에는 쓰지 않는다, NFR-03.02)
     */
    public record Bootstrap(Boolean enabled, Boolean exitAfterRun, String organizationCode, String organizationName,
                            String adminLoginId, String adminEmail, String adminName, String adminInitialPassword,
                            String passwordOutputFile) {
        public Bootstrap {
            enabled = enabled != null && enabled;
            exitAfterRun = exitAfterRun == null || exitAfterRun;
            organizationCode = organizationCode == null || organizationCode.isBlank() ? "default" : organizationCode;
            organizationName = organizationName == null || organizationName.isBlank() ? "data2flow" : organizationName;
            adminName = adminName == null || adminName.isBlank() ? "관리자" : adminName;
        }
    }

    /**
     * 도메인 이벤트 소비(큐 {@code core.events}, architecture.md §4.3).
     *
     * @param listenerEnabled 소비자 실행(로컬 기본 꺼짐: 개발자끼리 vhost data2flow-dev를 함께 쓰므로 같은 큐를 나눠 갖지 않게)
     * @param concurrency     동시 소비자 수(기본 2)
     */
    public record Events(Boolean listenerEnabled, Integer concurrency) {
        public Events {
            listenerEnabled = listenerEnabled == null || listenerEnabled;
            concurrency = concurrency == null || concurrency < 1 ? 2 : concurrency;
        }
    }

    /**
     * 실시간 화면(SSE). {@code data2flow.telemetry}를 소비자 그룹 {@code core-live}(로컬은 {@code core-live-<developer>})로 최신부터 읽는다.
     *
     * @param telemetryEnabled 텔레메트리 스트림 소비 실행(테스트·로컬은 끌 수 있다)
     * @param developer        로컬 개발자 이름(소비자 그룹 접미사, deployment.md §8.2). 운영·staging은 비운다
     */
    public record Live(Boolean telemetryEnabled, String developer) {
        public Live {
            telemetryEnabled = telemetryEnabled == null || telemetryEnabled;
            developer = developer == null || developer.isBlank() ? null : developer.strip();
        }
    }
}
