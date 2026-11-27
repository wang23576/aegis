import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { api, FileContent, FileNode } from './api';

/**
 * Read-only workspace browser (paw/DSH parity).
 * Left: file tree (refresh modes + hidden-file toggle). Right: read-only viewer.
 */
export default function WorkspaceView({ agentId }: { agentId: string }) {
  const [tree, setTree] = useState<FileNode[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState<FileContent | null>(null);
  const [contentLoading, setContentLoading] = useState(false);
  const [showHidden, setShowHidden] = useState(false);
  const [wrap, setWrap] = useState(false);

  const loadTree = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const nodes = await api.workspaceTree(agentId);
      setTree(nodes);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载文件树失败');
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => { loadTree(); }, [loadTree]);

  useEffect(() => {
    if (!selected) { setContent(null); return; }
    let alive = true;
    setContentLoading(true);
    api.workspaceFile(agentId, selected)
      .then((c: FileContent) => { if (alive) setContent(c); })
      .catch((e: unknown) => { if (alive) setContent({ path: selected, size: 0, content: '', message: e instanceof Error ? e.message : '读取失败' }); })
      .finally(() => { if (alive) setContentLoading(false); });
    return () => { alive = false; };
  }, [agentId, selected]);

  const visibleTree = useMemo(() => {
    if (!tree) return null;
    if (showHidden) return tree;
    const keep = (nodes: FileNode[]): FileNode[] =>
      nodes.filter(n => !n.name.startsWith('.')).map(n =>
        n.children ? { ...n, children: keep(n.children) } : n);
    return keep(tree);
  }, [tree, showHidden]);

  const viewer = contentLoading
    ? <div className="ws-empty">加载中…</div>
    : !content
      ? <div className="ws-empty">从左侧选择文件查看</div>
      : content.binary || content.truncated
        ? <div className="ws-empty">{content.message || '无法预览'}</div>
        : (
          <textarea
            className="ws-viewer"
            wrap={wrap ? 'soft' : 'off'}
            style={{ whiteSpace: wrap ? 'pre-wrap' : 'pre', overflowWrap: wrap ? 'break-word' : 'normal' }}
            value={content.content}
            readOnly
            spellCheck={false}
          />
        );

  return (
    <div className="ws">
      <div className="ws-tree">
        <div className="ws-toolbar">
          <span className="ws-title">Workspace</span>
          <button className="ws-btn" title="刷新文件树" onClick={loadTree}>↻</button>
          <button
            className={'ws-btn' + (showHidden ? ' active' : '')}
            title={showHidden ? '隐藏隐藏文件' : '显示隐藏文件'}
            onClick={() => setShowHidden(s => !s)}
          >{showHidden ? '👁 显示隐藏' : '👁 隐藏项'}</button>
        </div>
        <div className="ws-tree-scroll">
          {error && <div className="ws-empty">{error}</div>}
          {!error && !tree && !loading && <div className="ws-empty">加载中…</div>}
          {tree && <TreeNodes nodes={visibleTree || []} depth={0} selected={selected} onSelect={setSelected} />}
        </div>
      </div>
      <div className="ws-view">
        <div className="ws-view-bar">
          <span className="ws-path">{content?.path || selected || '—'}</span>
          <span className="ws-badge">read-only</span>
          {content && !content.binary && !content.truncated && (
            <button
              className="ws-btn"
              title={wrap ? '换行：开（仅显示）' : '换行：关（长行横向滚动）'}
              onClick={() => setWrap(w => !w)}
            >{wrap ? 'wrap: on' : 'wrap: off'}</button>
          )}
        </div>
        {viewer}
      </div>
    </div>
  );
}

function TreeNodes({ nodes, depth, selected, onSelect }: {
  nodes: FileNode[]; depth: number; selected: string | null; onSelect: (p: string) => void;
}) {
  return (
    <>
      {nodes.map(n => (
        <div key={n.path}>
          <div
            className={'ws-node' + (selected === n.path ? ' active' : '')}
            style={{ paddingLeft: 8 + depth * 14 }}
            onClick={() => n.type === 'file' && onSelect(n.path)}
          >
            <span className="ws-node-icon">{n.type === 'dir' ? '📁' : '📄'}</span>
            <span className="ws-node-name">{n.name}</span>
            {n.type === 'file' && n.size != null && <span className="ws-node-size">{n.size}</span>}
          </div>
          {n.children && n.children.length > 0 && (
            <TreeNodes nodes={n.children} depth={depth + 1} selected={selected} onSelect={onSelect} />
          )}
        </div>
      ))}
    </>
  );
}
