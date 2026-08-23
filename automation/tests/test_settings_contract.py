"""automation 패키지가 config.settings 에서 import 하는 이름이 실제로 존재하는지 검사한다.

`SUPABASE_DB_PASSWORD` 누락처럼 compileall 로는 잡히지 않고 실행 시점에 터지는 ImportError 를 막는다.
표준 라이브러리만 사용한다 (ast 로 소스를 훑고, settings 모듈만 실제로 import).
"""
import ast
import importlib
import pathlib

AUTOMATION_DIR = pathlib.Path(__file__).resolve().parent.parent


def imported_settings_names():
    """automation/**/*.py 에서 `from config.settings import X` 로 가져오는 (파일, 이름) 목록."""
    for source in sorted(AUTOMATION_DIR.rglob("*.py")):
        tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module == "config.settings":
                for alias in node.names:
                    yield source, alias.name


def test_settings_exports_every_imported_name():
    available = set(dir(importlib.import_module("config.settings")))
    missing = sorted(
        {(str(source.relative_to(AUTOMATION_DIR.parent)), name)
         for source, name in imported_settings_names()
         if name != "*" and name not in available}
    )
    assert not missing, "config.settings 에 없는 이름을 import 하고 있습니다: %s" % missing


if __name__ == "__main__":
    test_settings_exports_every_imported_name()
    print("ok: config.settings 계약 검사 통과")
