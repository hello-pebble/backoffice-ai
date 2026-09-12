# Backoffice AI PRD

**버전** 2.0 · **기준일** 2026-09-06 · **기준 코드** `main` `1e41ff0`

> v1.0(2024)은 "Python으로 네이버 블로그에 매일 자동 포스팅" 하나를 다룬 문서였다. 지금 제품은 Kotlin 대시보드가 중심이고 블로그 자동화는 그 안의 기능 하나다. v1.0 내용 중 살아 있는 것은 §4 블로그 자동화 절로 흡수했고, 나머지는 폐기했다.

---

## 1. 제품 정의

**1인 AI 콘텐츠 스튜디오의 아침 점검 화면.** 에이전트가 밤새 한 일(실행·토큰·비용)을 확인하고, 검토 대기 초안을 승인하고, 소식·메일·종목을 한 화면에서 본다.

| 항목 | 내용 |
|---|---|
| 사용자 | 운영자 1명(주인). 그 외에는 데모 방문자만 |
| 핵심 가치 | ① AI가 만든 것은 **항상 검토 대기**로 멈춘다 ② 모든 AI 실행은 **비용까지 기록**된다 |
| 성공 기준 | 아침 5분 안에 "어제 얼마 썼고, 뭘 검토해야 하는지" 파악 |
| 범위 밖 | 다중 사용자·권한, 매매·투자 판단, 외부 플랫폼 자동 발행(승인 전) |

---

## 2. 기능 범위 (as-built)

화면 순서 그대로. 열 "상태"는 실제 동작 여부다.

| # | 화면 | 하는 일 | 구현 | 상태 |
|---|---|---|---|---|
| 1 | AI 운영 센터 | 실행 1건=행 1개. 기능·모델·기간 필터, 모델별 토큰·비용 집계, 6개월 보관 | `AiOperationsService`, `ai_operation_run` | 운영 |
| 2 | 주제 대본 초안 | §2-3의 쇼츠 채널. 전용 폼 없음(2026-09-12 통합 1단계). "소식·키워드에서 주제 가져오기"가 우선순위 1건을 원본 칸에 채우고, 생성 성공 시 키워드 소진. 검토 대기 저장 + Slack 링크. 이전 결과 목록만 남음 | `TopicDraftService` | 운영 |
| 3 | 대본 생성(콘텐츠 생성 에이전트) | 원본 1개 → 체크한 채널마다 실제 에이전트 실행. 인스타툰·쇼츠는 §2-4·§2-2 서비스 재사용(각 섹션에도 저장), 블로그는 워커 발행 큐(검토 대기), 카드뉴스는 모델 1회. 채널별 실패 격리. 요청은 202로 바로 끝나고 채널은 백그라운드에서 채워진다(화면 폴링) | `ContentStudioService`(오케스트레이터) | 운영 |
| 4 | 인스타툰 | §2-3의 인스타툰 채널(4·8컷 선택). 전용 폼 없음(2026-09-12 통합 1단계). 컷 이미지(Imagen) 버튼은 패키지 카드에. 재시작 시 생성중 컷 자동 재개. 이전 결과 목록만 남음 | `InstagramToonService`, `ToonImageService` | 운영 |
| 5 | 메일 | Gmail 읽기 전용 요약 | `GmailService` | 운영(주인만) |
| 6 | 최신 소식 | RSS 수집 + 핵심 3건 요약 | `AiNewsService`, `AiNewsBriefingService` | 운영 |
| 7 | 국내 관심 종목 | 토스증권 현재가 | `TossService` | 운영(설정 시) |
| 8 | Slack 연결 | 앱 설치·채널 선택. 알림만, 본문 전송 없음 | `SlackService` | 운영 |
| 9 | 블로그 자동화 | 키워드 수집 → 글 생성 → 네이버 발행 | Python 워커 | 워커 배포 시. 발행은 기본 꺼짐 |
| – | 데모 모드 | 로그인 없이 둘러보기. AI는 실제 실행, 개인 연동은 차단, 문서 키·owner로 격리 | `DemoMode`, `SessionAuthFilter` | 운영 |

---

## 3. 시스템 구조

```text
Vercel (frontend/static, /api/* rewrite)
  └─ Railway  backend/  Kotlin·Spring, 포트 8765
        ├─ PostgreSQL 16 (Flyway V1~V9)
        ├─ LlmClient → OpenAI 호환 chat · Ollama · Google Imagen
        └─ POST /run ─→ Railway  automation/  Python FastAPI 워커
                              └─ 결과는 /api/worker/* 로 백엔드에 저장
```

- 패키지: `auth` · `content` · `operations` · `automation`, 공용(설정·문서저장소·LLM·데모·운영기록)은 루트.
- 저장: 대부분 `app_document`(jsonb, 문서 키), 실행 이력은 `ai_operation_run` 행, 컷 이미지는 `toon_image`(bytea). Kotlin에 파일 쓰기 없음.
- 모델 호출은 `LlmClient` 한 곳. 타임아웃·재시도·단가·데모 상한이 여기 붙는다.
- 워커 실행은 두 경로: 백엔드의 `POST /api/automation/{mode}`(수동) 과 워커 내부 APScheduler(정시). **정시 실행의 주인은 워커다.**

---

## 4. 블로그 자동화 (v1.0 계승분)

| 항목 | 현재 |
|---|---|
| 키워드 수집 | 네이버 검색어 트렌드·데이터랩. 최소 검색량 필터, 중복 제거. `/api/worker/keywords` 저장 |
| 글 생성 | OpenAI 호환 chat. 제목·본문(최소 길이 설정)·태그. 사용량은 stdout 마커 한 줄로 백엔드에 넘김 |
| 발행 | Selenium. `NAVER_LOGIN_ENABLED=False` 기본. 재시도 `MAX_RETRY_ATTEMPTS` |
| 주기 | `KEYWORD_COLLECTION_TIME` → `CONTENT_GENERATION_TIME` → `POSTING_TIME` |
| 목표 빈도 | **주 1회**(v1.0 메모의 "일주일 한 번" 반영). 매일 실행은 폐기 |

v1.0의 CSV 저장, SQLite, Windows 작업 스케줄러, Telegram 알림은 없어졌다.

---

## 5. 불변 원칙

1. 생성물은 **검토 대기**로만 저장한다. 자동 발행·삭제·외부 전송은 승인 뒤.
2. AI 실행마다 모델·입출력 토큰·비용·도구·결과 요약을 `AI 운영 센터`에 남긴다.
3. 데모는 실제 모델을 부르되 상한(`office.demo.llm-*`, `image-*`) 안에서만, 데이터는 `demo:` 키·`owner='demo'`로 격리.
4. 비밀값은 `config/dashboard.properties`·`config/.env`(gitignore) 또는 Railway Variables에만.
5. 삭제는 `lifecycle_state='removed'` 소프트 삭제. 물리 삭제 없음.
6. 재시도는 멱등해야 한다. 같은 콘텐츠 중복 발행 금지.

---

## 6. 중복·정리 대상

"있는 것 같다"는 중복을 코드 기준으로 확인한 목록. **결정** 열이 이 PRD의 요구사항이다.

### 6.1 기능 중복

| 중복 | 근거 | 결정 |
|---|---|---|
| **콘텐츠 생성 에이전트 ↔ 인스타툰·주제 초안·블로그** | 과거에는 §2-3이 같은 결과물을 템플릿 문자열로 흉내 냈고 운영 센터에 0원 행을 남겼다 | **통합 완료(2026-09-06).** §2-3은 새 생성기 없이 기존 에이전트를 채널별로 호출하는 오케스트레이터다. 템플릿·`초안 템플릿` 기록·죽은 파일 경로 필드 제거 |
| **인스타툰 Python ↔ Kotlin** | `automation/jobs/instagram_toon/`, `scripts/run_instagram_toon.py`, `INSTAGRAM_TOON_MODEL`, `INSTAGRAM_TOONS_DIR`, `data/instagram-toons/`. 대시보드는 Kotlin만 부르고 Python 쪽 참조 0건 | **삭제.** 로컬 CLI 용도도 대시보드로 대체됐다 |
| **업무·승인·KPI(CEO Office 잔재)** | `/api/operations`·`/api/tasks`·`/api/approvals`, `OperationsService.sample()`(김지수·광고 소재 제작비), `task`·`approval`·`dashboard_kpi` 테이블. 프론트 참조 0건 | **삭제.** 라우트·서비스·샘플·테스트 제거. 테이블은 다음 마이그레이션에서 drop |
| **자동화 실행 기록 2중** | `OperationsService.recordRun`이 `operations` 문서에 최근 20건을 넣고, 같은 실행을 `PythonAutomationService`가 `ai_operation_run`에도 기록. `automation_run` 테이블은 셋째 후보 | **운영 센터 하나로.** `recordRun`과 `automation_run` 제거 |
| **워커 트리거 2경로** | 수동(`/api/automation/{mode}`)과 워커 내 스케줄러가 같은 job을 돈다. 동시 실행은 워커 락이 막는다 | 유지. 단, 정시 실행의 소유는 워커, 백엔드는 수동 트리거만이라고 문서에 못 박는다(§3) |

### 6.2 코드·설정 중복

| 중복 | 결정 |
|---|---|
| Python `UsageTracker`(토큰 집계) ↔ Kotlin `AiOperationsService`. 단가표는 Kotlin에만 있어 Python은 토큰만 세고 비용은 백엔드가 계산 | 유지. 경계가 명확하다(워커=토큰, 백엔드=단가·기록). 마커 문자열은 양쪽 주석으로 묶여 있음 |
| `AiNewsService.path` — 파일 경로 필드. 파일 쓰기 코드는 없음(`ContentStudioService.path`는 제거됨) | 삭제 |
| `config/env.example`에 `SUPABASE_*` 4개 + `settings.py`의 `SUPABASE_*`. 현재 DB는 Railway Postgres, Python은 DB 직접 접근 안 함 | 삭제 |
| `config/env.example`에 백엔드용 `OFFICE_*`·`APP_CORS_*` 25개가 Python `.env` 예시에 섞여 있음. 백엔드 예시는 `dashboard.properties.example`·`railway-backend.env.example`에 따로 있음 | `env.example`은 워커 키만 남긴다 |
| `data/` 하위 8개 폴더 | 워커 로그 외 전부 gitignore 대상 로컬 잔재. README에서 언급 제거 |

### 6.3 문서 중복

같은 규칙이 최대 4곳에 있다. 각 규칙의 **단일 소유 문서**를 정한다.

| 규칙 | 지금 있는 곳 | 소유 문서 |
|---|---|---|
| 브랜치 이름·PR 대상·hotfix | AGENTS, BRANCH_STRATEGY, PULL_REQUEST_RULES, DEVELOPMENT_RULES | `docs/BRANCH_STRATEGY.md` |
| AI 실행 기록·검토 대기·비밀값 금지 | AGENTS, DEVELOPMENT_RULES, PULL_REQUEST_RULES, AGENT_ROLES | 이 PRD §5. AGENTS는 링크만 |
| 에이전트 역할·요청 템플릿 | AGENT_ROLES, PARALLEL_WORKFLOW | `docs/AGENT_ROLES.md`로 합침 |
| 기능 목록 | README, backend/README, AGENTS | 이 PRD §2. README는 요약 5줄 |
| 배포 대상 | DEVELOPMENT_RULES·BRANCH_STRATEGY = **Cloud Run(틀림)**, 나머지 = Railway | Railway로 통일 |
| 백엔드 구성 | backend/README "패키지 없이 파일 단위", "Flyway V1~V7" — `d31bd9e`·V8·V9 이후 틀림 | §3으로 갱신 **(2026-09-12 완료)** |
| `architecture-docs-refresh.patch` (루트, 미추적) | `develop` 시절 상태를 담고 있고 `git apply --check` 실패 | 삭제 후보. 내용 중 살릴 것은 이미 커밋됨 |

---

## 7. 고도화 로드맵

정리(§6)를 먼저 한다. 그다음 순서.

| 순위 | 항목 | 왜 | 완료 기준 |
|---|---|---|---|
| 1 | §6.1 정리 남은 3건 | 통계 오염·죽은 라우트·중복 기록이 신규 기능마다 비용을 늘린다 | 프론트 API 목록 = 컨트롤러 라우트 목록. 테스트 통과 |
| 2 | §6.3 문서 소유 정리 | 규칙이 4곳이면 하나만 고쳐진다 | 각 규칙이 정확히 한 문서에 |
| 3 | 검토 대기 큐 화면 | "뭘 검토해야 하는지"가 초안·인스타툰에 흩어져 있다 | 검토 대기 항목이 한 목록에, 승인·반려 버튼 |
| 4 | 주간 비용 상한 알림 | 하루 상한은 있으나 주간 누적은 사람이 봐야 안다 | 주간 합계가 상한의 80% 넘으면 Slack 1회 |
| 5 | 블로그 글을 주제 초안과 같은 검토 흐름으로 | 워커 결과만 승인 UI가 없다 | `automation_content`가 검토 큐(3)에 뜬다 |

**2026-09-07 반영 (토스 TOI 글 참고, "실패는 커밋하지 않고 무거운 일은 메인 스레드 밖에서, 비싼 준비는 미리")**:

| 항목 | 상태 |
|---|---|
| 콘텐츠 패키지 202 + 백그라운드 + 폴링 (`ContentStudioService`, toon-image 와 같은 방식). 채널별 `생성중→성공|실패`, 재시작 시 남은 `생성중`은 실패 표시만 | 완료 |
| JSON 폴백 파싱을 `LlmClient.jsonOf` 한 곳으로 (3곳 복붙 제거) | 완료 |
| 아침 사전 준비 크론: 워커 `MORNING_PREP_TIME` → `POST /api/worker/morning-prep` → 소식·핵심 3건·주제 초안 순차 | 완료 |
| 프롬프트 버전 해시를 `ai_operation_run`에 남기기 | 안 함. 프롬프트가 Kotlin 상수라 git 이력으로 충분. 필요해지면 V10 컬럼 1개 |

**만들지 않는 것**: 범용 작업 큐, 다중 인스턴스(`SKIP LOCKED`), 다중 사용자, 티스토리·브런치 연동. 도입 조건은 `docs/RESTRUCTURE_2026_09.md` "안 만든 것" 표.

---

## 8. 비기능 요구

| 항목 | 기준 |
|---|---|
| 첫 화면 | 외부 API 응답을 기다리지 않는다(`/api/dashboard` 지연이 다른 카드를 막지 않음) |
| 비용 | 모델별 단가 `office.llm.prices[모델]` 필수. 미설정 시 과소 표시 경고 |
| 복구 | 재시작 후 `생성중` 컷 자동 재개, 자동 재시도 3회 상한 |
| 보안 | 외부 공개 시 `office.auth.enabled=true` + HTTPS. `allowed-emails` 비면 아무도 못 들어감 |
| 검증 | Kotlin 변경은 `:backend:test`(테스트 파일 28개), Python은 발행 없는 안전 실행 |

---

## 9. 문서 지도

| 알고 싶은 것 | 문서 |
|---|---|
| 제품이 뭐고 뭘 만들지 | 이 PRD |
| 실행·설정 | `README.md`, `backend/README.md`, `automation/README.md` |
| 환경변수 전체 | `docs/SETUP_REGISTRY.md` |
| 브랜치·PR·배포 순서 | `docs/BRANCH_STRATEGY.md` |
| 테이블·삭제 정책 | `docs/DATA_MODEL.md` |
| 에이전트 역할 | `docs/AGENT_ROLES.md` |
| 최근 구조 변경 이력 | `docs/RESTRUCTURE_2026_09.md` |
| 배포 장애 기록 | `docs/DEPLOYMENT_TROUBLESHOOTING.md` |
