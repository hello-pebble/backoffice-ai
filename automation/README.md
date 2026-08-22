# Python 자동화

백오피스의 블로그 자동화와 인스타툰 대본 생성을 담당하는 Python 워커입니다. 외부 발행은 검토 승인 전에는 실행하지 않습니다.

## 구성

- `main.py`: 블로그 자동화 워커 시작점
- `jobs/`: 키워드 수집, 콘텐츠 생성, 포스팅, 스케줄링과 인스타툰 생성 작업
- `scripts/`: 수동 실행용 명령
- `shared/`: 재사용 가능한 로깅과 데이터베이스 도구
- `requirements.txt`: Python 의존성

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
