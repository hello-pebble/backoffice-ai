"""구글 트렌드 RSS 파싱과 주제 필터. 네트워크 없이 표본 XML 로 검사한다."""
import json
from automation.jobs.keyword_collector import parse_trends_rss, parse_traffic, matches_topic, parse_trending_batch

SAMPLE = """<?xml version="1.0" encoding="UTF-8"?>
<rss xmlns:ht="https://trends.google.com/trending/rss" version="2.0"><channel>
<item><title>숲</title><ht:approx_traffic>1000+</ht:approx_traffic>
  <ht:news_item><ht:news_item_title>치악산 숲길 질주 산악자전거 페스티벌</ht:news_item_title></ht:news_item></item>
<item><title>갤럭시 S27</title><ht:approx_traffic>20K+</ht:approx_traffic>
  <ht:news_item><ht:news_item_title>삼성, 온디바이스 AI 강화한 갤럭시 S27 공개</ht:news_item_title></ht:news_item></item>
<item><title></title><ht:approx_traffic>5K+</ht:approx_traffic></item>
</channel></rss>"""


def test_트래픽_문자열을_정수로_읽는다():
    assert parse_traffic("1000+") == 1000
    assert parse_traffic("20K+") == 20_000
    assert parse_traffic("1M+") == 1_000_000
    assert parse_traffic("") == 0


def test_RSS_에서_키워드_검색량_관련_뉴스를_뽑는다():
    items = parse_trends_rss(SAMPLE)
    assert [i["keyword"] for i in items] == ["숲", "갤럭시 S27"], "빈 제목은 버린다"
    assert items[1]["searchVolume"] == 20_000
    assert "온디바이스 AI" in items[1]["context"]
    assert items[0]["category"] == "구글 트렌드"


def test_포함어는_관련_뉴스_제목까지_보고_제외어는_버린다():
    items = parse_trends_rss(SAMPLE)
    assert not matches_topic(items[0], include=["ai"], exclude=[])
    assert matches_topic(items[1], include=["ai"], exclude=[]), "키워드엔 없어도 뉴스 제목의 AI 로 통과"
    assert not matches_topic(items[1], include=["ai"], exclude=["갤럭시"])
    assert matches_topic(items[0], include=[], exclude=[]), "포함어가 비면 전부 통과"


# batchexecute 응답 표본: )]}' 접두, 길이 줄, 그 다음 JSON 문자열을 한 번 더 감싼 배열.
BATCH = ")]}'" + chr(10) + "123" + chr(10) + json.dumps([["wrb.fr", "i0OFE", json.dumps([None, [
    ["온디바이스 AI", None, "KR", [1789150200], None, None, 20000, None, 1000, ["온디바이스 AI", "갤럭시 AI"], [18], [], "온디바이스 AI"],
    ["", None, "KR", [1789150200], None, None, 500, None, 1000, [], [4], [], ""],
    ["손흥민", None, "KR", [1789107000], None, None, 100000, None, 1000, None, [20], [], "손흥민"],
]], separators=(",", ":")), None, None, None, "generic"]], separators=(",", ":"))


def test_내부_API_응답에서_검색어_검색량_연관어를_뽑는다():
    items = parse_trending_batch(BATCH)
    assert [i["keyword"] for i in items] == ["온디바이스 AI", "손흥민"], "빈 검색어는 버린다"
    assert items[0]["searchVolume"] == 20000
    assert items[0]["context"] == "갤럭시 AI", "자기 자신은 연관어에서 뺀다"
    assert items[1]["context"] == "", "연관어가 null 이어도 죽지 않는다"
    assert parse_trending_batch("<html>404</html>") == [], "응답 모양이 다르면 빈 목록(RSS 폴백)"
