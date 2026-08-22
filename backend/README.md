# Kotlin Office Dashboard

Kotlin/Spring Boot 웹 서버입니다. Python 자동화 코드는 [`automation/`](../automation/)에 분리되어 있으며, 이 서버가 `POST /api/automation/{keyword|content|posting|all}` 요청을 받을 때만 실행합니다.

## 포함 기능

- CEO 경영 현황: 매출, 비용, 목표 달성률, 확인 필요 항목
- 팀 업무: 업무 등록과 완료 상태 변경
- 승인 센터: 비용·휴가 요청의 승인 또는 반려
- 자동화: Python 블로그 자동화 실행 및 최근 실행 이력 저장
- 외부 정보: Gmail 읽기 전용 요약과 토스증권 국내 관심 종목 현재가
- 인스타툰 대본 생성: 에피소드에서 4·8컷 대본, 컷별 이미지 프롬프트, 캡션 초안 생성

KPI·업무·승인·실행 이력은 `data/office-dashboard/operations.json`에 로컬 저장됩니다. 처음 생성되는 값은 화면 사용 방법을 보여주는 예시 데이터입니다.

인스타툰 결과는 `data/instagram-toons/`에 JSON으로 저장됩니다. `config/.env`에 `OPENAI_API_KEY`와 `INSTAGRAM_TOON_MODEL=gpt-5.6-luna`를 설정하세요. Luna는 API 무료 티어에서 지원되지 않으므로 API 결제 계정이 필요합니다.

## 실행

```powershell
Copy-Item config\dashboard.properties.example config\dashboard.properties
.\gradlew.bat :backend:bootRun
```

브라우저에서 `http://127.0.0.1:8765`을 엽니다. 화면 자산은 `frontend/static/`에 두며, 웹 서버와 모든 API는 Kotlin이 제공합니다. 해당 폴더에는 Python 실행 코드가 없습니다.

## 연동 설정

- Gmail OAuth 클라이언트 JSON을 `data/office-dashboard/gmail-credentials.json`에 둡니다. 서버 실행 뒤 대시보드의 **Gmail 연결하기**를 누르면 Kotlin 서버가 읽기 전용 OAuth를 처리하고 토큰을 저장합니다. Google Cloud OAuth 리디렉션 URI에 `http://127.0.0.1:8765/api/gmail/callback`을 등록하세요.
- 토스증권 키와 관심 종목은 `config/dashboard.properties`에 둡니다.
- 기본 바인딩은 `127.0.0.1`입니다. 외부 공개 전에는 로그인과 HTTPS를 추가해야 합니다.
