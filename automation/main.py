"""
메인 실행 파일
백오피스 시스템의 진입점입니다.
"""
import argparse
import sys
from automation.shared.logger import logger
from automation.jobs.scheduler import Scheduler
from automation.jobs.keyword_collector import KeywordCollector
from automation.jobs.content_generator import ContentGenerator
from automation.jobs.blog_poster import BlogPoster


def run_scheduler():
    logger.info("백오피스 시스템 시작 (스케줄러 모드)")
    scheduler = Scheduler()
    scheduler.start()


def run_keyword_collection():
    logger.info("키워드 수집 실행")
    count = KeywordCollector().collect_all()
    logger.info(f"키워드 수집 완료: {count}개")


def run_content_generation():
    logger.info("콘텐츠 생성 실행")
    count = ContentGenerator().generate_from_keywords()
    logger.info(f"콘텐츠 생성 완료: {count}개")


def run_posting():
    logger.info("블로그 포스팅 실행")
    count = BlogPoster().post_pending_contents()
    logger.info(f"블로그 포스팅 완료: {count}개")


def run_all():
    logger.info("전체 작업 실행")
    run_keyword_collection()
    run_content_generation()
    run_posting()


def main():
    parser = argparse.ArgumentParser(description='네이버 블로그 자동 포스팅 백오피스 시스템')
    parser.add_argument(
        '--mode',
        choices=['api', 'scheduler', 'keyword', 'content', 'posting', 'all'],
        default='scheduler',
        help='실행 모드 선택 (기본값: scheduler)'
    )
    args = parser.parse_args()
    try:
        if args.mode == 'api':
            from automation.worker_api import serve
            serve()
        elif args.mode == 'scheduler':
            run_scheduler()
        elif args.mode == 'keyword':
            run_keyword_collection()
        elif args.mode == 'content':
            run_content_generation()
        elif args.mode == 'posting':
            run_posting()
        elif args.mode == 'all':
            run_all()
    except KeyboardInterrupt:
        logger.info("프로그램 종료")
        sys.exit(0)
    except Exception as e:
        logger.error(f"프로그램 실행 중 오류 발생: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()

