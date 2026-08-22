"""Kotlin 백엔드가 호출하는 인스타툰 대본 생성 CLI입니다."""
import argparse
import json

from modules.instagram_toon_generator import InstagramToonGenerator


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--id", required=True)
    parser.add_argument("--episode", required=True)
    parser.add_argument("--tone", default="공감형")
    parser.add_argument("--panels", type=int, choices=[4, 8], default=4)
    args = parser.parse_args()
    toon = InstagramToonGenerator().generate(args.episode, args.tone, args.panels, args.id)
    print(json.dumps(toon, ensure_ascii=False))


if __name__ == "__main__":
    main()
