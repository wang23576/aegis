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
  setPermission(id: string, mode: string): Promise<any> {
    return request(`/api/sessions/${id}/permission`, { method: 'PUT', body: JSON.stringify({ mode }) });
  },
  trajectory(id: string): Promise<any> {
    return request(`/api/sessions/${id}/trajectory`);
  },

  listUsers(): Promise<any[]> {
    return request('/api/users');
  },
  createUser(body: { username: string; password: string; role: string; displayName?: string }): Promise<any> {
    return request('/api/users', { method: 'POST', body: JSON.stringify(body) });
  },
};
