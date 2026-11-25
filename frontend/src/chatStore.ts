import { useCallback, useSyncExternalStore } from 'react';
import { api, Message } from './api';

/**
 * Session-scoped chat state that lives OUTSIDE React components.
 * Switching sessions / tabs / admin view unmounts ChatView; keeping the stream
 * state here means SSE callbacks keep updating and the UI restores the exact
 * running/completed state when the view is remounted (ISSUE-014).
 */

export interface LiveTool {
  id: string;
  name: string;
  args: string;
  result: string;
  startedAt: number;
  durationMs?: number;
  error: boolean;
  done: boolean;
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
  loaded: boolean;
}

const states = new Map<string, SessionChatState>();
const listeners = new Map<string, Set<() => void>>();

function blank(): SessionChatState {
  return { messages: [], stream: null, busy: false, tools: [], loaded: false };
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
    tools: [],
    busy: true,
  });

  const bump = (change: (s: SessionChatState) => Partial<SessionChatState>) => {
    patch(sessionId, change(getChatState(sessionId)));
  };

  try {
    const res = await api.chatStream(sessionId, text, {
      onDelta: d => bump(s => s.stream ? {
        stream: { ...s.stream, content: s.stream.content + d, phase: 'answering' },
      } : {}),
      onThinking: t => bump(s => s.stream ? {
        stream: { ...s.stream, thinking: s.stream.thinking + t },
      } : {}),
      onTool: name => bump(s => s.stream ? {
        stream: { ...s.stream, tool: name, phase: 'tool' },
      } : {}),
      onToolStart: (id, name) => bump(s => ({
        tools: [...s.tools, {
          id: id || name, name, args: '', result: '', startedAt: Date.now(),
          error: false, done: false,
        }],
      })),
      onToolArgs: (id, delta) => bump(s => ({
        tools: s.tools.map(t => findTool(s, id) === t ? { ...t, args: t.args + delta } : t),
      })),
      onToolResult: (id, delta) => bump(s => ({
        tools: s.tools.map(t => findTool(s, id) === t ? { ...t, result: t.result + delta } : t),
      })),
      onToolEnd: (id, durationMs, error) => bump(s => ({
        tools: s.tools.map(t => findTool(s, id) === t
          ? { ...t, durationMs, error, done: true } : t),
      })),
    });

    bump(s => ({
      messages: [...s.messages, {
        role: 'assistant',
        content: res.reply || s.stream?.content || '（无回复）',
        toolCalls: res.toolCallsJson || res.toolCalls.join(', '),
        createdAt: '',
      }],
      stream: null,
      busy: false,
    }));
    if (res.title) onTitle?.(res.title);
  } catch (e: any) {
    const msg = e?.message || String(e);
    bump(s => {
      const partial = s.stream?.content || '';
      return {
        messages: [...s.messages, {
          role: 'assistant',
          content: partial ? partial + '\n\n[中断] ' + msg : '[错误] ' + msg,
          toolCalls: '', createdAt: '',
        }],
        stream: null,
        busy: false,
      };
    });
  }
}
