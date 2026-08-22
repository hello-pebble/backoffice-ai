# 브랜치 및 배포 전략

## 목표

이 전략은 여러 사람 또는 AI 에이전트가 동시에 작업해도 운영 화면, API, 자동화가 서로 영향을 주지 않도록 만든다. 배포는 다음 구조를 기준으로 한다.

```text
Vercel (frontend/static) → Cloud Run (backend) → PostgreSQL / Blob Storage
                                      ↓
                           Python 자동화 워커 / Ollama
```

## 브랜치 역할

| 브랜치 | 목적 | 배포 환경 | 직접 푸시 |
|---|---|---|---|
| `main` | 검증된 운영 코드 | Vercel 운영, Cloud Run 운영 | 금지 |
| `develop` | 기능 통합 및 사전 검증 | Vercel 스테이징, Cloud Run 스테이징 | 금지 |
| `feature/<area>-<summary>` | 신규 기능 | PR 미리보기 | 허용 |
| `fix/<area>-<summary>` | 일반 버그 수정 | PR 미리보기 | 허용 |
| `hotfix/<summary>` | 운영 긴급 수정 | 운영 우선 | 허용 |
| `chore/<summary>` | 설정·의존성·정리 | PR 미리보기 | 허용 |
| `docs/<summary>` | 문서만 변경 | PR 미리보기 | 허용 |

`<area>`는 `frontend`, `backend`, `automation`, `ai`, `infra`, `docs` 중 하나를 사용한다.

예시:

```text
feature/ai-content-package
fix/frontend-task-status
chore/infra-vercel-config
docs/branch-strategy
```

## 일반 작업 흐름

```text
develop
  └─ 작업 브랜치 생성
       └─ 구현 및 검증
            └─ PR → develop
                 └─ 스테이징 확인
                      └─ PR → main
                           └─ 운영 배포 및 태그
```

1. 새 작업은 최신 `develop`에서 시작한다.
2. 작업 범위를 벗어난 파일은 수정하지 않는다.
3. PR에는 변경 목적, 영향 영역, 검증 결과, 설정 변경 여부를 적는다.
4. `develop` 병합 후 스테이징에서 화면·API 연결을 확인한다.
5. 운영 배포는 `develop`에서 `main`으로 만든 PR만 허용한다.
6. `main` 병합 후 `vMAJOR.MINOR.PATCH` 형식의 태그를 남긴다.

## 긴급 수정

```text
main → hotfix/<summary> → PR → main → 운영 배포
                                  └→ develop 반영
```

운영 장애·보안 문제만 `hotfix/`를 사용한다. 배포 후 같은 수정이 `develop`에 누락되지 않도록 즉시 반영한다.

## 배포 규칙

### Vercel

- `main` 병합: 운영 화면 배포
- `develop` 병합: 스테이징 화면 배포
- 작업 PR: 독립 미리보기 주소 생성
- 브라우저에 노출되는 환경 변수는 `NEXT_PUBLIC_` 접두어만 사용한다.

### Kotlin API와 Python 워커

- `main` 병합: 운영 Cloud Run/워커 이미지 배포
- `develop` 병합: 스테이징 이미지 배포
- Vercel 배포 성공만으로 API·자동화 배포 완료로 판단하지 않는다.
- 영상 렌더링, AI 생성, 외부 발행처럼 오래 걸리는 작업은 웹 요청이 아닌 워커에서 처리한다.

## AI 기능 추가 규칙

새 에이전트는 아래 항목을 반드시 남긴다.

- 에이전트 이름과 실행 시각
- 모델 또는 템플릿 이름
- 입력·출력 토큰, 예상 비용
- 호출한 도구와 외부 API
- 결과 요약, 실패 사유, 재실행 가능 여부

이는 `AI 운영 센터`에서 확인할 수 있어야 한다.

## 저장소에 넣지 않는 것

다음 항목은 `.gitignore`로 유지하고 PR에도 포함하지 않는다.

- API 키, 비밀번호, OAuth client secret, access token
- `config/dashboard.properties`, `config/.env`
- `data/`의 개인 메일·AI 결과·운영 이력
- `venv/`, `.gradle/`, `build/`
- 생성된 영상·음성·대용량 이미지

환경 변수 이름과 예제 값은 `*.example` 파일에만 남긴다.

## PR 최소 점검표

- [ ] 작업 브랜치 이름이 규칙에 맞는다.
- [ ] 비밀값·개인 데이터가 포함되지 않았다.
- [ ] 화면 변경이면 Vercel 미리보기에서 확인했다.
- [ ] Kotlin 변경이면 컴파일 검증을 통과했다.
- [ ] 자동화 변경이면 실제 외부 발행 없이 안전한 범위에서 확인했다.
- [ ] AI 기능이면 AI 운영 센터 기록이 남는다.
- [ ] 배포 환경 변수 또는 DB 변경 사항을 PR에 적었다.
