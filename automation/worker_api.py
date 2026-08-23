"""Railway에서 실행 버튼과 스케줄러를 함께 제공하는 워커 API입니다."""
import os
import subprocess
import sys
import threading
from typing import Literal

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel
import uvicorn

from automation.jobs.scheduler import Scheduler
from automation.shared.logger import logger

app = FastAPI(title="Backoffice Automation Worker")
_run_lock = threading.Lock()


class RunRequest(BaseModel):
    mode: Literal["keyword", "content", "posting", "all"]


def _start_scheduler() -> None:
    try:
        Scheduler().start()
    except Exception:
        logger.exception("자동화 스케줄러가 종료되었습니다.")


@app.get("/health")
def health() -> dict[str, bool]:
    return {"ok": True}


@app.post("/run")
def run(request: RunRequest, x_worker_api_key: str | None = Header(default=None)) -> dict[str, object]:
    expected = os.getenv("WORKER_API_KEY", "")
    if not expected or x_worker_api_key != expected:
        raise HTTPException(status_code=401, detail="워커 API 키가 올바르지 않습니다.")
    if not _run_lock.acquire(blocking=False):
        raise HTTPException(status_code=409, detail="다른 자동화 작업이 실행 중입니다.")
    try:
        result = subprocess.run(
            [sys.executable, "-m", "automation.main", "--mode", request.mode],
            capture_output=True,
            text=True,
            timeout=int(os.getenv("WORKER_RUN_TIMEOUT_SECONDS", "900")),
            check=False,
        )
        output = (result.stdout or "") + (result.stderr or "")
        return {
            "success": result.returncode == 0,
            "exitCode": result.returncode,
            "output": output[-12000:],
        }
    except subprocess.TimeoutExpired:
        return {"success": False, "exitCode": None, "output": "워커 작업 시간이 초과되었습니다."}
    finally:
        _run_lock.release()


def serve() -> None:
    threading.Thread(target=_start_scheduler, daemon=True, name="scheduler").start()
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8000")))


if __name__ == "__main__":
    serve()

