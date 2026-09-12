# Kotlin Office Dashboard

Kotlin/Spring Boot 웹 서버입니다. 화면이 쓰는 모든 API를 제공하고, AI 생성도 대부분 여기서 직접 합니다.
블로그 자동화(키워드 수집 → 글 생성 → 네이버 발행)만 Python 워커([`automation/`](../automation/))에 남아 있고,
`POST /api/automation/{keyword|content|posting|all}` 요청을 받을 때만 실행합니다.

## 포함 기능

- **AI 운영 센터**: 실행 이력을 모델별로 집계합니다. 입력·출력 토큰을 나눠 보여 주고, 기능·모델·기간(오늘 / 최근 7일 / 달별)으로 걸러 봅니다. 이력은 이번 달 포함 6개월 보관합니다.
- **최신 소식**: 공식 RSS/Atom 수집과 핵심 3건 요약(LLM).
- **주제 대본 초안**(대본 생성의 쇼츠 채널. 전용 폼 없음, "소식·키워드에서 주제 가져오기"가 후보를 원본 칸에 채움): 수집한 소식과 자동화 키워드 중 우선순위가 가장 높은 주제로 45~60초 숏폼 대본을 만듭니다. 검토 대기 상태로 저장하고 Slack에는 검토 링크만 보냅니다.
- **인스타툰 대본과 컷 이미지**(대본 생성의 인스타툰 채널. 전용 폼 없음, 컷 이미지 버튼은 패키지 카드에): 에피소드 한 줄에서 4·8컷 대본(장면·대사·나레이션·이미지 프롬프트)을 만들고, 그 프롬프트로 Google Imagen 이미지를 생성해 화면에서 바로 봅니다.
- **대본 생성(콘텐츠 생성 에이전트)**: 원본 하나로 체크한 채널마다 그 채널의 에이전트를 순차 실행합니다. 인스타툰은 `InstagramToonService`, 쇼츠는 `TopicDraftService`를 그대로 써서 각 목록에도 저장되고, 블로그는 `automation_content`에 검토 대기로 들어가 워커가 발행할 수 있습니다. 카드뉴스는 모델을 한 번 부릅니다. 요청은 모든 채널을 `생성중`으로 저장하고 바로 202로 끝나며, 백그라운드 스레드가 채널을 하나씩 채웁니다(화면은 목록 API 폴링). 한 채널이 실패해도 나머지는 계속 돌고, 재시작으로 끊긴 `생성중`은 실패로만 표시합니다.
- **검토·승인**: 패키지 출력마다 검토 대기·승인·반려. 승인이 실제로 바꾸는 것은 블로그뿐(`automation_content.status=approved`, 워커는 approved 만 발행). Slack 알림은 패키지당 1건, 재전송 버튼. 데모 세션은 승인·재전송 라우트가 403.
- **아침 사전 준비**: 워커 크론(`MORNING_PREP_TIME`)이 `POST /api/worker/morning-prep`를 불러 소식 수집 → 핵심 3건 요약 → 주제 초안을 순서대로 만들어 둡니다. 한 단계가 실패해도 다음 단계는 돕니다.
- **외부 정보**: Gmail 읽기 전용 요약과 토스증권 국내 관심 종목 현재가.
- **데모 모드**: 로그인 없이 둘러보는 모드. 아래 별도 절 참고.

데이터는 전부 Postgres에 저장합니다. 대부분은 `app_document`(jsonb) 한 테이블에 문서 키로 나눠 담고,
컷 이미지 바이트만 `toon_image`(bytea)에 따로 둡니다. Kotlin 쪽에는 파일로 쓰는 코드가 없습니다.

## 구성

`src/main/kotlin/com/backoffice/dashboard/`는 루트의 공용 파일과 기능별 패키지 4개로 나뉩니다.

- 루트(공용): `DashboardApplication.kt`(엔트리포인트. 컨테이너 타임존과 무관하게 기록 시각이 한국시간이 되도록 JVM 기본 타임존을 한 번 고정), `LlmClient.kt`(OpenAI 호환 chat·Ollama·Google Imagen `:predict`를 여기서만 부르고 타임아웃·재시도·오류 문구·단가·JSON 폴백 파싱을 관리), `AiOperationsService.kt`(AI 실행 이력, 실패 Slack 알림), `JsonDocumentStore.kt`(jsonb 문서 저장소), `DemoMode.kt`(`DemoContext`·`DemoBudget`), `OfficeProperties.kt`, `ApiErrorHandler.kt`, `CorsConfiguration.kt`
- `auth/`: Google 로그인·세션(`AuthController`, `AuthService`), `SessionAuthFilter`, `WorkerAuthFilter`(`/api/worker/*` API 키), `GmailTokenStore`
- `content/`: `ContentController` 아래 `ContentStudioService`(채널 오케스트레이터), `TopicDraftService`, `InstagramToonService`, `ToonImageService`·`ToonImageRepository`, `AiNewsService`, `AiNewsBriefingService`
- `operations/`: `OperationsController` 아래 `OperationsService`, `GmailService`, `TossService`, `AutomationRepository`(키워드·콘텐츠 큐)
- `automation/`: `AutomationController` 아래 `PythonAutomationService`(워커 HTTP 위임), `SlackService`
- `src/main/resources/db/migration/`: Flyway 마이그레이션 V1~V9 (스키마 생성 → task → feature 테이블 → 네이밍·soft-delete 정리 → 컷 이미지 테이블 → 실행 이력 행 전환 → 컷 attempts)

Python 자동화 실행은 `PythonAutomationService`가 `automation/worker_api.py`에 HTTP로 위임합니다(로컬에서는 프로세스를 직접 띄웁니다).

## 실행

```powershell
Copy-Item config\dashboard.properties.example config\dashboard.properties
.\gradlew.bat :backend:bootRun
```

브라우저에서 `http://127.0.0.1:8765`을 엽니다. 화면 자산은 `frontend/static/`에 두며, 웹 서버와 모든 API는 Kotlin이 제공합니다.
로컬 DB는 docker 컨테이너를 씁니다(`postgres:16-alpine`, db `backoffice`). 없으면 서버가 뜨지 않습니다.

## 연동 설정

전부 `config/dashboard.properties`에 둡니다(gitignore 대상). 배포에서는 같은 값을 환경변수로 줍니다.

- **로그인**: Google OAuth 한 번으로 로그인과 Gmail 읽기 권한을 함께 받습니다. 별도의 "Gmail 연결" 절차는 없습니다. Google Cloud 콘솔에는 `office.auth.redirect-uri` 하나만 등록하면 됩니다. `office.auth.allowed-emails`가 비어 있으면 아무도 로그인할 수 없습니다(닫힌 기본값).
- **모델**: `office.ai-news.summary-provider`(`openai` 호환 또는 `ollama`)와 `summary-model`. 모델별 100만 토큰 단가는 `office.llm.prices[모델명]=입력,출력`으로 적습니다. 안 적으면 기본 단가로 비용이 과소 표시될 수 있습니다.
- **컷 이미지**: `office.llm.image-api-key`(Google AI Studio 키). **`open-ai-api-key`를 재사용하지 않습니다** — 그 값이 다른 제공자 키일 수 있고 그대로 구글에 보내면 유출입니다. 같은 키를 쓰려면 여기에 직접 적으세요. 장당 단가와 상한은 `image-price-usd`, `image-daily-limit`.
- **Slack**: 화면의 "Slack 연결" 버튼으로 설치합니다. 대본 전문이 아니라 알림과 검토 링크만 보냅니다.
- **토스증권**: 자격증명과 관심 종목을 적고 `enabled=true`로 바꿉니다.
- 기본 바인딩은 `127.0.0.1`입니다. 외부 공개 전에는 로그인(`office.auth.enabled=true`)과 HTTPS가 필요합니다.

## 데모 모드

`office.demo.enabled=true`면 로그인 화면에 "데모로 둘러보기" 버튼이 생깁니다. 예약 이메일로 세션을 만들어
앱 전체가 로그인 상태로 동작하고, AI 생성과 소식 수집은 **실제로 실행됩니다**. 개인 계정이 필요한 Gmail·Slack과
서버 프로세스를 띄우는 워커 실행은 안내 문구로 막습니다.

격리는 두 곳에서 겁니다.

- `JsonDocumentStore`가 데모 요청의 문서 키에 `demo:`를 붙입니다. 새 기능이 문서를 추가해도 자동으로 격리됩니다.
- `SessionAuthFilter`의 허용 목록에 없는 경로는 403입니다. 새 엔드포인트는 데모에 자동으로 닫혀 있습니다.

문서 저장소를 타지 않는 `toon_image`는 `owner` 컬럼으로 가르고, 그 값은 서버가 `DemoContext`로 정합니다.
비용은 `office.demo.llm-*`·`image-*` 상한으로 막습니다. 인증이 꺼져 있으면(`office.auth.enabled=false`)
격리를 거는 필터 자체가 돌지 않으므로 데모 시작을 거부합니다.

씨앗 데이터는 `src/main/resources/demo/`에 있고 첫 데모 방문 때 들어갑니다.
다시 넣으려면 `delete from app_document where document_key like 'demo:%';`
