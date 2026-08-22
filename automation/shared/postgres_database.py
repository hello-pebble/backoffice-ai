import json
from datetime import datetime
from typing import Any, Dict, List

import psycopg
from psycopg.rows import dict_row

from config.settings import SUPABASE_DB_PASSWORD, SUPABASE_DB_USER, SUPABASE_PG_DSN
from automation.shared.logger import logger


class PostgresDatabase:
    def __init__(self):
        if not SUPABASE_PG_DSN:
            raise RuntimeError('SUPABASE_PG_DSN 또는 SUPABASE_DB_URL을 설정하세요.')

    def get_connection(self):
        return psycopg.connect(SUPABASE_PG_DSN, user=SUPABASE_DB_USER or None, password=SUPABASE_DB_PASSWORD or None, row_factory=dict_row, sslmode='require')

    def save_keyword(self, data: Dict[str, Any]) -> int:
        with self.get_connection() as conn, conn.cursor() as cursor:
            if data.get('id'):
                cursor.execute('update automation_keyword set used = %s, priority = %s where id = %s and lifecycle_state = ''active'' returning id', (bool(data.get('used', False)), data.get('priority', 0), data['id']))
            else:
                cursor.execute('insert into automation_keyword (keyword, search_volume, category, collected_at, used, priority) values (%s, %s, %s, %s, %s, %s) returning id', (data.get('keyword'), data.get('search_volume', 0), data.get('category', ''), data.get('collected_date', datetime.now().isoformat()), bool(data.get('used', False)), data.get('priority', 0)))
            row = cursor.fetchone()
            return row['id']

    def get_unused_keywords(self, limit: int = 10) -> List[Dict[str, Any]]:
        with self.get_connection() as conn, conn.cursor() as cursor:
            cursor.execute('select id, keyword, search_volume, category, collected_at as collected_date, used, priority from automation_keyword where used = false and lifecycle_state = ''active'' order by priority desc, search_volume desc limit %s', (limit,))
            return list(cursor.fetchall())

    def save_content(self, data: Dict[str, Any]) -> bool:
        with self.get_connection() as conn, conn.cursor() as cursor:
            cursor.execute('insert into automation_content (legacy_key, keyword, title, content, tags, created_at, status, posted_at, lifecycle_state, removed_at) values (%s, %s, %s, %s, cast(%s as jsonb), %s, %s, %s, ''active'', null) on conflict (legacy_key) do update set keyword = excluded.keyword, title = excluded.title, content = excluded.content, tags = excluded.tags, status = excluded.status, posted_at = excluded.posted_at, lifecycle_state = ''active'', removed_at = null', (data['id'], data.get('keyword', ''), data.get('title', ''), data.get('content', ''), json.dumps(data.get('tags', []), ensure_ascii=False), data.get('created_date', datetime.now().isoformat()), data.get('status', 'pending'), data.get('posted_date')))
        return True

    def save_posting_history(self, data: Dict[str, Any]) -> bool:
        with self.get_connection() as conn, conn.cursor() as cursor:
            cursor.execute('insert into automation_posting_record (legacy_key, content_id, blog_url, posted_at, status, error_message, lifecycle_state) select %s, content.id, %s, %s, %s, %s, ''active'' from automation_content content where content.legacy_key = %s and content.lifecycle_state = ''active''', (data['id'], data.get('blog_url'), data.get('posted_date', datetime.now().isoformat()), data['status'], data.get('error_message'), data['content_id']))
            if cursor.rowcount != 1:
                raise ValueError('발행할 자동화 콘텐츠를 찾을 수 없습니다.')
        return True


Database = PostgresDatabase
