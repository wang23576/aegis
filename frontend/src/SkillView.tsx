import React, { useCallback, useEffect, useState } from 'react';
import { api, SkillRec, SkillVersion } from './api';

/**
 * Skill management (Harness → Skill tab).
 * Active skills live in workspace/skills/<name>/SKILL.md (framework-native and
 * visible in Workspace); this view edits them and manages a revision history.
 */
export default function SkillView({ agentId }: { agentId: string }) {
  const [skills, setSkills] = useState<SkillRec[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<SkillRec | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [dirty, setDirty] = useState(false);
  const [editing, setEditing] = useState(false);

  // create dialog
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');
  const [newContent, setNewContent] = useState('# 新技能\n\n描述触发场景与执行步骤。\n');

  // version history
  const [versions, setVersions] = useState<SkillVersion[]>([]);
  const [showVersions, setShowVersions] = useState(false);
  const [preview, setPreview] = useState<SkillVersion | null>(null);

  const [draftDesc, setDraftDesc] = useState('');
  const [draftContent, setDraftContent] = useState('');

  const flash = (msg: string) => { setToast(msg); setTimeout(() => setToast(''), 2200); };

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const list = await api.listSkills(agentId);
      setSkills(list);
      setSelected(prev => {
        if (prev && list.some(s => s.name === prev)) return prev;
        return list.length ? list[0].name : null;
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally { setLoading(false); }
  }, [agentId]);

  useEffect(() => { load(); }, [load]);

  const loadDetail = useCallback(async (name: string) => {
    setError('');
    try {
      const d = await api.getSkill(agentId, name);
      setDetail(d); setDraftDesc(d.description); setDraftContent(d.content);
      setEditing(false); setDirty(false); setPreview(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载技能失败');
    }
  }, [agentId]);

  useEffect(() => { if (selected) loadDetail(selected); else setDetail(null); }, [selected, loadDetail]);

  const loadVersions = useCallback(async (name: string) => {
    try { setVersions(await api.listSkillVersions(agentId, name)); }
    catch (e) { setError(e instanceof Error ? e.message : '加载版本失败'); }
  }, [agentId]);

  useEffect(() => { if (showVersions && selected) loadVersions(selected); }, [showVersions, selected, loadVersions]);

  function changeDesc(v: string) { setDraftDesc(v); setDirty(true); setEditing(true); }
  function changeContent(v: string) { setDraftContent(v); setDirty(true); setEditing(true); }

  async function save() {
    if (!selected || !dirty) return;
    setError('');
    try {
      await api.updateSkill(agentId, selected, { description: draftDesc, content: draftContent });
      flash('已保存，下次会话生效');
      await load(); await loadDetail(selected);
      if (showVersions) loadVersions(selected);
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败'); }
  }

  async function create() {
    if (!newName.trim()) { setError('请填写技能名称'); return; }
    setError('');
    try {
      await api.createSkill(agentId, { name: newName.trim(), description: newDesc.trim(), content: newContent });
      setShowCreate(false); setNewName(''); setNewDesc('');
      flash('技能已创建');
      await load(); setSelected(newName.trim());
    } catch (e) { setError(e instanceof Error ? e.message : '创建失败'); }
  }

  async function remove() {
    if (!selected || !window.confirm(`确认删除技能 ${selected}？其版本历史也会一并删除。`)) return;
    setError('');
    try {
      await api.deleteSkill(agentId, selected);
      flash('技能已删除');
      setSelected(null); setDetail(null);
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '删除失败'); }
  }

  async function snapshot() {
    if (!selected) return;
    setError('');
    try {
      const r = await api.snapshotSkill(agentId, selected, `手动保存 · ${draftDesc.slice(0, 40)}`);
      flash(`已保存版本 v${r.version}`);
      if (showVersions) loadVersions(selected); else { setShowVersions(true); }
    } catch (e) { setError(e instanceof Error ? e.message : '保存版本失败'); }
  }

  async function rollback(v: number) {
    if (!selected || !window.confirm(`确认回滚到 v${v}？当前内容会先自动存为版本。`)) return;
    setError('');
    try {
      await api.rollbackSkill(agentId, selected, v);
      flash(`已回滚到 v${v}，下次会话生效`);
      setPreview(null);
      await loadDetail(selected); await load(); await loadVersions(selected);
    } catch (e) { setError(e instanceof Error ? e.message : '回滚失败'); }
  }

  return (
    <div className="skill-view">
      <div className="tool-bar">
        <button className="tool-btn primary" onClick={() => setShowCreate(true)}>+ 新建技能</button>
        <span className="tool-spacer" />
        {dirty && <span className="tool-dirty">未保存</span>}
        <button className="tool-btn" onClick={load} title="重新加载">↻</button>
      </div>
      {error && <div className="tool-error">{error}</div>}

      <div className="skill-body">
        <aside className="skill-list">
          <div className="skill-list-head">技能（{skills.length}）</div>
          {loading && <div className="ws-empty">加载中…</div>}
          {!loading && skills.length === 0 && <div className="ws-empty">暂无技能</div>}
          {skills.map(s => (
            <div key={s.name}
                 className={'skill-item' + (selected === s.name ? ' active' : '')}
                 onClick={() => setSelected(s.name)}>
              <div className="skill-item-name">🧩 {s.name}</div>
              <div className="skill-item-desc">{s.description || '（无描述）'}</div>
              <div className="skill-item-meta">{s.versionCount || 0} 个版本</div>
            </div>
          ))}
        </aside>

        <section className="skill-detail">
          {!detail ? (
            <div className="ws-empty" style={{ flex: 1 }}>从左侧选择一个技能</div>
          ) : (
            <>
              <div className="skill-detail-head">
                <span className="skill-detail-title">{detail.name}</span>
                <span className="tool-tag">source: {detail.source}</span>
                <span className="tool-spacer" />
                <button className={'tool-btn' + (showVersions ? ' active' : '')}
                        onClick={() => setShowVersions(v => !v)}>🕘 版本历史</button>
                <button className="tool-btn" onClick={snapshot}>保存版本</button>
                <button className="tool-btn danger" onClick={remove}>删除</button>
              </div>

              {showVersions && (
                <div className="skill-versions">
                  <div className="skill-section-title">版本历史</div>
                  {versions.length === 0 && <div className="skill-version-empty">暂无历史版本（每次保存会自动快照）</div>}
                  {versions.map(v => (
                    <div key={v.version} className={'skill-version-row' + (preview?.version === v.version ? ' active' : '')}>
                      <span className="skill-ver-tag">v{v.version}</span>
                      <span className="skill-ver-time">{v.savedAt}</span>
                      <span className="skill-ver-note">{v.note || v.description}</span>
                      <span className="tool-spacer" />
                      <button className="tool-btn" onClick={() => setPreview(v)}>查看</button>
                      <button className="tool-btn" onClick={() => rollback(v.version)}>回滚</button>
                    </div>
                  ))}
                  {preview && (
                    <div className="skill-version-preview">
                      <div className="skill-preview-head">
                        <strong>v{preview.version} 内容</strong>
                        <button className="tool-btn" onClick={() => setPreview(null)}>关闭</button>
                      </div>
                      <pre>{preview.content}</pre>
                    </div>
                  )}
                </div>
              )}

              <div className="skill-form">
                <label>描述
                  <input value={draftDesc} onChange={e => changeDesc(e.target.value)}
                         placeholder="简短说明何时使用该技能" />
                </label>
                <label>正文（SKILL.md）
                  <textarea value={draftContent} onChange={e => changeContent(e.target.value)}
                            rows={16} spellCheck={false} />
                </label>
                <div className="skill-form-actions">
                  <button className="tool-btn primary" onClick={save} disabled={!dirty}>保存</button>
                  {dirty && <button className="tool-btn" onClick={() => selected && loadDetail(selected)}>放弃修改</button>}
                </div>
              </div>
            </>
          )}
        </section>
      </div>

      {showCreate && (
        <div className="tool-modal-mask" onClick={() => setShowCreate(false)}>
          <div className="tool-modal" onClick={e => e.stopPropagation()}>
            <div className="tool-modal-head">
              <strong>新建技能</strong>
              <button className="tool-btn" onClick={() => setShowCreate(false)}>关闭</button>
            </div>
            <div className="tool-form">
              <label>名称（英文，kebab-case）
                <input value={newName} onChange={e => setNewName(e.target.value)} placeholder="例如 code-review" />
              </label>
              <label>描述
                <input value={newDesc} onChange={e => setNewDesc(e.target.value)} placeholder="何时使用该技能" />
              </label>
              <label>正文
                <textarea rows={10} value={newContent} onChange={e => setNewContent(e.target.value)} spellCheck={false} />
              </label>
            </div>
            <div className="tool-modal-foot">
              <button className="tool-btn primary" onClick={create}>创建</button>
            </div>
          </div>
        </div>
      )}
      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}
