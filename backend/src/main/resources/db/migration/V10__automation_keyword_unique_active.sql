-- 워커가 매일 7일치 트렌드를 다시 넣으므로 같은 키워드가 활성 행으로 두 번 있으면 안 된다.
-- 이미 있는 중복은 최신 행만 남기고 소프트 삭제한 뒤, 활성 행에만 대소문자 무시 유니크를 건다.
update automation_keyword k
set lifecycle_state = 'removed', removed_at = now()
where lifecycle_state = 'active'
  and id < (select max(id) from automation_keyword d where lower(d.keyword) = lower(k.keyword) and d.lifecycle_state = 'active');

create unique index automation_keyword_active_keyword_idx
    on automation_keyword (lower(keyword)) where lifecycle_state = 'active';
