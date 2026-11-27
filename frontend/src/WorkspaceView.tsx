import React, { useCallback, useEffect, useState } from 'react';
import { api, FileContent, FileNode } from './api';

/**
 * Read-only workspace browser (paw/DSH parity).
 * Lazy tree: only one directory level is fetched at a time — the root list on
 * mount, then a child listing when a directory is first expanded. Keeps large
 * workspaces fast (one listing per click instead of walking the whole tree).
 */
export default function WorkspaceView({ agentId }: { agentId: string }) {
  const [cache, setCache] = useState<Record<string, FileNode[]>>({}); // path -> direct children
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadingDirs, setLoadingDirs] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState<FileContent | null>(null);
  const [contentLoading, setContentLoading] = useState(false);
  const [showHidden, setShowHidden] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [wrap, setWrap] = useState(false);

  /** Load one directory level into the cache. */
  const loadDir = useCallback(async (path: string) => {
    setLoadingDirs(prev => new Set(prev).add(path));
    try {
      const nodes = await api.workspaceTree(agentId, path);
      setCache(c => ({ ...c, [path]: nodes }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载目录失败');
    } finally {
      setLoadingDirs(prev => { const n = new Set(prev); n.delete(path); return n; });
    }
  }, [agentId]);

  /** (Re)load the root level, dropping cached children and collapse state. */
  const reload = useCallback(async () => {
    setLoading(true);
    setError('');
    setCache({});
    setExpanded(new Set());
    setSelected(null);
    setContent(null);
    try {
      const nodes = await api.workspaceTree(agentId, '');
      setCache({ '': nodes });
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载文件树失败');
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => { reload(); }, [reload]);

  /** Direct children of a path, with hidden files filtered at display time. */
  const childrenOf = useCallback((path: string): FileNode[] => {
    const nodes = cache[path] || [];
    return showHidden ? nodes : nodes.filter(n => !n.name.startsWith('.'));
  }, [cache, showHidden]);

  /** Toggle one directory: expand (loading children on first open) or collapse. */
  const toggleDir = useCallback((path: string) => {
    if (expanded.has(path)) {
      setExpanded(prev => { const n = new Set(prev); n.delete(path); return n; });
      return;
    }
    if (!cache[path]) void loadDir(path);
    setExpanded(prev => new Set(prev).add(path));
  }, [expanded, cache, loadDir]);

  const anyExpanded = expanded.size > 0;

  /** Collapse all, or recursively load + expand every directory (breadth-first). */
  const toggleAll = useCallback(async () => {
    if (anyExpanded) { setExpanded(new Set()); return; }
    const queue: FileNode[] = [...(cache[''] || [])];
    const toExpand = new Set<string>();
    while (queue.length) {
      const node = queue.shift()!;
      if (node.type !== 'dir' || node.hasChildren === false) continue;
      toExpand.add(node.path);
      let kids = cache[node.path];
      if (!kids) {
        try {
          kids = await api.workspaceTree(agentId, node.path);
          const loaded = kids;
          setCache(c => ({ ...c, [node.path]: loaded }));
        } catch { continue; }
      }
      queue.push(...kids.filter(n => n.type === 'dir'));
    }
    setExpanded(toExpand);
  }, [anyExpanded, cache, agentId]);

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
          <button className="ws-btn" title="刷新文件树" onClick={reload}>↻</button>
          <button className="ws-btn" title={anyExpanded ? '收缩全部目录' : '展开全部目录'} onClick={toggleAll}>
            {anyExpanded ? '⊟' : '⊞'}
          </button>
          <button
            className={'ws-btn' + (showHidden ? ' active' : '')}
            title={showHidden ? '隐藏隐藏文件' : '显示隐藏文件'}
            onClick={() => setShowHidden(s => !s)}
          >👁</button>
        </div>
        <div className="ws-tree-scroll">
          {error && <div className="ws-empty">{error}</div>}
          {loading && <div className="ws-empty">加载中…</div>}
          {!loading && <TreeNodes nodes={childrenOf('')} depth={0} selected={selected}
            expanded={expanded} loadingDirs={loadingDirs} childrenOf={childrenOf}
            onToggle={toggleDir} onSelect={setSelected} />}
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

function TreeNodes({ nodes, depth, selected, expanded, loadingDirs, childrenOf, onToggle, onSelect }: {
  nodes: FileNode[]; depth: number; selected: string | null;
  expanded: Set<string>; loadingDirs: Set<string>;
  childrenOf: (path: string) => FileNode[];
  onToggle: (p: string) => void; onSelect: (p: string) => void;
}) {
  return (
    <>
      {nodes.map(n => {
        const isDir = n.type === 'dir';
        const isOpen = expanded.has(n.path);
        const expandable = isDir && n.hasChildren !== false;
        const kids = isOpen ? childrenOf(n.path) : [];
        return (
          <div key={n.path}>
            <div
              className={'ws-node' + (selected === n.path ? ' active' : '') + (isDir ? ' ws-dir' : '')}
              style={{ paddingLeft: 8 + depth * 14 }}
              onClick={() => isDir ? onToggle(n.path) : onSelect(n.path)}
              title={isDir ? (isOpen ? '收缩目录' : '展开目录') : n.path}
            >
              <span className="ws-node-arrow">{expandable ? (isOpen ? '▾' : '▸') : ''}</span>
              <span className="ws-node-icon">{isDir ? (isOpen ? '📂' : '📁') : '📄'}</span>
              <span className="ws-node-name">{n.name}</span>
              {!isDir && n.size != null && <span className="ws-node-size">{n.size}</span>}
            </div>
            {isDir && isOpen && (
              loadingDirs.has(n.path)
                ? <div className="ws-node-loading" style={{ paddingLeft: 22 + depth * 14 }}>加载中…</div>
                : <TreeNodes nodes={kids} depth={depth + 1} selected={selected}
                    expanded={expanded} loadingDirs={loadingDirs} childrenOf={childrenOf}
                    onToggle={onToggle} onSelect={onSelect} />
            )}
          </div>
        );
      })}
    </>
  );
}
