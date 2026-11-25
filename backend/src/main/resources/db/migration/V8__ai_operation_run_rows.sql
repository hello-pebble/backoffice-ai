-- AI 실행 이력을 app_document 의 JSON 배열 한 행에서 ai_operation_run 행으로 옮긴다.
-- 테이블은 V3 에서 이미 만들어져 있었고 쓰는 코드가 없었다. 데모 격리용 owner 만 더한다.
alter table ai_operation_run add column owner text not null default 'owner';
create index ai_operation_run_owner_executed_at_idx on ai_operation_run (owner, executed_at desc);

-- 주인 문서와 데모 문서를 행으로 푼다. 모델 이름은 LlmClient.canonicalModel 과 같은 규칙(공백 제거·'/' 뒤·소문자)으로 굳힌다.
insert into ai_operation_run (legacy_key, owner, executed_at, agent, provider, model, status, duration_ms,
                              input_tokens, output_tokens, estimated_cost_usd, tools, result_preview, error)
-- 데모 씨앗은 주인 실데이터에서 만들어 id 가 같다. 접두사를 붙여야 legacy_key 유니크에서 살아남는다.
select case when d.document_key like 'demo:%' then 'demo:' || (r->>'id') else r->>'id' end,
       case when d.document_key like 'demo:%' then 'demo' else 'owner' end,
       (r->>'executedAt')::timestamptz,
       r->>'agent', r->>'provider',
       lower(regexp_replace(trim(r->>'model'), '^.*/', '')),
       r->>'status',
       coalesce((r->>'durationMs')::bigint, 0),
       coalesce((r->>'inputTokens')::bigint, 0),
       coalesce((r->>'outputTokens')::bigint, 0),
       coalesce((r->>'estimatedCostUsd')::double precision, 0),
       coalesce(r->'tools', '[]'::jsonb),
       coalesce(r->>'resultPreview', ''),
       r->>'error'
from app_document d, jsonb_array_elements(d.payload) r
where d.document_key in ('ai-operations', 'demo:ai-operations') and d.lifecycle_state = 'active'
on conflict (legacy_key) do nothing;

-- 문서는 지우지 않고 소프트 삭제한다. 되돌릴 일이 생기면 payload 가 그대로 있다.
update app_document set lifecycle_state = 'removed', removed_at = now()
where document_key in ('ai-operations', 'demo:ai-operations') and lifecycle_state = 'active';
