import React, { useEffect, useRef, useState } from 'react';
import { api, AgentInfo, CurrentUser, Message, SessionInfo, clearToken, getToken } from './api';

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

function ChatView({
  session, agents, onPermissionChange,
}: {
  session: SessionInfo;
  agents: AgentInfo[];
  onPermissionChange: (mode: string) => void;
}) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState(session.permissionMode);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    api.getMessages(session.id).then(setMessages).catch(() => setMessages([]));
    setMode(session.permissionMode);
  }, [session.id, session.permissionMode]);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  async function send() {
    if (!text.trim() || busy) return;
    const content = text;
    setText(''); setBusy(true);
    setMessages(m => [...m, { role: 'user', content, toolCalls: '', createdAt: '' }]);
    try {
      const res = await api.chat(session.id, content);
      setMessages(m => [...m, { role: 'assistant', content: res.reply, toolCalls: '', createdAt: '' }]);
    } catch (e: any) {
      setMessages(m => [...m, { role: 'assistant', content: '[错误] ' + e.message, toolCalls: '', createdAt: '' }]);
    } finally { setBusy(false); }
  }

  async function changeMode(newMode: string) {
    setMode(newMode);
    try { await api.setPermission(session.id, newMode); onPermissionChange(newMode); } catch { /* ignore */ }
  }

  const agent = agents.find(a => a.id === session.agentId);

  return (
    <>
      <div className="chat-area">
        {messages.length === 0 && <div style={{ color: '#aaa', textAlign: 'center', marginTop: 60 }}>开始对话吧 · 智能体：{agent?.name || session.agentId}</div>}
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role === 'user' ? 'user' : ''}`}>
            <div className="avatar">{m.role === 'user' ? '我' : 'AI'}</div>
            <div className="bubble">{m.content}</div>
          </div>
        ))}
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
            <button className="send" onClick={send} disabled={busy || !text.trim()}>{busy ? '发送中…' : '发送'}</button>
          </div>
        </div>
      </div>
    </>
  );
}

function TrajectoryView({ sessionId }: { sessionId: string }) {
  const [data, setData] = useState<any>(null);
  useEffect(() => { api.trajectory(sessionId).then(setData).catch(() => setData(null)); }, [sessionId]);
  if (!data) return <div className="traj">加载轨迹中…</div>;
  return (
    <div className="traj">
      <div className="traj-stats">
        <div className="stat"><div className="v">{data.turns}</div><div className="l">轮次</div></div>
        <div className="stat"><div className="v">{data.toolCalls}</div><div className="l">工具调用</div></div>
        <div className="stat"><div className="v">{data.messages?.length || 0}</div><div className="l">消息数</div></div>
      </div>
      <h3 style={{ marginBottom: 12, fontSize: 15 }}>执行轨迹</h3>
      {data.messages?.map((m: any, i: number) => (
        <div className="traj-row" key={i}>
          <span className="role">{m.role === 'user' ? '用户' : '助手'}</span>
          <span className="t">{m.createdAt}</span>
          <div style={{ marginTop: 4, color: '#666', whiteSpace: 'pre-wrap' }}>{m.content?.slice(0, 300)}</div>
        </div>
      ))}
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

function Workspace({ user, onLogout }: { user: CurrentUser; onLogout: () => void }) {
  const [agents, setAgents] = useState<AgentInfo[]>([]);
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [current, setCurrent] = useState<SessionInfo | null>(null);
  const [view, setView] = useState<'chat' | 'trajectory'>('chat');
  const [showChooser, setShowChooser] = useState(false);
  const [admin, setAdmin] = useState(false);

  const loadSessions = () => api.listSessions().then(setSessions).catch(() => {});
  useEffect(() => {
    api.listAgents().then(setAgents).catch(() => {});
    loadSessions();
  }, []);

  async function pickAgent(a: AgentInfo) {
    try {
      const s = await api.createSession({ agentId: a.id, title: '与 ' + a.name + ' 的对话' });
      setSessions(prev => [s, ...prev]);
      setCurrent(s); setShowChooser(false); setView('chat');
    } catch (e: any) { alert('创建会话失败：' + e.message); }
  }

  function openSession(s: SessionInfo) { setCurrent(s); setView('chat'); }

  return (
    <div className="app">
      <div className="sidebar">
        <div className="brand"><span className="dot" /> Aegis</div>
        <button className="new-chat" onClick={() => { setShowChooser(true); setCurrent(null); }}>+ 新会话</button>
        <div className="side-scroll">
          <div className="ws-group">
            <div className="ws-title">工作区 · 默认</div>
            {sessions.map(s => (
              <div key={s.id} className={`sess-item ${current?.id === s.id ? 'active' : ''}`} onClick={() => openSession(s)}>
                <span>{s.title || s.id.slice(0, 8)}</span>
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
                </div>
              )}
            </div>
            {showChooser || !current ? <AgentChooser agents={agents} onPick={pickAgent} /> : (
              view === 'chat'
                ? <ChatView session={current} agents={agents} onPermissionChange={m => setCurrent({ ...current, permissionMode: m })} />
                : <TrajectoryView sessionId={current.id} />
            )}
          </>
        )}
      </div>
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
