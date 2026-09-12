"""
키워드 수집 모듈
구글 트렌드 일간 RSS 를 수집한다. 네이버 트렌드·데이터랩은 아직 TODO 라 빈 목록이다.
"""
import json
import re
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from typing import List, Dict, Any
from config.settings import (
    NAVER_CLIENT_ID, NAVER_CLIENT_SECRET, MIN_SEARCH_VOLUME,
    GOOGLE_TRENDS_GEO, GOOGLE_TRENDS_HOURS, KEYWORD_INCLUDE, KEYWORD_EXCLUDE,
)
from automation.shared.clock import now_kst
from automation.shared.logger import logger
from automation.shared.backend_client import Database

TRENDS_NS = {"ht": "https://trends.google.com/trending/rss"}
# 백엔드 점수식은 priority + 검색량/1000(최대 5). 트렌드는 검색량이 커서 priority 없이도 소식과 겨룰 수 있지만,
# "1000+" 처럼 하한만 오므로 기본 priority 를 조금 얹는다. ponytail: 3 은 임의값, 실데이터로 조정.
TRENDS_PRIORITY = 3


def parse_traffic(text: str) -> int:
    """'1000+', '20K+', '1M+' → 정수. 못 읽으면 0."""
    m = re.match(r"\s*([\d.]+)\s*([KM]?)", (text or "").upper())
    if not m:
        return 0
    value = float(m.group(1)) * {"": 1, "K": 1_000, "M": 1_000_000}[m.group(2)]
    return int(value)


def parse_trends_rss(xml_text: str) -> List[Dict[str, Any]]:
    """구글 트렌드 RSS 본문 → [{keyword, searchVolume, category, priority, context}]. context 는 필터용 관련 뉴스 제목."""
    items = []
    for item in ET.fromstring(xml_text).iter("item"):
        keyword = (item.findtext("title") or "").strip()
        if not keyword:
            continue
        news = [t.text or "" for t in item.findall("ht:news_item/ht:news_item_title", TRENDS_NS)]
        items.append({
            "keyword": keyword,
            "searchVolume": parse_traffic(item.findtext("ht:approx_traffic", default="", namespaces=TRENDS_NS)),
            "category": "구글 트렌드",
            "priority": TRENDS_PRIORITY,
            "context": " ".join(news),
        })
    return items


def parse_trending_batch(raw: str) -> List[Dict[str, Any]]:
    """
    trends.google.com 의 "실시간 인기 급상승" 내부 API(batchexecute, rpc i0OFE) 응답 → 키워드 목록.
    공식 API 가 없어 화면이 쓰는 호출을 그대로 흉내 낸다. 응답은 )]}' 접두 + JSON 문자열을 한 번 더 감싼 형태.
    항목은 [검색어, ?, 지역, [시작 시각], ?, ?, 검색량, ?, ?, [연관 검색어], [카테고리 id], [기사 id], 검색어].
    ponytail: 위치 기반 파싱이라 구글이 배열을 바꾸면 깨진다. 그때는 RSS 폴백이 받는다.
    """
    m = re.search(r'"i0OFE","(.*?)",null,null,null,"generic"', raw, re.S)
    if not m:
        return []
    inner = json.loads(json.loads('"' + m.group(1) + '"'))
    items = []
    for it in inner[1] or []:
        keyword = (it[0] or "").strip()
        if not keyword:
            continue
        related = [r for r in (it[9] or []) if r and r != keyword]
        items.append({
            "keyword": keyword,
            "searchVolume": int(it[6] or 0),
            "category": "구글 트렌드",
            "priority": TRENDS_PRIORITY,
            "context": " ".join(related),
        })
    return items


def matches_topic(kw: Dict[str, Any], include=None, exclude=None) -> bool:
    """키워드 + 관련 뉴스 제목에 포함어가 하나라도 있고 제외어는 없어야 통과. 포함어가 비면 전부 통과."""
    include = KEYWORD_INCLUDE if include is None else include
    exclude = KEYWORD_EXCLUDE if exclude is None else exclude
    text = f"{kw.get('keyword', '')} {kw.get('context', '')}".lower()
    if any(w in text for w in exclude):
        return False
    return not include or any(w in text for w in include)


class KeywordCollector:
    """키워드 수집 클래스"""

    def __init__(self):
        """키워드 수집기 초기화"""
        self.db = Database()
        self.naver_client_id = NAVER_CLIENT_ID
        self.naver_client_secret = NAVER_CLIENT_SECRET

    def collect_google_trends(self) -> List[Dict[str, Any]]:
        """최근 GOOGLE_TRENDS_HOURS 시간의 인기 검색어. 내부 API 가 깨지면 당일 RSS 로 물러선다."""
        try:
            payload = json.dumps([[["i0OFE", json.dumps([None, None, GOOGLE_TRENDS_GEO, 0, "ko", GOOGLE_TRENDS_HOURS, 1]), None, "generic"]]])
            request = urllib.request.Request(
                "https://trends.google.com/_/TrendsUi/data/batchexecute?rpcids=i0OFE&hl=ko",
                data=urllib.parse.urlencode({"f.req": payload}).encode("utf-8"),
                headers={"User-Agent": "Mozilla/5.0", "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"},
            )
            with urllib.request.urlopen(request, timeout=20) as response:
                items = parse_trending_batch(response.read().decode("utf-8"))
            if items:
                logger.info(f"구글 트렌드 수집: {len(items)}개 ({GOOGLE_TRENDS_GEO}, 최근 {GOOGLE_TRENDS_HOURS}시간)")
                return items
            logger.warning("구글 트렌드 내부 API 응답을 읽지 못했습니다. 당일 RSS 로 대체합니다.")
        except Exception as e:
            logger.warning(f"구글 트렌드 내부 API 실패, 당일 RSS 로 대체: {e}")
        try:
            with urllib.request.urlopen(f"https://trends.google.com/trending/rss?geo={GOOGLE_TRENDS_GEO}", timeout=20) as response:
                items = parse_trends_rss(response.read().decode("utf-8"))
            logger.info(f"구글 트렌드 RSS 수집: {len(items)}개 (당일)")
            return items
        except Exception as e:
            logger.error(f"구글 트렌드 수집 실패: {e}")
            return []

    def collect_naver_trends(self) -> List[Dict[str, Any]]:
        """
        네이버 검색어 트렌드 수집

        Returns:
            키워드 딕셔너리 리스트
        """
        keywords = []
        try:
            # TODO: 네이버 검색어 트렌드 API 연동 구현
            # 현재는 예시 데이터 반환
            logger.info("네이버 검색어 트렌드 수집 시작")

            # 실제 구현 시 네이버 API 호출
            # url = "https://openapi.naver.com/v1/search/trend"
            # headers = {
            #     "X-Naver-Client-Id": self.naver_client_id,
            #     "X-Naver-Client-Secret": self.naver_client_secret
            # }
            # response = requests.get(url, headers=headers)

            logger.info("네이버 검색어 트렌드 수집 완료")
        except Exception as e:
            logger.error(f"네이버 검색어 트렌드 수집 실패: {e}")

        return keywords

    def collect_naver_datalab(self) -> List[Dict[str, Any]]:
        """
        네이버 데이터랩 인기 검색어 수집

        Returns:
            키워드 딕셔너리 리스트
        """
        keywords = []
        try:
            # TODO: 네이버 데이터랩 API 연동 구현
            logger.info("네이버 데이터랩 인기 검색어 수집 시작")

            logger.info("네이버 데이터랩 인기 검색어 수집 완료")
        except Exception as e:
            logger.error(f"네이버 데이터랩 인기 검색어 수집 실패: {e}")

        return keywords

    def filter_keywords(self, keywords: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        키워드 필터링 (검색량 기준)

        Args:
            keywords: 원본 키워드 리스트

        Returns:
            필터링된 키워드 리스트
        """
        filtered = [
            kw for kw in keywords
            if kw.get('searchVolume', kw.get('search_volume', 0)) >= MIN_SEARCH_VOLUME and matches_topic(kw)
        ]
        logger.info(f"키워드 필터링 완료: {len(keywords)} -> {len(filtered)} (포함어 {len(KEYWORD_INCLUDE)}개)")
        return filtered

    def remove_duplicates(self, keywords: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        중복 키워드 제거

        Args:
            keywords: 키워드 리스트

        Returns:
            중복 제거된 키워드 리스트
        """
        seen = set()
        unique_keywords = []
        for kw in keywords:
            keyword = kw.get('keyword', '').lower()
            if keyword and keyword not in seen:
                seen.add(keyword)
                unique_keywords.append(kw)

        logger.info(f"중복 키워드 제거 완료: {len(keywords)} -> {len(unique_keywords)}")
        return unique_keywords

    def save_keywords(self, keywords: List[Dict[str, Any]]) -> int:
        """
        키워드를 데이터베이스에 저장

        Args:
            keywords: 저장할 키워드 리스트

        Returns:
            저장된 키워드 개수
        """
        saved_count = 0
        collected_date = now_kst().isoformat()

        for kw in keywords:
            try:
                kw['collectedDate'] = collected_date
                kw.pop('context', None)  # 필터용. 백엔드 요청 필드가 아니다.
                self.db.save_keyword(kw)
                saved_count += 1
            except Exception as e:
                logger.error(f"키워드 저장 실패: {kw.get('keyword')} - {e}")

        logger.info(f"키워드 저장 완료: {saved_count}개")
        return saved_count

    def collect_all(self) -> int:
        """
        모든 소스에서 키워드 수집 및 저장

        Returns:
            저장된 키워드 개수
        """
        logger.info("키워드 수집 프로세스 시작")

        all_keywords = []

        # 구글 트렌드 (실제 동작)
        all_keywords.extend(self.collect_google_trends())

        # 네이버 검색어 트렌드 수집
        trends = self.collect_naver_trends()
        all_keywords.extend(trends)

        # 네이버 데이터랩 수집
        datalab = self.collect_naver_datalab()
        all_keywords.extend(datalab)

        # 필터링 및 중복 제거
        filtered = self.filter_keywords(all_keywords)
        unique = self.remove_duplicates(filtered)

        # 저장
        saved_count = self.save_keywords(unique)

        logger.info(f"키워드 수집 프로세스 완료: {saved_count}개 저장")
        return saved_count
