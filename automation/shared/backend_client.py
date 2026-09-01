"""자동화 결과를 백엔드로 넘긴다. 워커는 DB 에 직접 쓰지 않는다.

스키마를 아는 곳을 백엔드 하나로 모으기 위해서다. 예전에는 마이그레이션이 바뀌면
Kotlin 과 Python 양쪽의 SQL 을 같이 고쳐야 했고, 한쪽만 고치면 배포 후에야 터졌다.

기존 Database 와 메서드 이름·인자를 그대로 맞춰서 부르는 쪽은 바뀌지 않는다.
표준 라이브러리만 쓴다(requests 는 워커에 이미 있지만 여기서는 필요 없다).
"""
import json
import urllib.error
import urllib.request
from typing import Any, Dict, List

from config.settings import BACKEND_API_URL, WORKER_API_KEY
from automation.shared.logger import logger

TIMEOUT_SECONDS = 20


class BackendClient:
    """백엔드의 워커 전용 API 를 부른다."""

    def __init__(self) -> None:
        if not BACKEND_API_URL:
            raise RuntimeError("BACKEND_API_URL 을 설정하세요. 워커는 백엔드를 통해 저장합니다.")
        if not WORKER_API_KEY:
            raise RuntimeError("WORKER_API_KEY 를 설정하세요. 백엔드가 워커 요청을 거부합니다.")
        self.base_url = BACKEND_API_URL.rstrip("/")

    def save_keyword(self, data: Dict[str, Any]) -> int:
        return self._request("POST", "/api/worker/keywords", data)["id"]

    def get_unused_keywords(self, limit: int = 10) -> List[Dict[str, Any]]:
        return self._request("GET", f"/api/worker/keywords/unused?limit={int(limit)}")

    def save_content(self, data: Dict[str, Any]) -> bool:
        self._request("POST", "/api/worker/contents", data)
        return True

    def save_posting_history(self, data: Dict[str, Any]) -> bool:
        self._request("POST", "/api/worker/posting-records", data)
        return True

    def _request(self, method: str, path: str, body: Dict[str, Any] | None = None):
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            method=method,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "X-Worker-API-Key": WORKER_API_KEY,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                payload = response.read().decode("utf-8")
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            # 백엔드는 사유를 {"detail": "..."} 로 준다. 상태 코드만 남기면 원인을 알 수 없다.
            detail = error.read().decode("utf-8", errors="replace")[:300]
            logger.error("백엔드 저장 실패 %s %s → %s %s", method, path, error.code, detail)
            raise RuntimeError(f"백엔드 저장 실패({error.code}): {detail}") from error
        except urllib.error.URLError as error:
            logger.error("백엔드에 연결하지 못했습니다 %s%s: %s", self.base_url, path, error.reason)
            raise RuntimeError(f"백엔드에 연결하지 못했습니다: {error.reason}") from error


Database = BackendClient
