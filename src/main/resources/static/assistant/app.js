"use strict";
const $ = id => document.getElementById(id);
let token = sessionStorage.getItem("travelmate-token") || "";
let sessionId = sessionStorage.getItem("travelmate-session") || "";
let busy = false;
let currentCity = "";
function status(text) { $("status").textContent = text; }
function account() { $("login-form").hidden = !!token; $("account").hidden = !token; }
function el(tag, text, cls) { const n = document.createElement(tag); if(text!==undefined)n.textContent=text; if(cls)n.className=cls; return n; }
async function api(path, method="GET", body) {
  const response = await fetch(path, {method,headers:{"Content-Type":"application/json",...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();
  if(response.status===401){
    token="";sessionId="";sessionStorage.removeItem("travelmate-token");sessionStorage.removeItem("travelmate-session");account();
    $("session-status").textContent="请重新登录";
  }
  if(!response.ok||result.code!==0)throw new Error(result.message||"请求失败，请重试");
  return result.data;
}
async function login(register=false) {
  try {
    const data=await api(register?"/api/auth/register":"/api/auth/password/login","POST",{phone:$("phone").value,password:$("password").value});
    token=data.accessToken; sessionStorage.setItem("travelmate-token",token);$("password").value="";
    sessionId="";sessionStorage.removeItem("travelmate-session");account();status("登录成功，可以开启新对话。");
  } catch(e){status(e.message);}
}
$("login-form").addEventListener("submit",e=>{e.preventDefault();login();});
$("register").addEventListener("click",()=>login(true));
$("logout").addEventListener("click",async()=>{if(busy)return status("请等待当前请求完成。");try{await api("/api/auth/logout","POST",{});}catch(e){} token="";sessionId="";sessionStorage.removeItem("travelmate-token");sessionStorage.removeItem("travelmate-session");account();$("messages").replaceChildren();$("session-status").textContent="尚未开始";status("已退出登录");});
function formValues(){return {durationMinutes:Number($("duration").value),interests:$("interests").value,companions:$("companions").value};}
function syncConstraints(c){currentCity=c.cityKey;$("city").value=c.cityKey;$("duration").value=c.durationMinutes;$("interests").value=c.interests;$("companions").value=c.companions;}
$("trip-form").addEventListener("submit",async e=>{
  e.preventDefault();if(busy)return;if(!token)return status("请先登录。");
  try {const s=await api("/api/ai/assistant/sessions","POST",{cityKey:$("city").value,...formValues()});sessionId=s.id;currentCity=s.cityKey;sessionStorage.setItem("travelmate-session",s.id);$("messages").replaceChildren();$("session-status").textContent="对话已开启";status("说说你的旅行需求吧。");}catch(e){status(e.message);}
});
function bubble(text,user=false){$("messages").append(el("div",text,"bubble"+(user?" user":"")));}
function render(answer){
  bubble(answer.text);
  if(answer.route){const r=answer.route, card=el("section",undefined,"card");
    const timing=r.timingStatus==="within_budget"?`预计 ${r.totalMinutes} 分钟 / 预算 ${r.budgetMinutes} 分钟`:r.timingStatus==="infeasible"?"当前预算下暂无可行路线":"路线时间尚未验证";
    card.append(el("h3",timing));r.stops.forEach((s,i)=>{const row=el("div",undefined,"stop");row.append(el("span",`${i+1}. ${s.name} · 停留 ${s.stayMinutes??"待确认"} 分钟`));const b=el("button","替换此站");b.type="button";b.addEventListener("click",()=>{$("message").value=`请替换第${i+1}站（${s.name}），保留其他站点和已有条件。`;$("message").focus();});row.append(b);card.append(row);});
    r.warnings.forEach(w=>card.append(el("p",w,"warning")));$("messages").append(card);
  }
  if(answer.comparison?.length){const card=el("section",undefined,"card");card.append(el("h3","地点对比"));const wrap=el("div",undefined,"table-wrap"),table=el("table"),head=el("tr");["地点","类型","建议停留","开放时间","资料状态"].forEach(x=>head.append(el("th",x)));table.append(head);answer.comparison.forEach(s=>{const row=el("tr");[s.name,s.category,s.suggestedStay,s.openingHours,s.sourceStatus==="verified"?"已核实":"待核实"].forEach(x=>row.append(el("td",x)));table.append(row);});wrap.append(table);card.append(wrap);$("messages").append(card);}
  if(answer.citations?.length){const details=el("details",undefined,"sources");details.append(el("summary",`查看资料依据（${answer.citations.length}）`));answer.citations.forEach(c=>{const item=el("p");item.append(el("strong",c.title+" · "+(c.sourceStatus==="verified"?"已核实":"演示 / 待核实")));item.append(el("div",c.excerpt));if(c.sourceUrl?.startsWith("https://")){const a=el("a","查看来源");a.href=c.sourceUrl;a.target="_blank";a.rel="noopener noreferrer";item.append(a);}details.append(item);});$("messages").append(details);}
  $("messages").scrollTop=$("messages").scrollHeight;
}
$("message-form").addEventListener("submit",async e=>{
  e.preventDefault();if(busy)return;if(!sessionId)return status("请先开启新对话。");
  if(!$("trip-form").reportValidity())return;
  if($("city").value!==currentCity)return status("城市已更改，请先开启新对话。");
  const text=$("message").value.trim();if(!text)return;
  busy=true;$("send").disabled=true;status("正在查询资料并安排路线……");bubble(text,true);$("message").value="";
  try{const result=await api(`/api/ai/assistant/sessions/${encodeURIComponent(sessionId)}/messages`,"POST",{message:text,...formValues()});syncConstraints(result.constraints);render(result.answer);status(result.answer.fallback?"本次生成未完成，可以重试。":"已更新，可以继续调整。");}
  catch(e){status(e.message);$("message").value=text;}
  finally{busy=false;$("send").disabled=false;}
});
$("delete").addEventListener("click",async()=>{if(!sessionId||busy)return;try{await api(`/api/ai/assistant/sessions/${encodeURIComponent(sessionId)}`,"DELETE");sessionId="";sessionStorage.removeItem("travelmate-session");$("messages").replaceChildren();$("session-status").textContent="尚未开始";status("对话已删除。");}catch(e){status(e.message);}});
document.querySelectorAll("[data-prompt]").forEach(b=>b.addEventListener("click",()=>{$("message").value=b.dataset.prompt;$("message").focus();}));
account();
if(token&&sessionId)api(`/api/ai/assistant/sessions/${encodeURIComponent(sessionId)}`).then(s=>{syncConstraints(s);$("messages").replaceChildren();for(const m of JSON.parse(s.historyJson))bubble(m.content,m.role==="user");if(s.lastResultJson){const result=JSON.parse(s.lastResultJson);$("messages").lastChild?.remove();render(result.answer);}$("session-status").textContent="已恢复对话";}).catch(e=>{sessionId="";sessionStorage.removeItem("travelmate-session");status(e.message);});
