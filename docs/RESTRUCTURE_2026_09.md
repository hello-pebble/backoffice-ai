# 백오피스 구조 정리 3건 (2026-09-06)

한 패키지·한 컨트롤러였던 백엔드를 기능별로 나누고, AI 실행 이력을 JSON 한 덩어리에서 DB 행으로 바꾸고, 컷 이미지 생성이 서버 재시작을 견디게 했다. 새 의존성 0개, 새 테이블 0개.

| 커밋 | 내용 |
|---|---|
| `d31bd9e` | 기능별로 패키지와 컨트롤러를 나눈다 |
| `085d42c` | 실행 이력을 행 단위로 저장하고 필터·페이지를 DB 가 처리한다 |
| `12d2bc5` | 재시작하면 생성중이던 컷을 자동으로 이어 만든다 |

테스트 132개 통과. 배포 시 Flyway 가 V8·V9 를 자동 적용한다.

---

## 1. 기능별 패키지·컨트롤러 분리

**왜**: 파일 26개가 한 폴더에, 라우트 35개가 컨트롤러 하나에 있어 어디를 고쳐야 할지 찾는 데 시간이 들었다. 동작은 그대로 두고 자리만 옮겼다.

| 전 | 후 |
|---|---|
| `dashboard/` 평평한 파일 26개 | `auth` · `content` · `operations` · `automation` 4개 패키지 |
| `DashboardController` 하나, 의존성 16개 | 컨트롤러 4개. 공용(설정·문서저장소·LLM·데모·운영기록)은 루트 |

| 패키지 | 들어간 것 | URL |
|---|---|---|
| auth | Google 로그인, 세션·워커 필터, Gmail 토큰 | `/api/auth/*` |
| content | 인스타툰 대본·이미지, 콘텐츠 패키지, 주제 초안, AI 뉴스·브리핑 | `/api/instagram-toons`, `/api/ai-news`, `/api/topic-drafts`, `/api/content-packages` |
| operations | 대시보드, 업무·승인, AI 운영 센터 조회, 워커 수신, health | `/api/operations`, `/api/tasks`, `/api/ai-operations`, `/api/worker/*` |
| automation | Python 자동화 실행, Slack 연결 | `/api/automation/*`, `/api/slack/*` |

확인한 것
- URL·응답 변경 없음. 프론트 수정 없음.
- 기존 테스트 127개가 그대로 통과 = 동작 불변의 증거.
- Gradle 모듈은 나누지 않았다. 실행 애플리케이션은 하나.

---

## 2. AI 실행 이력 → 행 단위 저장

**왜**: 실행 이력 전부가 `app_document` 한 행의 JSON 배열이었다. 실행마다 배열을 통째로 읽고 다시 쓰고, 필터와 페이지는 브라우저가 전체를 받아서 했다. V3 때 만들어 두고 아무도 안 쓰던 `ai_operation_run` 테이블을 그대로 살렸다.

| 전 | 후 |
|---|---|
| JSON 배열 1행, 매 실행 read-modify-write | 실행 1건 = 행 1개 |
| `@Synchronized`, 상한 5000건 | 상한 없음(DB 가 감당), 보관 6개월은 SQL 조건 |
| 서버가 전부 내려주고 JS 가 필터·집계·페이지 | `GET /api/ai-operations?range=7d&agent=…&model=…&page=1`. 지표·모델별 표·목록·셀렉트 옵션 전부 SQL |

기존 데이터
- **V8 마이그레이션**이 주인 문서·데모 문서를 행으로 푼다. 문서는 지우지 않고 소프트 삭제(payload 그대로 남음).
- 데모 격리는 `owner` 컬럼(`owner`/`demo`). 데모 씨앗은 주인 실데이터에서 만든 것이라 id 가 겹쳐서 데모 행 키에 `demo:` 접두사를 붙였다. 이걸 놓치면 씨앗 8건이 유니크 키에 막혀 조용히 사라진다.
- 데모 씨앗은 문서가 아니라 행이 되었으므로 로그인 때가 아니라 **기동 때 한 번** 넣는다(`AiOperationsService.seedDemo`, 같은 id 는 건너뜀).
- 모델 이름 정규화(소문자, `/` 뒤)는 백필 때 SQL 로 굳혔다.

확인한 것
- 로컬 DB 에서 V8 을 트랜잭션 안에서 미리 돌려 주인 8건 + 데모 16건 확인 후 실제 적용.
- 브라우저 데모 로그인: 기간을 7일로 바꾸면 `range=7d` 요청, 다음 페이지는 `page=1`. 데모에는 데모 행만 보임.
- `AiOperationsServiceTest` 를 실제 Postgres 에 붙여 필터·페이지·격리·보관기간을 검증.

파일: `V8__ai_operation_run_rows.sql`, `AiOperationsService.kt`, `OperationsController.kt`, `frontend/static/app.js`, `docs/DATA_MODEL.md`

---

## 3. 컷 이미지 생성, 재시작해도 이어서

**왜**: "복구가 필요한 장시간 작업"은 백엔드에 컷 이미지 생성 하나뿐이다. 그리고 `toon_image` 는 이미 작업 테이블이었다(즉시 202, 상태, `(toon_id, panel_number)` 중복 방지 키). 빠진 두 가지만 채우고 큐 테이블·폴러·SKIP LOCKED 는 만들지 않았다.

| 전 | 후 |
|---|---|
| 재시작하면 `생성중` 컷이 그대로 멈춤 | 기동 시 `생성중` 행을 전부 다시 잡아 이어 만듦(`ToonImageService.resumeOrphans`) |
| 10분 지나면 화면에만 실패로 보이고, 사람이 다시 눌러야 재개 | `prompt`·`attempts` 컬럼 추가(V9), 자동 복구 상한 3회 |

규칙
- **자동 복구**는 같은 컷을 최대 3회(`office.llm.image-max-attempts`)까지만. 넘으면 그대로 두고 화면에 실패로 보인다. 재시작 루프가 돈을 계속 쓰는 걸 막는다.
- **사람이 다시 누른 재시도**는 attempts 를 1 로 되돌린다. 자동 복구만 세므로 막다른 화면이 생기지 않는다.
- 예산은 다시 세지 않고(처음 요청 때 이미 셌음), 데모 컷은 데모 표시를 되살려 기록이 주인 쪽에 섞이지 않는다.

확인한 것
- 로컬 DB 에 `생성중` 행을 넣고 재시작 → 기동 직후 자동 처리(로컬엔 이미지 키가 없어 `실패`), attempts 2, 운영 센터 기록 생성.
- `ToonImageRepositoryTest`(실제 Postgres): 3회 상한, 완료 컷 미접촉, 수동 재시도 시 attempts 리셋.

파일: `V9__toon_image_attempts.sql`, `ToonImageRepository.kt`, `ToonImageService.kt`, `OfficeProperties.kt`, `config/dashboard.properties.example`

---

## 계획과 달라진 점

- 데모 행 키 `demo:` 접두사 — 백필 검증 중 발견.
- 복구는 나이 조건 없이 `생성중` 전부 — 단일 인스턴스에서 재시작 후 `생성중` 은 곧 고아.
- 시도 상한은 자동 복구에만 — 수동 재시도는 리셋.

## 안 만든 것, 언제 만드나

| 안 만든 것 | 만들 시점 |
|---|---|
| 범용 작업 큐 테이블 | 복구가 필요한 두 번째 장시간 작업이 생기면 |
| `SKIP LOCKED` | 백엔드 인스턴스가 2대가 되면. `ToonImageRepository.orphaned()` 의 `ponytail:` 주석 참고 |
| 외부 발행 중복 방지 | 인스타에 실제 게시하는 기능이 생기면 `posting_record` 유니크 키로 |
| Gradle 모듈 분리 | 요청 없음, 필요 없음 |

## 배포 전 확인

- Railway 기동 시 Flyway 가 V8·V9 를 자동 적용한다. V8 은 기존 이력을 옮기므로 첫 기동 로그에서 `Successfully applied 2 migrations` 를 확인한다.
- 새 환경변수 `OFFICE_LLM_IMAGE_MAX_ATTEMPTS` 는 선택(기본 3).
- 롤백이 필요하면 `app_document` 의 `ai-operations` 문서가 `removed` 상태로 그대로 있다.
