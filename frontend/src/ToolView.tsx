import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from './api';
import ToolGroupView from './ToolGroupView';

/**
 * Tool management (Harness → Tool tab).
 * Built-in tools are toggleable via allow/deny in workspace/tools.json;
 * MCP servers are configured verbatim (framework-native format, offline-friendly).
 */
type Builtin = {
  name: string; category: string; readOnly: boolean; description: string; enabled: boolean;
};
type McpServer = Record<string, any>;
type View = {
  builtins: Builtin[]; allow: string[]; deny: string[];
  mcpServers: Record<string, McpServer>;
};

const CATEGORY_LABEL: Record<string, string> = {
  filesystem: '文件', shell: '命令', web: '网络', memory: '记忆', session: '会话',
  subagent: '子智能体', skill: '技能', task: '任务', artifact: '交付物', plan: '计划',
  tool: '工具', other: '其他',
};

const DESC_LIMIT = 120;

function booleanField(v: any): boolean {
  return v === true || v === 'true';
}

export default function ToolView({ agentId }: { agentId: string }) {
  const [view, setView] = useState<View | null>(null);
  const [tab, setTab] = useState<'builtin' | 'mcp' | 'groups'>('builtin');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [dirty, setDirty] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState<McpServer>({});
  const [expandedDesc, setExpandedDesc] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const v = await api.toolView(agentId);
      setView(v); setDirty(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally { setLoading(false); }
  }, [agentId]);
  useEffect(() => { load(); }, [load]);

  const groups = useMemo(() => {
    const m = new Map<string, Builtin[]>();
    (view?.builtins || []).forEach(b => {
      const arr = m.get(b.category) || []; arr.push(b); m.set(b.category, arr);
    });
    return Array.from(m.entries());
  }, [view]);

  function toggleDesc(name: string) {
    setExpandedDesc(prev => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name); else next.add(name);
      return next;
    });
  }

  function toggleBuiltin(name: string) {
    if (!view) return;
    const allowMode = view.allow.length > 0;
    const wasEnabled = view.builtins.find(b => b.name === name)?.enabled ?? false;
    let allow = [...view.allow], deny = [...view.deny];
    if (wasEnabled) {
      if (!deny.includes(name)) deny.push(name);
      allow = allow.filter(x => x !== name);
    } else {
      deny = deny.filter(x => x !== name);
      if (allowMode && !allow.includes(name)) allow.push(name);
    }
    setView({
      ...view, allow, deny,
      builtins: view.builtins.map(b => b.name === name ? { ...b, enabled: !wasEnabled } : b),
    });
    setDirty(true);
  }

  async function save() {
    if (!view) return;
    setSaving(true); setError('');
    try {
      await api.saveTools(agentId, { allow: view.allow, deny: view.deny, mcpServers: view.mcpServers });
      setDirty(false); setToast('已保存，下次会话生效');
      setTimeout(() => setToast(''), 2200);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally { setSaving(false); }
  }

  function startAdd() {
    setEditing('__new__');
    setDraft({ transport: 'sse', url: '', headers: {} });
  }
  function startEdit(name: string) {
    setEditing(name);
    setDraft({ ...(view?.mcpServers[name] || {}) });
  }
  function saveServer() {
    if (!view || !editing) return;
    const name = editing === '__new__' ? String(draft.name || '').trim() : editing;
    if (!name) { setError('请填写 Server 名称'); return; }
    const s: McpServer = { ...draft }; delete s.name;
    const mcpServers = { ...view.mcpServers, [name]: s };
    setView({ ...view, mcpServers }); setEditing(null); setDirty(true); setError('');
  }
  function removeServer(name: string) {
    if (!view) return;
    const mcpServers = { ...view.mcpServers }; delete mcpServers[name];
    setView({ ...view, mcpServers }); setDirty(true);
  }
  function field(key: string, value: any) {
    setDraft(d => ({ ...d, [key]: value }));
  }

  const serverNames = Object.keys(view?.mcpServers || {});

  return (
    <div className="tool-view">
      <div className="tool-bar">
        <button className={tab === 'builtin' ? 'active' : ''} onClick={() => setTab('builtin')}>🔧 内置工具</button>
        <button className={tab === 'mcp' ? 'active' : ''} onClick={() => setTab('mcp')}>🔌 MCP 服务</button>
        <button className={tab === 'groups' ? 'active' : ''} onClick={() => setTab('groups')}>🧩 工具分组</button>
        <span className="tool-spacer" />
        {tab !== 'groups' && dirty && <span className="tool-dirty">未保存</span>}
        {tab !== 'groups' && <button className="tool-btn" onClick={load} title="重新加载">↻</button>}
        {tab !== 'groups' && <button className="tool-btn primary" onClick={save} disabled={!dirty || saving}>{saving ? '保存中…' : '保存'}</button>}
      </div>
      {error && <div className="tool-error">{error}</div>}
      {tab === 'groups' ? (
        <ToolGroupView agentId={agentId} tools={view?.builtins || []} />
      ) : loading && !view ? <div className="ws-empty">加载中…</div> : (
        tab === 'builtin' ? (
          <div className="tool-body">
            <div className="tool-hint">内置工具由 Harness 提供。关闭的工具会从智能体的可用工具中排除，下次会话生效。</div>
            {groups.map(([category, tools]) => (
              <div key={category} className="tool-group">
                <div className="tool-group-title">{CATEGORY_LABEL[category] || category}</div>
                {tools.map(t => (
                  <label key={t.name} className="tool-row">
                    <input type="checkbox" checked={t.enabled} onChange={() => toggleBuiltin(t.name)} />
                    <span className="tool-row-main">
                      <span className="tool-row-name">{t.name}
                        <span className="tool-tag">{t.readOnly ? '只读' : '写入'}</span>
                      </span>
                      <span className="tool-row-desc">
                        {t.description.length > DESC_LIMIT && !expandedDesc.has(t.name)
                          ? t.description.slice(0, DESC_LIMIT) + '… ' : t.description}
                        {t.description.length > DESC_LIMIT && (
                          <button type="button" className="tool-desc-more"
                            onClick={e => { e.preventDefault(); e.stopPropagation(); toggleDesc(t.name); }}>
                            {expandedDesc.has(t.name) ? '收起' : '展开'}
                          </button>
                        )}
                      </span>
                    </span>
                  </label>
                ))}
              </div>
            ))}
          </div>
        ) : (
          <div className="tool-body">
            <div className="tool-hint">MCP 服务配置写入 workspace/tools.json，格式与框架原生一致。内网环境请填写可达的服务地址或本地命令。</div>
            <div className="tool-toolbar"><button className="tool-btn primary" onClick={startAdd}>+ 添加 MCP 服务</button></div>
            {serverNames.length === 0 && <div className="ws-empty">暂无 MCP 服务</div>}
            {serverNames.map(name => {
              const s = view?.mcpServers[name] || {};
              return (
                <div key={name} className="tool-server">
                  <div className="tool-server-head">
                    <span className="tool-server-name">{name}</span>
                    <span className="tool-tag">{(s.transport || '').toUpperCase()}</span>
                    <span className="tool-spacer" />
                    <button className="tool-btn" onClick={() => startEdit(name)}>编辑</button>
                    <button className="tool-btn danger" onClick={() => removeServer(name)}>删除</button>
                  </div>
                  <div className="tool-server-meta">
                    {s.transport === 'stdio' ? `命令: ${s.command || ''} ${(s.args || []).join(' ')}` : `地址: ${s.url || ''}`}
                  </div>
                </div>
              );
            })}
          </div>
        )
      )}

      {editing && (
        <div className="tool-modal-mask" onClick={() => setEditing(null)}>
          <div className="tool-modal" onClick={e => e.stopPropagation()}>
            <div className="tool-modal-head">
              <strong>{editing === '__new__' ? '添加 MCP 服务' : `编辑 ${editing}`}</strong>
              <button className="tool-btn" onClick={() => setEditing(null)}>关闭</button>
            </div>
            <div className="tool-form">
              {editing === '__new__' && (
                <label>名称<input value={draft.name || ''} onChange={e => field('name', e.target.value)} placeholder="例如 internal-git" /></label>
              )}
              <label>传输协议
                <select value={draft.transport || 'sse'} onChange={e => field('transport', e.target.value)}>
                  <option value="sse">SSE</option>
                  <option value="http">HTTP</option>
                  <option value="streamable-http">Streamable HTTP</option>
                  <option value="stdio">stdio（本地命令）</option>
                </select>
              </label>
              {(draft.transport === 'stdio') ? (
                <>
                  <label>命令<input value={draft.command || ''} onChange={e => field('command', e.target.value)} placeholder="例如 npx" /></label>
                  <label>参数（逗号分隔）<input value={(draft.args || []).join(',')} onChange={e => field('args', e.target.value.split(',').map(x => x.trim()).filter(Boolean))} /></label>
                </>
              ) : (
                <label>URL<input value={draft.url || ''} onChange={e => field('url', e.target.value)} placeholder="http://mcp.internal.example/sse" /></label>
              )}
              <label>请求头（JSON 对象）<textarea rows={3} value={JSON.stringify(draft.headers || {}, null, 2)} onChange={e => {
                try { field('headers', JSON.parse(e.target.value)); setError(''); }
                catch { field('headers', draft.headers || {}); }
              }} /></label>
              {draft.transport === 'stdio' && (
                <label>环境变量（JSON 对象）<textarea rows={3} value={JSON.stringify(draft.env || {}, null, 2)} onChange={e => {
                  try { field('env', JSON.parse(e.target.value)); setError(''); }
                  catch { field('env', draft.env || {}); }
                }} /></label>
              )}
              <label>仅启用工具（逗号分隔，可选）<input value={(draft.enableTools || []).join(',')} onChange={e => field('enableTools', e.target.value.split(',').map(x => x.trim()).filter(Boolean))} /></label>
              <label>必连（连接失败则构建失败）<input type="checkbox" checked={booleanField(draft.required)} onChange={e => field('required', e.target.checked)} /></label>
            </div>
            <div className="tool-modal-foot">
              <button className="tool-btn primary" onClick={saveServer}>确定</button>
            </div>
          </div>
        </div>
      )}
      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}
