"""
스케줄러 모듈
작업을 자동으로 실행하는 스케줄러를 관리합니다.
"""
from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger
from datetime import datetime
from config.settings import (
    KEYWORD_COLLECTION_TIME,
    CONTENT_GENERATION_TIME,
    POSTING_TIME
)
from automation.shared.logger import logger
from automation.jobs.keyword_collector import KeywordCollector
from automation.jobs.content_generator import ContentGenerator
from automation.jobs.blog_poster import BlogPoster


class Scheduler:
    """스케줄러 클래스"""
    
    def __init__(self):
        """스케줄러 초기화"""
        self.scheduler = BlockingScheduler()
        self.keyword_collector = KeywordCollector()
        self.content_generator = ContentGenerator()
        self.blog_poster = BlogPoster()
    
    def schedule_keyword_collection(self):
        """키워드 수집 작업 스케줄 등록"""
        hour, minute = map(int, KEYWORD_COLLECTION_TIME.split(':'))
        self.scheduler.add_job(
            self._run_keyword_collection,
            trigger=CronTrigger(hour=hour, minute=minute),
            id='keyword_collection',
            name='키워드 수집',
            replace_existing=True
        )
        logger.info(f"키워드 수집 스케줄 등록: 매일 {KEYWORD_COLLECTION_TIME}")
    
    def schedule_content_generation(self):
        """콘텐츠 생성 작업 스케줄 등록"""
        hour, minute = map(int, CONTENT_GENERATION_TIME.split(':'))
        self.scheduler.add_job(
            self._run_content_generation,
            trigger=CronTrigger(hour=hour, minute=minute),
            id='content_generation',
            name='콘텐츠 생성',
            replace_existing=True
        )
        logger.info(f"콘텐츠 생성 스케줄 등록: 매일 {CONTENT_GENERATION_TIME}")
    
    def schedule_posting(self):
        """포스팅 작업 스케줄 등록"""
        hour, minute = map(int, POSTING_TIME.split(':'))
        self.scheduler.add_job(
            self._run_posting,
            trigger=CronTrigger(hour=hour, minute=minute),
            id='posting',
            name='블로그 포스팅',
            replace_existing=True
        )
        logger.info(f"블로그 포스팅 스케줄 등록: 매일 {POSTING_TIME}")
    
    def _run_keyword_collection(self):
        """키워드 수집 작업 실행"""
        logger.info("=" * 50)
        logger.info("키워드 수집 작업 시작")
        logger.info("=" * 50)
        try:
            count = self.keyword_collector.collect_all()
            logger.info(f"키워드 수집 작업 완료: {count}개")
        except Exception as e:
            logger.error(f"키워드 수집 작업 실패: {e}")
    
    def _run_content_generation(self):
        """콘텐츠 생성 작업 실행"""
        logger.info("=" * 50)
        logger.info("콘텐츠 생성 작업 시작")
        logger.info("=" * 50)
        try:
            count = self.content_generator.generate_from_keywords()
            logger.info(f"콘텐츠 생성 작업 완료: {count}개")
        except Exception as e:
            logger.error(f"콘텐츠 생성 작업 실패: {e}")
    
    def _run_posting(self):
        """포스팅 작업 실행"""
        logger.info("=" * 50)
        logger.info("블로그 포스팅 작업 시작")
        logger.info("=" * 50)
        try:
            count = self.blog_poster.post_pending_contents()
            logger.info(f"블로그 포스팅 작업 완료: {count}개")
        except Exception as e:
            logger.error(f"블로그 포스팅 작업 실패: {e}")
    
    def start(self):
        """스케줄러 시작"""
        logger.info("스케줄러 시작")
        
        # 모든 작업 스케줄 등록
        self.schedule_keyword_collection()
        self.schedule_content_generation()
        self.schedule_posting()
        
        try:
            self.scheduler.start()
        except KeyboardInterrupt:
            logger.info("스케줄러 종료")
            self.scheduler.shutdown()
    
    def run_once(self):
        """한 번만 실행 (테스트용)"""
        logger.info("일회성 작업 실행")
        self._run_keyword_collection()
        self._run_content_generation()
        self._run_posting()
