"""UsageTracker 가 백오피스(Kotlin)와 약속한 형식대로 사용량을 넘기는지 검사한다.

표준 라이브러리만 사용한다. 여기 형식이 바뀌면
backend PythonAutomationService.USAGE_MARKER 와 WorkerTokenUsage 도 함께 바꿔야 한다.
"""
import json

from automation.shared.usage import MARKER, UsageTracker


class FakeUsage:
    def __init__(self, prompt_tokens, completion_tokens):
        self.prompt_tokens = prompt_tokens
        self.completion_tokens = completion_tokens


class FakeResponse:
    def __init__(self, usage=None):
        self.usage = usage


def test_여러_호출의_토큰을_더한다():
    tracker = UsageTracker("gpt-3.5-turbo")

    tracker.add(FakeResponse(FakeUsage(100, 200)))
    tracker.add(FakeResponse(FakeUsage(50, 30)))

    assert tracker.as_dict() == {
        "model": "gpt-3.5-turbo",
        "input_tokens": 150,
        "output_tokens": 230,
        "calls": 2,
    }


def test_usage_를_주지_않는_제공자면_호출_수만_센다():
    tracker = UsageTracker("compat-model")

    tracker.add(FakeResponse(usage=None))

    assert tracker.as_dict()["calls"] == 1
    assert tracker.as_dict()["input_tokens"] == 0


def test_add_는_응답을_그대로_돌려준다():
    tracker = UsageTracker("m")
    response = FakeResponse(FakeUsage(1, 2))

    assert tracker.add(response) is response


def test_출력_한_줄은_표시와_JSON_으로_이루어진다():
    tracker = UsageTracker("m")
    tracker.add(FakeResponse(FakeUsage(1, 2)))

    line = tracker.line()

    assert line.startswith(MARKER)
    assert "\n" not in line, "여러 줄이면 백오피스가 마지막 줄만 읽어 값을 잃는다"
    assert json.loads(line[len(MARKER):]) == tracker.as_dict()
