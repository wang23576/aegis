export type Role = 'SUPER_ADMIN' | 'AGENT_ADMIN' | 'USER';

export interface CurrentUser {
  id: number;
  username: string;
  role: Role;
  displayName?: string;
}

export interface AgentInfo {
  id: string;
  name: string;
  description: string;
  ownerId: number;
  builtin: boolean;
  sysPrompt: string;
  model: string;
  createdAt: string;
}

export interface SessionInfo {
  id: string;
  agentId: string;
  userId: number;
  title: string;
  permissionMode: string;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  role: string;
  content: string;
  toolCalls: string;
  createdAt: string;
}

const TOKEN_KEY = 'aegis_token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}
export function setToken(t: string) {
  localStorage.setItem(TOKEN_KEY, t);
}
export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(path, { ...options, headers: { ...headers, ...(options.headers as any) } });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      msg = body.error || body.message || msg;
    } catch { /* ignore */ }
    throw new Error(msg);
  }
  return res.json();
}

export const api = {
  async login(username: string, password: string): Promise<CurrentUser & { token: string }> {
    const data = await request<any>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
    setToken(data.token);
    return data;
  },

  listAgents(scope: 'visible' | 'manageable' = 'visible'): Promise<AgentInfo[]> {
    return request(`/api/agents?scope=${scope}`);
  },
  createAgent(body: Partial<AgentInfo>): Promise<AgentInfo> {
    return request('/api/agents', { method: 'POST', body: JSON.stringify(body) });
  },
  updateAgent(id: string, body: Partial<AgentInfo>): Promise<AgentInfo> {
    return request(`/api/agents/${id}`, { method: 'PUT', body: JSON.stringify(body) });
  },
  deleteAgent(id: string): Promise<any> {
    return request(`/api/agents/${id}`, { method: 'DELETE' });
  },
  toolCatalog(): Promise<any[]> {
    return request('/api/agents/tools/catalog');
  },

  listSessions(): Promise<SessionInfo[]> {
    return request('/api/sessions');
  },
  createSession(body: { agentId: string; title?: string; permissionMode?: string }): Promise<SessionInfo> {
    return request('/api/sessions', { method: 'POST', body: JSON.stringify(body) });
  },
  getMessages(id: string): Promise<Message[]> {
    return request(`/api/sessions/${id}/messages`);
  },
  chat(id: string, message: string): Promise<{ reply: string; error?: boolean }> {
    return request(`/api/sessions/${id}/chat`, { method: 'POST', body: JSON.stringify({ message }) });
  },

  /** Streaming chat over SSE (fetch + ReadableStream, since EventSource cannot send headers). */
  async chatStream(
    id: string,
    message: string,
    handlers: { onDelta: (t: string) => void; onThinking?: (t: string) => void;     onTool?: (name: string) => void },
  ): Promise<{ reply: string; toolCalls: string[]; title?: string }> {
    const token = getToken();
    const res = await fetch(`/api/sessions/${id}/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ message }),
    });
    if (!res.ok || !res.body) {
      let msg = `HTTP ${res.status}`;
      try { const b = await res.json(); msg = b.error || b.message || msg; } catch { /* ignore */ }
      throw new Error(msg);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let final = { reply: '', toolCalls: [] as string[], title: '' };
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        let event = 'message';
        const dataLines: string[] = [];
        for (const line of frame.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
        }
        if (!dataLines.length) continue;
        let payload: any;
        try { payload = JSON.parse(dataLines.join('\n')); } catch { continue; }
        if (event === 'delta') handlers.onDelta(payload.text);
        else if (event === 'thinking') handlers.onThinking?.(payload.text);
        else if (event === 'tool') handlers.onTool?.(payload.name);
        else if (event === 'done') final = { reply: payload.reply ?? '', toolCalls: payload.toolCalls ?? [], title: payload.title ?? '' };
        else if (event === 'error') throw new Error(payload.message);
      }
    }
    return final;
  },
  setPermission(id: string, mode: string): Promise<any> {
    return request(`/api/sessions/${id}/permission`, { method: 'PUT', body: JSON.stringify({ mode }) });
  },
  trajectory(id: string): Promise<any> {
    return request(`/api/sessions/${id}/trajectory`);
  },

  /** Downloads the session log archive (zip) via a hidden anchor so the browser saves it. */
  async downloadSessionLog(id: string): Promise<void> {
    const t = getToken();
    const res = await fetch(`/api/sessions/${id}/export`, {
      headers: t ? { Authorization: `Bearer ${t}` } : {},
    });
    if (!res.ok) {
      let msg = `HTTP ${res.status}`;
      try { msg = (await res.json()).error || msg; } catch { /* ignore */ }
      throw new Error(msg);
    }
    const disposition = res.headers.get('Content-Disposition') || '';
    const match = disposition.match(/filename="([^"]+)"/);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = match ? match[1] : `aegis-session-${id}.zip`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },

  listUsers(): Promise<any[]> {
    return request('/api/users');
  },
  createUser(body: { username: string; password: string; role: string; displayName?: string }): Promise<any> {
    return request('/api/users', { method: 'POST', body: JSON.stringify(body) });
  },
};
