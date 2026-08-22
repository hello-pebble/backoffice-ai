"""
블로그 포스팅 모듈
네이버 블로그에 자동으로 포스팅합니다.
"""
import uuid
import time
from datetime import datetime
from typing import Dict, Any, Optional
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException, NoSuchElementException
from config.settings import (
    NAVER_ID,
    NAVER_PASSWORD,
    SELENIUM_HEADLESS,
    SELENIUM_WAIT_TIME,
    MAX_RETRY_ATTEMPTS,
    RETRY_DELAY
)
from utils.logger import logger
from utils.database import Database


class BlogPoster:
    """블로그 포스팅 클래스"""
    
    def __init__(self):
        """블로그 포스터 초기화"""
        self.db = Database()
        self.naver_id = NAVER_ID
        self.naver_password = NAVER_PASSWORD
        self.driver = None
    
    def _init_driver(self):
        """Selenium WebDriver 초기화"""
        try:
            options = webdriver.ChromeOptions()
            if SELENIUM_HEADLESS:
                options.add_argument('--headless')
            options.add_argument('--no-sandbox')
            options.add_argument('--disable-dev-shm-usage')
            options.add_argument('--disable-blink-features=AutomationControlled')
            options.add_experimental_option("excludeSwitches", ["enable-automation"])
            options.add_experimental_option('useAutomationExtension', False)
            
            self.driver = webdriver.Chrome(options=options)
            self.driver.implicitly_wait(SELENIUM_WAIT_TIME)
            logger.info("WebDriver 초기화 완료")
        except Exception as e:
            logger.error(f"WebDriver 초기화 실패: {e}")
            raise
    
    def _close_driver(self):
        """WebDriver 종료"""
        if self.driver:
            try:
                self.driver.quit()
                logger.info("WebDriver 종료 완료")
            except Exception as e:
                logger.error(f"WebDriver 종료 실패: {e}")
    
    def login(self) -> bool:
        """
        네이버 로그인
        
        Returns:
            로그인 성공 여부
        """
        if not self.driver:
            self._init_driver()
        
        try:
            logger.info("네이버 로그인 시작")
            
            # 네이버 로그인 페이지 접근
            self.driver.get("https://nid.naver.com/nidlogin.login")
            time.sleep(2)
            
            # 아이디 입력
            id_input = WebDriverWait(self.driver, SELENIUM_WAIT_TIME).until(
                EC.presence_of_element_located((By.ID, "id"))
            )
            id_input.clear()
            id_input.send_keys(self.naver_id)
            time.sleep(1)
            
            # 비밀번호 입력
            pw_input = self.driver.find_element(By.ID, "pw")
            pw_input.clear()
            pw_input.send_keys(self.naver_password)
            time.sleep(1)
            
            # 로그인 버튼 클릭
            login_button = self.driver.find_element(By.ID, "log.login")
            login_button.click()
            time.sleep(3)
            
            # 로그인 성공 확인
            if "nid.naver.com" not in self.driver.current_url:
                logger.info("네이버 로그인 성공")
                return True
            else:
                logger.error("네이버 로그인 실패")
                return False
                
        except TimeoutException:
            logger.error("로그인 페이지 로딩 시간 초과")
            return False
        except Exception as e:
            logger.error(f"네이버 로그인 실패: {e}")
            return False
    
    def post_blog(self, content_data: Dict[str, Any]) -> Optional[str]:
        """
        블로그 포스팅
        
        Args:
            content_data: 포스팅할 콘텐츠 데이터
            
        Returns:
            포스팅된 블로그 URL (실패 시 None)
        """
        if not self.driver:
            self._init_driver()
        
        try:
            logger.info(f"블로그 포스팅 시작: {content_data.get('title')}")
            
            # 네이버 블로그 글쓰기 페이지 접근
            self.driver.get("https://blog.naver.com/PostWriteForm.naver")
            time.sleep(3)
            
            # 제목 입력
            title_input = WebDriverWait(self.driver, SELENIUM_WAIT_TIME).until(
                EC.presence_of_element_located((By.CSS_SELECTOR, "input[placeholder='제목']"))
            )
            title_input.clear()
            title_input.send_keys(content_data.get('title', ''))
            time.sleep(1)
            
            # 본문 입력 (iframe 내부)
            # 네이버 블로그는 에디터가 iframe으로 구성되어 있음
            iframe = WebDriverWait(self.driver, SELENIUM_WAIT_TIME).until(
                EC.presence_of_element_located((By.ID, "mainFrame"))
            )
            self.driver.switch_to.frame(iframe)
            time.sleep(1)
            
            # 본문 에디터 찾기
            content_editor = WebDriverWait(self.driver, SELENIUM_WAIT_TIME).until(
                EC.presence_of_element_located((By.CSS_SELECTOR, ".se-component-content"))
            )
            
            # 본문 내용 입력 (실제 구현 시 에디터에 맞게 수정 필요)
            # 네이버 블로그 에디터 구조에 따라 다를 수 있음
            self.driver.execute_script(
                f"arguments[0].innerHTML = '{content_data.get('content', '')}'",
                content_editor
            )
            time.sleep(2)
            
            # 메인 프레임으로 복귀
            self.driver.switch_to.default_content()
            
            # 태그 입력 (있는 경우)
            tags = content_data.get('tags', [])
            if tags:
                try:
                    tag_input = self.driver.find_element(By.CSS_SELECTOR, "input[placeholder='태그']")
                    tag_text = ', '.join(tags[:5])  # 최대 5개
                    tag_input.send_keys(tag_text)
                    time.sleep(1)
                except NoSuchElementException:
                    logger.warning("태그 입력 필드를 찾을 수 없습니다.")
            
            # 발행 버튼 클릭
            publish_button = WebDriverWait(self.driver, SELENIUM_WAIT_TIME).until(
                EC.element_to_be_clickable((By.CSS_SELECTOR, "button[class*='publish']"))
            )
            publish_button.click()
            time.sleep(5)
            
            # 포스팅 성공 확인 및 URL 추출
            current_url = self.driver.current_url
            if "blog.naver.com" in current_url and "PostView" in current_url:
                logger.info(f"블로그 포스팅 성공: {current_url}")
                return current_url
            else:
                logger.error("블로그 포스팅 실패: URL 확인 불가")
                return None
                
        except TimeoutException:
            logger.error("블로그 포스팅 시간 초과")
            return None
        except Exception as e:
            logger.error(f"블로그 포스팅 실패: {e}")
            return None
    
    def post_with_retry(self, content_data: Dict[str, Any]) -> Optional[str]:
        """
        재시도 로직이 포함된 포스팅
        
        Args:
            content_data: 포스팅할 콘텐츠 데이터
            
        Returns:
            포스팅된 블로그 URL (실패 시 None)
        """
        for attempt in range(MAX_RETRY_ATTEMPTS):
            try:
                # 로그인 확인 및 재로그인
                if attempt == 0 or not self._is_logged_in():
                    if not self.login():
                        logger.error("로그인 실패로 포스팅 중단")
                        return None
                
                # 포스팅 시도
                blog_url = self.post_blog(content_data)
                if blog_url:
                    return blog_url
                
                # 실패 시 대기 후 재시도
                if attempt < MAX_RETRY_ATTEMPTS - 1:
                    logger.info(f"{RETRY_DELAY}초 후 재시도... ({attempt + 1}/{MAX_RETRY_ATTEMPTS})")
                    time.sleep(RETRY_DELAY)
                    
            except Exception as e:
                logger.error(f"포스팅 시도 실패 ({attempt + 1}/{MAX_RETRY_ATTEMPTS}): {e}")
                if attempt < MAX_RETRY_ATTEMPTS - 1:
                    time.sleep(RETRY_DELAY)
        
        logger.error("모든 포스팅 시도 실패")
        return None
    
    def _is_logged_in(self) -> bool:
        """
        로그인 상태 확인
        
        Returns:
            로그인 여부
        """
        try:
            if not self.driver:
                return False
            self.driver.get("https://www.naver.com")
            time.sleep(2)
            # 로그인 상태 확인 로직 (실제 구현 필요)
            return True
        except:
            return False
    
    def save_posting_history(self, content_id: str, blog_url: Optional[str], status: str, error_message: Optional[str] = None):
        """
        포스팅 이력 저장
        
        Args:
            content_id: 콘텐츠 ID
            blog_url: 포스팅된 블로그 URL
            status: 상태 (success/failed)
            error_message: 에러 메시지 (실패 시)
        """
        history_data = {
            'id': str(uuid.uuid4()),
            'content_id': content_id,
            'blog_url': blog_url or '',
            'posted_date': datetime.now().isoformat(),
            'status': status,
            'error_message': error_message
        }
        
        self.db.save_posting_history(history_data)
        
        # 콘텐츠 상태 업데이트
        if status == 'success':
            content_data = {
                'id': content_id,
                'status': 'posted',
                'posted_date': datetime.now().isoformat()
            }
            self.db.save_content(content_data)
    
    def post_pending_contents(self) -> int:
        """
        대기 중인 콘텐츠 포스팅
        
        Returns:
            포스팅 성공 개수
        """
        logger.info("대기 중인 콘텐츠 포스팅 시작")
        
        try:
            # TODO: 데이터베이스에서 pending 상태의 콘텐츠 조회
            # 현재는 예시 구현
            
            self._init_driver()
            posted_count = 0
            
            # 실제 구현 시 데이터베이스에서 조회
            # contents = self.db.get_pending_contents()
            # for content in contents:
            #     blog_url = self.post_with_retry(content)
            #     if blog_url:
            #         self.save_posting_history(
            #             content['id'], 
            #             blog_url, 
            #             'success'
            #         )
            #         posted_count += 1
            #     else:
            #         self.save_posting_history(
            #             content['id'], 
            #             None, 
            #             'failed',
            #             '포스팅 실패'
            #         )
            
            logger.info(f"콘텐츠 포스팅 완료: {posted_count}개")
            return posted_count
            
        except Exception as e:
            logger.error(f"콘텐츠 포스팅 실패: {e}")
            return 0
        finally:
            self._close_driver()
