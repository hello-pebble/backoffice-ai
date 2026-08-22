# 외부 API 연결 체크리스트

값은 `config/env.example`을 복사한 `config/.env`(또는 배포 환경 변수)에 채웁니다.
네 연동 모두 **기본값이 꺼짐**이며, 켜기 전까지는 화면에 "비활성화" 안내만 나옵니다.

---

## 1. Gmail

| 항목 | 값 |
|---|---|
| 켜는 플래그 | `OFFICE_GMAIL_ENABLED=true` |
| 환경 변수 | `OFFICE_GMAIL_REDIRECT_URI` |
| 추가 파일 | `data/office-dashboard/gmail-credentials.json` (Google Cloud에서 받은 OAuth 클라이언트 JSON) |
| 토큰 저장 위치 | `data/office-dashboard/gmail-token/` |

**꺼져 있을 때 동작**: `GmailService.overview()`가 즉시 `연동 안 됨 + "Gmail 연동이 비활성화되어 있습니다."`를 반환합니다.
켜져 있어도 credentials 파일이 없으면 `"Gmail OAuth 설정 파일이 없습니다."`, 인증 전이면 `"Gmail 연결이 아직 완료되지 않았습니다."`를 반환합니다. 예외는 잡아서 로그만 남기므로 앱이 죽지는 않습니다.

**리스크**
- **리다이렉트 URI는 Google Cloud 콘솔에 등록한 값과 문자 하나까지 같아야 합니다.** `OFFICE_GMAIL_REDIRECT_URI`, 콘솔 등록값, 실제 접속 도메인 세 곳이 모두 일치해야 하며 다르면 `redirect_uri_mismatch`로 실패합니다. 기본값은 로컬용 `http://127.0.0.1:8765/api/gmail/callback` 이므로, 배포 도메인에서 쓰려면 그 도메인 값을 따로 등록·설정해야 합니다.
- `/api/gmail/callback`은 인증 필터의 예외 경로입니다. 콜백은 API 키 없이 열려 있습니다.
- 토큰은 파일로만 저장됩니다. 컨테이너를 재생성하면 재인증이 필요합니다.
- 스코프는 `gmail.readonly` 하나입니다. 발송은 불가합니다.

---

## 2. 토스증권 (Toss Securities Open API)

| 항목 | 값 |
|---|---|
| 켜는 플래그 | `OFFICE_TOSS_ENABLED=true` |
| 환경 변수 | `OFFICE_TOSS_BASE_URL`, `OFFICE_TOSS_CLIENT_ID`, `OFFICE_TOSS_CLIENT_SECRET`, `OFFICE_TOSS_WATCHLIST` |

**꺼져 있을 때 동작**: `"토스증권 연동이 비활성화되어 있습니다."` 반환. 켜져 있는데 client-id/secret이 비어 있으면 `"토스증권 Open API 자격증명이 아직 설정되지 않았습니다."` 반환. 호출 실패 시에도 예외를 삼키고 `"토스증권 시세를 불러오지 못했습니다."`만 표시합니다.

**엔드포인트 확인 결과**
- `https://openapi.tossinvest.com` — **실재함** (토스증권 공식 Open API 호스트).
- `POST /oauth2/token` (OAuth2 액세스 토큰 발급), `GET /api/v1/prices` (현재가 조회) — **공식 API 레퍼런스에 존재 확인됨**.
- 다만 **요청/응답 세부 형식은 미검증**입니다. 코드는 아래를 가정하고 있으며 실제 스펙과 다를 수 있습니다.
  - 토큰: `application/x-www-form-urlencoded` 로 `grant_type=client_credentials&client_id=...&client_secret=...` 전송
  - 시세: 쿼리 파라미터 이름이 `symbols`, 응답이 `result` 배열이고 각 항목에 `symbol` / `lastPrice` / `currency` / `timestamp` 필드
- 최초 연결 시 **`GET /api/v1/prices` 응답 원문을 한 번 직접 확인**하고, 필드명이 다르면 `TossService.kt`를 고쳐야 합니다. 실패해도 조용히 빈 목록이 되므로 화면만 보고는 알 수 없습니다.
- 참고: 공식 OpenAPI 스펙 JSON — `https://openapi.tossinvest.com/openapi-docs/latest/openapi.json`

---

## 3. 네이버 블로그

| 항목 | 값 |
|---|---|
| 켜는 플래그 | `NAVER_LOGIN_ENABLED=True` |
| 환경 변수 | `NAVER_ID`, `NAVER_PASSWORD`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` |
| 관련 설정 | `SELENIUM_HEADLESS`, `SELENIUM_WAIT_TIME`, `MAX_RETRY_ATTEMPTS`, `RETRY_DELAY` |

**꺼져 있을 때 동작**: `BlogPoster.login()`과 `post_blog()`가 경고 로그만 남기고 각각 `False` / `None`을 반환합니다. 브라우저를 띄우지 않으며 실제 발행은 일어나지 않습니다. 키워드 수집·콘텐츠 생성은 이 플래그와 무관하게 동작합니다.

**리스크**
- **Selenium 자동 로그인은 CAPTCHA / 2단계 인증에 걸릴 가능성이 높고, 코드에는 이에 대한 처리가 전혀 없습니다.** 캡차가 뜨면 `login()`은 그냥 "로그인 실패"로 끝납니다. 우회 코드를 넣지 마세요. 현실적인 대안은 헤드리스를 끄고(`SELENIUM_HEADLESS=False`) 사람이 직접 캡차를 통과하는 방식입니다.
- 네이버는 자동화 로그인에 대해 계정 잠금·기기 등록 요구를 할 수 있습니다. 주 계정으로 먼저 시험하지 마세요.
- `NAVER_CLIENT_ID/SECRET`(검색 API)과 `NAVER_ID/PASSWORD`(블로그 로그인)는 서로 다른 자격증명입니다.
- 글쓰기 페이지 DOM 셀렉터에 의존합니다. 네이버가 에디터를 바꾸면 조용히 깨집니다.

---

## 4. OpenAI

| 항목 | 값 |
|---|---|
| 켜는 플래그 | 별도 플래그 없음. `OPENAI_API_KEY`가 채워지면 동작합니다. |
| 환경 변수 | `OPENAI_API_KEY`, `OPENAI_MODEL`, `INSTAGRAM_TOON_MODEL` |
| 백엔드 AI 뉴스 요약 | `office.ai-news.summary-provider` (기본 `ollama`), `office.ai-news.open-ai-api-key` |

**설정 안 됐을 때 동작**: 키가 비어 있으면 콘텐츠 생성이 실패 로그를 남기고 건너뜁니다. 백엔드 AI 뉴스 요약은 기본값이 로컬 `ollama`이므로 OpenAI 키 없이도 돌아갑니다. OpenAI를 쓰려면 `summary-provider=openai`로 바꿔야 합니다.

**리스크**
- **과금이 즉시 발생합니다.** 먼저 사용량 한도를 걸어두세요.
- `INSTAGRAM_TOON_MODEL` 기본값 `gpt-5.6-luna`, 백엔드 `summary-model` 기본값도 동일합니다. **이 모델 이름이 실제 계정에서 사용 가능한지 확인되지 않았습니다.** 없는 모델이면 404가 납니다. 최소 비용 확인 방법: 모델 목록 API로 존재 여부부터 확인.
- Kotlin(`office.ai-news.open-ai-api-key`)과 Python(`OPENAI_API_KEY`)이 키를 따로 읽습니다. 양쪽 다 채워야 합니다.

---

## 연결 순서 권장

1. `APP_AUTH_ENABLED=false`로 로컬에서 앱이 뜨는지 확인
2. OpenAI → 토스증권 → Gmail 순으로 하나씩 켜고 `/api/health` 및 해당 화면 확인
3. 네이버는 마지막. `NAVER_LOGIN_ENABLED`는 실제 발행을 승인할 때만 켭니다.
4. 배포에서는 `APP_AUTH_ENABLED=true` + `APP_AUTH_API_KEY` 설정 (docs/DEPLOYMENT_TROUBLESHOOTING.md 참고)
