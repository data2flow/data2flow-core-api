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

## 작업 규칙

스펙 ID에서 시작하고(인수 테스트 → 테스트 케이스 → 구현), 브랜치·PR·테스트 이름에 스펙 ID를 남깁니다. 1.0 전에는 `main` + `feat/<스펙ID>-<요약>`, 1.0 뒤에는 버전 브랜치 `feature/vX.Y`를 씁니다(ADR-039).
