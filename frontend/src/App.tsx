import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { marked } from 'marked';
import { api, AgentInfo, CurrentUser, SessionInfo, StepView, clearToken, getToken } from './api';
import { useChatState, ensureLoaded, sendChat, LiveStep, LiveTool } from './chatStore';

marked.setOptions({ gfm: true, breaks: true });

/** Assistant content is markdown (like DSH); user content stays plain text. */
function renderMarkdown(text: string): string {
  return marked.parse(text) as string;
}

const PERMISSION_LABELS: Record<string, string> = {
  EXPLORE: '仅可查看',
  ACCEPT_EDITS: '工作区修改',
  BYPASS: '完全权限',
};

function Login({ onLogin }: { onLogin: (u: CurrentUser) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr('');
    try {
      const u = await api.login(username, password);
      onLogin(u);
    } catch (e: any) {
      setErr(e.message || '登录失败');
    } finally { setBusy(false); }
  }
  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h1>Aegis</h1>
        <div className="sub">企业智能体治理平台</div>
        <label>账号</label>
        <input value={username} onChange={e => setUsername(e.target.value)} placeholder="用户名" autoFocus />
        <label>密码</label>
        <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="密码" />
        <button disabled={busy}>{busy ? '登录中…' : '登录'}</button>
        {err && <div className="err">{err}</div>}
      </form>
    </div>
  );
}

function AgentChooser({ agents, onPick }: { agents: AgentInfo[]; onPick: (a: AgentInfo) => void }) {
  return (
    <div className="chooser">
      <div className="chooser-inner">
        <h2>开始新会话</h2>
        <p>选择一个智能体开始对话</p>
        <div className="agent-grid">
          {agents.map(a => (
            <button key={a.id} className="agent-card" onClick={() => onPick(a)}>
              <div className="n">
                {a.name}
                {a.builtin && <span className="badge">内置</span>}
              </div>
              <div className="d">{a.description || '暂无描述'}</div>
            </button>
          ))}
          {agents.length === 0 && <p>暂无可用智能体，请联系管理员。</p>}
        </div>
      </div>
    </div>
  );
}

/** One LLM invocation, rendered DSH-style: process steps fold, final answer stays visible. */
type StepLike = {
  stepNo: number; thinking: string; content: string; tools: any[];
  durationMs?: number; status?: string; usage?: Record<string, number>; interrupted?: boolean;
};

function StepBlocks({ steps, live = false }: { steps: StepLike[]; live?: boolean }) {
  if (!steps.length) return null;
  // DSH answer boundary: the last step without tool calls is the visible reply;
  // earlier model calls (thinking + tools) are folded into the process area.
  let answerIndex = -1;
  for (let i = steps.length - 1; i >= 0; i--) {
    if (!(steps[i].tools || []).length) { answerIndex = i; break; }
  }
  return (
    <>
      {steps.map((s, i) => {
        const tools = s.tools || [];
        const isAnswer = i === answerIndex && !!s.content;
        const thinking = s.thinking
          ? <details className={'thinking' + (live ? ' thinking-live' : '')}>
              <summary>💭 思考过程 {s.durationMs != null ? `· ${fmtMs(s.durationMs)}` : ''}</summary>
              <div className="thinking-body">{s.thinking}</div>
            </details>
          : null;
        const toolRows = tools.map((t: any, j: number) => (
          <div key={j} className={'step-tool' + (t.error ? ' is-error' : '')}>
            <span className="step-tool-name">⚙ {t.name}</span>
            {t.args ? <span className="step-tool-args">{argPreview(typeof t.args === 'string' ? t.args : '')}</span> : null}
            {t.result ? <span className="step-tool-result">→ {resultPreview(typeof t.result === 'string' ? t.result : '')}</span> : null}
            {t.done === false ? <span className="tool-running">运行中…</span> : null}
          </div>
        ));
        const tokens = s.usage && Object.keys(s.usage).length
          ? <span className="step-tokens">
              {s.usage.inputTokens != null ? `↑${s.usage.inputTokens} ` : ''}
              {s.usage.outputTokens != null ? `↓${s.usage.outputTokens} ` : ''}
              {s.usage.reasoningTokens != null ? `💭${s.usage.reasoningTokens}` : ''}
            </span>
          : null;
        if (isAnswer) {
          return (
            <div key={i} className="bubble">
              {thinking}
              {toolRows.length ? <div className="step-tools">{toolRows}</div> : null}
              <div className="md" dangerouslySetInnerHTML={{ __html: renderMarkdown(s.content) }} />
              {live && <span className="cursor" />}
              {tokens}
            </div>
          );
        }
        if (!s.thinking && !tools.length && !s.content) return null;
        return (
          <details key={i} className="step-process" open={live}>
            <summary>
              第 {s.stepNo} 步 · {tools.length ? `${tools.length} 次工具调用` : '思考'}
              {s.durationMs != null ? ` · ${fmtMs(s.durationMs)}` : ''}
              {s.status === 'interrupted' ? ' · 已中断' : ''}
              {tokens}
            </summary>
            <div className="step-process-body">
              {thinking}
              {toolRows.length ? <div className="step-tools">{toolRows}</div> : null}
              {s.content ? <div className="md" dangerouslySetInnerHTML={{ __html: renderMarkdown(s.content) }} /> : null}
            </div>
          </details>
        );
      })}
    </>
  );
}

function ChatView({
  session, agents, onPermissionChange, onTitle,
}: {
  session: SessionInfo;
  agents: AgentInfo[];
  onPermissionChange: (mode: string) => void;
  onTitle?: (title: string) => void;
}) {
  const chat = useChatState(session.id);
  const { messages, stream, busy, liveSteps, runtimeEvents } = chat;
  const [text, setText] = useState('');
  const [mode, setMode] = useState(session.permissionMode);
  const endRef = useRef<HTMLDivElement>(null);
  // Only follow the tail while the user is pinned near the bottom; never steal
  // scroll position when they scroll up to read history during streaming.
  const areaRef = useRef<HTMLDivElement>(null);
  const pinnedRef = useRef(true);
  const [showJump, setShowJump] = useState(false);

  useEffect(() => {
    ensureLoaded(session.id);
    setMode(session.permissionMode);
    pinnedRef.current = true;
    setShowJump(false);
  }, [session.id, session.permissionMode]);

  function onAreaScroll() {
    const el = areaRef.current;
    if (!el) return;
    const dist = el.scrollHeight - el.scrollTop - el.clientHeight;
    pinnedRef.current = dist < 80;
    setShowJump(dist > 200 && !pinnedRef.current);
  }

  useEffect(() => { if (pinnedRef.current) endRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);
  // follow the tail while streaming (instant scroll, no animation spam)
  useEffect(() => {
    if (!stream) return;
    if (pinnedRef.current) {
      endRef.current?.scrollIntoView({ behavior: 'auto' });
    } else {
      // content grows without scroll events firing — refresh the jump button visibility
      const el = areaRef.current;
      if (el) setShowJump(el.scrollHeight - el.scrollTop - el.clientHeight > 200);
    }
  }, [stream?.content, stream?.thinking, stream?.phase, liveSteps.length,
      liveSteps.map(s => s.content.length + s.thinking.length).join('|')]);

  async function send() {
    if (!text.trim() || busy) return;
    const content = text;
    setText('');
    pinnedRef.current = true; setShowJump(false);
    // State lives in chatStore: switching pages/tabs during the run no longer
    // drops the stream — callbacks keep updating even while this view is away.
    await sendChat(session.id, content, t => onTitle?.(t));
  }

  async function changeMode(newMode: string) {
    setMode(newMode);
    try { await api.setPermission(session.id, newMode); onPermissionChange(newMode); } catch { /* ignore */ }
  }

  const agent = agents.find(a => a.id === session.agentId);

  return (
    <>
      <div className="chat-area" ref={areaRef} onScroll={onAreaScroll}>
        {showJump && (
          <button
            className="jump-bottom"
            onClick={() => { pinnedRef.current = true; setShowJump(false); endRef.current?.scrollIntoView({ behavior: 'smooth' }); }}
          >
            ↓ 回到底部
          </button>
        )}
        {messages.length === 0 && <div style={{ color: '#aaa', textAlign: 'center', marginTop: 60 }}>开始对话吧 · 智能体：{agent?.name || session.agentId}</div>}
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role === 'user' ? 'user' : ''}`}>
            <div className="avatar">{m.role === 'user' ? '我' : 'AI'}</div>
            {m.role === 'user' ? (
              <div className="bubble">{m.content}</div>
            ) : (
              <div className="msg-body">
                {m.steps && m.steps.length ? (
                  <StepBlocks steps={m.steps} />
                ) : (
                  <div className="bubble">
                    <div className="md" dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content) }} />
                  </div>
                )}
              </div>
            )}
          </div>
        ))}
        {runtimeEvents.filter(e => e.type === 'REQUEST_STOP' || e.type === 'EXCEED_MAX_ITERS'
          || e.type === 'ALL_TOOLS_DENIED' || e.type === 'SUBAGENT_EXPOSED').map((e, i) => (
          <div key={i} className="runtime-event">ⓘ {e.type}{e.summary ? ` · ${e.summary}` : ''}</div>
        ))}
        {stream && (
          <div className="msg">
            <div className="avatar">AI</div>
            <div className="msg-body">
            {liveSteps.length ? (
              <StepBlocks steps={liveSteps} live />
            ) : (
              <div className="bubble streaming">
                {stream.thinking && (
                  <details className="thinking" open>
                    <summary>💭 思考过程</summary>
                    <div className="thinking-body">{stream.thinking}</div>
                  </details>
                )}
                {stream.phase === 'tool' && (
                  <div className="stream-status">⚙ 正在调用工具 {stream.tool}…</div>
                )}
                {stream.phase === 'thinking' && !stream.content && !stream.thinking && (
                  <div className="stream-status">思考中…</div>
                )}
                {stream.content
                  ? <div className="md" dangerouslySetInnerHTML={{ __html: renderMarkdown(stream.content) }} />
                  : null}
                <span className="cursor" />
              </div>
            )}
            </div>
          </div>
        )}
        <div ref={endRef} />
      </div>
      <div className="composer">
        <div className="composer-box">
          <textarea
            value={text}
            onChange={e => setText(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          />
          <div className="composer-row">
            <select value={mode} onChange={e => changeMode(e.target.value)} title="操作权限">
              {Object.entries(PERMISSION_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
            <span className="spacer" />
            <select title="模型" defaultValue="default">
              <option value="default">默认模型</option>
            </select>
            <button className="send" onClick={send} disabled={busy || !text.trim()}>{busy ? '回复中…' : '发送'}</button>
          </div>
        </div>
      </div>
    </>
  );
}

interface ToolRec { name: string; args: string; result: string; startedAt: number; durationMs?: number; error: boolean; done?: boolean }

interface TrajRow {
  kind: 'user' | 'message' | 'tool' | 'context' | 'compacted' | 'system' | 'subtool';
  turn: number;
  stepNo?: number;
  text: string;            // single-line summary
  detail: string;          // full content for the inspector
  thinking?: string;       // reasoning of this model call
  result?: string;         // tool result (inline second column)
  rec?: ToolRec;           // tool record for inspector tabs
  createdAt: string;
  startedAt?: number;
  durationMs?: number;
  status?: string;
  meta?: any;
  live?: boolean;          // in-flight row (not yet persisted)
}

function fmtMs(ms: number): string {
  if (ms == null) return '—';
  if (ms >= 1000) return (ms / 1000).toFixed(ms < 10000 ? 2 : 0) + ' 秒';
  return ms + ' 毫秒';
}

/** Markdown → one-line plain text for the ledger (mirrors DSH previewMarkdown). */
function plainText(md: string | undefined, max = 240): string {
  if (!md) return '';
  return md
    .replace(/```[\s\S]*?```/g, m => m.replace(/```[^\n]*\n?/g, ' ').replace(/```/g, ' '))
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/`([^`]+)`/g, '$1').replace(/`/g, '')
    .replace(/\*\*([^*]+)\*\*/g, '$1').replace(/__([^_]+)__/g, '$1')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1$2')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^\s*\|?[\s:|-]+\|?\s*$/gm, '')   // table separator row |---|---|
    .replace(/\|/g, ' ')                        // table cell separators
    .replace(/^\s*[>]\s*/gm, '')
    .replace(/^\s*[-+*]\s+/gm, '')
    .replace(/\s{2,}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, max) + (md.length > max ? '…' : '');
}

/** Readable tool argument preview: key=value pairs from JSON, compact object otherwise. */
function argPreview(args: string | undefined, max = 180): string {
  if (!args) return '';
  let obj: any;
  try { obj = JSON.parse(args); } catch { return plainText(args, max); }
  if (!obj || typeof obj !== 'object') return String(obj).slice(0, max);
  const parts = Object.entries(obj).map(([k, v]) => {
    const raw = typeof v === 'string' ? v : JSON.stringify(v);
    return k + '=' + (raw.length > 64 ? raw.slice(0, 64) + '…' : raw);
  });
  return plainText(parts.join(' · ').replace(/\n/g, ' ⏎ '), max);
}

/** Tool result summary: keep the exit line plus the first output lines. */
function resultPreview(result: string | undefined, max = 160): string {
  if (!result) return '';
  const lines = result.split(/\r?\n/).map(s => s.trim()).filter(Boolean).slice(0, 3);
  return plainText(lines.join(' · '), max);
}

function prettyJson(raw: string | undefined): string {
  if (!raw) return '';
  try { return JSON.stringify(JSON.parse(raw), null, 2); } catch { return raw; }
}

function fmtStartedAt(ms: number | undefined): string {
  if (!ms) return '';
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;
}

function TrajectoryView({ sessionId }: { sessionId: string }) {
  const [data, setData] = useState<any>(null);
  const [collapsedTurns, setCollapsedTurns] = useState<Set<number>>(new Set());
  const [selected, setSelected] = useState<number | null>(null);
  const [query, setQuery] = useState('');
  const [detailRaw, setDetailRaw] = useState(false);
  const chat = useChatState(sessionId);
  const wasBusy = useRef(false);

  useEffect(() => {
    setSelected(null); setQuery(''); setCollapsedTurns(new Set()); setDetailRaw(false);
    api.trajectory(sessionId).then(setData).catch(() => setData(null));
  }, [sessionId]);
  // Reload persisted trajectory when a live turn finishes (tool results become final).
  useEffect(() => {
    if (wasBusy.current && !chat.busy) {
      api.trajectory(sessionId).then(setData).catch(() => {});
    }
    wasBusy.current = chat.busy;
  }, [chat.busy, sessionId]);

  if (!data) return <div className="traj"><div className="traj-loading">加载轨迹中…</div></div>;

  // The backend projects rows from per-model-call steps + raw events (1:1 ledger).
  // Fall back to message-based projection only for sessions persisted before steps existed.
  const rows: TrajRow[] = [];
  let turn = 0;
  if (Array.isArray(data.rows) && data.rows.length) {
    for (const r of data.rows as any[]) {
      const tool = r.kind === 'tool' || r.kind === 'subtool';
      rows.push({
        kind: r.kind, turn: r.turn || 0, stepNo: r.stepNo || 0,
        text: tool ? (r.text + (argPreview(r.detail) ? ' ' + argPreview(r.detail) : '')) : plainText(r.text || ''),
        detail: tool ? (r.text + (r.detail ? '\n\n' + prettyJson(r.detail) : '')) : (r.detail || ''),
        thinking: r.thinking || '',
        result: tool ? (resultPreview(r.result) || undefined) : undefined,
        rec: tool ? {
          name: r.text, args: r.detail || '', result: r.result || '',
          startedAt: Number(r.startedAt) || 0, durationMs: r.durationMs,
          error: r.status === 'failed', done: r.status !== 'running',
        } : undefined,
        createdAt: r.createdAt || '', startedAt: Number(r.startedAt) || 0,
        durationMs: r.durationMs ?? undefined, status: r.status || '', meta: r.meta,
      });
    }
    turn = Math.max(0, ...rows.map(r => r.turn));
  } else {
    for (const m of data.messages as any[]) {
      if (m.role === 'user') {
        turn++;
        rows.push({ kind: 'user', turn, text: plainText(m.content) || '(空)', detail: m.content || '', createdAt: m.createdAt });
        continue;
      }
      rows.push({ kind: 'message', turn, text: plainText(m.content) || '(无文本回复)', detail: m.content || '', createdAt: m.createdAt });
      let tools: ToolRec[] = [];
      try {
        const tc = typeof m.toolCalls === 'string' && m.toolCalls ? JSON.parse(m.toolCalls) : null;
        if (tc?.tools) tools = tc.tools;
      } catch { /* legacy comma format */ }
      for (const t of tools) {
        rows.push({
          kind: 'tool', turn,
          text: t.name + (argPreview(t.args) ? ' ' + argPreview(t.args) : ''),
          detail: t.name + (t.args ? '\n\n' + prettyJson(t.args) : ''),
          result: resultPreview(t.result) || undefined,
          rec: t, createdAt: m.createdAt, durationMs: t.durationMs,
        });
      }
    }
  }

  // In-flight turn, read from the shared store — updated live by SSE even if
  // this view was unmounted while the turn was running.
  const persistedMsgs = data.messages as any[];
  const persistedUsers = persistedMsgs.filter(m => m.role === 'user').length;
  const persistedAssistants = persistedMsgs.filter(m => m.role === 'assistant').length;
  const allUsers = chat.messages.filter(m => m.role === 'user');
  const allAssistants = chat.messages.filter(m => m.role === 'assistant');
  const pendingUser = allUsers.length > persistedUsers ? allUsers[allUsers.length - 1] : null;
  const pendingAssistant = allAssistants.length > persistedAssistants ? allAssistants[allAssistants.length - 1] : null;
  // Show live rows while running AND in the short gap after completion before
  // the persisted trajectory reloads — without duplicating rows twice.
  if (chat.busy || pendingUser || pendingAssistant) {
    const liveTurn = pendingUser ? turn + 1 : Math.max(turn, 1);
    if (pendingUser) {
      rows.push({ kind: 'user', turn: liveTurn,
        text: plainText(pendingUser.content) || '(空)', detail: pendingUser.content || '',
        createdAt: '', live: true });
    }
    // One row per in-flight model call, so thinking → tool → thinking → reply
    // is visible in the ledger while the turn is running.
    for (const s of chat.liveSteps) {
      rows.push({ kind: 'message', turn: liveTurn, stepNo: s.stepNo,
        text: plainText(s.content) || (s.thinking ? '💭 正在思考…' : (s.tools.length ? '⚙ 工具调用中…' : '执行中…')),
        detail: s.content || '', thinking: s.thinking || '', createdAt: '',
        startedAt: undefined, durationMs: s.durationMs, status: s.status, live: true });
      for (const t of s.tools) {
        rows.push({ kind: 'tool', turn: liveTurn, stepNo: s.stepNo,
          text: t.name + (argPreview(t.args) ? ' ' + argPreview(t.args) : ''),
          detail: t.name + (t.args ? '\n\n' + prettyJson(t.args) : ''),
          result: resultPreview(t.result) || undefined,
          rec: { name: t.name, args: t.args, result: t.result, startedAt: t.startedAt,
                 durationMs: t.durationMs, error: t.error, done: t.done },
          createdAt: '', durationMs: t.durationMs,
          status: t.done ? (t.error ? 'failed' : 'complete') : 'running', live: true });
      }
    }
    for (const e of chat.runtimeEvents) {
      if (['REQUEST_STOP', 'EXCEED_MAX_ITERS', 'ALL_TOOLS_DENIED', 'SUBAGENT_EXPOSED'].includes(e.type)) {
        rows.push({ kind: 'context', turn: liveTurn,
          text: e.type + (e.summary ? ' · ' + e.summary : ''), detail: e.summary || '',
          createdAt: '', status: 'complete', live: true });
      }
    }
    const st = chat.stream;
    if (!chat.liveSteps.length && st) {
      rows.push({ kind: 'message', turn: liveTurn,
        text: plainText(st.content) || (st.thinking ? '💭 正在思考…' : '执行中…'),
        detail: (st.thinking ? '思考过程：\n' + st.thinking + '\n\n' : '') + (st.content || ''),
        createdAt: '', live: true });
    } else if (!chat.liveSteps.length && pendingAssistant && !chat.busy) {
      // Turn just finished: show its final text until /trajectory catches up.
      rows.push({ kind: 'message', turn: liveTurn,
        text: plainText(pendingAssistant.content) || '(无文本回复)',
        detail: pendingAssistant.content || '', createdAt: '', live: true });
    }
    if (!chat.liveSteps.length) {
      const liveTools: ToolRec[] = chat.busy
        ? chat.tools.map(t => ({ name: t.name, args: t.args, result: t.result, startedAt: t.startedAt,
            durationMs: t.durationMs, error: t.error, done: t.done }))
        : (() => {
            try {
              const root = pendingAssistant?.toolCalls ? JSON.parse(pendingAssistant.toolCalls) : null;
              return root?.tools || [];
            } catch { return []; }
          })();
      for (const t of liveTools) {
        rows.push({
          kind: 'tool', turn: liveTurn,
          text: t.name + (argPreview(t.args) ? ' ' + argPreview(t.args) : ''),
          detail: t.name + (t.args ? '\n\n' + prettyJson(t.args) : ''),
          result: resultPreview(t.result) || undefined,
          rec: t, createdAt: '', durationMs: t.durationMs, live: true,
        });
      }
    }
  }

  // per-turn summary data for collapsed rows
  const turnSummaries = new Map<number, { steps: number; tools: number }>();
  for (const r of rows) {
    const s = turnSummaries.get(r.turn) || { steps: 0, tools: 0 };
    s.steps++; if (r.kind === 'tool') s.tools++;
    turnSummaries.set(r.turn, s);
  }

  const q = query.trim().toLowerCase();
  const matches = (r: TrajRow) =>
    !q || r.text.toLowerCase().includes(q) || (r.result || '').toLowerCase().includes(q) || (r.detail || '').toLowerCase().includes(q);

  const totalMs = (data.durationMs as number) || 0;
  const sel = selected != null ? rows[selected] : null;

  const kindLabel = {
    user: '用户', message: '助手', tool: '工具', subtool: '子工具',
    context: '事件', compacted: '压缩', system: '系统',
  } as const;
  const renderRow = (r: TrajRow, i: number, asSummary = false) => (
    <tr key={asSummary ? `s${i}` : i}
        data-kind={asSummary ? 'summary' : r.kind}
        data-selected={selected === i || undefined}
        data-error={r.rec?.error || undefined}
        data-running={(r.live && r.rec && r.rec.done === false) || undefined}
        title={asSummary ? '点击展开该轮次' : undefined}
        className={asSummary ? 'summary-row' : (r.live ? 'live-row' : (selected === i ? 'selected-row' : ''))}
        onClick={() => {
          if (asSummary) {
            setCollapsedTurns(prev => { const next = new Set(prev); next.delete(r.turn); return next; });
          } else { setSelected(i); setDetailRaw(false); }
        }}>
      <td className="traj-event">
        <span className="turn-rail" />
        {r.turn > 0 && (i === 0 || rows[i - 1].turn !== r.turn) && <span className="turn-label">第 {r.turn} 轮</span>}
        {!asSummary && <span className={`kind-chip chip-${r.kind}`}>{kindLabel[r.kind]}</span>}
        {!asSummary && r.kind === 'message' && r.stepNo ? <span className="step-tag">第 {r.stepNo} 步</span> : null}
      </td>
      <td className="traj-content">
        {asSummary
          ? `… ${turnSummaries.get(r.turn)?.steps || 0} 个步骤 · ${turnSummaries.get(r.turn)?.tools || 0} 个工具调用（点击展开）`
          : r.kind === 'tool'
            ? <span className="tool-line">
                <span className="tool-call">{r.text.slice(0, 200)}</span>
                {r.result && <span className={'tool-result' + (r.rec?.error ? ' is-error' : '')}>→ {r.result.slice(0, 160)}</span>}
                {r.rec?.error && <span className="tool-state is-error">失败</span>}
                {r.live && r.rec && r.rec.done === false && <span className="tool-running">运行中…</span>}
              </span>
            : <span className="content-text">
                {r.thinking && !r.text.startsWith('💭') && <span className="thinking-dot" title="本步包含思考过程">💭 </span>}
                {r.text.slice(0, 240)}
                {(r.status === 'interrupted' || r.status === 'max-iters' || r.status === 'denied')
                  && <span className="tool-state is-error">{r.status}</span>}
              </span>}
      </td>
    </tr>
  );

  // Search filters rows (DSH parity); collapsed turns show a clickable summary.
  const rendered: React.ReactNode[] = [];
  let lastTurn = -1;
  rows.forEach((r, i) => {
    if (!matches(r)) return;
    const turnStart = r.turn !== lastTurn;
    lastTurn = r.turn;
    if (collapsedTurns.has(r.turn)) {
      if (turnStart) rendered.push(renderRow(r, i, true));
      return; // hide every remaining row of a collapsed turn
    }
    rendered.push(renderRow(r, i));
  });

  return (
    <div className="traj">
      <div className="traj-toolbar">
        <div className="traj-actions">
          <span className="traj-stat" title="会话累计耗时（含工具执行）">⏱ {fmtMs(totalMs)}</span>
          <span className="traj-stat">{data.turns} 轮</span>
          <span className="traj-stat">{data.toolCalls} 次调用</span>
          <button className="traj-toggle" onClick={() =>
            setCollapsedTurns(collapsedTurns.size ? new Set() : new Set(turnSummaries.keys()))}>
            {collapsedTurns.size ? '⊞ 展开轮次' : '⊟ 折叠轮次'}
          </button>
        </div>
        <input className="traj-search" type="search" placeholder="搜索轨迹…"
               value={query} onChange={e => setQuery(e.target.value)} />
      </div>
      <div className={'traj-ledger' + (sel ? ' with-details' : '')}>
        <div className="traj-table-wrap">
          <table className="traj-table">
            <colgroup><col className="event-col" /><col /></colgroup>
            <tbody>{rendered}</tbody>
          </table>
        </div>
        {sel && (
          <aside className="traj-details">
            <div className="details-head">
              <span className={`kind-chip chip-${sel.kind}`}>{kindLabel[sel.kind]}</span>
              <span className="details-time" title={sel.rec?.startedAt ? String(sel.rec.startedAt) : undefined}>
                {sel.rec?.startedAt ? fmtStartedAt(sel.rec.startedAt) : (sel.createdAt || (sel.live ? '实时' : ''))}
              </span>
              {sel.kind === 'message' && (
                <button className="details-view" onClick={() => setDetailRaw(!detailRaw)}>
                  {detailRaw ? '渲染' : '原文'}
                </button>
              )}
              <button className="details-close" onClick={() => setSelected(null)}>✕</button>
            </div>
            <div className="details-body">
              {sel.kind === 'tool' && sel.rec ? (
                <>
                  <div className="details-tab">概述</div>
                  <dl className="kv">
                    <dt>工具</dt><dd className="mono">{sel.rec.name}</dd>
                    <dt>开始时间</dt><dd className="mono">{fmtStartedAt(sel.rec.startedAt) || '—'}</dd>
                    <dt>耗时</dt><dd>{fmtMs(sel.rec.durationMs ?? (sel.live && sel.rec.done === false ? Date.now() - sel.rec.startedAt : 0))}</dd>
                    <dt>状态</dt><dd className={sel.rec.error ? 'err' : (sel.rec.done === false ? 'running' : 'ok')}>
                      {sel.rec.error ? '失败' : (sel.rec.done === false ? '运行中' : '完成')}</dd>
                  </dl>
                  <div className="details-tab">参数（JSON）</div>
                  <pre className="payload">{prettyJson(sel.rec.args) || '(无参数)'}</pre>
                  <div className="details-tab">结果</div>
                  <pre className={'payload' + (sel.rec.error ? ' error-payload' : '')}>{sel.rec.result || '(无输出)'}</pre>
                </>
              ) : (
                <>
                  <div className="details-tab">{sel.kind === 'user' ? '用户消息' : sel.kind === 'context' ? '运行时事件' : '助手回复'}</div>
                  {sel.durationMs != null && <div className="details-duration">耗时 {fmtMs(sel.durationMs)}</div>}
                  {sel.thinking && (
                    <>
                      <div className="details-tab">思考过程（本次模型调用）</div>
                      <details className="thinking" open>
                        <summary>💭 展开完整思考</summary>
                        <div className="thinking-body">{sel.thinking}</div>
                      </details>
                    </>
                  )}
                  {sel.kind === 'message' && !detailRaw ? (
                    <div className="md" dangerouslySetInnerHTML={{ __html: renderMarkdown(sel.detail || '') }} />
                  ) : (
                    <pre className="payload">{sel.detail || '(空)'}</pre>
                  )}
                  {sel.meta?.usage && Object.keys(sel.meta.usage).length > 0 && (
                    <>
                      <div className="details-tab">Token 用量</div>
                      <dl className="kv">
                        {Object.entries(sel.meta.usage).map(([k, v]) =>
                          <React.Fragment key={k}><dt>{k}</dt><dd className="mono">{String(v)}</dd></React.Fragment>)}
                      </dl>
                    </>
                  )}
                </>
              )}
            </div>
          </aside>
        )}
      </div>
    </div>
  );
}

function AdminPanel({ user, onClose }: { user: CurrentUser; onClose: () => void }) {
  const [users, setUsers] = useState<any[]>([]);
  const [agents, setAgents] = useState<AgentInfo[]>([]);
  const [tab, setTab] = useState<'users' | 'agents'>('users');
  const [nu, setNu] = useState({ username: '', password: '', role: 'USER', displayName: '' });
  const [na, setNa] = useState({ id: '', name: '', description: '' });
  const [msg, setMsg] = useState('');

  const load = () => {
    if (user.role === 'SUPER_ADMIN') api.listUsers().then(setUsers).catch(() => {});
    api.listAgents('manageable').then(setAgents).catch(() => {});
  };
  useEffect(load, []);

  async function createUser(e: React.FormEvent) {
    e.preventDefault();
    try { await api.createUser(nu); setMsg('用户已创建'); setNu({ username: '', password: '', role: 'USER', displayName: '' }); load(); }
    catch (e: any) { setMsg('错误：' + e.message); }
  }
  async function createAgent(e: React.FormEvent) {
    e.preventDefault();
    try { await api.createAgent(na); setMsg('智能体已创建'); setNa({ id: '', name: '', description: '' }); load(); }
    catch (e: any) { setMsg('错误：' + e.message); }
  }
  async function delAgent(id: string) {
    try { await api.deleteAgent(id); setMsg('已删除'); load(); } catch (e: any) { setMsg('错误：' + e.message); }
  }

  return (
    <div className="admin">
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 20 }}>
        <h2 style={{ flex: 1 }}>管理后台</h2>
        <button className="btn-sm" onClick={onClose}>返回对话</button>
      </div>
      <div className="topbar" style={{ padding: 0, border: 'none', marginBottom: 16 }}>
        <div className="tabs">
          <button className={tab === 'users' ? 'active' : ''} onClick={() => setTab('users')}>用户管理</button>
          <button className={tab === 'agents' ? 'active' : ''} onClick={() => setTab('agents')}>智能体管理</button>
        </div>
      </div>

      {tab === 'users' && (
        <div className="admin-section">
          <h2>用户列表</h2>
          <table>
            <thead><tr><th>ID</th><th>账号</th><th>角色</th><th>显示名</th><th>创建时间</th></tr></thead>
            <tbody>{users.map(u => <tr key={u.id}><td>{u.id}</td><td>{u.username}</td><td>{u.role}</td><td>{u.displayName}</td><td>{u.createdAt}</td></tr>)}</tbody>
          </table>
          {user.role === 'SUPER_ADMIN' && (
            <form className="admin-form" onSubmit={createUser}>
              <input placeholder="账号" value={nu.username} onChange={e => setNu({ ...nu, username: e.target.value })} />
              <input placeholder="密码" type="password" value={nu.password} onChange={e => setNu({ ...nu, password: e.target.value })} />
              <input placeholder="显示名" value={nu.displayName} onChange={e => setNu({ ...nu, displayName: e.target.value })} />
              <select value={nu.role} onChange={e => setNu({ ...nu, role: e.target.value })}>
                <option value="USER">普通用户</option>
                <option value="AGENT_ADMIN">智能体管理员</option>
                <option value="SUPER_ADMIN">超级管理员</option>
              </select>
              <button className="btn-sm" type="submit">创建用户</button>
            </form>
          )}
        </div>
      )}

      {tab === 'agents' && (
        <div className="admin-section">
          <h2>智能体列表</h2>
          <table>
            <thead><tr><th>ID</th><th>名称</th><th>内置</th><th>归属</th><th>操作</th></tr></thead>
            <tbody>{agents.map(a => (
              <tr key={a.id}>
                <td>{a.id}</td><td>{a.name}</td>
                <td>{a.builtin ? <span className="badge">内置</span> : '-'}</td>
                <td>{a.ownerId}</td>
                <td>{!a.builtin && <button className="btn-sm danger" onClick={() => delAgent(a.id)}>删除</button>}</td>
              </tr>
            ))}</tbody>
          </table>
          <form className="admin-form" onSubmit={createAgent}>
            <input placeholder="ID（英文）" value={na.id} onChange={e => setNa({ ...na, id: e.target.value })} />
            <input placeholder="名称" value={na.name} onChange={e => setNa({ ...na, name: e.target.value })} />
            <input placeholder="描述" value={na.description} onChange={e => setNa({ ...na, description: e.target.value })} />
            <button className="btn-sm" type="submit">创建智能体</button>
          </form>
        </div>
      )}
      {msg && <div className="toast" onAnimationEnd={() => setMsg('')}>{msg}</div>}
    </div>
  );
}

/** Clipboard with legacy fallback (DSH parity); returns whether write succeeded. */
async function writeClipboard(text: string): Promise<boolean> {
  const active = document.activeElement as HTMLElement | null;
  let ok = false;
  try {
    if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(text); ok = true; }
  } catch { /* fall through */ }
  if (!ok) {
    try {
      const ta = document.createElement('textarea');
      ta.value = text; ta.readOnly = true;
      ta.style.cssText = 'position:fixed;left:-9999px;top:0';
      document.body.appendChild(ta); ta.select();
      ok = document.execCommand('copy');
      ta.remove();
    } catch { ok = false; }
  }
  active?.focus?.({ preventScroll: true });
  return ok;
}

interface SessionMenu { id: string; x: number; y: number }

function Workspace({ user, onLogout }: { user: CurrentUser; onLogout: () => void }) {
  const [agents, setAgents] = useState<AgentInfo[]>([]);
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [current, setCurrent] = useState<SessionInfo | null>(null);
  const [view, setView] = useState<'chat' | 'trajectory'>('chat');
  const [showChooser, setShowChooser] = useState(false);
  const [admin, setAdmin] = useState(false);
  const [menu, setMenu] = useState<SessionMenu | null>(null);
  const [toast, setToast] = useState<{ text: string; ok: boolean } | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  const loadSessions = () => api.listSessions().then(setSessions).catch(() => {});
  useEffect(() => {
    api.listAgents().then(setAgents).catch(() => {});
    loadSessions();
  }, []);

  // Dismiss the session menu on outside pointerdown or Escape; restore trigger focus.
  useEffect(() => {
    if (!menu) return;
    const onDown = (e: PointerEvent) => {
      const target = e.target as HTMLElement;
      if (menuRef.current?.contains(target) || target.closest('[data-session-menu-trigger]')) return;
      setMenu(null);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMenu(null);
        document.querySelector<HTMLElement>(`[data-session-menu-trigger="${menu.id}"]`)?.focus();
      }
    };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('pointerdown', onDown); document.removeEventListener('keydown', onKey); };
  }, [menu]);

  async function copySessionId(id: string) {
    const ok = await writeClipboard(id);
    setMenu(null);
    setToast({ text: ok ? 'Session ID 已复制' : '复制失败，请重试', ok });
  }

  async function pickAgent(a: AgentInfo) {
    try {
      const s = await api.createSession({ agentId: a.id, title: '与 ' + a.name + ' 的对话' });
      setSessions(prev => [s, ...prev]);
      setCurrent(s); setShowChooser(false); setView('chat');
    } catch (e: any) { alert('创建会话失败：' + e.message); }
  }

  function openSession(s: SessionInfo) { setCurrent(s); setView('chat'); }

  /** Refreshes one session's title in the sidebar (after LLM auto-titling). */
  function refreshTitle(sessionId: string, title: string) {
    if (!title) return;
    setSessions(prev => prev.map(s => s.id === sessionId ? { ...s, title } : s));
    setCurrent(c => c && c.id === sessionId ? { ...c, title } : c);
  }

  return (
    <div className="app">
      <div className="sidebar">
        <div className="brand"><span className="dot" /> Aegis</div>
        <button className="new-chat" onClick={() => { setShowChooser(true); setCurrent(null); }}>+ 新会话</button>
        <div className="side-scroll">
          <div className="ws-group">
            <div className="ws-title">工作区 · 默认</div>
            {sessions.map(s => (
              <div key={s.id} data-session-id={s.id} className={`sess-item ${current?.id === s.id ? 'active' : ''}`} onClick={() => openSession(s)}>
                <span>{s.title || s.id.slice(0, 8)}</span>
                <button
                  className="sess-more" title="更多操作" aria-label="会话更多操作"
                  aria-haspopup="menu" aria-expanded={menu?.id === s.id}
                  data-session-menu-trigger={s.id}
                  onClick={e => {
                    e.stopPropagation();
                    if (menu?.id === s.id) { setMenu(null); return; }
                    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
                    (e.currentTarget as HTMLElement).focus({ preventScroll: true });
                    setMenu({ id: s.id, x: Math.min(r.left, window.innerWidth - 190), y: r.bottom + 4 });
                  }}
                  onKeyDown={e => {
                    if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
                      setMenu({ id: s.id, x: Math.min(r.left, window.innerWidth - 190), y: r.bottom + 4 });
                    }
                  }}
                >···</button>
              </div>
            ))}
            {sessions.length === 0 && <div style={{ color: '#bbb', fontSize: 12, padding: 8 }}>暂无历史会话</div>}
          </div>
        </div>
        <div className="sidebar-foot">
          <div className="u">{user.displayName || user.username}</div>
          <div className="r">{user.role}</div>
          {(user.role === 'SUPER_ADMIN' || user.role === 'AGENT_ADMIN') && (
            <button onClick={() => setAdmin(true)}>管理后台</button>
          )}
          <button onClick={onLogout} style={{ marginLeft: 12 }}>退出登录</button>
        </div>
      </div>

      <div className="main">
        {admin ? <AdminPanel user={user} onClose={() => setAdmin(false)} /> : (
          <>
            <div className="topbar">
              <div className="title">{current?.title || (showChooser ? '新会话' : 'Aegis')}</div>
              {current && (
                <div className="tabs">
                  <button className={view === 'chat' ? 'active' : ''} onClick={() => setView('chat')}>对话</button>
                  <button className={view === 'trajectory' ? 'active' : ''} onClick={() => setView('trajectory')}>轨迹</button>
                  <button
                    title="下载会话日志（zip：对话 JSONL + 轨迹 + 元数据）"
                    onClick={() => api.downloadSessionLog(current.id)}
                  >⬇ 日志</button>
                </div>
              )}
            </div>
            {showChooser || !current ? <AgentChooser agents={agents} onPick={pickAgent} /> : (
              view === 'chat'
                ? <ChatView session={current} agents={agents} onPermissionChange={m => setCurrent({ ...current, permissionMode: m })} onTitle={t => refreshTitle(current.id, t)} />
                : <TrajectoryView sessionId={current.id} />
            )}
          </>
        )}
      </div>
      {menu && createPortal(
        <div className="session-menu" role="menu" ref={menuRef}
             style={{ left: menu.x, top: menu.y }}>
          <button role="menuitem" autoFocus onClick={() => copySessionId(menu.id)}>
            复制 Session ID
          </button>
        </div>, document.body)}
      {toast && createPortal(
        <div className={'toast' + (toast.ok ? '' : ' warn')} role="alert"
             onAnimationEnd={() => setToast(null)}>{toast.text}</div>, document.body)}
    </div>
  );
}

export default function App() {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    if (!getToken()) { setChecking(false); return; }
    // verify token by listing agents
    api.listAgents().then(() => {
      const stored = localStorage.getItem('aegis_user');
      if (stored) setUser(JSON.parse(stored));
    }).catch(() => clearToken()).finally(() => setChecking(false));
  }, []);

  function onLogin(u: CurrentUser) {
    setUser(u);
    localStorage.setItem('aegis_user', JSON.stringify(u));
  }
  function onLogout() { clearToken(); localStorage.removeItem('aegis_user'); setUser(null); }

  if (checking) return <div style={{ padding: 40 }}>加载中…</div>;
  if (!user) return <Login onLogin={onLogin} />;
  return <Workspace user={user} onLogout={onLogout} />;
}
