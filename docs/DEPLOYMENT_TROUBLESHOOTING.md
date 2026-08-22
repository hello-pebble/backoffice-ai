# Git 충돌과 Railway·Supabase 배포 문제 해결 기록

이 문서는 `backoffice-ai`의 `develop → main` 배포 과정에서 실제 발생한 충돌과 오류를 기록합니다. 다음 배포 때 같은 문제를 빠르게 확인하고 재발을 막는 것이 목적입니다.

## 1. Git PR 충돌

### 문제 상황

`develop`을 `main`에 병합하는 PR에서 아래 파일이 충돌했습니다.

- `.github/workflows/ci.yml`
- `automation/jobs/blog_poster.py`
- `automation/jobs/content_generator.py`
- `automation/jobs/keyword_collector.py`
- `automation/requirements.txt`

### 원인

`main`에는 자동화 폴더 구조 변경이 먼저 반영됐고, `develop`에는 같은 변경과 PostgreSQL 전환 작업이 다른 이력으로 들어갔습니다. 특히 통합 PR을 **Squash and merge**로 병합하면 실제 병합 부모 이력이 남지 않습니다. Git은 두 브랜치가 같은 변경을 이미 공유한다고 판단하지 못해, 동일 파일을 각각 새로 추가·수정한 것으로 보고 충돌을 만들었습니다.

### 해결 방법

1. `develop`에서 `fix/` 통합 브랜치를 만듭니다.
2. 그 브랜치에 `main`을 병합합니다.
3. 충돌 파일에서는 최신 `develop` 내용을 유지합니다.
   - Python 작업은 `automation.shared.postgres_database`를 사용합니다.
   - `automation/requirements.txt`에는 `psycopg[binary]`를 유지합니다.
   - CI는 `python -m compileall -q automation`을 사용합니다.
4. 이전 폴더 구조의 중복 안내 파일은 제거합니다.
5. Kotlin 컴파일과 Python 문법 검사를 실행합니다.
6. 통합 브랜치를 `develop`에 **Create a merge commit** 방식으로 병합합니다.
7. 이후 `develop → main` PR을 만들고, 역시 **Create a merge commit**으로 병합합니다.

### 재발 방지

- 모든 기능과 배포 수정은 `feature/`, `fix/`, `chore/`, `docs/` 브랜치에서 시작합니다.
- 작업 브랜치는 먼저 `develop`에 병합하고, 운영 반영은 `develop → main` PR로만 진행합니다.
- `develop → main` 통합 PR에는 **Create a merge commit**을 사용합니다. 이 PR에서 `Squash and merge`를 사용하면 공통 이력이 사라져 같은 충돌이 재발할 수 있습니다.
- 폴더 이동·대규모 정리는 한 브랜치에서 한 번만 수행하고, 같은 파일 이동을 다른 브랜치에서 다시 수행하지 않습니다.
- PR 설명에는 목적, 영향 범위, 검증 결과를 작성합니다.

## 2. Railway Docker 빌드 실패

### 문제 상황

Railway 이미지 빌드 중 아래 오류가 발생했습니다.

```text
Unable to access jarfile /workspace/gradle/wrapper/gradle-wrapper.jar
```

### 원인

`Dockerfile.backend`에서 `gradle/` 폴더를 다른 파일과 함께 복사하면서, Docker 내부 경로가 `/workspace/gradle/...`가 아닌 다른 위치가 됐습니다. Gradle 실행 스크립트는 `/workspace/gradle/wrapper/gradle-wrapper.jar`를 찾기 때문에 빌드가 실패했습니다.

### 해결 방법

Gradle 실행 스크립트와 `gradle/` 폴더를 분리해, 폴더 경로를 명확히 유지하도록 수정했습니다.

```dockerfile
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ ./gradle/
```

### 재발 방지

- Dockerfile에서 디렉터리를 복사할 때는 목적지 경로를 명시합니다.
- Docker 배포 전 `./gradlew :backend:bootJar`로 JAR 생성 여부를 확인합니다.
- 최초 빌드는 Java 이미지와 Gradle 의존성을 내려받아 오래 걸릴 수 있습니다. 소스만 바꾼 후속 빌드는 Docker 레이어 캐시를 사용하므로 더 빨라집니다.

## 3. Flyway가 PostgreSQL 17을 지원하지 않는 오류

### 문제 상황

애플리케이션 시작 중 아래 오류가 발생했습니다.

```text
Unsupported Database: PostgreSQL 17.6
```

### 원인

Flyway 11부터 PostgreSQL 지원 모듈이 분리됐습니다. `flyway-core`만 추가돼 있어 Supabase의 PostgreSQL 17을 인식하지 못했습니다.

### 해결 방법

`backend/build.gradle.kts`에 PostgreSQL용 Flyway 모듈을 추가했습니다.

```kotlin
implementation("org.flywaydb:flyway-database-postgresql")
```

### 재발 방지

- Flyway를 새 버전으로 올릴 때 데이터베이스별 모듈이 필요한지 확인합니다.
- 운영 DB와 같은 주요 버전에서 마이그레이션 시작 로그를 확인합니다.

## 4. Flyway 기준점(history) 테이블 오류

### 문제 상황

Supabase 연결 후 아래 오류가 발생했습니다.

```text
Found non-empty schema(s) "public" but no schema history table.
```

### 원인

Supabase의 `public` 스키마에는 서비스가 만들기 전부터 시스템용 항목이 있을 수 있습니다. Flyway는 스키마가 비어 있지 않은데 자신의 이력 테이블이 없으면 기존 데이터에 임의로 마이그레이션을 적용하지 않기 위해 시작을 중단합니다.

### 해결 방법

Railway Variables에 아래 값을 추가합니다.

```text
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
SPRING_FLYWAY_BASELINE_VERSION=0
```

`0`을 기준점으로 기록하면 V1, V2, V3 마이그레이션은 그대로 실행되어 애플리케이션 테이블을 생성합니다. 기존 Supabase 시스템 항목을 삭제하지 않습니다.

### 재발 방지

- 새 Supabase 프로젝트의 첫 배포 전에 Flyway 기준점 설정을 확인합니다.
- `baseline-version`을 V1보다 낮은 `0`으로 둡니다. V1로 기준점을 잡으면 V1 마이그레이션이 건너뛰어질 수 있습니다.
- 운영 DB에서 테이블을 임의로 삭제하거나 Flyway 이력 테이블을 수동으로 수정하지 않습니다.

## 배포 확인 순서

1. Railway Variables에 DB 연결값, `SPRING_PROFILES_ACTIVE=oci`, Flyway 기준점 값을 저장합니다. 비밀번호는 문서·Git·채팅에 남기지 않습니다.
2. Railway에서 최신 `main`을 배포합니다.
3. Deployment 상태가 `Active`인지, 로그에 Flyway 마이그레이션 완료가 보이는지 확인합니다.
4. Networking에서 포트 `8080`으로 공개 도메인을 생성합니다.
5. `https://생성된-도메인/api/health`에 접속해 정상 응답을 확인합니다.
6. 이후 Vercel 프런트엔드의 API 경로를 Railway 도메인으로 연결하고 Task 생성·상태 변경·삭제를 확인합니다.
