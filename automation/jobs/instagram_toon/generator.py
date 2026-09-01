"""짧은 에피소드를 인스타툰 대본으로 바꾸는 자동화 모듈입니다."""
import json
from datetime import datetime
from pathlib import Path
from typing import Any

from openai import OpenAI

from automation.shared.usage import UsageTracker
from config.settings import INSTAGRAM_TOON_MODEL, INSTAGRAM_TOONS_DIR, OPENAI_API_KEY, OPENAI_BASE_URL


class InstagramToonGenerator:
    """대본·컷 구성·게시 캡션을 한 번에 생성합니다."""

    def __init__(self) -> None:
        self.client = OpenAI(api_key=OPENAI_API_KEY, base_url=OPENAI_BASE_URL or None) if OPENAI_API_KEY else None
        self.usage = UsageTracker(INSTAGRAM_TOON_MODEL)

    def generate(self, episode: str, tone: str, panel_count: int, toon_id: str) -> dict[str, Any]:
        if not self.client:
            raise RuntimeError("OPENAI_API_KEY가 설정되지 않았습니다.")
        if panel_count not in (4, 8):
            raise ValueError("컷 수는 4 또는 8만 가능합니다.")

        prompt = f"""
짧은 에피소드를 한국어 인스타그램 웹툰 제작용 대본으로 구성하세요.

에피소드: {episode}
톤: {tone}
컷 수: {panel_count}

반드시 JSON 객체만 반환하세요. 마크다운은 사용하지 마세요.
스키마:
{{
  "title": "15자 안팎의 제목",
  "caption": "인스타그램 게시글 캡션 (줄바꿈 포함 가능)",
  "hashtags": ["#태그"],
  "panels": [
    {{"number": 1, "scene": "장면/표정/구도", "dialogue": "말풍선 대사", "narration": "필요한 독백 또는 효과음", "image_prompt": "일관된 캐릭터가 유지되는 한국 웹툰 스타일의 영어 이미지 프롬프트"}}
  ]
}}

규칙:
- panels 배열은 정확히 {panel_count}개입니다.
- 일상 공감형 서사이며, 마지막 컷에 명확한 여운 또는 반전을 둡니다.
- 대사는 짧고 읽기 쉽게 작성합니다.
- hashtags는 8~12개입니다.
"""
        response = self.usage.add(self.client.chat.completions.create(
            model=INSTAGRAM_TOON_MODEL,
            messages=[
                {"role": "system", "content": "당신은 인스타그램 웹툰 전문 작가이자 스토리보드 작가입니다."},
                {"role": "user", "content": prompt},
            ],
            response_format={"type": "json_object"},
            temperature=0.8,
        ))
        raw = response.choices[0].message.content
        if not raw:
            raise RuntimeError("모델이 대본을 반환하지 않았습니다.")
        result = json.loads(raw)
        panels = result.get("panels", [])
        if len(panels) != panel_count:
            raise RuntimeError(f"요청한 {panel_count}컷과 다른 결과가 반환되었습니다.")

        toon = {
            "id": toon_id,
            "episode": episode,
            "tone": tone,
            "panel_count": panel_count,
            "title": result.get("title", "제목 없음"),
            "caption": result.get("caption", ""),
            "hashtags": result.get("hashtags", []),
            "panels": panels,
            "created_at": datetime.now().isoformat(),
            "model": INSTAGRAM_TOON_MODEL,
            "usage": self.usage.as_dict(),
        }
        self.save(toon)
        return toon

    @staticmethod
    def save(toon: dict[str, Any]) -> Path:
        INSTAGRAM_TOONS_DIR.mkdir(parents=True, exist_ok=True)
        path = INSTAGRAM_TOONS_DIR / f"{toon['id']}.json"
        path.write_text(json.dumps(toon, ensure_ascii=False, indent=2), encoding="utf-8")
        return path

