import React, { useCallback, useEffect, useState } from 'react';
import { api, SkillRec, SkillToolGroupRec } from './api';

/**
 * SkillToolGroup management (Harness → Tool → 工具分组).
 * A group is a named bundle of tools the framework activates when a skill loads.
 * Definitions are persisted by Aegis and replayed into the agent's Toolkit at build.
 */
type Props = {
  agentId: string;
  tools: { name: string; category: string; readOnly: boolean; description: string }[];
};

const CATEGORY_LABEL: Record<string, string> = {
  filesystem: '文件', shell: '命令', web: '网络', memory: '记忆', session: '会话',
  subagent: '子智能体', skill: '技能', task: '任务', artifact: '交付物', plan: '计划',
  tool: '工具', other: '其他',
};

export default function ToolGroupView({ agentId, tools }: Props) {
  const [groups, setGroups] = useState<SkillToolGroupRec[]>([]);
  const [skills, setSkills] = useState<SkillRec[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');

  const [editing, setEditing] = useState(false);
  const [draftName, setDraftName] = useState('');
  const [draftDesc, setDraftDesc] = useState('');
  const [draftSkill, setDraftSkill] = useState('');
  const [draftTools, setDraftTools] = useState<string[]>([]);
  const [toolFilter, setToolFilter] = useState('');

  const flash = (msg: string) => { setToast(msg); setTimeout(() => setToast(''), 2200); };

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [g, s] = await Promise.all([api.listToolGroups(agentId), api.listSkills(agentId)]);
      setGroups(g); setSkills(s);
      setSelected(prev => (prev && g.some(x => x.name === prev)) ? prev : (g.length ? g[0].name : null));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally { setLoading(false); }
  }, [agentId]);
  useEffect(() => { load(); }, [load]);

  const current = groups.find(g => g.name === selected) || null;

  function startNew() {
    setEditing(true);
    setDraftName(''); setDraftDesc('');
    setDraftSkill(skills.length ? skills[0].name : '');
    setDraftTools([]); setToolFilter(''); setError('');
  }
  function startEdit(g: SkillToolGroupRec) {
    setEditing(true);
    setDraftName(g.name); setDraftDesc(g.description);
    setDraftSkill(g.activateOnSkill);
    setDraftTools([...g.tools]); setToolFilter(''); setError('');
  }

  async function save() {
    if (!draftName.trim()) { setError('请填写分组名称'); return; }
    if (!draftSkill) { setError('请选择激活技能'); return; }
    if (!draftTools.length) { setError('至少选择一个工具'); return; }
    setError('');
    try {
      const body = { description: draftDesc.trim(), activateOnSkill: draftSkill, tools: draftTools };
      if (editing && current) {
        await api.updateToolGroup(agentId, current.name, body);
        flash('分组已更新，下次会话生效');
      } else {
        await api.createToolGroup(agentId, { name: draftName.trim(), ...body });
        flash('分组已创建，下次会话生效');
        setSelected(draftName.trim());
      }
      setEditing(false);
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败'); }
  }

  async function remove() {
    if (!current || !window.confirm(`确认删除分组 ${current.name}？下次会话生效。`)) return;
    setError('');
    try {
      await api.deleteToolGroup(agentId, current.name);
      flash('分组已删除'); setEditing(false); setSelected(null);
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '删除失败'); }
  }

  function toggleTool(name: string) {
    setDraftTools(prev => prev.includes(name) ? prev.filter(x => x !== name) : [...prev, name]);
  }

  const filteredTools = tools.filter(t =>
    !toolFilter || t.name.toLowerCase().includes(toolFilter.toLowerCase())
      || (CATEGORY_LABEL[t.category] || t.category).includes(toolFilter));

  return (
    <div className="tool-body tg-body">
      <div className="tool-hint">
        工具分组在加载指定技能时自动激活其中的工具。框架原生机制：activateOnSkill 按技能名称绑定，
        不绑定版本。分组保存后下次会话生效。
      </div>
      <div className="tool-toolbar">
        <button className="tool-btn primary" onClick={startNew}>+ 新建分组</button>
        <span className="tool-spacer" />
        <button className="tool-btn" onClick={load} title="重新加载">↻</button>
      </div>
      {error && <div className="tool-error">{error}</div>}

      <div className="tg-layout">
        <aside className="tg-list">
          {loading && <div className="ws-empty">加载中…</div>}
          {!loading && groups.length === 0 && <div className="ws-empty">暂无工具分组</div>}
          {groups.map(g => (
            <div key={g.name}
                 className={'tg-item' + (selected === g.name ? ' active' : '')}
                 onClick={() => { setSelected(g.name); setEditing(false); }}>
              <div className="tg-item-name">🧩 {g.name}</div>
              <div className="tg-item-meta">技能: {g.activateOnSkill} · {g.tools.length} 个工具</div>
            </div>
          ))}
        </aside>

        <section className="tg-detail">
          {editing ? (
            <div className="tg-form">
              <div className="tg-form-title">{current ? `编辑 ${current.name}` : '新建分组'}</div>
              {!current && (
                <label>名称（英文，kebab-case）
                  <input value={draftName} onChange={e => setDraftName(e.target.value)} placeholder="例如 code-review-tools" />
                </label>
              )}
              <label>描述
                <input value={draftDesc} onChange={e => setDraftDesc(e.target.value)} placeholder="这个分组的用途" />
              </label>
              <label>激活技能
                <select value={draftSkill} onChange={e => setDraftSkill(e.target.value)}>
                  <option value="">请选择技能</option>
                  {skills.map(s => <option key={s.name} value={s.name}>{s.name}</option>)}
                </select>
              </label>
              {skills.length === 0 && <div className="tool-hint">当前智能体还没有技能，请先在 Skill 页签创建。</div>}
              <div className="tg-tool-picker">
                <div className="tg-section-title">包含工具（已选 {draftTools.length}）</div>
                <input className="tg-filter" value={toolFilter} onChange={e => setToolFilter(e.target.value)}
                       placeholder="筛选工具名或分类…" />
                <div className="tg-tool-grid">
                  {filteredTools.map(t => (
                    <label key={t.name} className="tg-tool-row">
                      <input type="checkbox" checked={draftTools.includes(t.name)} onChange={() => toggleTool(t.name)} />
                      <span>{t.name}</span>
                      <span className="tool-tag">{CATEGORY_LABEL[t.category] || t.category}</span>
                    </label>
                  ))}
                </div>
              </div>
              <div className="skill-form-actions">
                <button className="tool-btn primary" onClick={save}>保存</button>
                <button className="tool-btn" onClick={() => setEditing(false)}>取消</button>
              </div>
            </div>
          ) : current ? (
            <div className="tg-form">
              <div className="tg-form-title">{current.name}</div>
              <div className="tg-readonly-row"><span className="tg-label">描述</span><span>{current.description || '（无）'}</span></div>
              <div className="tg-readonly-row"><span className="tg-label">激活技能</span><span className="tg-skill-tag">{current.activateOnSkill}</span></div>
              <div className="tg-tool-picker">
                <div className="tg-section-title">包含工具（{current.tools.length}）</div>
                <div className="tg-tool-chips">
                  {current.tools.map(t => <span key={t} className="tg-chip">{t}</span>)}
                </div>
              </div>
              <div className="skill-form-actions">
                <button className="tool-btn" onClick={() => startEdit(current)}>编辑</button>
                <button className="tool-btn danger" onClick={remove}>删除</button>
              </div>
            </div>
          ) : (
            <div className="ws-empty" style={{ flex: 1 }}>从左侧选择分组，或新建一个</div>
          )}
        </section>
      </div>
      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}
