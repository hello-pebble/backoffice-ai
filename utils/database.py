"""
데이터베이스 유틸리티 모듈
SQLite 데이터베이스 관리를 담당합니다.
"""
import sqlite3
import json
from datetime import datetime
from pathlib import Path
from typing import Optional, List, Dict, Any
from config.settings import DB_PATH
from utils.logger import logger


class Database:
    """데이터베이스 관리 클래스"""
    
    def __init__(self, db_path: Optional[Path] = None):
        """
        데이터베이스 초기화
        
        Args:
            db_path: 데이터베이스 파일 경로 (기본값: 설정 파일의 DB_PATH)
        """
        self.db_path = db_path or DB_PATH
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_database()
    
    def _init_database(self):
        """데이터베이스 테이블 초기화"""
        try:
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                
                # 키워드 테이블
                cursor.execute('''
                    CREATE TABLE IF NOT EXISTS keywords (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        keyword TEXT NOT NULL,
                        search_volume INTEGER,
                        category TEXT,
                        collected_date TEXT NOT NULL,
                        used INTEGER DEFAULT 0,
                        priority INTEGER DEFAULT 0,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                ''')
                
                # 콘텐츠 테이블
                cursor.execute('''
                    CREATE TABLE IF NOT EXISTS contents (
                        id TEXT PRIMARY KEY,
                        keyword TEXT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tags TEXT,
                        created_date TEXT NOT NULL,
                        status TEXT DEFAULT 'pending',
                        posted_date TEXT,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                ''')
                
                # 포스팅 이력 테이블
                cursor.execute('''
                    CREATE TABLE IF NOT EXISTS posting_history (
                        id TEXT PRIMARY KEY,
                        content_id TEXT NOT NULL,
                        blog_url TEXT,
                        posted_date TEXT NOT NULL,
                        status TEXT NOT NULL,
                        error_message TEXT,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (content_id) REFERENCES contents(id)
                    )
                ''')
                
                conn.commit()
                logger.info(f"데이터베이스 초기화 완료: {self.db_path}")
        except Exception as e:
            logger.error(f"데이터베이스 초기화 실패: {e}")
            raise
    
    def get_connection(self) -> sqlite3.Connection:
        """
        데이터베이스 연결 반환
        
        Returns:
            SQLite 연결 객체
        """
        return sqlite3.connect(self.db_path)
    
    def save_keyword(self, keyword_data: Dict[str, Any]) -> int:
        """
        키워드 저장
        
        Args:
            keyword_data: 키워드 데이터 딕셔너리
            
        Returns:
            저장된 키워드의 ID
        """
        try:
            with self.get_connection() as conn:
                cursor = conn.cursor()
                cursor.execute('''
                    INSERT INTO keywords (keyword, search_volume, category, collected_date, used, priority)
                    VALUES (?, ?, ?, ?, ?, ?)
                ''', (
                    keyword_data.get('keyword'),
                    keyword_data.get('search_volume', 0),
                    keyword_data.get('category', ''),
                    keyword_data.get('collected_date', datetime.now().isoformat()),
                    int(keyword_data.get('used', False)),
                    keyword_data.get('priority', 0)
                ))
                conn.commit()
                keyword_id = cursor.lastrowid
                logger.debug(f"키워드 저장 완료: {keyword_data.get('keyword')} (ID: {keyword_id})")
                return keyword_id
        except Exception as e:
            logger.error(f"키워드 저장 실패: {e}")
            raise
    
    def get_unused_keywords(self, limit: int = 10) -> List[Dict[str, Any]]:
        """
        미사용 키워드 조회
        
        Args:
            limit: 조회할 키워드 개수
            
        Returns:
            키워드 딕셔너리 리스트
        """
        try:
            with self.get_connection() as conn:
                conn.row_factory = sqlite3.Row
                cursor = conn.cursor()
                cursor.execute('''
                    SELECT * FROM keywords
                    WHERE used = 0
                    ORDER BY priority DESC, search_volume DESC
                    LIMIT ?
                ''', (limit,))
                rows = cursor.fetchall()
                return [dict(row) for row in rows]
        except Exception as e:
            logger.error(f"키워드 조회 실패: {e}")
            return []
    
    def save_content(self, content_data: Dict[str, Any]) -> bool:
        """
        콘텐츠 저장
        
        Args:
            content_data: 콘텐츠 데이터 딕셔너리
            
        Returns:
            저장 성공 여부
        """
        try:
            with self.get_connection() as conn:
                cursor = conn.cursor()
                tags_json = json.dumps(content_data.get('tags', []), ensure_ascii=False)
                cursor.execute('''
                    INSERT OR REPLACE INTO contents 
                    (id, keyword, title, content, tags, created_date, status, posted_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ''', (
                    content_data.get('id'),
                    content_data.get('keyword'),
                    content_data.get('title'),
                    content_data.get('content'),
                    tags_json,
                    content_data.get('created_date', datetime.now().isoformat()),
                    content_data.get('status', 'pending'),
                    content_data.get('posted_date')
                ))
                conn.commit()
                logger.debug(f"콘텐츠 저장 완료: {content_data.get('id')}")
                return True
        except Exception as e:
            logger.error(f"콘텐츠 저장 실패: {e}")
            return False
    
    def save_posting_history(self, history_data: Dict[str, Any]) -> bool:
        """
        포스팅 이력 저장
        
        Args:
            history_data: 포스팅 이력 데이터 딕셔너리
            
        Returns:
            저장 성공 여부
        """
        try:
            with self.get_connection() as conn:
                cursor = conn.cursor()
                cursor.execute('''
                    INSERT INTO posting_history 
                    (id, content_id, blog_url, posted_date, status, error_message)
                    VALUES (?, ?, ?, ?, ?, ?)
                ''', (
                    history_data.get('id'),
                    history_data.get('content_id'),
                    history_data.get('blog_url'),
                    history_data.get('posted_date', datetime.now().isoformat()),
                    history_data.get('status'),
                    history_data.get('error_message')
                ))
                conn.commit()
                logger.debug(f"포스팅 이력 저장 완료: {history_data.get('id')}")
                return True
        except Exception as e:
            logger.error(f"포스팅 이력 저장 실패: {e}")
            return False
