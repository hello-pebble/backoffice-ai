# Backoffice AI

1인 AI 콘텐츠 스튜디오용 백오피스입니다. 에이전트가 밤새 한 일(실행·토큰·비용)을 아침에 점검하고,
검토 대기 대본을 승인하고, 소식·메일·종목을 한 화면에서 봅니다.

- 화면과 API: [`backend/`](backend/) (Kotlin·Spring) + [`frontend/`](frontend/) (정적 파일)
- 블로그 자동화 워커: [`automation/`](automation/) (Python)

## 주요 기능

| 기능 | 하는 일 | 구현 |
|---|---|---|
| AI 운영 센터 | 모델별 실행·입출력 토큰·비용·시간 집계, 기능/모델/기간 필터 | Kotlin |
| 최신 소식 | 공식 RSS 수집과 핵심 3건 요약 | Kotlin + LLM |
| 주제 대본 초안 | 소식·키워드 중 우선순위 1건으로 숏폼 대본 생성, Slack 검토 알림 | Kotlin + LLM |
| 인스타툰 | 에피소드 → 4·8컷 대본(장면·대사·이미지 프롬프트) → 컷 이미지 생성 | Kotlin + LLM·Imagen |
| 콘텐츠 생성 에이전트 | 원본 하나를 채널별 초안으로 묶기 | Kotlin (템플릿) |
| 메일·국내 관심 종목 | Gmail 읽기 전용 요약, 토스증권 현재가 | Kotlin |
| 블로그 자동화 | 키워드 수집 → 글 생성 → 네이버 발행 | Python 워커 |
| 데모 모드 | 로그인 없이 둘러보기(AI는 실제 실행, 개인 계정 연동은 차단) | Kotlin |

## 시스템 요구사항

- JDK 21 (대시보드)
- Postgres 16 (로컬은 docker 컨테이너)
- Python 3.12와 Chrome (블로그 자동화 워커를 쓸 때만)

## 빠른 시작

```powershell
# 1. DB
docker run -d --name backoffice-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=backoffice -p 5432:5432 postgres:16-alpine

# 2. 설정
Copy-Item config\dashboard.properties.example config\dashboard.properties

# 3. 실행
.\gradlew.bat :backend:bootRun
```

`http://127.0.0.1:8765`을 엽니다. 로컬 기본값은 `office.auth.enabled=false`라 로그인 없이 열립니다.
자세한 설정과 연동은 [`backend/README.md`](backend/README.md)를, 워커 실행은 [`automation/README.md`](automation/README.md)를 보세요.

## 프로젝트 구조

```text
backoffice-ai/
├── backend/                 # Kotlin · Spring API + 화면 서빙
│   └── src/main/resources/
│       ├── db/migration/    # Flyway V1~V7
│       └── demo/            # 데모 씨앗 데이터
├── frontend/static/         # HTML·CSS·JS (빌드 도구 없음)
├── automation/              # Python 블로그 자동화 워커
│   ├── main.py              # CLI 진입점
│   ├── worker_api.py        # 배포용 HTTP 워커
│   ├── jobs/                # 키워드·콘텐츠·포스팅·스케줄러
│   ├── scripts/             # 수동 실행용 명령
│   └── shared/              # 로깅·백엔드 클라이언트·사용량
├── config/                  # 예시 설정만, 실제 비밀값은 gitignore
├── deploy/                  # 배포 설정
├── docs/                    # 운영·개발 규칙
├── build.gradle.kts
└── AGENTS.md                # 폴더별 소유 규칙
```

## 설정

- **대시보드**: `config/dashboard.properties` (예시는 `.example`). 배포에서는 같은 값을 환경변수로.
- **워커**: `config/.env` (예시는 `config/env.example`). 스케줄 시간, 콘텐츠 길이, 키워드 필터, Selenium, 재시도 설정이 여기 있습니다.

둘 다 gitignore 대상이며 **실제 키를 커밋하지 않습니다.**

## 주의사항

1. 네이버 자동 포스팅은 계정 정지 위험이 있어 기본으로 꺼져 있습니다(`NAVER_LOGIN_ENABLED=False`).
2. 생성된 콘텐츠는 검토 후 발행하세요. 대본 초안은 항상 검토 대기 상태로만 저장됩니다.
3. 모델·이미지 호출은 실제 비용이 나갑니다. 데모와 주인 양쪽에 하루 상한이 걸려 있습니다.

## 라이선스

개인 사용 목적으로 제작되었습니다.
