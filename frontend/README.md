# Frontend

정적 파일로만 구성된 화면입니다. 빌드 도구나 프레임워크 없이 순수 HTML/CSS/JS로 작성되어 있고, 모든 API는 `backend/`(Kotlin) 서버가 제공합니다. 이 폴더에는 비밀값이나 업무 규칙을 두지 않습니다.

## 구성

- `static/index.html`: 대시보드 화면 뼈대. Google 로그인 게이트, 사이드바 내비게이션(AI 운영 센터, 국내 관심 종목, 메일, 최신 소식, Slack 연결, 주제 대본 초안, 콘텐츠 생성 에이전트)
- `static/app.js`: 화면 로직 전체. `/api/*`를 호출해 데이터를 그려주는 순수 함수 위주(빌드 없이 바로 브라우저에서 실행)
  - 세션은 HttpOnly 쿠키로 관리하고, 로그인 여부만 표시용 쿠키(`office_session_hint`)로 즉시 판단해 로그인 카드 깜빡임을 방지
  - `renderPaged`가 목록형 UI(뉴스, AI 운영 이력, 콘텐츠 패키지, 주제 대본 등)의 페이지네이션을 공통 처리
  - Slack 연결/채널 선택, 콘텐츠 패키지 생성, 주제 대본 새로고침·Slack 알림 재시도, 자동화 워커 수동 실행 등 사용자 액션을 fetch로 호출
- `static/styles.css`, `static/toon.css`: 스타일시트
- `static/favicon.svg`: 파비콘
- `static/vercel.json`: 정적 배포 설정

## 규칙 (AGENTS.md)

- API 응답을 표시만 하고, API 계약 변경은 backend 담당과 먼저 맞춘다.
- 로딩·빈 상태·실패 상태를 모두 사용자 문구로 표시한다.
- API 키, OAuth 토큰, DB 접속 정보를 넣지 않는다.
