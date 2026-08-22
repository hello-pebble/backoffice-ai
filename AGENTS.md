# Backoffice AI 작업 안내

이 문서는 이 저장소에서 작업하는 사람과 AI 에이전트가 가장 먼저 읽는 안내문입니다. 상세 배포·브랜치 규칙은 [`docs/BRANCH_STRATEGY.md`](docs/BRANCH_STRATEGY.md)를 따릅니다.

## 제품과 배포 구조

- 제품: 업무 대시보드, AI 운영 센터, 콘텐츠 스튜디오, 블로그 자동화
- 화면: `frontend/static/` — Vercel 배포 대상
- API: `backend/` — Kotlin/Spring Boot, Cloud Run 배포 대상
- 자동화: `automation/` — Python 워커 배포 대상
- 운영 데이터: PostgreSQL로 이전 예정. 현재 `data/` JSON은 로컬 개발 데이터다.

## 작업 원칙

1. 한 작업은 한 목적의 브랜치에서만 수행한다.
2. 화면·API·자동화 중 영향을 받는 영역을 먼저 확인하고, 필요한 부분만 수정한다.
3. API 키, OAuth 토큰, 개인 데이터, `data/`, `venv/`는 커밋하지 않는다.
4. 새 AI 기능은 실행 결과뿐 아니라 모델·토큰·비용·사용 도구를 AI 운영 센터에 기록한다.
5. 자동 발행·삭제·외부 전송은 기본값을 검토 대기 상태로 둔다.
6. 변경 뒤에는 해당 영역의 가장 작은 검증을 실행한다. 예: Kotlin 변경은 `:backend:compileKotlin`.

## 데이터베이스 명명 규칙

- 테이블명은 단수형 영문 소문자(`task`, `approval`)로 만든다.
- PostgreSQL 예약어와 예약어로 오인하기 쉬운 일반 용어(`user`, `order`, `group`, `select` 등)는 테이블·컬럼명으로 사용하지 않는다.
- 기본 키는 사람이 확인하기 쉬운 숫자형 ID를 우선 사용한다. 외부 시스템 식별자가 필요하면 별도 컬럼으로 분리한다.
- 이미 운영에 반영된 테이블명·키 형식 변경은 기존 행을 보존하는 Flyway 마이그레이션으로만 처리한다.

## 브랜치와 PR

- `main`: 운영 배포 기준. 직접 푸시 금지.
- `develop`: 통합·스테이징 기준.
- 작업 브랜치: `feature/`, `fix/`, `chore/`, `docs/` 중 하나로 시작한다.
- 모든 변경은 PR로 `develop`에 합치고, 운영 반영은 `develop`에서 `main`으로 PR을 연다.
- 긴급 수정은 `hotfix/`로 `main`에서 시작하고, 배포 후 반드시 `develop`에도 반영한다.

## 확인할 문서

- [브랜치 및 배포 전략](docs/BRANCH_STRATEGY.md)
- [프로젝트 개요](README.md)
- [Kotlin 대시보드 설정](backend/README.md)
