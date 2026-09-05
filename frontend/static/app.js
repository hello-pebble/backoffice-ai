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
function renderAiOperations(data){$('ai-run-count').textContent=data.totalRuns;$('ai-success-count').textContent=data.successfulRuns;$('ai-token-count').textContent=new Intl.NumberFormat('ko-KR').format(data.totalTokens);$('ai-cost').textContent=`$${Number(data.estimatedCostUsd).toFixed(4)}`;$('ai-duration').textContent=dur(data.totalDurationMs||0);$('ai-model-list').innerHTML=(data.models||[]).map(m=>`<span>${esc(m.model)} · ${m.runs}회</span>`).join('');renderPaged('ai-operation-list',data.items,'ai-operation-list','아직 AI 실행 이력이 없습니다.',x=>`<article class="ai-operation"><div class="ai-operation-head"><div><span class="tag ${x.status==='성공'?'done':'late'}">${esc(x.status)}</span><b>${esc(x.agent)}</b><span>${esc(x.executedAt.replace('T',' ').slice(0,16))} · ${dur(x.durationMs)}</span></div><strong>$${Number(x.estimatedCostUsd).toFixed(4)}</strong></div><p><b>${esc(x.provider)}</b> · ${esc(x.model)} · 토큰 ${new Intl.NumberFormat('ko-KR').format(x.inputTokens+x.outputTokens)}</p><div class="tool-list">${x.tools.map(tool=>`<span>${esc(tool)}</span>`).join('')}</div><small>${esc(x.error||x.resultPreview||'결과 정보가 없습니다.')}</small></article>`)}
function renderContentPackages(items){renderPaged('content-package-list',items,'content-package-list','아직 생성된 콘텐츠 패키지가 없습니다.',item=>`<article class="content-package"><b>${esc(item.title)}</b><p>${esc(item.tone)} · ${esc(item.target)} · ${esc(item.createdAt.replace('T',' ').slice(0,16))}</p><div class="content-output-grid">${item.outputs.map(output=>`<details><summary>${esc(output.channel)} · ${esc(output.title)}</summary><pre>${esc(output.body)}</pre></details>`).join('')}</div></article>`)}
const SLACK_LABEL={SENT:'Slack 전송됨',FAILED:'Slack 전송 실패',NOT_CONFIGURED:'Slack 미설정'};
function renderTopicDrafts(items){renderPaged('topic-draft-list',items,'topic-draft-list','아직 생성된 대본 초안이 없습니다.',x=>`<article class="topic-draft" id="topic-draft-${esc(x.id)}"><div class="topic-draft-head"><div><b>${esc(x.title)}</b><p class="meta"><span class="tag wait">검토 대기</span> <span class="tag ${x.slackStatus==='SENT'?'done':'late'}">${esc(SLACK_LABEL[x.slackStatus]||x.slackStatus)}</span> ${esc(x.source)} · ${esc(x.category)} · 우선순위 ${Number(x.priorityScore).toFixed(2)} · ${esc(x.createdAt.replace('T',' ').slice(0,16))}</p></div>${x.slackStatus==='SENT'?'':`<button class="light" data-notify-id="${esc(x.id)}">Slack 알림 재시도</button>`}</div><p class="hook">${esc(x.hook)}</p><pre>${esc(x.script)}</pre><p class="meta">${(x.hashtags||[]).map(t=>esc(t)).join(' ')}</p>${x.slackError?`<p class="slack-error">${esc(x.slackError)}</p>`:''}<a class="text-link" href="${esc(x.sourceUrl)}" target="_blank" rel="noreferrer">출처 원문 열기 →</a></article>`);focusHashDraft()}
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






$('automation-content-run').onclick=async()=>{const b=$('automation-content-run');b.disabled=true;b.textContent='워커 실행 중…';try{const r=await fetch('/api/automation/content',{method:'POST'});const data=await r.json();alert(data.output||(data.success?'워커 실행이 완료되었습니다.':'워커 실행에 실패했습니다.'))}catch(error){alert('워커 호출에 실패했습니다: '+error.message)}finally{b.disabled=false;b.textContent='워커 실행 테스트'}};
