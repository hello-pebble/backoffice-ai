"""구글 트렌드 RSS 파싱과 주제 필터. 네트워크 없이 표본 XML 로 검사한다."""
from automation.jobs.keyword_collector import parse_trends_rss, parse_traffic, matches_topic

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
