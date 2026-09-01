# 등록 체크리스트 — 어디에 무엇을 등록하는가

내일 오전 외부 연동 작업용. 위에서부터 순서대로 진행하면 됩니다.
값의 의미와 실패 시 동작은 [`EXTERNAL_API_SETUP.md`](EXTERNAL_API_SETUP.md)를 참고하세요.

---

## 0. 먼저 — 로그인 잠금 주의

인증이 공유 API 키에서 **Google 로그인**으로 바뀌었습니다. `APP_AUTH_ENABLED` · `APP_AUTH_API_KEY`는 더 이상 쓰이지 않으니 Railway에서 지워도 됩니다.

배포 프로파일은 `office.auth.enabled=true`가 기본입니다. **`OFFICE_AUTH_ALLOWED_EMAILS`가 비어 있으면 앱은 정상 기동하지만 아무도 로그인할 수 없습니다.** 헬스체크로는 잡히지 않으니 배포 전에 먼저 넣으세요.

---

## 1. Railway — 백엔드 환경변수

`SPRING_PROFILES_ACTIVE=oci` 기준입니다. (프로파일 이름이 `oci`지만 실제 배포처는 Railway입니다. 이름 변경은 Railway 변수도 함께 바꿔야 해서 미뤄 두었습니다.)

### 이미 등록됨 (확인만)

`APP_CORS_ALLOWED_ORIGINS` · `JAVA_TOOL_OPTIONS` · `OFFICE_AUTOMATION_EXECUTION_ENABLED`
`SPRING_FLYWAY_BASELINE_ON_MIGRATE` · `SPRING_FLYWAY_BASELINE_VERSION` · `SPRING_PROFILES_ACTIVE`
`SUPABASE_DB_URL` · `SUPABASE_DB_USER` · `SUPABASE_DB_PASSWORD`

> `APP_CORS_ALLOWED_ORIGINS`는 `https://backoffice-ai-orpin.vercel.app` 여야 합니다 (끝 슬래시 없이).

### 로그인 (필수)

| 변수 | 값 | 비고 |
|---|---|---|
| `OFFICE_AUTH_ALLOWED_EMAILS` | 본인 이메일 (쉼표로 여러 개) | **비면 아무도 로그인 불가** |

나머지는 배포 프로파일에 기본값이 들어 있어 **값이 같다면 등록하지 않아도 됩니다.**

| 변수 | 기본값 | 언제 바꾸나 |
|---|---|---|
| `OFFICE_AUTH_ENABLED` | `true` | 임시로 인증을 끌 때만 `false` |
| `OFFICE_AUTH_REDIRECT_URI` | `https://backoffice-ai-orpin.vercel.app/api/auth/callback` | 프런트 도메인이 바뀌면 |
| `OFFICE_AUTH_SUCCESS_REDIRECT` | `https://backoffice-ai-orpin.vercel.app/` | 위와 동일 |
| `OFFICE_AUTH_COOKIE_SECURE` | `true` | https가 아닌 환경에서만 `false` |
| `OFFICE_AUTH_COOKIE_SAME_SITE` | `Lax` | 아래 설명 참고 |

> **리디렉션 주소는 Railway가 아니라 Vercel 도메인이어야 합니다.**
> `frontend/static/vercel.json`이 `/api/*`를 Railway로 rewrite하므로 브라우저에는 `vercel.app` 한 도메인만 보입니다.
> Railway 주소로 잡으면 세션 쿠키가 `railway.app`에 저장되고, 이후 요청은 `vercel.app`으로 가서
> 쿠키가 실리지 않습니다. 로그인은 되는데 **새로고침하면 계속 풀리는** 증상이 이것입니다.
> 같은 이유로 쿠키는 교차 도메인이 아니므로 `SameSite=Lax`로 충분합니다.

### Slack 연동

| 변수 | 값 | 가져오는 곳 |
|---|---|---|
| `OFFICE_SLACK_CLIENT_ID` | 발급값 | api.slack.com → Basic Information |
| `OFFICE_SLACK_CLIENT_SECRET` | 발급값 | 같은 화면 |
| `OFFICE_SLACK_REDIRECT_URI` | `https://backoffice-ai-orpin.vercel.app/api/slack/callback` | Slack 앱의 Redirect URLs와 **글자까지 동일** |
| `OFFICE_SLACK_REVIEW_BASE_URL` | `https://backoffice-ai-orpin.vercel.app` | 알림에 넣을 검토 화면 주소 |

뒤 두 개는 기본값과 같으면 등록하지 않아도 됩니다.

### 외부 연동 (쓸 때만)

| 변수 | 값 | 가져오는 곳 |
|---|---|---|
| `OFFICE_TOSS_ENABLED` | `true` | — |
| `OFFICE_TOSS_CLIENT_ID` / `_SECRET` | 발급값 | 토스증권 개발자센터 (§3) |
| `OFFICE_TOSS_WATCHLIST` | `005930,000660,373220` | 원하는 종목코드 |
| `OFFICE_GMAIL_ENABLED` | `true` | — |
| `OFFICE_GMAIL_REDIRECT_URI` | `https://backoffice-ai-production.up.railway.app/api/gmail/callback` | **이미 등록된 값 유지.** Gmail은 쿠키를 쓰지 않아 Railway 주소 그대로 둡니다 |
| `OFFICE_AI_NEWS_SUMMARY_PROVIDER` | `openai` | Railway엔 ollama가 없습니다 |
| `OFFICE_AI_NEWS_OPEN_AI_API_KEY` | API 키 | OpenAI (§5) |
| `OFFICE_AI_NEWS_SUMMARY_MODEL` | 모델명 | OpenAI (§5) |

> Kotlin과 Python이 OpenAI 키를 **따로** 읽습니다.
> 백엔드는 `OFFICE_AI_NEWS_OPEN_AI_API_KEY`, 워커는 `OPENAI_API_KEY`. 양쪽 다 필요합니다.

### 워커 → 백엔드 저장 (워커 서비스에도 등록)

워커는 DB에 직접 쓰지 않고 백엔드의 워커 전용 API로 결과를 넘깁니다.

| 서비스 | 변수 | 값 |
|---|---|---|
| 백엔드 | `OFFICE_AUTOMATION_WORKER_API_KEY` | 긴 랜덤 문자열 |
| 워커 | `WORKER_API_KEY` | **위와 같은 값** |
| 워커 | `BACKEND_API_URL` | `https://backoffice-ai-production.up.railway.app` |

> 두 값이 다르면 백엔드가 401로 막고 **자동화 결과가 조용히 사라집니다.**
> 백엔드 쪽 키가 비어 있으면 워커 경로는 전부 거부됩니다(설정 누락 시 무인증으로 열리지 않도록).
> `BACKEND_API_URL`은 Railway 내부 주소를 써도 됩니다. Vercel을 거칠 필요가 없습니다.

### 더 이상 쓰지 않음 (지워도 됨)

`APP_AUTH_ENABLED` · `APP_AUTH_API_KEY` — Google 로그인으로 대체됐습니다.

워커 서비스의 `SUPABASE_PG_DSN` · `SUPABASE_DB_USER` · `SUPABASE_DB_PASSWORD`도 더 이상 필요 없습니다(백엔드를 통해 저장). 백엔드 쪽 DB 변수는 그대로 둡니다.

---

## 2. Google Cloud Console — Gmail

### 가져올 것
1. 프로젝트 생성 → **API 및 서비스 → 라이브러리 → Gmail API 사용 설정**
2. **OAuth 동의 화면** 구성 (외부 / 테스트 사용자에 본인 계정 추가)
3. **사용자 인증 정보 → OAuth 클라이언트 ID → 웹 애플리케이션**
4. 생성된 **클라이언트 JSON 파일 다운로드**

### 등록할 것
- 승인된 리디렉션 URI에 `https://backoffice-ai-production.up.railway.app/api/gmail/callback` 추가 — **등록 완료**
- 같은 값을 Railway `OFFICE_GMAIL_REDIRECT_URI`에도 등록 — **한 글자라도 다르면 `redirect_uri_mismatch`**
- 로컬에서도 쓸 거면 `http://127.0.0.1:8765/api/gmail/callback`도 함께 등록

### 배포 환경 대응 — 완료됨

`GmailService`가 자격증명을 파일에서만 읽고 토큰을 컨테이너 파일시스템에 두던 문제를 해결했습니다.

| 항목 | 이전 | 현재 |
|---|---|---|
| 클라이언트 JSON | `data/.../gmail-credentials.json` 파일 전용. 이미지에 없어 배포 시 항상 실패 | `OFFICE_GMAIL_CREDENTIALS_JSON` 환경변수 우선, 없으면 파일. **로컬은 파일, 배포는 환경변수** |
| OAuth 토큰 | `data/.../gmail-token/` — 재배포마다 소멸 | Postgres `app_documents`에 저장. **재배포해도 재인증 불필요** |

Railway에 추가할 변수는 하나입니다.

| 변수 | 값 |
|---|---|
| `OFFICE_GMAIL_CREDENTIALS_JSON` | 다운로드한 클라이언트 JSON **원문을 그대로** 붙여넣기 |

> Railway 변수 입력창은 여러 줄을 받으므로 JSON을 그대로 붙여넣어도 됩니다.
> 값이 비어 있으면 파일 경로로 넘어가고, 그것도 없으면
> `"Gmail OAuth 자격증명이 설정되지 않았습니다."`를 반환합니다.

**로컬에서는 이 변수를 비워두세요.** 파일(`data/office-dashboard/gmail-credentials.json`)이 그대로 쓰입니다.

### 로컬 검증 완료 (2026-08-23)

- 동의 완료 후 `app_documents`에 `gmail-token-StoredCredential` 행 생성 확인
- **리프레시 토큰 실제 값 저장 확인** — 없으면 약 1시간 뒤 재인증을 요구한다
- 백엔드 완전 재시작(재배포 상황 재현) 후에도 **재인증 없이 연결 유지**
- 읽지 않은 메일 개수 + 최근 5건 정상 반환

남은 작업은 Railway에 `OFFICE_GMAIL_CREDENTIALS_JSON`과
`OFFICE_GMAIL_ENABLED=true`를 등록하는 것뿐이다.

---

## 3. 토스증권 개발자센터

### 가져올 것
- https://developers.tossinvest.com — 앱 등록 후 **Client ID / Client Secret**

### 등록할 것
- Railway: `OFFICE_TOSS_CLIENT_ID`, `OFFICE_TOSS_CLIENT_SECRET`, `OFFICE_TOSS_ENABLED=true`

### 연결 직후 반드시 확인
호스트와 경로(`POST /oauth2/token`, `GET /api/v1/prices`)는 공식 레퍼런스에 존재함을 확인했지만,
**요청·응답 형식은 미검증**입니다. 코드가 가정하는 값:

- 토큰: form-urlencoded `grant_type=client_credentials&client_id=…&client_secret=…`
- 시세: 쿼리 `symbols=`, 응답 `result[]` 안에 `symbol` / `lastPrice` / `currency` / `timestamp`

`TossService`는 실패를 전부 삼켜서 "불러오지 못했습니다"만 표시합니다.
**형식이 달라도 화면상으로는 미설정과 구분되지 않으므로, 응답 원문을 한 번 직접 찍어보세요.**

> 참고: `client_id`/`client_secret`이 폼 바디에 URL 인코딩 없이 들어갑니다.
> 시크릿에 `&` `+` `=` 가 포함되면 조용히 실패합니다. 발급값에 이 문자가 있으면 코드부터 고쳐야 합니다.

---

## 4. 네이버

**두 가지 자격증명이 서로 다릅니다.** 헷갈리지 마세요.

| 용도 | 자격증명 | 가져오는 곳 |
|---|---|---|
| 검색·데이터랩 API | `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | https://developers.naver.com 앱 등록 |
| 블로그 자동 발행 로그인 | `NAVER_ID` / `NAVER_PASSWORD` | 블로그 계정 자체 |

### 등록할 곳
- 워커 실행 환경 (`config/.env` 또는 워커 컨테이너 env)
- **Railway 백엔드에는 넣지 마세요.** 백엔드는 이 값을 읽지 않습니다.

### ⚠️ 켜기 전에 알아야 할 것
- `NAVER_LOGIN_ENABLED`는 기본 `False`이며, **이 값을 켜기 전까지 실제 발행은 일어나지 않습니다.**
- Selenium 자동 로그인은 **CAPTCHA / 2단계 인증에 걸릴 가능성이 높고 처리 코드가 없습니다.**
  현실적 대안은 `SELENIUM_HEADLESS=False`로 두고 사람이 직접 통과하는 방식입니다.
- 주 계정으로 먼저 시험하지 마세요. 자동화 로그인은 계정 잠금 사유가 될 수 있습니다.
- **켜기 전에 §5의 placeholder 문제를 먼저 해결하세요.** 지금 켜면 OpenAI 실패 시
  자리표시자 텍스트가 실제 블로그에 발행됩니다.

---

## 5. OpenAI Platform

### 가져올 것
- https://platform.openai.com → **API 키**
- 같은 화면에서 **사용량 한도(usage limit)를 먼저 설정**하세요. 과금은 즉시 발생합니다.

### 등록할 곳
| 변수 | 위치 | 용도 |
|---|---|---|
| `OPENAI_API_KEY` | 워커 env | 콘텐츠·인스타툰 생성 |
| `OPENAI_BASE_URL` | 워커 env | 비우면 OpenAI. 현재 예시값은 NVIDIA NIM `https://integrate.api.nvidia.com/v1` |
| `OPENAI_MODEL` | 워커 env | 기본 `gpt-3.5-turbo` |
| `INSTAGRAM_TOON_MODEL` | 워커 env | 기본 `gpt-5.6-luna` |
| `OFFICE_AI_NEWS_OPEN_AI_API_KEY` | Railway | 백엔드 AI 뉴스 요약 |
| `OFFICE_AI_NEWS_OPEN_AI_BASE_URL` | Railway | 워커의 `OPENAI_BASE_URL` 과 같은 형식. 버전 경로까지 포함한다: `https://integrate.api.nvidia.com/v1` |
| `OFFICE_AI_NEWS_SUMMARY_PROVIDER` | Railway | **`openai` 로 바꿔야 한다.** 기본값 `ollama` 로 두면 이 설정이 전부 무시된다 |
| `OFFICE_AI_NEWS_SUMMARY_MODEL` | Railway | 요약 모델 ID. 엔드포인트를 바꿨으면 반드시 같이 바꾼다 |
| `OFFICE_AI_NEWS_INPUT_PRICE_PER_MILLION_USD` / `..._OUTPUT_...` | Railway | 기본값은 OpenAI 단가다. 다른 제공자면 비용 기록이 틀어진다 |

### ⚠️ 먼저 확인할 것
- **`gpt-5.6-luna`가 실제 계정에서 쓸 수 있는 모델인지 확인되지 않았습니다.** 3곳에서 기본값으로 쓰입니다.
  모델 목록 API로 존재 여부부터 확인하세요. 없으면 호출 시 404입니다.
- **`content_generator.py`는 OpenAI 호출이 실패해도 예외를 던지지 않고 자리표시자 문장을 반환합니다**
  (`"{keyword}에 대한 완벽한 가이드"` 등). 이 상태로 네이버 발행을 켜면
  **할당량 초과나 잘못된 키 하나로 실제 블로그에 자리표시자 글이 올라갑니다.**
  네이버 연동 전에 이 동작부터 실패로 바꾸는 것을 권합니다.

---

## 6. Vercel — 지금은 등록할 것 없음

`frontend/static/vercel.json`이 `/api/*`를 Railway로 rewrite 합니다.

- **주의**: rewrite는 서버에서 헤더를 주입할 수 없습니다. 그래서 API 키는 브라우저가 직접 보냅니다.
  대시보드 첫 접속 시 입력창이 뜨고 `sessionStorage`에 저장됩니다.
- 키를 브라우저에서 완전히 숨기려면 rewrite를 서버리스 프록시 함수로 바꾸고
  Vercel 환경변수에 키를 두어야 합니다. 1인 운영이면 지금 방식으로 충분합니다.

---

## 7. 로컬 개발 — `config/.env`

```
cp config/env.example config/.env
```

로컬은 `APP_AUTH_ENABLED=false`로 두면 키 없이 바로 뜹니다.

---

## 8. GitHub Actions — 등록할 것 없음

현재 CI는 컴파일·테스트·문법 검사만 하며 시크릿을 쓰지 않습니다.

---

## 권장 진행 순서

1. **`APP_AUTH_API_KEY` 등록 → 배포 → `/api/health` 200 확인** (이게 안 되면 나머지는 의미 없음)
2. 대시보드 접속 → 키 입력 → 화면이 정상적으로 뜨는지 확인
3. **OpenAI** — 모델명 존재 확인 → 키 등록 → AI 브리핑 동작 확인
4. **토스증권** — 등록 후 `/api/v1/prices` 응답 원문 확인
5. **Gmail** — §2의 A/B/C 중 방안 결정 후 진행
6. **네이버** — 마지막. placeholder 문제 해결 후에만 `NAVER_LOGIN_ENABLED=True`
