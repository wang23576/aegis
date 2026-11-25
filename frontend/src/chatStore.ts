import { useCallback, useSyncExternalStore } from 'react';
import { api, Message, StepView } from './api';

/**
 * Session-scoped chat state that lives OUTSIDE React components.
 * Switching sessions / tabs / admin view unmounts ChatView; keeping the stream
 * state here means SSE callbacks keep updating and the UI restores the exact
 * running/completed state when the view is remounted (ISSUE-014).
 * Each LLM invocation is tracked as a separate step (DSH step model, ISSUE-019).
 */

export interface LiveTool {
  id: string;
  name: string;
  args: string;
  result: string;
  startedAt: number;
  stepNo: number;
  durationMs?: number;
  error: boolean;
  done: boolean;
}

export interface LiveStep {
  stepNo: number;
  replyId: string;
  thinking: string;
  content: string;
  tools: LiveTool[];
  usage: Record<string, number>;
  durationMs?: number;
  status: string;
}

export interface RuntimeEvent {
  type: string;
  summary: string;
}

export interface ChatStreamView {
  content: string;
  thinking: string;
  tool: string;
  phase: 'thinking' | 'tool' | 'answering';
}

export interface SessionChatState {
  messages: Message[];
  stream: ChatStreamView | null;
  busy: boolean;
  tools: LiveTool[];
  liveSteps: LiveStep[];
  runtimeEvents: RuntimeEvent[];
  loaded: boolean;
}

const states = new Map<string, SessionChatState>();
const listeners = new Map<string, Set<() => void>>();

function blank(): SessionChatState {
  return { messages: [], stream: null, busy: false, tools: [], liveSteps: [], runtimeEvents: [], loaded: false };
}

export function getChatState(sessionId: string): SessionChatState {
  let s = states.get(sessionId);
  if (!s) { s = blank(); states.set(sessionId, s); }
  return s;
}

function patch(sessionId: string, change: Partial<SessionChatState>): void {
  const prev = getChatState(sessionId);
  const next = { ...prev, ...change };
  states.set(sessionId, next);
  listeners.get(sessionId)?.forEach(fn => fn());
}

export function subscribeChat(sessionId: string, fn: () => void): () => void {
  let set = listeners.get(sessionId);
  if (!set) { set = new Set(); listeners.set(sessionId, set); }
  set.add(fn);
  return () => { set!.delete(fn); };
}

export function useChatState(sessionId: string): SessionChatState {
  const subscribe = useCallback((fn: () => void) => subscribeChat(sessionId, fn), [sessionId]);
  const snapshot = useCallback(() => getChatState(sessionId), [sessionId]);
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}

/** Loads persisted messages once; never overwrites an in-flight turn. */
export async function ensureLoaded(sessionId: string): Promise<void> {
  if (getChatState(sessionId).loaded) return;
  try {
    const msgs = await api.getMessages(sessionId);
    if (!getChatState(sessionId).loaded) patch(sessionId, { messages: msgs, loaded: true });
  } catch {
    // transient failure: keep unloaded so a later mount retries
  }
}

function findTool(state: SessionChatState, id: string): LiveTool | undefined {
  return state.tools.find(t => t.id === id || (!t.done && t.name === id));
}

function patchTool(sessionId: string, id: string, change: (t: LiveTool) => LiveTool): void {
  patch(sessionId, {
    tools: getChatState(sessionId).tools.map(t => findTool(getChatState(sessionId), id) === t ? change(t) : t),
    liveSteps: getChatState(sessionId).liveSteps.map(s => ({
      ...s,
      tools: s.tools.map(t => t.id === id || (!t.done && t.name === id) ? change(t) : t),
    })),
  });
}

function patchStep(sessionId: string, stepNo: number | undefined, change: (s: LiveStep) => LiveStep): void {
  // Deltas without an explicit step number belong to the CURRENT (last started)
  // model call only. Never fan a delta out to every step — that duplicated the
  // streaming reply into earlier folded steps (ISSUE-021).
  const steps = getChatState(sessionId).liveSteps;
  const active = steps[steps.length - 1];
  patch(sessionId, {
    liveSteps: steps.map(s => (stepNo == null ? s === active : s.stepNo === stepNo) ? change(s) : s),
  });
}

/** Starts one turn; safe to call from any mounted view, state survives unmount. */
export async function sendChat(
  sessionId: string,
  text: string,
  onTitle?: (title: string) => void,
): Promise<void> {
  if (!text.trim() || getChatState(sessionId).busy) return;
  await ensureLoaded(sessionId);
  if (getChatState(sessionId).busy) return;

  patch(sessionId, {
    messages: [...getChatState(sessionId).messages,
      { role: 'user', content: text, toolCalls: '', createdAt: '' }],
    stream: { content: '', thinking: '', tool: '', phase: 'thinking' },
    tools: [], liveSteps: [], runtimeEvents: [],
    busy: true,
  });

  try {
    const res = await api.chatStream(sessionId, text, {
      onStepStart: (stepNo, replyId) => patch(sessionId, {
        liveSteps: [...getChatState(sessionId).liveSteps, {
          stepNo, replyId, thinking: '', content: '', tools: [],
          usage: {}, status: 'running',
        }],
      }),
      onStepEnd: (stepNo, durationMs, usageJson) => {
        let usage: Record<string, number> = {};
        try { usage = JSON.parse(usageJson || '{}'); } catch { /* ignore */ }
        patchStep(sessionId, stepNo, s => ({ ...s, durationMs, usage, status: 'complete' }));
      },
      onEvent: (type, summary) => patch(sessionId, {
        runtimeEvents: [...getChatState(sessionId).runtimeEvents, { type, summary }],
      }),
      onDelta: d => {
        patch(sessionId, {
          stream: getChatState(sessionId).stream
            ? { ...getChatState(sessionId).stream!, content: getChatState(sessionId).stream!.content + d, phase: 'answering' }
            : null,
        });
        patchStep(sessionId, undefined, s => ({ ...s, content: s.content + d }));
      },
      onThinking: t => {
        patch(sessionId, {
          stream: getChatState(sessionId).stream
            ? { ...getChatState(sessionId).stream!, thinking: getChatState(sessionId).stream!.thinking + t }
            : null,
        });
        patchStep(sessionId, undefined, s => ({ ...s, thinking: s.thinking + t }));
      },
      onTool: name => patch(sessionId, {
        stream: getChatState(sessionId).stream
          ? { ...getChatState(sessionId).stream!, tool: name, phase: 'tool' }
          : null,
      }),
      onToolStart: (id, name, stepNo) => {
        const tool: LiveTool = {
          id, name, args: '', result: '', startedAt: Date.now(), stepNo,
          error: false, done: false,
        };
        patch(sessionId, {
          tools: [...getChatState(sessionId).tools, tool],
          liveSteps: getChatState(sessionId).liveSteps.map(s =>
            s.stepNo === stepNo || (stepNo === 0 && s === getChatState(sessionId).liveSteps[s.stepNo - 1])
              ? { ...s, tools: [...s.tools, tool] } : s),
        });
      },
      onToolArgs: (id, delta) => patchTool(sessionId, id, t => ({ ...t, args: t.args + delta })),
      onToolResult: (id, delta) => patchTool(sessionId, id, t => ({ ...t, result: t.result + delta })),
      onToolEnd: (id, durationMs, error) => patchTool(sessionId, id, t => ({ ...t, durationMs, error, done: true })),
    });

    let steps: StepView[] = [];
    try { steps = res.stepsJson ? JSON.parse(res.stepsJson) : []; } catch { /* ignore */ }
    patch(sessionId, {
      messages: [...getChatState(sessionId).messages, {
        role: 'assistant',
        content: res.reply || getChatState(sessionId).stream?.content || '（无回复）',
        toolCalls: res.toolCallsJson || res.toolCalls.join(', '),
        createdAt: '',
        steps,
      }],
      stream: null,
      liveSteps: [],
      runtimeEvents: [],
      busy: false,
    });
    if (res.title) onTitle?.(res.title);
  } catch (e: any) {
    const msg = e?.message || String(e);
    patch(sessionId, {
      messages: [...getChatState(sessionId).messages, {
        role: 'assistant',
        content: getChatState(sessionId).stream?.content
          ? getChatState(sessionId).stream!.content + '\n\n[中断] ' + msg : '[错误] ' + msg,
        toolCalls: '', createdAt: '',
      }],
      stream: null,
      busy: false,
    });
  }
}
