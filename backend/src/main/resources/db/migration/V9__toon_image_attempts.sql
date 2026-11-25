-- 컷 이미지 행을 재시작 복구가 가능한 작업 행으로 만든다.
-- prompt: 재시작 뒤에는 메모리에 없다. 대본 문서를 다시 읽지 않고 행만 보고 이어 만들 수 있게 둔다.
-- attempts: 자동 복구가 같은 컷을 몇 번 다시 잡았는지. office.llm.image-max-attempts 에서 멈춘다.
alter table toon_image add column prompt text not null default '';
alter table toon_image add column attempts integer not null default 1;
