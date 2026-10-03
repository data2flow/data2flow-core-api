# data2flow-core-api

회원·조직·권한·기기·공간·소스·스크립트·플로우 정의·규칙·알람·대시보드·조회·운영 설정, 실시간 화면(SSE).

- 관련 스펙: DEV, DSC, SCR, FLW, RUL, TSD, DSH, OPS, IAM (정본은 비공개 저장소 `data2flow-docs`)
- 패키지: `net.java21.data2flow.core` · Spring Boot 4.1.1 · Java 21 · Maven Wrapper
- 포트: API 8080, actuator 8081(프로브·지표 전용)

## 빌드와 실행

```bash
./mvnw verify                 # 단위·통합 테스트 + 커버리지 80% 검사
./mvnw spring-boot:run        # 로컬 실행(프로필 local)
```

공통 라이브러리 `data2flow-contracts`는 GitHub Packages에 있어서 읽기에도 토큰이 필요합니다. `~/.m2/settings.xml`에 서버 `github`(사용자 이름 + `read:packages` 권한 토큰)를 넣거나, `data2flow-contracts`를 받아 `./mvnw install`로 로컬 저장소에 설치합니다.

## M1 로그인·권한 (IAM·OPS-07)

회원·초대·가입 신청·비밀번호(Argon2id)·2단계 인증·Refresh 계보·역할(기본 5개 + 사용자 정의)·감사 로그·조직 설정·외부 서비스(메일) 설정을 맡습니다.
스키마 `data2flow_core`(Flyway `V202610040900__iam_ops_init`, `V202610040910__iam_m1_guards`).

| 환경변수 | 용도 |
|---|---|
| `DATA2FLOW_DB_HOST`·`_PORT`·`_NAME`·`_USERNAME`·`_PASSWORD` (staging·prod는 `DATA2FLOW_DB_HOST_INTERNAL`) | PostgreSQL `data2flow` |
| `DATA2FLOW_RABBITMQ_HOST`·`_PORT`·`_VHOST`·`_USERNAME`·`_PASSWORD` | 이벤트 아웃박스 릴레이(`data2flow.events`) |
| `DATA2FLOW_SECRETS_MASTER_KEYS`(필수)·`DATA2FLOW_SECRETS_ACTIVE_KEY_ID` | 비밀값 암호화(메일 비밀번호, TOTP 비밀, NFR-03.02) |
| `DATA2FLOW_AUTH_BASE_URL`(기본 `http://data2flow-auth`) | 세션 폐기 시 블랙리스트 등록(API-IAM-37b) |
| `DATA2FLOW_WEB_BASE_URL`(기본 `https://data2flow.java21.net`) | 메일 링크 |
| `DATA2FLOW_BOOTSTRAP_ADMIN_LOGIN_ID`·`_EMAIL`·`_INITIAL_PASSWORD`·`DATA2FLOW_BOOTSTRAP_PASSWORD_FILE`·`DATA2FLOW_BOOTSTRAP_ORG_CODE`·`_ORG_NAME` | 최초 관리자 Job |
| `DATA2FLOW_PASSWORD_HIBP_ENABLED`(기본 false) | 유출 비밀번호 k-익명성 조회 |

- Flyway는 기본 `validate`이고 staging 프로필만 `migrate`입니다(DB를 staging·prod가 함께 씀, ADR-030).
- 최초 관리자 Job: `java -jar app.jar --data2flow.core.bootstrap.enabled=true --spring.main.web-application-type=none`
  (이미 ADMIN이 있으면 아무것도 하지 않음. 초기 비밀번호는 Secret 값이나 생성 파일로만 전달하고 로그에는 남기지 않음)
- 로컬 프로필은 아웃박스 릴레이와 정리 작업을 끕니다(공용 DB의 운영 아웃박스를 대신 보내지 않도록).

## M2 수집 경로 (DEV·DSC·SCR·TSD·ING·DSH·IAM 공간 권한)

공간 트리·속성·평면도·목표·시간표·시맨틱, 기기 모델(아카데미 실측 6종 시드)·측정 항목·별칭, 기기 발견→승인·그룹·태그·속성·게이트웨이·기기 자격,
데이터 소스·커넥터 카탈로그·연결 상태·지표·연결 테스트, 스크립트 정의·배포, 시계열·주석 조회, 수집 모니터·실패 보관함, 실시간 화면(SSE)·홈 요약을 맡습니다.
Flyway `V202610050900`~`V202610050970`(추가만, ADR-030). 시계열·원본·기기 상태는 pipeline 소유 `data2flow_pipeline`을 읽기만 합니다(conventions §6).

| 환경변수 | 용도 |
|---|---|
| `DATA2FLOW_ORGANIZATION_CODE` | 이 배포가 맡는 조직 코드(prod `default`, staging `staging`, ADR-030). 공개 경로·로그인·아웃박스 릴레이·조직 전체 내부 조회의 범위. 비우면 ACTIVE 조직이 하나일 때 그 조직 |
| `DATA2FLOW_INGRESS_BASE_URL`(기본 `http://data2flow-ingress`) | 연결 테스트(API-DSC-51)·원본 샘플 SSE(API-DSC-52) |
| `DATA2FLOW_PIPELINE_BASE_URL`(기본 `http://data2flow-pipeline`) | 스크립트 검사·테스트 실행(API-SCR-30·31), 재처리·폐기(API-ING-22·23·24), 별칭 재매핑(API-TSD-51) |
| `DATA2FLOW_RABBITMQ_STREAM_PORT`(기본 5552) | Super Stream `data2flow.telemetry` 실시간 소비(그룹 `core-live`, 최신부터, 오프셋 저장 없음) |
| `DATA2FLOW_DEVELOPER` | 로컬 소비자 그룹 접미사(`core-live-<이름>`) |
| `DATA2FLOW_CATALOG_SEED_ON_STARTUP`(기본 true, local false) | 시작할 때 ACTIVE 조직에 기본 모델 6종·측정 항목 시드(멱등) |

- 메시지: 도메인 이벤트는 `DomainEvent` 봉투로 `data2flow.events`, 설정 변경은 `ConfigChangedMessage`로 `data2flow.config`(둘 다 아웃박스).
  소비는 Quorum 큐 `core.events`(DLQ `core.events.dlq`, 중복 제거 `processed_messages`)와, 화면용 파드별 임시 큐 `core.live.<uuid>`.
- 실시간: `GET /core/stream/live?topics=…`(API-DSH-20·21), `GET /core/stream/sources/{source-id}/live`(API-DSC-10). 15초 ping, 세션 폐기 시 `session-revoked`.
- 로컬 프로필은 `core.events` 소비·카탈로그 시드를 끕니다(공용 vhost·DB).

## 작업 규칙

스펙 ID에서 시작하고(인수 테스트 → 테스트 케이스 → 구현), 브랜치·PR·테스트 이름에 스펙 ID를 남깁니다. 1.0 전에는 `main` + `feat/<스펙ID>-<요약>`, 1.0 뒤에는 버전 브랜치 `feature/vX.Y`를 씁니다(ADR-039).
