// 인증은 로그인 세션 쿠키로 한다. 공유 API 키는 없앴다.
const _fetch=window.fetch.bind(window);
// 동작 보기 패널. 이 화면이 보낸 요청, 폴링에서 감지한 상태 변화, 운영 센터에 새로 쌓인 실행을 시간순으로 쌓는다.
// 같은 줄이 연달아 오면(폴링) 한 줄에 ×n 으로 접는다. 화면 상태일 뿐이라 서버는 모른다.
const TRACE=[];
function trace(kind,text,key=text){const last=TRACE[0];if(last&&last.kind===kind&&last.key===key){last.n++;last.t=new Date();last.text=text}else{TRACE.unshift({kind,text,key,t:new Date(),n:1});if(TRACE.length>200)TRACE.length=200}renderTrace()}
function renderTrace(){const el=document.getElementById('trace-log');if(!el||el.closest('[hidden]'))return;el.innerHTML=TRACE.map(x=>`<li><span class="trace-time">${x.t.toTimeString().slice(0,8)}</span><span class="tag ${x.kind==='AI 실행'?'done':x.kind==='상태'?'wait':''}">${x.kind}</span>${esc(x.text)}${x.n>1?` <i>×${x.n}</i>`:''}</li>`).join('')}
window.fetch=(u,o={})=>{const t0=performance.now();return _fetch(u,String(u).startsWith('/api/')?{...o,credentials:'include'}:o).then(r=>{
 if(r.status===401&&!String(u).includes('/api/auth/'))showLogin();
 // 폴링 같은 반복 요청은 ms 만 다르므로 경로·상태로 접는다.
 if(String(u).startsWith('/api/')){const head=`${o.method||'GET'} ${String(u).replace(/\?.*$/,'')} → ${r.status}`;trace('요청',`${head} · ${Math.round(performance.now()-t0)}ms`,head)}
 return r;
})};
// 게이트는 HTML 에서 기본으로 덮여 있다(스크립트가 죽어도 대시보드가 새지 않도록).
// 세션 쿠키는 HttpOnly 라 읽을 수 없어서, 로그인 때 함께 받는 표시용 쿠키로 즉시 판단한다.
// 이게 없으면 /api/auth/me 왕복 동안 새로고침마다 로그인 카드가 깜빡인다.
function showLogin(message){const gate=document.getElementById('login-gate');if(!gate)return;gate.hidden=false;document.getElementById('login-message').textContent=message||'허용된 Google 계정으로 로그인하세요.'}
function hideLogin(){const gate=document.getElementById('login-gate');if(gate)gate.hidden=true}
// 첫 페인트 전에 동기로 실행된다. 표시용일 뿐이라 위조해도 서버는 세션 쿠키만 본다.
if(document.cookie.split('; ').includes('office_session_hint=1'))hideLogin();
const $=id=>document.getElementById(id), won=n=>new Intl.NumberFormat('ko-KR',{style:'currency',currency:'KRW',maximumFractionDigits:0}).format(n||0);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function row(left,sub,right){return `<div class="row"><div><b>${left}</b><span>${sub}</span></div><div class="right">${right}</div></div>`}
function renderDashboard(d){
 const g=d.gmail,s=d.stocks;
 $('mail-count').textContent=g.connected?`확인할 메일 ${g.unread||0}건${g.more?'+':''}`:'';
 $('mail-list').innerHTML=g.connected?(g.messages.length?g.messages.map(x=>row(esc(x.subject),esc(x.from),`<span>${esc(x.date)}</span>`)).join(''):'확인할 메일이 없습니다.'):(g.message||'Google 로그인 후 표시됩니다.');
 $('stock-list').innerHTML=s.connected&&s.items.length?s.items.map(x=>row(esc(x.name),esc(x.symbol),`<b>${won(Number(x.price))}</b>`)).join(''):(s.message||'토스증권 API 연결 후 표시됩니다.');
}
const PAGE_SIZE=5,pageState={};
// 쌓이는 목록은 전부 이걸 쓴다. items 를 넘기면 목록을 갈아끼우고(페이지는 유지),
// 없이 부르면 현재 목록에서 페이지만 다시 그린다. 첫 호출에 넘긴 표시 방법을 기억한다.
function renderPaged(id,items,className,empty,card){
 const s=pageState[id]||(pageState[id]={items:[],page:0});
 if(card){s.className=className;s.empty=empty;s.card=card}
 if(items)s.items=items;
 const total=s.items.length,last=Math.max(1,Math.ceil(total/PAGE_SIZE)),target=$(id);
 s.page=Math.min(Math.max(s.page,0),last-1);
 if(!total){target.className='empty';target.textContent=s.empty;return}
 const start=s.page*PAGE_SIZE;
 const pager=last>1?`<div class="pager"><button class="light" data-page="${id}" data-step="-1" ${s.page?'':'disabled'}>이전</button><span>${s.page+1} / ${last} · 전체 ${total}건</span><button class="light" data-page="${id}" data-step="1" ${s.page<last-1?'':'disabled'}>다음</button></div>`:'';
 target.className=s.className;
 target.innerHTML=s.items.slice(start,start+PAGE_SIZE).map(s.card).join('')+pager;
}
document.addEventListener('click',e=>{const b=e.target.closest('button[data-page]');if(!b)return;pageState[b.dataset.page].page+=Number(b.dataset.step);renderPaged(b.dataset.page)});
// 목록이 길어져도 한 화면에 머물게 한다.
function renderNews(items){renderPaged('news-list',items,'','아직 수집된 AI 소식이 없습니다.',x=>`<article class="news-card"><div class="news-meta">${x.read?'':'<i class="unread-dot"></i>'}<span>${esc(x.source)}</span><span class="tag">${esc(x.category)}</span><span>${esc(x.publishedAt||x.collectedAt)}</span></div><a href="${esc(x.url)}" target="_blank" rel="noreferrer" data-news-id="${esc(x.id)}">${esc(x.title)}</a><p>${esc(x.summary)}</p></article>`)}

function renderBriefing(data){const target=$('briefing-list');if(!data){target.className='briefing empty';target.textContent='아직 생성된 핵심 브리핑이 없습니다.';return}const byId=Object.fromEntries(data.news.map(x=>[x.id,x]));target.className='briefing';target.innerHTML=data.items.map((x,i)=>`<article class="briefing-card"><span class="tag">핵심 ${i+1}</span><b>${esc(byId[x.id]?.title||'AI 소식')}</b><p>${esc(x.summary)}</p><strong>업무 영향 · ${esc(x.impact)}</strong></article>`).join('')}
const dur=ms=>ms>=60000?`${Math.floor(ms/60000)}분 ${Math.round(ms%60000/1000)}초`:`${(Number(ms)/1000).toFixed(1)}초`;
// AI 운영 센터: 기간·기능·모델 필터, 페이지, 집계는 전부 서버가 한다(/api/ai-operations?range&agent&model&page).
// 타일·모델별 표·목록이 같은 where 절에서 나오므로 서로 어긋날 수 없다. 화면은 받은 대로 그린다.
const fmtN=n=>new Intl.NumberFormat('ko-KR').format(n||0),usd=n=>`$${Number(n||0).toFixed(4)}`;
const AI_OPS={page:0};
// 고정 옵션(keep개)은 남기고 뒤를 데이터로 채운다. 고르고 있던 값은 유지한다.
function fillSelect(id,options,keep){const s=$(id),v=s.value;s.innerHTML=[...s.options].slice(0,keep).map(o=>o.outerHTML).join('')+options.map(([value,label])=>`<option value="${esc(value)}">${esc(label)}</option>`).join('');s.value=v;if(s.selectedIndex<0)s.selectedIndex=0}
async function loadAiOperations(){
 const q=new URLSearchParams({range:$('ai-filter-range').value,agent:$('ai-filter-agent').value,model:$('ai-filter-model').value,page:AI_OPS.page});
 const d=await j('/api/ai-operations?'+q);if(d)renderAiOperations(d);
}
function renderAiOperations(d){
 d.items.forEach(x=>{const key=`op|${x.id}`;if(!(key in SEEN)){if(SEEN.opsSeeded)trace('AI 실행',`${x.agent} · ${x.model} · 입력 ${fmtN(x.inputTokens)} / 출력 ${fmtN(x.outputTokens)} · ${usd(x.estimatedCostUsd)} · ${dur(x.durationMs)} · ${x.status}`);SEEN[key]=1}});SEEN.opsSeeded=1;
 const last=Math.max(1,Math.ceil(d.total/d.size));
 // 마지막 페이지에 있다가 이력이 줄면 빈 페이지가 온다. 한 번만 앞 페이지로 물린다.
 if(d.total&&d.page>=last){AI_OPS.page=last-1;return loadAiOperations()}
 fillSelect('ai-filter-agent',d.agents.map(a=>[a,a]),1);
 fillSelect('ai-filter-model',d.modelNames.map(m=>[m,m]),1);
 fillSelect('ai-filter-range',d.months.map(ym=>[ym,`${ym.slice(0,4)}년 ${Number(ym.slice(5,7))}월`]),2);
 $('ai-run-count').textContent=d.totalRuns;$('ai-success-count').textContent=d.successfulRuns;
 $('ai-token-count').textContent=`${fmtN(d.inputTokens)} / ${fmtN(d.outputTokens)}`;$('ai-cost').textContent=usd(d.estimatedCostUsd);$('ai-duration').textContent=dur(d.totalDurationMs);
 const t=$('ai-model-matrix');t.hidden=!d.models.length;
 t.innerHTML=`<thead><tr><th>모델</th><th>실행</th><th>입력 토큰</th><th>출력 토큰</th><th>예상 비용</th><th>평균 시간</th></tr></thead><tbody>${d.models.map(r=>`<tr><td>${esc(r.model)}</td><td>${r.runs}</td><td>${fmtN(r.inputTokens)}</td><td>${fmtN(r.outputTokens)}</td><td>${usd(r.estimatedCostUsd)}</td><td>${dur(r.durationMs/r.runs)}</td></tr>`).join('')}</tbody>`;
 const target=$('ai-operation-list');
 if(!d.total){target.className='empty';target.textContent=d.agents.length?'이 조건에 해당하는 실행이 없습니다.':'아직 AI 실행 이력이 없습니다.';return}
 const pager=last>1?`<div class="pager"><button class="light" data-ai-step="-1" ${d.page?'':'disabled'}>이전</button><span>${d.page+1} / ${last} · 전체 ${d.total}건</span><button class="light" data-ai-step="1" ${d.page<last-1?'':'disabled'}>다음</button></div>`:'';
 target.className='ai-operation-list';
 target.innerHTML=d.items.map(x=>`<article class="ai-operation"><div class="ai-operation-head"><div><span class="tag ${x.status==='성공'?'done':'late'}">${esc(x.status)}</span><b>${esc(x.agent)}</b><span>${esc(x.executedAt.replace('T',' ').slice(0,16))} · ${dur(x.durationMs)}</span></div><strong>${usd(x.estimatedCostUsd)}</strong></div><p><b>${esc(x.provider)}</b> · ${esc(x.model)} · 입력 ${fmtN(x.inputTokens)} · 출력 ${fmtN(x.outputTokens)}</p><div class="tool-list">${x.tools.map(tool=>`<span>${esc(tool)}</span>`).join('')}</div><small>${esc(x.error||x.resultPreview||'결과 정보가 없습니다.')}</small></article>`).join('')+pager;
}
$('ai-operation-list').addEventListener('click',e=>{const b=e.target.closest('button[data-ai-step]');if(!b)return;AI_OPS.page+=Number(b.dataset.aiStep);loadAiOperations()});
['ai-filter-agent','ai-filter-model','ai-filter-range'].forEach(id=>$(id).onchange=()=>{AI_OPS.page=0;loadAiOperations()});
// 검토 대기 = 성공했는데 아직 승인·반려 안 한 출력이 하나라도 있는 패키지. 필터는 화면 상태라 서버에 안 묻는다.
let PACKAGES=[],PKG_FILTER='all';
const hasPending=p=>p.outputs.some(o=>o.status==='성공'&&o.reviewStatus==='REVIEW_PENDING');
const SEEN={};
// 이전에 본 상태와 다르면 기록한다. 처음 본 항목은 기준선만 잡는다(페이지 열 때 과거 이력을 쏟아내지 않도록).
function noteChange(key,label,status){const old=SEEN[key];SEEN[key]=status;if(old!==undefined&&old!==status)trace('상태',`${label}: ${old} → ${status}`)}
function renderContentPackages(items){
 if(items){items.forEach(p=>p.outputs.forEach(o=>noteChange(`pkg|${p.id}|${o.channel}`,`${p.title.slice(0,18)} · ${o.channel}`,o.status+(o.status==='성공'?' · '+(REVIEW_LABEL[o.reviewStatus]||[])[1]:''))));PACKAGES=items}
 const pending=PACKAGES.filter(hasPending);
 $('package-filter').innerHTML=`<button class="light ${PKG_FILTER==='pending'?'active':''}" data-pkg-filter="pending">검토 대기 ${pending.length}건</button><button class="light ${PKG_FILTER==='all'?'active':''}" data-pkg-filter="all">전체 ${PACKAGES.length}건</button>`;
 renderPackageList(PKG_FILTER==='pending'?pending:PACKAGES);
}
document.addEventListener('click',e=>{const b=e.target.closest('button[data-pkg-filter]');if(!b)return;PKG_FILTER=b.dataset.pkgFilter;if(pageState['content-package-list'])pageState['content-package-list'].page=0;renderContentPackages()});
function renderPackageList(items){renderPaged('content-package-list',items,'content-package-list',PKG_FILTER==='pending'?'검토 대기 중인 콘텐츠가 없습니다.':'아직 생성된 콘텐츠 패키지가 없습니다.',item=>`<article class="content-package" id="content-package-${esc(item.id)}"><b>${esc(item.title)}</b><p>${esc(item.tone)} · ${esc(item.target)} · ${esc(item.createdAt.replace('T',' ').slice(0,16))}${item.slackStatus?` · <span class="tag ${item.slackStatus==='SENT'?'done':'late'}">${esc(SLACK_LABEL[item.slackStatus]||item.slackStatus)}</span>`:''}${item.slackStatus&&item.slackStatus!=='SENT'?` <button class="light" data-package-notify="${esc(item.id)}">Slack 알림 재시도</button>`:''}</p><div class="content-output-grid">${item.outputs.map(output=>output.status==='생성중'?`<details><summary>${esc(output.channel)} · 생성 중…</summary></details>`:output.status==='실패'?`<details><summary>${esc(output.channel)} · 실패</summary><p class="muted">${esc(output.error||'생성에 실패했습니다.')}</p></details>`:`<details><summary>${esc(output.channel)} · ${esc(output.title)} ${reviewTag(output)}</summary>${reviewBar(item,output)}${output.refId?`<p class="muted">${esc(OUTPUT_NEXT[output.channel]||'')}</p>`:''}<pre>${esc(output.body)}</pre>${outputExtra(output)}</details>`).join('')}</div>${item.slackError?`<p class="slack-error">${esc(item.slackError)}</p>`:''}</article>`);focusHash('content-package-','content-package-list')}
const OUTPUT_NEXT={'블로그':'블로그 발행 큐(검토 대기)에 저장했습니다.'};
// 패키지 카드가 refId 로 툰·초안을 찾아 컷 이미지 버튼과 Slack 상태를 붙인다. 두 목록을 그릴 때 채운다.
const TOONS={};
function cacheToons(items){(items||[]).forEach(x=>{TOONS[x.id]=x;(x.images||[]).forEach(i=>noteChange(`img|${x.id}|${i.panel_number}`,`${x.title.slice(0,18)} · ${i.panel_number}컷 이미지`,i.status))})}
const REVIEW_LABEL={REVIEW_PENDING:['wait','검토 대기'],APPROVED:['done','승인'],REJECTED:['late','반려']};
// 승인·반려 버튼은 주인만 본다(body.demo 에서 숨김). 서버도 데모 세션의 PATCH 를 403 으로 막는다.
function reviewTag(o){const [cls,label]=REVIEW_LABEL[o.reviewStatus]||REVIEW_LABEL.REVIEW_PENDING;return `<span class="tag ${cls}">${label}</span>`}
// 접힌 상태에서도 제목 옆 태그로 검토 상태가 보이고, 펼치면 대본을 읽기 전에 버튼이 먼저 나온다.
function reviewBar(pkg,o){if(o.status!=='성공')return '';const btn=(st,text)=>o.reviewStatus===st?'':`<button class="light owner-only" data-review="${esc(pkg.id)}|${esc(o.channel)}|${st}">${text}</button>`;return `<p class="review-bar">${btn('APPROVED','승인')} ${btn('REJECTED','반려')} <button class="light" data-copy>복사</button></p>`}
// 대본 본문만 클립보드로. 이스케이프된 HTML 이 아니라 그려진 텍스트를 읽어 원문 그대로 복사한다.
document.addEventListener('click',async e=>{const b=e.target.closest('button[data-copy]');if(!b)return;const text=b.closest('details')?.querySelector('pre')?.textContent||'';try{await navigator.clipboard.writeText(text);b.textContent='복사됨';setTimeout(()=>b.textContent='복사',1500)}catch{alert('클립보드에 복사하지 못했습니다. 브라우저 권한을 확인하세요.')}});
function outputExtra(o){
 if(o.channel==='인스타툰'&&TOONS[o.refId]){const t=TOONS[o.refId];return `<p class="meta">${toonImageButton(t)}</p>${t.panels.map(p=>panelImage(t,p)).join('')}`}
 return ''
}
const SLACK_LABEL={SENT:'Slack 전송됨',FAILED:'Slack 전송 실패',NOT_CONFIGURED:'Slack 미설정'};
// Slack 알림의 검토 링크는 특정 초안을 가리킨다. 그 초안이 뒤 페이지에 있으면
// 링크를 눌러도 아무 일이 없으므로, 해당 페이지로 옮긴 뒤 그 카드로 스크롤한다.
// Slack 검토 링크(#content-package-{id})가 뒤 페이지에 있으면 그 페이지로 옮긴 뒤 카드로 스크롤한다.
function focusHash(prefix,listId){const id=location.hash.replace('#'+prefix,'');if(!id||id===location.hash)return;const s=pageState[listId],i=s.items.findIndex(x=>x.id===id);if(i<0)return;const page=Math.floor(i/PAGE_SIZE);if(page!==s.page){s.page=page;renderPaged(listId)}document.getElementById(prefix+id)?.scrollIntoView({block:'center'})}
const SLACK_STATE={channels:[]};
function renderSlack(status){const target=$('slack-status');if(!status){target.className='empty';target.textContent='Slack 상태를 불러오지 못했습니다.';return}
 if(!status.configured){target.className='empty';target.innerHTML='Slack 앱 자격증명이 아직 설정되지 않았습니다. <code>office.slack.client-id</code>와 <code>client-secret</code>을 설정하세요.';$('slack-connect').disabled=true;return}
 $('slack-connect').disabled=false;$('slack-connect').textContent=status.connected?'Slack 다시 연결':'Slack 연결';
 if(!status.connected){target.className='empty';target.textContent='아직 연결되지 않았습니다. Slack 연결 버튼으로 앱을 설치하세요.';return}
 target.className='slack-status';
 const options=SLACK_STATE.channels.map(c=>`<option value="${esc(c.id)}" ${c.id===status.channelId?'selected':''}>#${esc(c.name)}</option>`).join('');
 target.innerHTML=`<span class="tag done">연결됨</span><b>${esc(status.teamName||'워크스페이스')}</b><span>알림 채널</span><select id="slack-channel">${options||'<option value="">채널 목록을 불러오세요</option>'}</select><button class="light" id="slack-channel-load">채널 목록 새로고침</button>${status.channelName?`<span class="muted">현재 #${esc(status.channelName)}</span>`:'<span class="muted">채널을 고르면 알림이 시작됩니다.</span>'}`;
}
// 컷 이미지: 완료면 그림, 생성중이면 자리표시자, 실패면 사유. 바이트는 목록에 안 실려 오고 img 가 따로 가져간다.
// 같은 컷을 다시 만들면 id 가 그대로라 완료 시각을 붙여 캐시를 깬다.
function panelImage(toon,panel){
 const s=(toon.images||[]).find(i=>i.panel_number===panel.number);
 if(!s)return '';
 if(s.status==='완료')return `<img class="toon-cut" loading="lazy" alt="${panel.number}컷 이미지" src="/api/toon-images/${s.id}?v=${encodeURIComponent(s.completed_at||'')}">`;
 if(s.status==='실패')return `<p class="meta">이미지 실패: ${esc(s.error||'사유가 기록되지 않았습니다.')}</p>`;
 return '<p class="meta">이미지 생성 중…</p>';
}
function toonImageButton(x){
 const images=x.images||[];
 if(images.some(i=>i.status==='생성중'))return '<button class="light" disabled>이미지 생성 중…</button>';
 const label=images.length&&images.every(i=>i.status==='완료')?'컷 이미지 다시 생성':'컷 이미지 생성';
 return `<button class="light" data-toon-image="${esc(x.id)}">${label}</button>`;
}
// 컷은 접어 둔다. 8컷이면 카드 하나가 화면을 다 먹는다.

const j=u=>fetch(u).then(r=>r.ok?(r.status===204?null:r.json()):null).catch(()=>null);
// 응답을 다 모아 기다리지 않고 도착하는 대로 그린다. 외부 API(Gmail·토스)를 부르는
// /api/dashboard 가 2초 걸려도 나머지 화면은 먼저 뜬다.
// drawNull 은 값이 없을 때도 그려야 하는 화면(브리핑·Slack)에만 켠다.
function load(){
 const paint=(url,render,drawNull)=>j(url).then(v=>{if(v||drawNull)render(v)});
 return Promise.all([
  paint('/api/dashboard',renderDashboard),
  paint('/api/content-packages',renderContentPackages),
  paint('/api/ai-news',renderNews),
  paint('/api/ai-news/briefing',renderBriefing,true),
  loadAiOperations(),
  paint('/api/slack/status',renderSlack,true),
  paint('/api/instagram-toons',cacheToons),
 ]).then(()=>{pollToons();pollPackages()});
}
async function newsRead(id){await fetch(`/api/ai-news/${id}/read`,{method:'PATCH'}).then(r=>r.json()).then(renderNews)}
$('refresh').onclick=load;
const toggleTrace=open=>{$('trace').hidden=!open;document.body.classList.toggle('trace-open',open);if(open)renderTrace()};
$('trace-toggle').onclick=()=>toggleTrace($('trace').hidden);$('trace-close').onclick=()=>toggleTrace(false);
$('news-list').addEventListener('click',e=>{const a=e.target.closest('a[data-news-id]');if(a)newsRead(a.dataset.newsId)});
$('news-refresh').onclick=async()=>{const b=$('news-refresh');b.disabled=true;b.textContent='수집 중…';try{if(pageState['news-list'])pageState['news-list'].page=0;renderNews(await fetch('/api/ai-news/refresh',{method:'POST'}).then(r=>r.json()))}finally{b.disabled=false;b.textContent='소식 가져오기'}};
$('briefing-refresh').onclick=async()=>{const b=$('briefing-refresh');b.disabled=true;b.textContent='요약 중…';try{const r=await fetch('/api/ai-news/briefing/refresh',{method:'POST'});if(!r.ok){const error=await r.json();throw new Error(error.detail||'요약 생성에 실패했습니다.')}renderBriefing(await r.json())}catch(error){alert(error.message)}finally{b.disabled=false;b.textContent='핵심 3건 요약';await loadAiOperations()}};
$('ai-operations-refresh').onclick=loadAiOperations;
$('content-package-form').onsubmit=async e=>{e.preventDefault();const form=e.target,button=$('content-package-submit');const data=new FormData(form);const payload={source:data.get('source'),tone:data.get('tone'),target:data.get('target'),channels:data.getAll('channels'),panelCount:Number(data.get('panelCount')),sourceId:data.get('sourceId')||null,keywordId:data.get('keywordId')?Number(data.get('keywordId')):null};button.disabled=true;button.textContent='패키지 생성 중…';try{const r=await fetch('/api/content-packages',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});if(!r.ok){const error=await r.json();throw new Error(error.detail||'콘텐츠 패키지 생성에 실패했습니다.')}form.reset();form.sourceId.value='';form.keywordId.value='';renderContentPackages(await j('/api/content-packages')||[]);pollPackages()}catch(error){alert(error.message)}finally{button.disabled=false;button.textContent='콘텐츠 패키지 생성'}};
// 채널은 서버가 백그라운드에서 채운다(202). 생성중이 사라질 때까지 목록을 다시 읽고, 끝나면 툰·초안·운영 센터도 갱신한다.
let packageTimer=null;
function pollPackages(){
 clearTimeout(packageTimer);
 packageTimer=setTimeout(async()=>{
  const items=await j('/api/content-packages');if(!items)return;
  renderContentPackages(items);
  if(items.some(x=>x.outputs.some(o=>o.status==='생성중')))pollPackages();
  else{cacheToons(await j('/api/instagram-toons'));renderContentPackages();await loadAiOperations()}
 },3000);
}
// 소식·키워드에서 우선순위 1건을 가져와 원본 칸을 채운다. 생성은 사용자가 채널을 고르고 누른다.
$('topic-candidate').onclick=async()=>{const b=$('topic-candidate'),form=$('content-package-form');b.disabled=true;b.textContent='가져오는 중…';try{const r=await fetch('/api/topic-candidates/next');if(r.status===204){alert('새 주제가 없습니다. 소식이나 키워드가 갱신된 뒤 다시 시도하세요.');return}if(!r.ok){const error=await r.json().catch(()=>({}));throw new Error(error.detail||'주제를 가져오지 못했습니다.')}const c=await r.json();form.source.value=`${c.title}\n\n${c.context}`;form.sourceId.value=c.sourceId;form.keywordId.value=c.keywordId??''}catch(error){alert(error.message)}finally{b.disabled=false;b.textContent='소식·키워드에서 주제 가져오기'}};
document.addEventListener('click',async e=>{const b=e.target.closest('button[data-review]');if(!b)return;const [id,channel,reviewStatus]=b.dataset.review.split('|');b.disabled=true;try{const r=await fetch(`/api/content-packages/${encodeURIComponent(id)}/outputs/${encodeURIComponent(channel)}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({reviewStatus})});if(!r.ok){const error=await r.json().catch(()=>({}));throw new Error(error.detail||'검토 상태를 바꾸지 못했습니다.')}renderContentPackages(await j('/api/content-packages')||[])}catch(error){alert(error.message);b.disabled=false}});
document.addEventListener('click',async e=>{const b=e.target.closest('button[data-package-notify]');if(!b)return;b.disabled=true;b.textContent='재시도 중…';try{const r=await fetch(`/api/content-packages/${encodeURIComponent(b.dataset.packageNotify)}/notify`,{method:'POST'});if(!r.ok){const error=await r.json().catch(()=>({}));throw new Error(error.detail||'Slack 알림 재시도에 실패했습니다.')}const pkg=await r.json();if(pkg.slackStatus!=='SENT')alert(`Slack 알림을 보내지 못했습니다: ${pkg.slackError||'웹훅이 설정되지 않았습니다.'}`);renderContentPackages(await j('/api/content-packages')||[])}catch(error){alert(error.message)}});
document.addEventListener('click',async e=>{
 const button=e.target.closest('[data-toon-image]');if(!button)return;
 button.disabled=true;
 try{
  const r=await fetch(`/api/instagram-toons/${encodeURIComponent(button.dataset.toonImage)}/images`,{method:'POST'});
  if(!r.ok){const error=await r.json().catch(()=>({}));throw new Error(error.detail||'이미지 생성을 시작하지 못했습니다.')}
  cacheToons(await j('/api/instagram-toons'));renderContentPackages();
  pollToons();
 }catch(error){alert(error.message);button.disabled=false}
});
// 생성중인 컷이 남아 있는 동안만 3초마다 다시 그린다. 끝나면 스스로 멈추고 운영 센터를 한 번 갱신한다.
let toonTimer=null;
function pollToons(){
 clearTimeout(toonTimer);
 toonTimer=setTimeout(async()=>{
  const items=await j('/api/instagram-toons');if(!items)return;
  cacheToons(items);renderContentPackages();
  if(items.some(x=>(x.images||[]).some(i=>i.status==='생성중')))pollToons();
  else{await loadAiOperations()}
 },3000);
}
$('login-button').onclick=async()=>{const r=await fetch('/api/auth/login');if(!r.ok){const err=await r.json().catch(()=>({}));alert(err.detail||'로그인 주소를 가져오지 못했습니다.');return}location.href=(await r.json()).url};
$('logout-button').onclick=async()=>{await fetch('/api/auth/logout',{method:'POST'});location.reload()};
// 로그인 없이 둘러보기. 서버가 데모 세션 쿠키를 내려주면 새로고침만으로 평소 화면 흐름을 탄다.
$('demo-button').onclick=async()=>{const r=await fetch('/api/auth/demo',{method:'POST'});if(!r.ok){alert((await r.json().catch(()=>({}))).detail||'데모를 시작하지 못했습니다.');return}location.reload()};
$('slack-connect').onclick=async()=>{const r=await fetch('/api/slack/connect');if(!r.ok){const err=await r.json().catch(()=>({}));alert(err.detail||'Slack 연결 주소를 가져오지 못했습니다.');return}location.href=(await r.json()).url};
$('slack-status').addEventListener('click',async e=>{if(!e.target.closest('#slack-channel-load'))return;const channels=await j('/api/slack/channels');if(!channels){alert('채널 목록을 가져오지 못했습니다. 봇 권한을 확인하세요.');return}SLACK_STATE.channels=channels;renderSlack(await j('/api/slack/status'))});
$('slack-status').addEventListener('change',async e=>{if(e.target.id!=='slack-channel'||!e.target.value)return;const r=await fetch('/api/slack/channel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({channelId:e.target.value})});if(!r.ok){const err=await r.json().catch(()=>({}));alert(err.detail||'채널을 저장하지 못했습니다.');return}renderSlack(await r.json())});
// 세션이 없으면 대시보드 데이터를 부르지 않는다. 401 이 줄줄이 뜨는 걸 막는다.
async function start(){
 const me=await fetch('/api/auth/me').catch(()=>null);
 if(!me||!me.ok){showLogin();return}
 hideLogin();
 const info=await me.json();
 $('profile-email').textContent=info.email;
 // 데모는 배너를 띄우고, 개인 계정이 필요한 칸의 안내 문구를 켠다(CSS 가 body.demo 로 판단한다).
 if(info.demo){document.body.classList.add('demo');$('demo-banner').hidden=false;$('profile-email').textContent='데모 사용자'}
 load();
}
start();






// 사이드 메뉴: 화면 위쪽 30% 선을 지난 섹션 중 가장 아래 것을 현재 위치로 표시한다.
// 두 열 배치에서는 한 행의 두 패널이 같은 높이에서 시작하므로, 동점이면 방금 누른 메뉴(location.hash)를 우선한다.
const navLinks=[...document.querySelectorAll('nav a[href^="#"]')],navSections=navLinks.map(a=>document.getElementById(a.hash.slice(1))).filter(Boolean);
function spyNav(){
 const line=innerHeight*.3,atBottom=innerHeight+scrollY>=document.documentElement.scrollHeight-2;
 let best=null,bestTop=-Infinity;
 for(const s of navSections){const top=s.getBoundingClientRect().top;if(top>line&&!atBottom)continue;if(top>bestTop||(top===bestTop&&'#'+s.id===location.hash)){best=s;bestTop=top}}
 const id=(best||navSections[0]).id;
 navLinks.forEach(a=>a.classList.toggle('active',a.hash==='#'+id));
}
addEventListener('scroll',spyNav,{passive:true});addEventListener('hashchange',spyNav);spyNav();

$('automation-content-run').onclick=async()=>{const b=$('automation-content-run');b.disabled=true;b.textContent='워커 실행 중…';try{const r=await fetch('/api/automation/content',{method:'POST'});const data=await r.json();alert(data.output||(data.success?'워커 실행이 완료되었습니다.':'워커 실행에 실패했습니다.'))}catch(error){alert('워커 호출에 실패했습니다: '+error.message)}finally{b.disabled=false;b.textContent='워커 실행 테스트'}};
