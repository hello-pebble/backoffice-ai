"""워커가 백엔드로 결과를 넘기는 경로를 검사한다.

DB 직접 쓰기를 없앤 뒤로 저장은 전부 이 클라이언트를 지난다. 인증 헤더가 빠지거나
주소가 틀리면 자동화 결과가 통째로 유실되므로, 요청 모양을 실제 HTTP 왕복으로 확인한다.
표준 라이브러리만 사용한다.
"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

received = []


class Handler(BaseHTTPRequestHandler):
    status = 200
    body = {"id": 7}

    def _respond(self):
        length = int(self.headers.get("Content-Length") or 0)
        received.append({
            "method": self.command,
            "path": self.path,
            "key": self.headers.get("X-Worker-API-Key"),
            "body": json.loads(self.rfile.read(length).decode("utf-8")) if length else None,
        })
        payload = json.dumps(Handler.body).encode("utf-8")
        self.send_response(Handler.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    do_GET = _respond
    do_POST = _respond

    def log_message(self, *args):  # 테스트 출력을 더럽히지 않는다
        pass


@pytest.fixture()
def client():
    received.clear()
    Handler.status, Handler.body = 200, {"id": 7}
    server = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    os.environ["BACKEND_API_URL"] = f"http://127.0.0.1:{server.server_port}"
    os.environ["WORKER_API_KEY"] = "test-worker-key"

    import importlib
    from config import settings
    from automation.shared import backend_client
    importlib.reload(settings)
    importlib.reload(backend_client)

    yield backend_client.BackendClient()
    server.shutdown()


def test_키워드를_워커_경로로_보내고_id_를_받는다(client):
    assert client.save_keyword({"keyword": "백오피스", "search_volume": 100}) == 7

    call = received[0]
    assert call["method"] == "POST"
    assert call["path"] == "/api/worker/keywords"
    # 헤더가 빠지면 백엔드가 401 로 막고 수집 결과가 통째로 사라진다.
    assert call["key"] == "test-worker-key"
    assert call["body"]["keyword"] == "백오피스"


def test_사용_전_키워드는_limit_을_붙여_조회한다(client):
    Handler.body = [{"id": 1, "keyword": "키워드"}]

    assert client.get_unused_keywords(5) == [{"id": 1, "keyword": "키워드"}]
    assert received[0]["path"] == "/api/worker/keywords/unused?limit=5"


def test_콘텐츠와_발행기록은_각자_경로로_간다(client):
    client.save_content({"id": "uuid", "title": "제목"})
    client.save_posting_history({"id": "uuid", "content_id": "uuid", "status": "success"})

    assert [call["path"] for call in received] == ["/api/worker/contents", "/api/worker/posting-records"]


def test_백엔드가_거부하면_사유를_담아_올린다(client):
    Handler.status, Handler.body = 400, {"detail": "콘텐츠 id 가 uuid 형식이 아닙니다"}

    with pytest.raises(RuntimeError) as error:
        client.save_content({"id": "not-a-uuid"})

    # 상태 코드만 남기면 어느 값이 잘못됐는지 알 수 없다.
    assert "400" in str(error.value)
    assert "uuid" in str(error.value)
