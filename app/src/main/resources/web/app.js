"use strict";

const $ = (id) => document.getElementById(id);
let scenarios = [];
let current = null;   // session view
let sessionId = null;
let published = null; // published config

const defaultOffer = {
  clientId: "client-X",
  versions: ["v3", "v2", "v1"],
  caps: ["base", "reliable"],
  requiredExts: ["tls"],
  optionalExts: ["compress", "auth"],
  paramInts: { timeout: 45 },
  paramStrings: { mode: "safe" }
};
$("offerBox").value = JSON.stringify(defaultOffer, null, 2);

async function api(path, method, body) {
  const res = await fetch(path, {
    method: method || "GET",
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch (_) { json = { raw: text }; }
  if (!res.ok) {
    const err = new Error((json && json.error) || ("HTTP " + res.status));
    err.status = res.status; err.body = json; throw err;
  }
  return json;
}

async function loadScenarios() {
  scenarios = await api("/api/scenarios");
  const sel = $("scenarioSelect");
  sel.innerHTML = "";
  for (const s of scenarios) {
    const o = document.createElement("option");
    o.value = s.id; o.textContent = `${s.name} (rev ${s.revision}, ${s.serverId})`;
    sel.appendChild(o);
  }
  if (scenarios.length) await selectScenario();
}

async function selectScenario() {
  const id = Number($("scenarioSelect").value);
  const s = scenarios.find((x) => x.id === id);
  $("revInfo").textContent = `已发布 revision ${s.revision}`;
  published = await api(`/api/scenarios/${id}/published/${s.revision}`);
  renderCaps(s);
  resetFlow();
  await loadSeedSession();
}

function renderCaps(s) {
  const cfg = published.config;
  const offer = JSON.parse($("offerBox").value);
  const client = {
    supportsVersions: offer.versions,
    capabilities: offer.caps,
    requiredExts: offer.requiredExts,
    optionalExts: offer.optionalExts,
    params: { ...offer.paramInts, ...offer.paramStrings }
  };
  const serverCaps = new Set();
  for (const v of cfg.versions) for (const c of v.requiredCaps) serverCaps.add(c);
  const server = {
    serverId: s.serverId,
    versions: cfg.versions.map((v) => ({
      version: v.version, priority: v.priority, requiredCaps: v.requiredCaps,
      extensions: v.extensions.map((e) => e.name + (e.required ? "!" : "")),
      degradeTo: v.degradeTo
    })),
    capabilities: [...serverCaps],
    mutexGroups: cfg.mutexGroups
  };
  $("clientCaps").textContent = JSON.stringify(client, null, 2);
  $("serverCaps").textContent = JSON.stringify(server, null, 2);
}

function resetFlow() {
  current = null; sessionId = null;
  $("selectBtn").disabled = true; $("confirmBtn").disabled = true;
  $("reofferBtn").disabled = true; $("exportBtn").disabled = true;
  $("messages").innerHTML = ""; $("rejections").innerHTML = "";
  $("finalParams").textContent = "—"; $("sessionState").textContent = "—";
  setNote("", "");
}

async function loadSeedSession() {
  // the seeded session carries a known idempotency key; offer with same key replays it
  const id = Number($("scenarioSelect").value);
  try {
    const offer = JSON.parse($("offerBox").value);
    const v = await api(`/api/scenarios/${id}/offer`, "POST", {
      idemKey: "seed-demo-v3-to-v2", offer
    });
    bindSession(v);
    setNote("已恢复预置会话（停在 select）：再发 select 必须返回同一选择", "warn");
  } catch (e) {
    setNote("无预置会话：点击 offer 新建", "warn");
  }
}

function bindSession(v) {
  // Merge with any prior view so confirm/active responses keep the select panel
  // (candidate rejections, chosen version) visible.
  if (current && current.sessionId === v.sessionId && !v.select && current.select) {
    v.select = current.select;
  }
  current = v; sessionId = v.sessionId;
  $("exportBtn").disabled = false;
  renderAll();
}

function setNote(msg, cls) {
  const n = $("actionNote");
  n.textContent = msg; n.className = "note " + (cls || "");
}

function renderAll() {
  renderMessages();
  renderState();
  const stage = current.stage;
  $("selectBtn").disabled = stage !== "offer" && current.select === undefined;
  if (current.select !== undefined) $("selectBtn").disabled = false;
  $("confirmBtn").disabled = !(current.select && current.select.result && current.select.result.success);
  if (stage === "active") $("confirmBtn").disabled = false;
  $("reofferBtn").disabled = false;
}

function renderMessages() {
  const box = $("messages"); box.innerHTML = "";
  const msgs = current.messages || [];
  for (const m of msgs) {
    const div = document.createElement("div");
    div.className = "msg";
    div.innerHTML = `<div class="head">
        <span class="dir ${m.direction}">${m.direction}</span>
        <span class="phase">${m.phase} · gen ${m.generation}</span>
        <span class="bytes">${m.bytes} B</span>
      </div>
      ${m.note ? `<div class="msgnote">${escapeHtml(m.note)}</div>` : ""}
      <pre>${escapeHtml(JSON.stringify(m.body, null, 2))}</pre>`;
    box.appendChild(div);
  }
  const rj = $("rejections"); rj.innerHTML = "";
  const result = current.select && current.select.result;
  if (result) {
    if (result.visitedPath && result.visitedPath.length) {
      const p = document.createElement("div");
      p.className = "path";
      p.textContent = "显式边访问路径: " + result.visitedPath.join(" → ");
      rj.appendChild(p);
    }
    for (const r of result.rejections || []) {
      const d = document.createElement("div");
      d.className = "rj";
      d.innerHTML = `<span class="ver">${escapeHtml(r.version)}</span>
        <span class="why">${escapeHtml(r.reason)}</span>`;
      rj.appendChild(d);
    }
    if (result.success) {
      $("finalParams").textContent = JSON.stringify(
        { chosenVersion: result.chosenVersion, extensions: result.extensions,
          params: result.params, tieBreak: result.tieBreak }, null, 2);
    } else {
      $("finalParams").textContent = "明确失败: " + result.failureReason;
    }
  }
}

function renderState() {
  const s = {
    sessionId: current.sessionId, stage: current.stage,
    generation: current.generation, revision: current.revision,
    idemKey: current.idemKey,
    confirmToken: current.confirmToken
  };
  $("sessionState").textContent = JSON.stringify(s, null, 2);
}

$("reloadBtn").onclick = async () => { await loadScenarios(); };
$("scenarioSelect").onchange = selectScenario;
$("offerBox").oninput = () => { try { const id = scenarios[Number($("scenarioSelect").value)-1]; renderCaps(scenarios.find(x=>x.id===Number($("scenarioSelect").value))); } catch(_){} };

$("offerBtn").onclick = async () => {
  try {
    const id = Number($("scenarioSelect").value);
    const offer = JSON.parse($("offerBox").value);
    const v = await api(`/api/scenarios/${id}/offer`, "POST", {
      idemKey: $("idemKey").value.trim(), offer
    });
    bindSession(v);
    setNote(v.replayed ? "相同幂等键+相同 offer：返回原会话" : "offer 已记录", v.replayed ? "warn" : "ok");
  } catch (e) { setNote(e.message, "err"); }
};

$("selectBtn").onclick = async () => {
  try {
    const body = { crashAfterSelect: $("crashSelect").checked };
    if ($("dupSelect").checked) body.selection = { fake: true }; // mismatched selection -> 409
    const v = await api(`/api/sessions/${sessionId}/select`, "POST", body);
    bindSession(v);
    setNote($("crashSelect").checked
      ? "进程在保存 select 后退出（模拟）。再次 select 将返回存储的同一选择"
      : "select 完成", "ok");
  } catch (e) {
    if (e.status === 599) {
      await refreshSession();
      setNote("模拟进程退出：select 已持久化，无响应。再点 select 验证恢复", "warn");
    } else setNote(e.message, "err");
  }
};

$("confirmBtn").onclick = async () => {
  try {
    const result = current.select.result;
    const body = {
      token: $("staleToken").checked ? "old-stale-token" : current.confirmToken,
      selection: result,
      simulateLoss: $("simulateLoss").checked
    };
    const v = await api(`/api/sessions/${sessionId}/confirm`, "POST", body);
    bindSession(v);
    setNote($("simulateLoss").checked
      ? "confirm 回复丢失（服务器已 ACTIVE）：再次 confirm 返回原结果"
      : "会话已 ACTIVE", "ok");
    if ($("dupConfirm").checked) {
      const again = await api(`/api/sessions/${sessionId}/confirm`, "POST", body);
      bindSession(again);
      setNote("重复 confirm：返回原结果，未重复激活", "ok");
    }
  } catch (e) {
    if (e.status === 599) { await refreshSession(); setNote("confirm 丢失模拟：再次 confirm", "warn"); }
    else setNote(e.message, "err");
  }
};

$("reofferBtn").onclick = async () => {
  try {
    const v = await api(`/api/sessions/${sessionId}/reoffer`, "POST", {});
    sessionId = v.sessionId;
    bindSession(v);
    setNote(`新代次 gen ${v.generation}：旧 token 不能激活新代次`, "warn");
  } catch (e) { setNote(e.message, "err"); }
};

$("exportBtn").onclick = async () => {
  const t = await api(`/api/sessions/${sessionId}/transcript`);
  const blob = new Blob([JSON.stringify(t, null, 2)], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob); a.download = "transcript.json"; a.click();
  setNote("transcript 已导出", "ok");
  window.__lastTranscript = t;
};

$("importBtn").onclick = async () => {
  const t = window.__lastTranscript;
  if (!t) { setNote("请先导出一个 transcript", "err"); return; }
  try {
    const id = Number($("scenarioSelect").value);
    const v = await api(`/api/sessions/${id}/transcript`, "POST", t);
    setNote("导入校验通过：重新计算得到相同选择 → " + v.selection.chosenVersion, "ok");
  } catch (e) { setNote(e.message, "err"); }
};

async function refreshSession() {
  const v = await api(`/api/sessions/${sessionId}`);
  v.messages = v.messages || [];
  if (current && current.sessionId === v.sessionId && !v.select && current.select) {
    v.select = current.select;
  }
  current = v; renderAll();
}

function escapeHtml(s) {
  return String(s).replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
}

loadScenarios();
