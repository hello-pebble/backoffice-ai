"""LLM 호출의 토큰 사용량을 모아 백오피스(Kotlin)로 넘깁니다.

워커는 로컬 실행이든 Railway 워커 API든 stdout 문자열 하나만 돌려주므로,
마지막에 ``AI_USAGE {...}`` 한 줄을 찍어 사용량을 전달합니다.
표준 라이브러리만 사용합니다.
"""
import json

MARKER = "AI_USAGE "


class UsageTracker:
    """한 번의 워커 실행에서 일어난 모든 LLM 호출의 토큰을 더합니다."""

    def __init__(self, model: str = "") -> None:
        self.model = model
        self.input_tokens = 0
        self.output_tokens = 0
        self.calls = 0

    def add(self, response):
        """OpenAI 호환 응답을 더하고 그대로 돌려줍니다.

        usage 를 주지 않는 호환 제공자도 있어, 없으면 호출 횟수만 셉니다.
        토큰을 못 받은 실행이 비용 0으로 보이는 건 어쩔 수 없지만 호출 수로 구분은 됩니다.
        """
        usage = getattr(response, "usage", None)
        self.input_tokens += int(getattr(usage, "prompt_tokens", 0) or 0)
        self.output_tokens += int(getattr(usage, "completion_tokens", 0) or 0)
        self.calls += 1
        return response

    def as_dict(self) -> dict:
        return {
            "model": self.model,
            "input_tokens": self.input_tokens,
            "output_tokens": self.output_tokens,
            "calls": self.calls,
        }

    def line(self) -> str:
        """백오피스가 stdout 에서 찾아 읽는 한 줄."""
        return MARKER + json.dumps(self.as_dict(), ensure_ascii=False)
