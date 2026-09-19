import { useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, Empty, Select, Space, Typography } from 'antd';
import { api } from '../api/client';
import type { RevisionCandidateRecord } from '../api/client';

const bodyOf = (candidate: RevisionCandidateRecord) => {
  try { return String(JSON.parse(candidate.afterJson).content ?? ''); } catch { return ''; }
};

export default function SelectiveAcceptPanel({ projectId, requestId }: { projectId: string; requestId: string }) {
  const [candidates, setCandidates] = useState<RevisionCandidateRecord[]>([]);
  const [chapter, setChapter] = useState<number>();
  const [pieces, setPieces] = useState<{ previewId: string; paragraph: number }[]>([]);
  const [combined, setCombined] = useState<{ previewId: string; content: string } | null>(null);
  const [needsRecheck, setNeedsRecheck] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [accepted, setAccepted] = useState(false);
  const load = async () => {
    const rows = await api.listRevisionCandidates(projectId, undefined, 'READY');
    setCandidates(rows.filter(c => c.targetType === 'CHAPTER'));
  };
  useEffect(() => { void load().catch(e => setError(e.message)); }, [projectId, requestId]);
  const sources = candidates.filter(c => c.chapterIndex === chapter && !c.requestKey?.startsWith('combine:'));
  const saved = candidates.filter(c => c.requestKey?.startsWith(`combine:${requestId}:`));
  const run = async (work: () => Promise<void>) => {
    setBusy(true); setError('');
    try { await work(); } catch (e) { setError(e instanceof Error ? e.message : '操作未完成，候选保留'); }
    finally { setBusy(false); }
  };
  return <Card title="组合候选" size="small" extra={<Button disabled={busy} onClick={() => void run(load)}>刷新候选</Button>}>
    <Typography.Paragraph type="secondary">从同章候选勾选段落，按勾选顺序组成新候选。原候选保留，新组合必须单独检查后才能采纳。</Typography.Paragraph>
    <Space direction="vertical" style={{ width: '100%' }}>
      {saved.length > 0 && <Select aria-label="恢复组合候选" placeholder="恢复未采纳的组合候选" style={{ width: '100%' }}
        value={combined?.previewId} disabled={busy} options={saved.map(c => ({ value: c.id, label: `第 ${c.chapterIndex} 章 · ${c.createdAt}` }))}
        onChange={id => { const c = saved.find(c => c.id === id)!; setCombined({ previewId: id, content: bodyOf(c) }); setNeedsRecheck(true); setAccepted(false); }} />}
      <Select aria-label="组合章节" placeholder="选择章节" style={{ width: 200 }} value={chapter} disabled={busy}
        options={[...new Set(candidates.filter(c => !c.requestKey?.startsWith('combine:')).map(c => c.chapterIndex!))].sort((a,b) => a-b).map(n => ({ value: n, label: `第 ${n} 章` }))}
        onChange={n => { setChapter(n); setPieces([]); setCombined(null); setAccepted(false); }} />
      {sources.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择有整章候选的章节；可先在章节管理生成候选" /> : sources.map((c, i) => <Card key={c.id} size="small" title={`候选 ${i + 1} · 基于 v${c.baseVersion}`}>
        <div style={{ maxHeight: 260, overflow: 'auto' }}>{bodyOf(c).split('\n').map((paragraph, index) =>
          <div key={index} style={{ marginBottom: 8 }}><Checkbox disabled={busy} checked={pieces.some(p => p.previewId === c.id && p.paragraph === index)}
            onChange={e => { setPieces(prev => e.target.checked ? [...prev, { previewId: c.id, paragraph: index }] : prev.filter(p => p.previewId !== c.id || p.paragraph !== index)); setCombined(null); setNeedsRecheck(true); setAccepted(false); }}>
            第 {index + 1} 段：{paragraph || '（空行）'}
          </Checkbox></div>)}</div>
      </Card>)}
      <Button disabled={busy || !pieces.length || chapter == null} onClick={() => void run(async () => {
        const result = await api.combineRevisionCandidates(projectId, { requestId, chapterIndex: chapter!,
          fragments: pieces.map(p => ({ previewId: p.previewId, paragraphs: [p.paragraph] })) });
        setCombined(result); setNeedsRecheck(true); setAccepted(false); await load();
      })}>生成组合候选（{pieces.length} 段）</Button>
      {combined && <Card size="small" title="组合预览（按勾选顺序）">
        <pre data-combined-preview style={{ whiteSpace: 'pre-wrap', maxHeight: 350, overflow: 'auto' }}>{combined.content}</pre>
        <Space wrap>
          <Button loading={busy} disabled={accepted} onClick={() => void run(async () => {
            const result = await api.recheckCombinedCandidate(projectId, combined.previewId); setNeedsRecheck(result.needsRecheck);
          })}>重新检查组合</Button>
          <Button type="primary" loading={busy} disabled={needsRecheck || accepted} onClick={() => void run(async () => {
            await api.acceptCombinedCandidate(projectId, combined.previewId); setAccepted(true); await load();
          })}>采纳组合候选</Button>
          <Typography.Text>{accepted ? '已采纳，原候选保留' : needsRecheck ? '尚未通过本组合检查' : '本组合检查已通过'}</Typography.Text>
        </Space>
      </Card>}
      {error && <Alert type="error" showIcon message={error} />}
    </Space>
  </Card>;
}
