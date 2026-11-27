import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { api, FileContent, FileNode } from './api';

/** Collect all directory paths from a tree (used for expand-all / collapse-all). */
function collectDirs(nodes: FileNode[], out: string[] = []): string[] {
  for (const n of nodes) {
    if (n.type === 'dir') {
      out.push(n.path);
      if (n.children) collectDirs(n.children, out);
    }
  }
  return out;
}

/**
 * Read-only workspace browser (paw/DSH parity).
 * Left: expandable file tree + read-only viewer.
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
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const loadTree = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const nodes = await api.workspaceTree(agentId);
      setTree(nodes);
      setExpanded(new Set(collectDirs(nodes)));
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

  const allDirs = useMemo(() => collectDirs(visibleTree || []), [visibleTree]);
  const allExpanded = allDirs.length > 0 && allDirs.every(p => expanded.has(p));
  const toggleAll = () => setExpanded(allExpanded ? new Set() : new Set(allDirs));
  const toggleDir = (path: string) => setExpanded(prev => {
    const next = new Set(prev);
    if (next.has(path)) next.delete(path); else next.add(path);
    return next;
  });

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
          <button className="ws-btn" title={allExpanded ? '收缩全部目录' : '展开全部目录'} onClick={toggleAll}>
            {allExpanded ? '⊟' : '⊞'}
          </button>
          <button
            className={'ws-btn' + (showHidden ? ' active' : '')}
            title={showHidden ? '隐藏隐藏文件' : '显示隐藏文件'}
            onClick={() => setShowHidden(s => !s)}
          >👁</button>
        </div>
        <div className="ws-tree-scroll">
          {error && <div className="ws-empty">{error}</div>}
          {!error && !tree && !loading && <div className="ws-empty">加载中…</div>}
          {tree && <TreeNodes nodes={visibleTree || []} depth={0} selected={selected}
            expanded={expanded} onToggle={toggleDir} onSelect={setSelected} />}
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

function TreeNodes({ nodes, depth, selected, expanded, onToggle, onSelect }: {
  nodes: FileNode[]; depth: number; selected: string | null;
  expanded: Set<string>; onToggle: (p: string) => void; onSelect: (p: string) => void;
}) {
  return (
    <>
      {nodes.map(n => {
        const isDir = n.type === 'dir';
        const isOpen = expanded.has(n.path);
        return (
          <div key={n.path}>
            <div
              className={'ws-node' + (selected === n.path ? ' active' : '') + (isDir ? ' ws-dir' : '')}
              style={{ paddingLeft: 8 + depth * 14 }}
              onClick={() => isDir ? onToggle(n.path) : onSelect(n.path)}
              title={isDir ? (isOpen ? '收缩目录' : '展开目录') : n.path}
            >
              <span className="ws-node-arrow">{isDir ? (isOpen ? '▾' : '▸') : ''}</span>
              <span className="ws-node-icon">{isDir ? (isOpen ? '📂' : '📁') : '📄'}</span>
              <span className="ws-node-name">{n.name}</span>
              {!isDir && n.size != null && <span className="ws-node-size">{n.size}</span>}
            </div>
            {isDir && isOpen && n.children && n.children.length > 0 && (
              <TreeNodes nodes={n.children} depth={depth + 1} selected={selected}
                expanded={expanded} onToggle={onToggle} onSelect={onSelect} />
            )}
          </div>
        );
      })}
    </>
  );
}
