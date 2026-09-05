// 인증은 로그인 세션 쿠키로 한다. 공유 API 키는 없앴다.
const _fetch=window.fetch.bind(window);
window.fetch=(u,o={})=>_fetch(u,String(u).startsWith('/api/')?{...o,credentials:'include'}:o).then(r=>{
 if(r.status===401&&!String(u).includes('/api/auth/'))showLogin();
 return r;
});
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
// AI 운영 센터: 서버는 보관 중인 실행을 전부 주고, 기간·기능·모델 필터와 집계는 여기서 한다.
// 타일·모델별 표·목록이 같은 필터 배열에서 나오므로 서로 어긋날 수 없다.
const fmtN=n=>new Intl.NumberFormat('ko-KR').format(n||0),usd=n=>`$${Number(n||0).toFixed(4)}`;
// 서버 AiOperationsService.NON_MODEL_LABELS 와 같은 목록. 모델을 안 쓰는 실행은 표에서 뺀다.
const NON_MODEL=new Set(['모델 사용 안 함','초안 템플릿']);
const AI_OPS={items:[]};
const localDate=d=>new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,10);
// 고정 옵션(keep개)은 남기고 뒤를 데이터로 채운다. 고르고 있던 값은 유지한다.
function fillSelect(id,options,keep){const s=$(id),v=s.value;s.innerHTML=[...s.options].slice(0,keep).map(o=>o.outerHTML).join('')+options.map(([value,label])=>`<option value="${esc(value)}">${esc(label)}</option>`).join('');s.value=v;if(s.selectedIndex<0)s.selectedIndex=0}
function aiFiltered(items){
 const range=$('ai-filter-range').value,agent=$('ai-filter-agent').value,model=$('ai-filter-model').value;
 const today=localDate(new Date()),weekAgo=localDate(new Date(Date.now()-6*86400000));
 const inRange=x=>range==='today'?x.executedAt.slice(0,10)===today:range==='7d'?x.executedAt.slice(0,10)>=weekAgo:x.executedAt.slice(0,7)===range;
 return items.filter(x=>inRange(x)&&(!agent||x.agent===agent)&&(!model||x.model===model));
}
function modelMatrix(items){
 const m={};
 for(const x of items){if(NON_MODEL.has(x.model))continue;const r=m[x.model]||(m[x.model]={model:x.model,runs:0,inputTokens:0,outputTokens:0,estimatedCostUsd:0,durationMs:0});r.runs++;r.inputTokens+=x.inputTokens;r.outputTokens+=x.outputTokens;r.estimatedCostUsd+=x.estimatedCostUsd;r.durationMs+=x.durationMs}
 return Object.values(m).sort((a,b)=>b.runs-a.runs);
}
function renderAiOperations(data){
 AI_OPS.items=data.items||[];
 const distinct=f=>[...new Set(AI_OPS.items.map(f))];
 fillSelect('ai-filter-agent',distinct(x=>x.agent).sort().map(a=>[a,a]),1);
 fillSelect('ai-filter-model',distinct(x=>x.model).sort().map(m=>[m,m]),1);
 fillSelect('ai-filter-range',distinct(x=>x.executedAt.slice(0,7)).sort().reverse().map(ym=>[ym,`${ym.slice(0,4)}년 ${Number(ym.slice(5,7))}월`]),2);
 renderAiFiltered();
}
function renderAiFiltered(){
 const all=AI_OPS.items,items=aiFiltered(all),sum=k=>items.reduce((a,x)=>a+(x[k]||0),0);
 $('ai-run-count').textContent=items.length;$('ai-success-count').textContent=items.filter(x=>x.status==='성공').length;
 $('ai-token-count').textContent=`${fmtN(sum('inputTokens'))} / ${fmtN(sum('outputTokens'))}`;$('ai-cost').textContent=usd(sum('estimatedCostUsd'));$('ai-duration').textContent=dur(sum('durationMs'));
 const rows=modelMatrix(items),t=$('ai-model-matrix');t.hidden=!rows.length;
 t.innerHTML=`<thead><tr><th>모델</th><th>실행</th><th>입력 토큰</th><th>출력 토큰</th><th>예상 비용</th><th>평균 시간</th></tr></thead><tbody>${rows.map(r=>`<tr><td>${esc(r.model)}</td><td>${r.runs}</td><td>${fmtN(r.inputTokens)}</td><td>${fmtN(r.outputTokens)}</td><td>${usd(r.estimatedCostUsd)}</td><td>${dur(r.durationMs/r.runs)}</td></tr>`).join('')}</tbody>`;
 renderPaged('ai-operation-list',items,'ai-operation-list',all.length?'이 조건에 해당하는 실행이 없습니다.':'아직 AI 실행 이력이 없습니다.',x=>`<article class="ai-operation"><div class="ai-operation-head"><div><span class="tag ${x.status==='성공'?'done':'late'}">${esc(x.status)}</span><b>${esc(x.agent)}</b><span>${esc(x.executedAt.replace('T',' ').slice(0,16))} · ${dur(x.durationMs)}</span></div><strong>${usd(x.estimatedCostUsd)}</strong></div><p><b>${esc(x.provider)}</b> · ${esc(x.model)} · 입력 ${fmtN(x.inputTokens)} · 출력 ${fmtN(x.outputTokens)}</p><div class="tool-list">${x.tools.map(tool=>`<span>${esc(tool)}</span>`).join('')}</div><small>${esc(x.error||x.resultPreview||'결과 정보가 없습니다.')}</small></article>`);
}
['ai-filter-agent','ai-filter-model','ai-filter-range'].forEach(id=>$(id).onchange=()=>{if(pageState['ai-operation-list'])pageState['ai-operation-list'].page=0;renderAiFiltered()});
function renderContentPackages(items){renderPaged('content-package-list',items,'content-package-list','아직 생성된 콘텐츠 패키지가 없습니다.',item=>`<article class="content-package"><b>${esc(item.title)}</b><p>${esc(item.tone)} · ${esc(item.target)} · ${esc(item.createdAt.replace('T',' ').slice(0,16))}</p><div class="content-output-grid">${item.outputs.map(output=>`<details><summary>${esc(output.channel)} · ${esc(output.title)}</summary><pre>${esc(output.body)}</pre></details>`).join('')}</div></article>`)}
const SLACK_LABEL={SENT:'Slack 전송됨',FAILED:'Slack 전송 실패',NOT_CONFIGURED:'Slack 미설정'};
function renderTopicDrafts(items){renderPaged('topic-draft-list',items,'topic-draft-list','아직 생성된 대본 초안이 없습니다.',x=>`<article class="topic-draft" id="topic-draft-${esc(x.id)}"><div class="topic-draft-head"><div><b>${esc(x.title)}</b><p class="meta"><span class="tag wait">검토 대기</span> <span class="tag ${x.slackStatus==='SENT'?'done':'late'}">${esc(SLACK_LABEL[x.slackStatus]||x.slackStatus)}</span> ${esc(x.source)} · ${esc(x.category)} · 우선순위 ${Number(x.priorityScore).toFixed(2)} · ${esc(x.createdAt.replace('T',' ').slice(0,16))}</p></div>${x.slackStatus==='SENT'?'':`<button class="light" data-notify-id="${esc(x.id)}">Slack 알림 재시도</button>`}</div><p class="hook">${esc(x.hook)}</p><pre>${esc(x.script)}</pre><p class="meta">${(x.hashtags||[]).map(t=>esc(t)).join(' ')}</p>${x.slackError?`<p class="slack-error">${esc(x.slackError)}</p>`:''}${x.sourceUrl?`<a class="text-link" href="${esc(x.sourceUrl)}" target="_blank" rel="noreferrer">출처 원문 열기</a>`:''}</article>`);focusHashDraft()}
// Slack 알림의 검토 링크는 특정 초안을 가리킨다. 그 초안이 뒤 페이지에 있으면
// 링크를 눌러도 아무 일이 없으므로, 해당 페이지로 옮긴 뒤 그 카드로 스크롤한다.
function focusHashDraft(){const id=location.hash.replace('#topic-draft-','');if(!id||id===location.hash)return;const s=pageState['topic-draft-list'],i=s.items.findIndex(x=>x.id===id);if(i<0)return;const page=Math.floor(i/PAGE_SIZE);if(page!==s.page){s.page=page;renderPaged('topic-draft-list')}document.getElementById('topic-draft-'+id)?.scrollIntoView({block:'center'})}
const SLACK_STATE={channels:[]};
function renderSlack(status){const target=$('slack-status');if(!status){target.className='empty';target.textContent='Slack 상태를 불러오지 못했습니다.';return}
 if(!status.configured){target.className='empty';target.innerHTML='Slack 앱 자격증명이 아직 설정되지 않았습니다. <code>office.slack.client-id</code>와 <code>client-secret</code>을 설정하세요.';$('slack-connect').disabled=true;return}
 $('slack-connect').disabled=false;$('slack-connect').textContent=status.connected?'Slack 다시 연결':'Slack 연결';
 if(!status.connected){target.className='empty';target.textContent='아직 연결되지 않았습니다. Slack 연결 버튼으로 앱을 설치하세요.';return}
 target.className='slack-status';
 const options=SLACK_STATE.channels.map(c=>`<option value="${esc(c.id)}" ${c.id===status.channelId?'selected':''}>#${esc(c.name)}</option>`).join('');
 target.innerHTML=`<span class="tag done">연결됨</span><b>${esc(status.teamName||'워크스페이스')}</b><span>알림 채널</span><select id="slack-channel">${options||'<option value="">채널 목록을 불러오세요</option>'}</select><button class="light" id="slack-channel-load">채널 목록 새로고침</button>${status.channelName?`<span class="muted">현재 #${esc(status.channelName)}</span>`:'<span class="muted">채널을 고르면 알림이 시작됩니다.</span>'}`;
}
const j=u=>fetch(u).then(r=>r.ok?(r.status===204?null:r.json()):null).catch(()=>null);
async function load(){const [d,packages,news,briefing,aiOperations,topicDrafts,slack]=await Promise.all([j('/api/dashboard'),j('/api/content-packages'),j('/api/ai-news'),j('/api/ai-news/briefing'),j('/api/ai-operations'),j('/api/topic-drafts'),j('/api/slack/status')]);if(d)renderDashboard(d);if(packages)renderContentPackages(packages);if(news)renderNews(news);renderBriefing(briefing);if(aiOperations)renderAiOperations(aiOperations);if(topicDrafts)renderTopicDrafts(topicDrafts);renderSlack(slack)}
async function newsRead(id){await fetch(`/api/ai-news/${id}/read`,{method:'PATCH'}).then(r=>r.json()).then(renderNews)}
$('refresh').onclick=load;
$('news-list').addEventListener('click',e=>{const a=e.target.closest('a[data-news-id]');if(a)newsRead(a.dataset.newsId)});
$('news-refresh').onclick=async()=>{const b=$('news-refresh');b.disabled=true;b.textContent='수집 중…';try{if(pageState['news-list'])pageState['news-list'].page=0;renderNews(await fetch('/api/ai-news/refresh',{method:'POST'}).then(r=>r.json()))}finally{b.disabled=false;b.textContent='소식 가져오기'}};
$('briefing-refresh').onclick=async()=>{const b=$('briefing-refresh');b.disabled=true;b.textContent='요약 중…';try{const r=await fetch('/api/ai-news/briefing/refresh',{method:'POST'});if(!r.ok){const error=await r.json();throw new Error(error.detail||'요약 생성에 실패했습니다.')}renderBriefing(await r.json())}catch(error){alert(error.message)}finally{b.disabled=false;b.textContent='핵심 3건 요약';const ops=await fetch('/api/ai-operations').then(r=>r.ok?r.json():null).catch(()=>null);if(ops)renderAiOperations(ops)}};
$('ai-operations-refresh').onclick=async()=>renderAiOperations(await fetch('/api/ai-operations').then(r=>r.json()));
$('content-package-form').onsubmit=async e=>{e.preventDefault();const form=e.target,button=$('content-package-submit');const data=new FormData(form);const payload={source:data.get('source'),tone:data.get('tone'),target:data.get('target'),channels:data.getAll('channels')};button.disabled=true;button.textContent='패키지 생성 중…';try{const r=await fetch('/api/content-packages',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});if(!r.ok){const error=await r.json();throw new Error(error.detail||'콘텐츠 패키지 생성에 실패했습니다.')}form.reset();renderContentPackages(await fetch('/api/content-packages').then(response=>response.json()));renderAiOperations(await fetch('/api/ai-operations').then(response=>response.json()))}catch(error){alert(error.message)}finally{button.disabled=false;button.textContent='콘텐츠 패키지 생성'}};
$('topic-draft-refresh').onclick=async()=>{const b=$('topic-draft-refresh');b.disabled=true;b.textContent='초안 생성 중…';try{const r=await fetch('/api/topic-drafts/refresh',{method:'POST'});if(!r.ok){const error=await r.json().catch(()=>({}));throw new Error(error.detail||'대본 초안 생성에 실패했습니다.')}renderTopicDrafts(await j('/api/topic-drafts')||[])}catch(error){alert(error.message)}finally{b.disabled=false;b.textContent='주제 수집 및 초안 생성';const ops=await j('/api/ai-operations');if(ops)renderAiOperations(ops)}};
$('topic-draft-list').addEventListener('click',async e=>{const button=e.target.closest('button[data-notify-id]');if(!button)return;button.disabled=true;button.textContent='재시도 중…';try{const r=await fetch(`/api/topic-drafts/${button.dataset.notifyId}/notify`,{method:'POST'});if(!r.ok){const error=await r.json().catch(()=>({}));throw new Error(error.detail||'Slack 알림 재시도에 실패했습니다.')}const draft=await r.json();if(draft.slackStatus!=='SENT')alert(`Slack 알림을 보내지 못했습니다: ${draft.slackError||'웹훅이 설정되지 않았습니다.'}`);renderTopicDrafts(await j('/api/topic-drafts')||[])}catch(error){alert(error.message);button.disabled=false;button.textContent='Slack 알림 재시도'}});
$('login-button').onclick=async()=>{const r=await fetch('/api/auth/login');if(!r.ok){const err=await r.json().catch(()=>({}));alert(err.detail||'로그인 주소를 가져오지 못했습니다.');return}location.href=(await r.json()).url};
$('logout-button').onclick=async()=>{await fetch('/api/auth/logout',{method:'POST'});location.reload()};
$('slack-connect').onclick=async()=>{const r=await fetch('/api/slack/connect');if(!r.ok){const err=await r.json().catch(()=>({}));alert(err.detail||'Slack 연결 주소를 가져오지 못했습니다.');return}location.href=(await r.json()).url};
$('slack-status').addEventListener('click',async e=>{if(!e.target.closest('#slack-channel-load'))return;const channels=await j('/api/slack/channels');if(!channels){alert('채널 목록을 가져오지 못했습니다. 봇 권한을 확인하세요.');return}SLACK_STATE.channels=channels;renderSlack(await j('/api/slack/status'))});
$('slack-status').addEventListener('change',async e=>{if(e.target.id!=='slack-channel'||!e.target.value)return;const r=await fetch('/api/slack/channel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({channelId:e.target.value})});if(!r.ok){const err=await r.json().catch(()=>({}));alert(err.detail||'채널을 저장하지 못했습니다.');return}renderSlack(await r.json())});
// 세션이 없으면 대시보드 데이터를 부르지 않는다. 401 이 줄줄이 뜨는 걸 막는다.
async function start(){const me=await fetch('/api/auth/me').catch(()=>null);if(!me||!me.ok){showLogin();return}hideLogin();$('profile-email').textContent=(await me.json()).email;load()}
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
