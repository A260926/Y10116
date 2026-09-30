"use strict";
const $ = (id) => document.getElementById(id);
let scenarios = [];
let state = { A: null, B: null };

async function api(path, method, body) {
  const res = await fetch(path, {
    method: method || "GET",
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  });
  const json = await res.json();
  if (!res.ok) { const e = new Error(json.error || ("HTTP " + res.status)); e.status = res.status; throw e; }
  return json;
}

async function init() {
  scenarios = await api("/api/scenarios");
  for (const s of scenarios) {
    const o = document.createElement("option");
    o.value = s.id; o.textContent = s.name;
    $("scenarioSelect").appendChild(o);
  }
}

async function load() {
  const id = Number($("scenarioSelect").value);
  const d = await api(`/api/scenarios/${id}/draft`);
  const text = JSON.stringify(d.config, null, 2);
  $("draftA").value = text; $("draftB").value = text;
  state.A = d.version; state.B = d.version;
  $("verA").textContent = "baseVersion " + d.version;
  $("verB").textContent = "baseVersion " + d.version;
  note("A", "已加载", "ok"); note("B", "已加载", "ok");
}

function note(which, msg, cls) {
  const n = $("note" + which); n.textContent = msg; n.className = "note " + (cls || "");
}

async function save(which) {
  const id = Number($("scenarioSelect").value);
  let config;
  try { config = JSON.parse($("draft" + which).value); }
  catch (e) { note(which, "JSON 解析失败: " + e.message, "err"); return; }
  try {
    const r = await api(`/api/scenarios/${id}/draft`, "PUT", {
      baseVersion: state[which], config
    });
    state[which] = r.version;
    $("ver" + which).textContent = "baseVersion " + r.version;
    note(which, "保存成功，版本号 → " + r.version, "ok");
  } catch (e) {
    note(which, "保存被拒绝：" + e.message + "（请重新加载并合并）", "err");
  }
}

async function publish(which) {
  const id = Number($("scenarioSelect").value);
  try {
    const r = await api(`/api/scenarios/${id}/publish`, "POST", {});
    note(which, "已发布为 revision " + r.revision + "（不可修改，只能再发新版本）", "ok");
  } catch (e) { note(which, "发布被拒绝：" + e.message, "err"); }
}

$("loadBtn").onclick = load;
$("saveA").onclick = () => save("A");
$("saveB").onclick = () => save("B");
$("publishA").onclick = () => publish("A");
$("publishB").onclick = () => publish("B");
init();
