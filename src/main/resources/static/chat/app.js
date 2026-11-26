"use strict";

/* Acme Support chat UI. Talks to the REST API under /api; no build step, no dependencies.
   All dynamic text is inserted with textContent (never innerHTML), so model output can't inject markup. */

const $ = (id) => document.getElementById(id);
const els = {
  log: $("log"), input: $("input"), send: $("send"), composer: $("composer"),
  progress: $("progress"), progressText: $("progressText"), banner: $("banner"),
  identity: $("identity"), newChat: $("newChat"), startAs: $("startAs"),
  specialist: $("specialist"), specialistToggle: $("specialistToggle"),
  approvals: $("approvals"), approver: $("approver"), approvalCount: $("approvalCount"),
};

const STORE_KEY = "acme-support-thread";
const NODE_LABELS = {
  start_turn: "Starting…", input_guardrail: "Checking your message…", identify_customer: "Verifying your identity…",
  blocked_response: "Replying…", load_context: "Loading your account…", load_customer: "Loading your account…",
  recall_memory: "Recalling earlier conversations…", supervisor: "Working out how to help…",
  knowledge_agent: "Searching our help articles…", order_agent: "Looking up your orders…",
  refund_agent: "Checking refund eligibility…", human_approval: "Waiting for a specialist…",
  issue_refund: "Issuing your refund…", escalation_agent: "Raising a ticket…", smalltalk_agent: "Replying…",
  critic: "Double-checking the answer…", reviser: "Improving the answer…", output_guardrail: "Final safety check…",
  memory_writer: "Saving what matters…",
};

const state = {
  threadId: null, customerId: null, awaiting: false, busy: false, pollTimer: null, checking: false,
  baseline: null,            // server-side message count when the refund started waiting (see checkForDecision)
  deciding: new Set(),       // threads whose approval request is still running (the graph resumes synchronously)
  approvalList: [],
};

// ---------------------------------------------------------------- http

async function api(path, options = {}) {
  const res = await fetch("/api" + path, {
    headers: { "Content-Type": "application/json" },
    cache: "no-store",   // conversation state changes behind our back (specialist decisions): never serve a stale copy
    ...options,
  });
  if (!res.ok) {
    let detail = "";
    try { const body = await res.json(); detail = body.detail || body.message || body.error || ""; } catch { /* not json */ }
    const err = new Error(detail || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return res.status === 204 ? null : res.json();
}

// ---------------------------------------------------------------- rendering

function addMessage(role, text, result) {
  const wrap = document.createElement("div");
  wrap.className = "msg " + role;
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  wrap.appendChild(bubble);
  if (result) wrap.appendChild(renderMeta(result));
  els.log.appendChild(wrap);
  els.log.scrollTop = els.log.scrollHeight;
  return wrap;
}

function tag(text) {
  const t = document.createElement("span");
  t.className = "tag";
  t.textContent = text;
  return t;
}

/** Route, refund, ticket and the node-by-node trace of one agent turn. */
function renderMeta(r) {
  const meta = document.createElement("div");
  meta.className = "meta";
  if (r.route) meta.appendChild(tag("route: " + r.route));
  if (r.refund) {
    const amount = r.refund.amount != null ? " $" + Number(r.refund.amount).toFixed(2) : "";
    meta.appendChild(tag("refund " + r.refund.decision + amount));
  }
  if (r.ticketId) meta.appendChild(tag("ticket " + r.ticketId));
  if (r.revisions) meta.appendChild(tag(r.revisions + " revision" + (r.revisions > 1 ? "s" : "")));
  if (r.agentTrace && r.agentTrace.length) {
    const d = document.createElement("details");
    const s = document.createElement("summary");
    s.textContent = "How the agent got here";
    const pre = document.createElement("pre");
    pre.textContent = r.agentTrace.join("\n") + (r.traceId ? "\n\ntrace id: " + r.traceId : "");
    d.append(s, pre);
    meta.appendChild(d);
  }
  return meta;
}

function setIdentity(customerId) {
  state.customerId = customerId || null;
  els.identity.textContent = customerId ? "Verified · " + customerId : "Not verified";
  els.identity.className = "chip " + (customerId ? "chip-ok" : "chip-warn");
}

function setBanner(text, isError = false) {
  els.banner.hidden = !text;
  els.banner.textContent = text || "";
  els.banner.className = "banner" + (isError ? " error" : "");
}

function setBusy(busy, label) {
  state.busy = busy;
  els.progress.hidden = !busy;
  if (busy) els.progressText.textContent = label || "Thinking…";
  syncComposer();
}

function syncComposer() {
  const locked = state.busy || state.awaiting || !state.threadId;
  els.input.disabled = locked;
  els.send.disabled = locked;
  els.input.placeholder = state.awaiting ? "A specialist is reviewing your refund…"
    : state.customerId ? "Type your message…" : "Type your message (I'll verify your email first)…";
}

// ---------------------------------------------------------------- conversation lifecycle

/** Opening line for a conversation with no messages yet: by name if the customer is already verified. */
function greeting(conversation) {
  if (conversation.customerId) {
    return conversation.firstName
      ? `Welcome back ${conversation.firstName}! How can I help you today?`
      : "Welcome back! How can I help you today?";
  }
  return "Hi, I'm the Acme support assistant. To get started, what's the email address on your account?";
}

async function startConversation() {
  stopPolling();
  setBanner("");
  els.log.replaceChildren();
  state.awaiting = false;
  const customerId = els.startAs.value;
  try {
    const started = await api("/conversations", { method: "POST", body: JSON.stringify(customerId ? { customerId } : {}) });
    state.threadId = started.threadId;
    localStorage.setItem(STORE_KEY, started.threadId);
    setIdentity(started.customerId);
    addMessage("agent", greeting(started));
  } catch (e) {
    state.threadId = null;
    setBanner("Couldn't start a conversation: " + e.message, true);
  }
  syncComposer();
  els.input.focus();
}

/** Re-open the conversation saved in this browser, if the server still knows it. */
async function restoreConversation() {
  let saved = null;
  try { saved = localStorage.getItem(STORE_KEY); } catch { /* storage unavailable */ }
  if (!saved) return false;
  try {
    const view = await api("/conversations/" + encodeURIComponent(saved));
    state.threadId = saved;
    setIdentity(view.customerId);
    renderTranscript(view);
    if (!view.messages.length) addMessage("agent", greeting(view));
    applyAwaiting(view.awaitingApproval);
    syncComposer();
    return true;
  } catch {
    return false;
  }
}

function renderTranscript(view) {
  els.log.replaceChildren();
  for (const m of view.messages) {
    if (m.role === "customer" || m.role === "agent") addMessage(m.role, m.text);
  }
}

function applyAwaiting(awaiting) {
  if (awaiting && !state.awaiting) state.baseline = null;   // a new wait begins
  state.awaiting = awaiting;
  if (awaiting) {
    setBanner("Your refund is waiting for specialist approval. You'll see the outcome here.");
    startPolling();
  } else {
    setBanner("");
    stopPolling();
  }
  syncComposer();
}

/** While a refund awaits a specialist, poll the conversation so the customer sees the decision. */
function startPolling() {
  if (state.pollTimer) return;
  state.pollTimer = setInterval(checkForDecision, 2000);
}

async function checkForDecision() {
  if (!state.awaiting || !state.threadId || state.busy || state.checking) return;
  state.checking = true;
  const threadId = state.threadId;
  try {
    const view = await api("/conversations/" + encodeURIComponent(threadId));
    if (threadId !== state.threadId) return;   // the user started a new conversation meanwhile
    // The decision has arrived when the thread stops awaiting approval - or when the server transcript has grown
    // since the wait began (the agent's reply is in), which doesn't depend on the awaiting flag alone.
    if (state.baseline === null) state.baseline = view.messages.length;
    if (!view.awaitingApproval || view.messages.length > state.baseline) {
      renderTranscript(view);
      setIdentity(view.customerId);
      applyAwaiting(false);
      els.input.focus();
    }
  } catch (e) {
    console.warn("Checking for the specialist's decision failed; will retry:", e);
  } finally {
    state.checking = false;
  }
}

// Browsers throttle timers in background tabs (to roughly once a minute), so a decision made while this tab was
// hidden - e.g. approved from another window - must be picked up the moment the tab is visible again.
document.addEventListener("visibilitychange", () => { if (!document.hidden) checkForDecision(); });
window.addEventListener("focus", checkForDecision);
window.addEventListener("online", checkForDecision);

function stopPolling() {
  clearInterval(state.pollTimer);
  state.pollTimer = null;
}

// ---------------------------------------------------------------- sending (SSE over fetch)

async function sendMessage(text) {
  addMessage("customer", text);
  setBanner("");
  setBusy(true, "Thinking…");
  let result = null;
  try {
    const res = await fetch(`/api/conversations/${encodeURIComponent(state.threadId)}/messages/stream`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ message: text }),
    });
    if (!res.ok) {
      let detail = "";
      try { const b = await res.json(); detail = b.detail || b.message || ""; } catch { /* not json */ }
      throw new Error(detail || `Request failed (${res.status})`);
    }
    await readEvents(res.body, (event, data) => {
      if (event === "node") {
        els.progressText.textContent = NODE_LABELS[data.node] || "Working…";
      } else if (event === "result") {
        result = data;
      }
    });
    if (!result) throw new Error("The agent didn't return an answer. Please try again.");
    addMessage("agent", result.answer || "(no answer)", result);
    applyAwaiting(result.status === "AWAITING_APPROVAL");
    if (!state.customerId) await refreshIdentity();   // the identity step may just have verified the customer
  } catch (e) {
    setBanner(e.message, true);
  } finally {
    setBusy(false);
    if (!state.awaiting) els.input.focus();
  }
}

async function refreshIdentity() {
  try {
    const view = await api("/conversations/" + encodeURIComponent(state.threadId));
    setIdentity(view.customerId);
    syncComposer();
  } catch { /* cosmetic */ }
}

/** Minimal Server-Sent Events parser for a fetch() body stream. */
async function readEvents(body, onEvent) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const flush = (block) => {
    let event = "message";
    const data = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    if (!data.length) return;
    try { onEvent(event, JSON.parse(data.join("\n"))); } catch { /* ignore malformed event */ }
  };
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const sep = buffer.slice(idx).match(/^\r?\n\r?\n/)[0].length;
      flush(buffer.slice(0, idx));
      buffer = buffer.slice(idx + sep);
    }
  }
  if (buffer.trim()) flush(buffer);
}

// ---------------------------------------------------------------- specialist console

async function loadApprovals() {
  try {
    const list = await api("/approvals");
    state.approvalList = list;
    renderApprovals();
  } catch { /* console is optional */ }
}

function renderApprovals() {
  const list = state.approvalList;
  els.approvalCount.textContent = list.length;
  els.approvalCount.hidden = list.length === 0;
  if (els.specialist.hidden) return;
  els.approvals.replaceChildren();
  if (!list.length) {
    const p = document.createElement("p");
    p.className = "empty";
    p.textContent = "Nothing waiting for approval.";
    els.approvals.appendChild(p);
    return;
  }
  for (const a of list) els.approvals.appendChild(approvalCard(a));
}

function approvalCard(a) {
  const card = document.createElement("div");
  card.className = "card";
  const h = document.createElement("h3");
  h.textContent = `$${Number(a.amount).toFixed(2)} · order ${a.orderId || "?"}`;
  card.appendChild(h);
  for (const line of [`Customer ${a.customerId}`, a.reason, a.policyNote]) {
    if (!line) continue;
    const p = document.createElement("p");
    p.textContent = line;
    card.appendChild(p);
  }
  if (state.deciding.has(a.threadId)) {
    // the list is rebuilt every few seconds: progress must be derived from state, not from a button's DOM node
    const status = document.createElement("p");
    status.className = "working";
    status.textContent = "Decision recorded. The agent is finishing up (issuing the refund and checking the reply) - this can take a little while…";
    card.appendChild(status);
    return card;
  }
  const actions = document.createElement("div");
  actions.className = "actions";
  for (const [label, approved, cls] of [["Approve", true, "btn btn-primary"], ["Reject", false, "btn btn-danger"]]) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = cls;
    b.textContent = label;
    b.addEventListener("click", () => decide(a.threadId, approved));
    actions.appendChild(b);
  }
  card.appendChild(actions);
  return card;
}

async function decide(threadId, approved) {
  const approver = els.approver.value.trim();
  if (!approver) { els.approver.focus(); return; }
  state.deciding.add(threadId);
  renderApprovals();   // show progress straight away: the request below returns only when the graph has finished
  if (threadId === state.threadId) setBanner("A specialist has decided. Finishing up…");
  try {
    await api(`/conversations/${encodeURIComponent(threadId)}/approval`, {
      method: "POST",
      body: JSON.stringify({ approved, approver }),
    });
  } catch (e) {
    setBanner("Couldn't record the decision: " + e.message, true);
  } finally {
    state.deciding.delete(threadId);
  }
  await loadApprovals();
  if (threadId === state.threadId) {   // same browser is the customer: show the outcome right away
    try {
      const view = await api("/conversations/" + encodeURIComponent(threadId));
      renderTranscript(view);
      applyAwaiting(view.awaitingApproval);
    } catch { /* polling will catch up */ }
  }
}

// ---------------------------------------------------------------- wiring

els.composer.addEventListener("submit", (e) => {
  e.preventDefault();
  const text = els.input.value.trim();
  if (!text || state.busy || state.awaiting || !state.threadId) return;
  els.input.value = "";
  autosize();
  sendMessage(text);
});

els.input.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    els.composer.requestSubmit();
  }
});

function autosize() {
  els.input.style.height = "auto";
  els.input.style.height = Math.min(els.input.scrollHeight, 160) + "px";
}
els.input.addEventListener("input", autosize);

els.newChat.addEventListener("click", startConversation);
els.startAs.addEventListener("change", startConversation);

els.specialistToggle.addEventListener("click", () => {
  const open = els.specialist.hidden;
  els.specialist.hidden = !open;
  els.specialistToggle.setAttribute("aria-expanded", String(open));
  if (open) loadApprovals();
});

setInterval(loadApprovals, 5000);

(async function init() {
  syncComposer();
  if (!(await restoreConversation())) await startConversation();
  loadApprovals();
})();
