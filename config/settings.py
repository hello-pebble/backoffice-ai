"""
설정 파일
환경 변수 및 애플리케이션 설정을 관리합니다.
"""
import os
from pathlib import Path
from dotenv import load_dotenv

# 프로젝트 루트 디렉토리
BASE_DIR = Path(__file__).resolve().parent.parent

# .env 파일 로드
env_path = BASE_DIR / 'config' / '.env'
load_dotenv(dotenv_path=env_path)

# API 설정
OPENAI_API_KEY = os.getenv('OPENAI_API_KEY', '')
OPENAI_MODEL = os.getenv('OPENAI_MODEL', 'gpt-3.5-turbo')
INSTAGRAM_TOON_MODEL = os.getenv('INSTAGRAM_TOON_MODEL', 'gpt-5.6-luna')

NAVER_CLIENT_ID = os.getenv('NAVER_CLIENT_ID', '')
NAVER_CLIENT_SECRET = os.getenv('NAVER_CLIENT_SECRET', '')

# 네이버 블로그 계정 정보
NAVER_ID = os.getenv('NAVER_ID', '')
NAVER_PASSWORD = os.getenv('NAVER_PASSWORD', '')
# 네이버 자동 로그인/발행 opt-in 스위치 (기본 비활성화)
NAVER_LOGIN_ENABLED = os.getenv('NAVER_LOGIN_ENABLED', 'False').lower() == 'true'

# 데이터 디렉토리 경로
DATA_DIR = BASE_DIR / 'data'
KEYWORDS_DIR = DATA_DIR / 'keywords'
CONTENTS_DIR = DATA_DIR / 'contents'
INSTAGRAM_TOONS_DIR = DATA_DIR / 'instagram-toons'
LOGS_DIR = DATA_DIR / 'logs'

# 데이터베이스 설정
SUPABASE_PG_DSN = os.getenv('SUPABASE_PG_DSN', os.getenv('SUPABASE_DB_URL', '').removeprefix('jdbc:'))
SUPABASE_DB_USER = os.getenv('SUPABASE_DB_USER', '')
SUPABASE_DB_PASSWORD = os.getenv('SUPABASE_DB_PASSWORD', '')

# 로깅 설정
LOG_LEVEL = os.getenv('LOG_LEVEL', 'INFO')
LOG_FILE = LOGS_DIR / 'backoffice.log'

# 스케줄링 설정
KEYWORD_COLLECTION_TIME = os.getenv('KEYWORD_COLLECTION_TIME', '09:00')
CONTENT_GENERATION_TIME = os.getenv('CONTENT_GENERATION_TIME', '09:30')
POSTING_TIME = os.getenv('POSTING_TIME', '10:00')

# 콘텐츠 생성 설정
MIN_CONTENT_LENGTH = int(os.getenv('MIN_CONTENT_LENGTH', '1500'))
MAX_CONTENT_LENGTH = int(os.getenv('MAX_CONTENT_LENGTH', '3000'))

# 키워드 수집 설정
MIN_SEARCH_VOLUME = int(os.getenv('MIN_SEARCH_VOLUME', '100'))
MAX_KEYWORDS_PER_DAY = int(os.getenv('MAX_KEYWORDS_PER_DAY', '10'))

# Selenium 설정
SELENIUM_HEADLESS = os.getenv('SELENIUM_HEADLESS', 'False').lower() == 'true'
SELENIUM_WAIT_TIME = int(os.getenv('SELENIUM_WAIT_TIME', '10'))

# 재시도 설정
MAX_RETRY_ATTEMPTS = int(os.getenv('MAX_RETRY_ATTEMPTS', '3'))
RETRY_DELAY = int(os.getenv('RETRY_DELAY', '5'))

# 디렉토리 생성
for directory in [DATA_DIR, KEYWORDS_DIR, CONTENTS_DIR, INSTAGRAM_TOONS_DIR, LOGS_DIR]:
    directory.mkdir(parents=True, exist_ok=True)
