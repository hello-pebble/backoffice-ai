"""
콘텐츠 생성 모듈
OpenAI API를 사용하여 블로그 글을 자동 생성합니다.
"""
import uuid
from datetime import datetime
from typing import Dict, Any, Optional
from openai import OpenAI
from config.settings import (
    OPENAI_API_KEY,
    OPENAI_BASE_URL,
    OPENAI_MODEL, 
    MIN_CONTENT_LENGTH,
    MAX_KEYWORDS_PER_DAY
)
from automation.shared.logger import logger
from automation.shared.usage import UsageTracker
from automation.shared.postgres_database import Database


class ContentGenerator:
    """콘텐츠 생성 클래스"""
    
    def __init__(self):
        """콘텐츠 생성기 초기화"""
        self.db = Database()
        self.client = OpenAI(api_key=OPENAI_API_KEY, base_url=OPENAI_BASE_URL or None) if OPENAI_API_KEY else None
        self.model = OPENAI_MODEL
        # 이번 실행의 토큰 사용량. main 이 마지막에 한 줄로 찍어 백오피스에 넘긴다.
        self.usage = UsageTracker(self.model)
    
    def generate_title(self, keyword: str) -> str:
        """
        SEO 최적화된 제목 생성
        
        Args:
            keyword: 대상 키워드
            
        Returns:
            생성된 제목
        """
        if not self.client:
            logger.error("OpenAI API 키가 설정되지 않았습니다.")
            return f"{keyword}에 대한 완벽한 가이드"
        
        try:
            prompt = f"""
다음 키워드를 포함한 SEO 최적화된 블로그 제목을 생성해주세요.
키워드: {keyword}

요구사항:
- 키워드를 자연스럽게 포함
- 클릭을 유도하는 매력적인 제목
- 30자 이내
- 한국어로 작성

제목만 출력해주세요 (따옴표 없이):
"""
            response = self.usage.add(self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": "당신은 SEO 전문가입니다."},
                    {"role": "user", "content": prompt}
                ],
                max_tokens=50,
                temperature=0.7
            ))
            
            title = response.choices[0].message.content.strip().strip('"').strip("'")
            logger.debug(f"제목 생성 완료: {title}")
            return title
        except Exception as e:
            logger.error(f"제목 생성 실패: {e}")
            return f"{keyword}에 대한 완벽한 가이드"
    
    def generate_content(self, keyword: str, title: str) -> str:
        """
        블로그 본문 생성
        
        Args:
            keyword: 대상 키워드
            title: 제목
            
        Returns:
            생성된 본문 내용
        """
        if not self.client:
            logger.error("OpenAI API 키가 설정되지 않았습니다.")
            return f"{keyword}에 대한 상세한 내용을 작성합니다..."
        
        try:
            prompt = f"""
다음 키워드와 제목을 바탕으로 블로그 글을 작성해주세요.

키워드: {keyword}
제목: {title}

요구사항:
- 최소 {MIN_CONTENT_LENGTH}자 이상 작성
- SEO 최적화를 고려한 자연스러운 키워드 배치
- 실용적이고 유용한 정보 제공
- 읽기 쉬운 문단 구성
- 소제목 사용 (H2, H3 태그)
- 마크다운 형식으로 작성

본문 내용:
"""
            response = self.usage.add(self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": "당신은 전문 블로그 작가입니다."},
                    {"role": "user", "content": prompt}
                ],
                max_tokens=2000,
                temperature=0.8
            ))
            
            content = response.choices[0].message.content.strip()
            logger.debug(f"본문 생성 완료: {len(content)}자")
            return content
        except Exception as e:
            logger.error(f"본문 생성 실패: {e}")
            return f"{keyword}에 대한 상세한 내용을 작성합니다..."
    
    def generate_tags(self, keyword: str, title: str) -> list:
        """
        태그 생성
        
        Args:
            keyword: 대상 키워드
            title: 제목
            
        Returns:
            태그 리스트
        """
        if not self.client:
            return [keyword]
        
        try:
            prompt = f"""
다음 키워드와 제목에 적합한 태그 5개를 생성해주세요.

키워드: {keyword}
제목: {title}

요구사항:
- 관련성 높은 태그
- 검색량이 높은 태그
- 5개 이내
- 쉼표로 구분하여 출력

태그:
"""
            response = self.usage.add(self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": "당신은 SEO 전문가입니다."},
                    {"role": "user", "content": prompt}
                ],
                max_tokens=50,
                temperature=0.5
            ))
            
            tags_text = response.choices[0].message.content.strip()
            tags = [tag.strip() for tag in tags_text.split(',') if tag.strip()]
            logger.debug(f"태그 생성 완료: {tags}")
            return tags[:5]  # 최대 5개
        except Exception as e:
            logger.error(f"태그 생성 실패: {e}")
            return [keyword]
    
    def generate_blog_post(self, keyword: str) -> Optional[Dict[str, Any]]:
        """
        전체 블로그 포스트 생성
        
        Args:
            keyword: 대상 키워드
            
        Returns:
            생성된 콘텐츠 딕셔너리
        """
        logger.info(f"블로그 포스트 생성 시작: {keyword}")
        
        try:
            # 제목 생성
            title = self.generate_title(keyword)
            
            # 본문 생성
            content = self.generate_content(keyword, title)
            
            # 태그 생성
            tags = self.generate_tags(keyword, title)
            
            # 콘텐츠 데이터 구성
            content_data = {
                'id': str(uuid.uuid4()),
                'keyword': keyword,
                'title': title,
                'content': content,
                'tags': tags,
                'created_date': datetime.now().isoformat(),
                'status': 'pending'
            }
            
            # 데이터베이스에 저장
            if self.db.save_content(content_data):
                logger.info(f"블로그 포스트 생성 완료: {content_data['id']}")
                return content_data
            else:
                logger.error("콘텐츠 저장 실패")
                return None
                
        except Exception as e:
            logger.error(f"블로그 포스트 생성 실패: {e}")
            return None
    
    def generate_from_keywords(self, limit: int = None) -> int:
        """
        미사용 키워드로부터 콘텐츠 생성
        
        Args:
            limit: 생성할 콘텐츠 개수 (기본값: MAX_KEYWORDS_PER_DAY)
            
        Returns:
            생성된 콘텐츠 개수
        """
        if limit is None:
            limit = MAX_KEYWORDS_PER_DAY
        
        logger.info(f"키워드 기반 콘텐츠 생성 시작 (최대 {limit}개)")
        
        # 미사용 키워드 조회
        keywords = self.db.get_unused_keywords(limit=limit)
        
        if not keywords:
            logger.warning("생성할 키워드가 없습니다.")
            return 0
        
        generated_count = 0
        for kw_data in keywords:
            keyword = kw_data.get('keyword')
            if keyword:
                content = self.generate_blog_post(keyword)
                if content:
                    generated_count += 1
                    # 키워드 사용 표시
                    kw_data['used'] = True
                    self.db.save_keyword(kw_data)
        
        logger.info(f"콘텐츠 생성 완료: {generated_count}개")
        return generated_count



