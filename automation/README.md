# Python 자동화

백오피스의 블로그 자동화와 인스타툰 대본 생성을 담당하는 Python 워커입니다. 외부 발행은 검토 승인 전에는 실행하지 않습니다.

## 구성

- `main.py`: CLI 진입점. `--mode {api|scheduler|keyword|content|posting|all}`로 실행
- `worker_api.py`: FastAPI 서버. Railway에서 백엔드(Kotlin `PythonAutomationService`)가 `POST /run`으로 원격 트리거할 때 사용(`X-Worker-Api-Key` 헤더 인증, 동시 실행은 락으로 방지)
- `jobs/`: 키워드 수집(`keyword_collector.py`), 콘텐츠 생성(`content_generator.py`), 포스팅(`blog_poster.py`), 스케줄링(`scheduler.py`), 인스타툰 생성(`instagram_toon/generator.py`)
- `scripts/run_instagram_toon.py`: 인스타툰 생성 수동 실행 CLI
- `shared/`: 화면·특정 업무에 종속되지 않는 재사용 유틸 — `logger.py`, `backend_client.py`(백엔드 API 호출), `usage.py`(토큰 사용량 집계)
- `tests/`: `backend_client`, `settings_contract`, `usage` 단위 테스트
- `requirements.txt`: Python 의존성

결과와 상태는 화면이 아니라 API/DB로 전달합니다. 예를 들어 `content` 모드는 토큰 사용량 한 줄을 stdout으로만 출력하고, 백오피스가 이 줄을 파싱해 읽습니다.

## 설치

프로젝트 루트에서 가상 환경을 만들고 의존성을 설치합니다.

```powershell
python -m venv venv
venv\Scripts\Activate.ps1
pip install -r automation\requirements.txt
Copy-Item config\env.example config\.env
```

`config/.env`에는 필요한 API 키와 계정 정보를 설정합니다. 이 파일은 저장소에 포함하지 않습니다.

## 실행

프로젝트 루트에서 모듈 방식으로 실행합니다.

```powershell
python -m automation.main --mode scheduler
python -m automation.main --mode keyword
python -m automation.main --mode content
python -m automation.main --mode posting
python -m automation.main --mode all
```

인스타툰 대본 명령은 다음과 같습니다.

```powershell
python -m automation.scripts.run_instagram_toon --id example-id --episode "오늘 있었던 일을 기록합니다." --tone 공감형 --panels 4
```
