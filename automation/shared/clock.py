"""서버가 어떤 타임존에서 실행되든 기록되는 시각은 한국시간으로 고정합니다."""
from datetime import datetime
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")


def now_kst() -> datetime:
    return datetime.now(KST)
