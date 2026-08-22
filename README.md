# 자동포스팅 백오피스 시스템

> 개인 업무용 웹 대시보드는 [`backend/`](backend/) 서버와 [`frontend/`](frontend/) 화면으로 나뉘어 있습니다.

## Office Dashboard (Kotlin 웹 대시보드)

- Gmail: 읽지 않은 메일 수 및 최근 받은 메일 요약
- 토스증권 Open API: 국내 관심 종목의 현재가
- 실행과 연결 설정: [`backend/README.md`](backend/README.md)

블로그 자동화 코드와 데이터 모델은 Python으로 유지하며, Kotlin 대시보드가 필요할 때만 안전하게 호출합니다.

매일 자동으로 네이버 블로그에 검색량이 높은 키워드 기반의 콘텐츠를 생성하여 포스팅하는 백오피스 시스템입니다.

## 주요 기능

- **키워드 수집**: 네이버 검색어 트렌드 및 인기 키워드 자동 수집
- **콘텐츠 생성**: OpenAI API를 활용한 AI 기반 블로그 글 자동 생성
- **자동 포스팅**: 네이버 블로그에 자동으로 포스팅
- **스케줄링**: 매일 자동 실행 스케줄 관리

## 시스템 요구사항

- Python 3.11 이상
- Windows 10/11
- Chrome 브라우저 (Selenium용)

## 프로젝트 구조

```text
backoffice-ai/
├── backend/                 # Kotlin · Spring API
├── frontend/                # Vercel 화면
├── automation/              # Python 자동화 전체
│   ├── main.py              # 워커 시작점
│   ├── requirements.txt     # Python 의존성
│   ├── jobs/                # 실제 자동화 기능
│   │   └── instagram_toon/
│   ├── scripts/             # 수동 실행용 명령
│   │   └── run_instagram_toon.py
│   ├── shared/              # 공통 유틸·API 클라이언트
│   └── README.md            # 실행 방법·환경변수 설명
├── config/                  # 예시 설정만, 실제 비밀값 제외
├── docs/                    # 운영·개발 규칙
├── .github/                 # Actions, PR·Issue 템플릿
├── build.gradle.kts         # Kotlin 전체 빌드 설정
├── settings.gradle.kts
├── gradlew
├── gradlew.bat
├── README.md
└── AGENTS.md
```

자동화 워커 설치와 실행 방법은 [`automation/README.md`](automation/README.md)를 참조하세요.
## 설정 옵션

`config\.env` 파일에서 다음 설정을 변경할 수 있습니다:

- **스케줄링 시간**: `KEYWORD_COLLECTION_TIME`, `CONTENT_GENERATION_TIME`, `POSTING_TIME`
- **콘텐츠 길이**: `MIN_CONTENT_LENGTH`, `MAX_CONTENT_LENGTH`
- **키워드 필터**: `MIN_SEARCH_VOLUME`, `MAX_KEYWORDS_PER_DAY`
- **Selenium 설정**: `SELENIUM_HEADLESS`, `SELENIUM_WAIT_TIME`
- **재시도 설정**: `MAX_RETRY_ATTEMPTS`, `RETRY_DELAY`

## 주의사항

1. **API 키 보안**: `.env` 파일은 절대 공개 저장소에 커밋하지 마세요.
2. **네이버 계정**: 자동 포스팅으로 인한 계정 정지 위험이 있으니 주의하세요.
3. **포스팅 빈도**: 과도한 포스팅은 계정 정지로 이어질 수 있습니다.
4. **콘텐츠 품질**: 생성된 콘텐츠는 반드시 검토 후 포스팅하세요.

## 라이선스

이 프로젝트는 개인 사용 목적으로 제작되었습니다.

## 문의

문제가 발생하거나 개선 사항이 있으면 이슈를 등록해주세요.
