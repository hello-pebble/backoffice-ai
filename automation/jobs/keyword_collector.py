"""
키워드 수집 모듈
네이버 검색어 트렌드 및 인기 키워드를 수집합니다.
"""
import requests
from typing import List, Dict, Any
from config.settings import NAVER_CLIENT_ID, NAVER_CLIENT_SECRET, MIN_SEARCH_VOLUME
from automation.shared.clock import now_kst
from automation.shared.logger import logger
from automation.shared.backend_client import Database


class KeywordCollector:
    """키워드 수집 클래스"""
    
    def __init__(self):
        """키워드 수집기 초기화"""
        self.db = Database()
        self.naver_client_id = NAVER_CLIENT_ID
        self.naver_client_secret = NAVER_CLIENT_SECRET
    
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
            if kw.get('search_volume', 0) >= MIN_SEARCH_VOLUME
        ]
        logger.info(f"키워드 필터링 완료: {len(keywords)} -> {len(filtered)}")
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
                kw['collected_date'] = collected_date
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
