import { useEffect, useRef, useState } from 'react';
import { Card, Steps, Button, Input, Select, Space, Tag, Typography, Empty, Spin, Alert, List, message, Tooltip } from 'antd';
import { CheckCircleOutlined, EyeOutlined, ForkOutlined, SafetyOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { RevisionCandidateRecord } from '../api/client';
import SelectiveAcceptPanel from './SelectiveAcceptPanel';
import type { RevisionRequestItem, ImpactAnalysisResult } from '../types';

const { Text, Paragraph } = Typography;

/**
 * NCS-017（方案 §12/UX04/U3）：修订工作区——四步：影响范围 → 修订建议 → 逐项审阅 → 采纳与核对。
 * 顶部固定作者原要求/任务状态；「已读 ≠ 已采纳」；采纳按钮写明「采纳 N 章修订」。
 */

const ORIGIN_OPTIONS = [
  { value: 'CHAPTER_REWRITE', label: '整章重写' },
  { value: 'DIRECT_EDIT', label: '直接编辑' },
  { value: 'SETTING_CHANGE', label: '设定变更' },
  { value: 'PLAN_ADJUST', label: '计划调整' },
];

const CLASSIFICATION_META: Record<string, { color: string; label: string }> = {
  MUST_REPAIR: { color: 'red', label: '必须修复' },
  RECHECK: { color: 'orange', label: '需复核' },
  UNAFFECTED_WITH_REASON: { color: 'green', label: '未受影响（含理由）' },
};

export default function RevisionWorkspace({ projectId, currentChapter }: {
  projectId: string;
  currentChapter: number;
}) {
  const [requests, setRequests] = useState<RevisionRequestItem[]>([]);
  const [selectedId, setSelectedId] = useState<string>(() => { try { return localStorage.getItem(`revision-selected:${projectId}`) ?? ''; } catch { return ''; } });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [impact, setImpact] = useState<ImpactAnalysisResult | null>(null);
  const [readChapters, setReadChapters] = useState<Set<number>>(new Set());
  const [acceptMessage, setAcceptMessage] = useState<string>('');
  const [reviewCandidates, setReviewCandidates] = useState<RevisionCandidateRecord[]>([]);
  const [messageApi, messageContextHolder] = message.useMessage();
  // 新建表单
  const [newOrigin, setNewOrigin] = useState('CHAPTER_REWRITE');
  const [newIntent, setNewIntent] = useState('');
  const [newChapter, setNewChapter] = useState(Math.max(currentChapter, 1));
  const requestRef = useRef(0);

  const load = () => {
    const requestId = ++requestRef.current;
    void api.listRevisionRequests(projectId).then(r => {
      if (requestRef.current !== requestId) return;
      setRequests(r.requests ?? []);
      if (!selectedId && r.requests?.length) setSelectedId(r.requests[0].id);
    }).catch(() => {}).finally(() => {
      if (requestRef.current === requestId) setLoading(false);
    });
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [projectId]);

  const savePosition = (analysis: ImpactAnalysisResult | null, read: Set<number>) => {
    try { localStorage.setItem(`revision-review:${projectId}:${selectedId}`, JSON.stringify({ impact: analysis, read: [...read] })); }
    catch { messageApi.warning('审阅位置未能暂存，刷新后需要重新核对'); }
  };
  const loadCandidates = async () => {
    const rows = await api.listRevisionCandidates(projectId);
    setReviewCandidates(rows.filter(c => c.requestKey?.startsWith(`rr:${selectedId}:`)));
  };
  useEffect(() => {
    setImpact(null); setReadChapters(new Set()); setAcceptMessage(''); setReviewCandidates([]);
    if (!selectedId) return;
    try {
      localStorage.setItem(`revision-selected:${projectId}`, selectedId);
      const stored = JSON.parse(localStorage.getItem(`revision-review:${projectId}:${selectedId}`) ?? 'null');
      if (stored?.impact?.requestId === selectedId) {
        setImpact(stored.impact); setReadChapters(new Set(stored.read ?? []));
      }
    } catch { /* 暂存不可用时仍可重新分析 */ }
    void loadCandidates().catch(() => {});
  }, [projectId, selectedId]);

  const selected = requests.find(r => r.id === selectedId) ?? null;
  const step = !selected ? 0
    : selected.status === 'OPEN' ? 1
    : selected.status === 'SCOPE_APPROVED' && !impact ? 1
    : selected.status === 'SCOPE_APPROVED' ? 2
    : selected.status === 'COMPLETED' ? 4 : 0;

  const handleCreate = async () => {
    if (!newIntent.trim()) { messageApi.warning('请先写明修改意图'); return; }
    setBusy(true);
    try {
      const created = await api.createRevisionRequest(projectId, {
        origin: newOrigin, intent: newIntent.trim(),
        targetAnchors: JSON.stringify([{ chapterIndex: newChapter }]),
      });
      setSelectedId(created.id);
      setImpact(null); setReadChapters(new Set()); setAcceptMessage('');
      messageApi.success('修订请求已登记（OPEN）——下一步批准探索范围');
      load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '登记失败');
    } finally { setBusy(false); }
  };

  const handleApproveScope = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      await api.approveRevisionScope(projectId, selected.id, '批准影响分析与修复生成范围');
      messageApi.success('范围已批准（≠内容采纳）——可运行影响分析');
      load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '批准失败');
    } finally { setBusy(false); }
  };

  const handleAnalyze = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      const anchors = JSON.parse(selected.targetAnchors || '[]');
      const chapter = anchors[0]?.chapterIndex ?? newChapter;
      const result = await api.analyzeImpact(projectId, selected.id, chapter);
      setImpact(result); savePosition(result, readChapters);
      messageApi.success(`影响分析完成：${result.items.length} 个后续章节`);
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '分析失败');
    } finally { setBusy(false); }
  };

  const handleGenerate = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      const anchors = JSON.parse(selected.targetAnchors || '[]');
      const chapter = anchors[0]?.chapterIndex ?? newChapter;
      const result = await api.generateRepairs(projectId, selected.id, chapter, selected.intent);
      messageApi.success(`已生成 ${result.generated} 个修复候选（第 ${result.chapters.join('/')} 章）——候选不动主稿`);
      const re = await api.analyzeImpact(projectId, selected.id, chapter);
      setImpact(re); setReadChapters(new Set()); savePosition(re, new Set());
      await loadCandidates();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '生成失败');
    } finally { setBusy(false); }
  };

  const handleAccept = async () => {
    if (!selected) return;
    const must = (impact?.items ?? []).filter(i => i.classification === 'MUST_REPAIR');
    const unread = must.filter(i => !readChapters.has(i.chapterIndex));
    setBusy(true);
    try {
      // 结果未知时沿用同一命令；刷新也不会生成第二次采纳意图。
      const commandKey = `revision-accept:${projectId}:${selected.id}`;
      let commandId = localStorage.getItem(commandKey);
      if (!commandId) { commandId = crypto.randomUUID(); localStorage.setItem(commandKey, commandId); }
      const receipt = await api.acceptRevisionGroup(projectId, selected.id, commandId);
      setAcceptMessage(receipt.message);
      messageApi.success(receipt.message);
      load(); await loadCandidates();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '采纳失败（候选保留）');
    } finally { setBusy(false); }
    void unread;
  };

  if (loading) return <Card><Spin><div style={{ height: 100 }} /></Spin></Card>;

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      {messageContextHolder}
      <Card title="🛠 修订工作区" size="small" extra={
        <Space wrap>
          <Select value={newOrigin} onChange={setNewOrigin} options={ORIGIN_OPTIONS} style={{ width: 120 }} />
          <InputNumberish value={newChapter} min={1} onChange={v => setNewChapter(v ?? 1)} />
          <Input style={{ width: 260 }} placeholder="修改意图（如：改成当场焚信，保留结尾原句）"
            value={newIntent} onChange={e => setNewIntent(e.target.value)} />
          <Button type="primary" loading={busy} onClick={handleCreate}>登记修订请求</Button>
        </Space>
      }>
        {requests.length === 0 ? (
          <Empty description="尚无修订请求——从上方登记一个修改意图（如跨章改写）" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Space direction="vertical" style={{ width: '100%' }} size="small">
            <Space wrap>
              {requests.slice(0, 6).map(r => (
                <Button key={r.id} size="small"
                  type={r.id === selectedId ? 'primary' : 'default'}
                  onClick={() => { setSelectedId(r.id); setImpact(null); setReadChapters(new Set()); setAcceptMessage(''); }}>
                  {r.origin} · {r.intent.slice(0, 14)}{r.intent.length > 14 ? '…' : ''}
                  <Tag style={{ marginLeft: 6, fontSize: 10 }}>{r.status}</Tag>
                </Button>
              ))}
            </Space>
            {selected && (
              <div style={{ background: '#fafaf8', padding: '6px 10px', borderRadius: 4, fontSize: 12 }}>
                <Space size={6} wrap>
                  <Text strong>原要求：</Text><Text>{selected.intent}</Text>
                  <Tag>{selected.origin}</Tag>
                  <Tag color={selected.status === 'COMPLETED' ? 'green' : selected.status === 'SCOPE_APPROVED' ? 'blue' : 'gold'}>
                    {selected.status === 'OPEN' ? '待批准范围' : selected.status === 'SCOPE_APPROVED' ? '范围已批准' : selected.status === 'COMPLETED' ? '已采纳' : selected.status}
                  </Tag>
                  {selected.scopeDecision && <Tooltip title={selected.scopeDecision}><Tag color="cyan" style={{ fontSize: 10 }}>范围已批（≠采纳）</Tag></Tooltip>}
                  {selected.acceptance && <Tooltip title={selected.acceptance}><Tag color="green" style={{ fontSize: 10 }}>采纳记录</Tag></Tooltip>}
                </Space>
              </div>
            )}
          </Space>
        )}
      </Card>

      {selected && (
        <Card size="small">
          <Steps size="small" current={step}
            items={[
              { title: '登记请求' },
              { title: '影响范围', description: selected.status === 'OPEN' ? '先批准范围' : undefined },
              { title: '修订建议' },
              { title: '逐项审阅' },
              { title: '采纳与核对' },
            ]} />
          <Space style={{ marginTop: 12 }} wrap>
            {selected.status === 'OPEN' && (
              <Button icon={<SafetyOutlined />} loading={busy} onClick={handleApproveScope}>批准范围（探索与费用）</Button>
            )}
            {selected.status === 'SCOPE_APPROVED' && !impact && (
              <Button icon={<EyeOutlined />} loading={busy} onClick={handleAnalyze}>运行影响分析</Button>
            )}
            {impact && selected.status === 'SCOPE_APPROVED' && (
              <Button icon={<ThunderboltOutlined />} loading={busy} onClick={handleGenerate}>生成修复候选（不动主稿）</Button>
            )}
            {impact && selected.status === 'SCOPE_APPROVED' &&
              impact.items.some(i => i.classification === 'MUST_REPAIR') && (
              <Button type="primary" icon={<CheckCircleOutlined />} loading={busy}
                disabled={reviewCandidates.length === 0 || reviewCandidates.some(c => c.status !== 'READY' || !readChapters.has(c.chapterIndex!))}
                onClick={handleAccept}>
                采纳 {impact.items.filter(i => i.classification === 'MUST_REPAIR').length} 章修订
              </Button>
            )}
          </Space>
          {acceptMessage && <Alert type="success" showIcon style={{ marginTop: 8 }} message={acceptMessage} />}
        </Card>
      )}

      {reviewCandidates.map(candidate => <Card key={candidate.id} size="small"
        title={`第 ${candidate.chapterIndex} 章修订候选 · ${candidate.status}`}>
        <Paragraph type="secondary">{candidate.summary} · 基于 v{candidate.baseVersion}</Paragraph>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div><Text strong>原稿</Text><pre style={{ whiteSpace: 'pre-wrap', maxHeight: 280, overflow: 'auto' }}>{candidateText(candidate.beforeJson)}</pre></div>
          <div><Text strong>候选</Text><pre style={{ whiteSpace: 'pre-wrap', maxHeight: 280, overflow: 'auto' }}>{candidateText(candidate.afterJson)}</pre></div>
        </div>
        <Button onClick={() => { const next = new Set([...readChapters, candidate.chapterIndex!]); setReadChapters(next); savePosition(impact, next); }}>
          {readChapters.has(candidate.chapterIndex!) ? '已读此候选' : '已对照此候选'}
        </Button>
      </Card>)}

      {selected?.status === 'SCOPE_APPROVED' && <SelectiveAcceptPanel key={selected.id} projectId={projectId} requestId={selected.id} />}

      {impact && (
        <Card title={`影响范围 · ${impact.items.length} 个后续章节`} size="small"
          extra={<Tooltip title={impact.coverageNote}><Tag style={{ cursor: 'help' }}>覆盖说明</Tag></Tooltip>}>
          <Paragraph type="secondary" style={{ fontSize: 12 }}>{impact.coverageNote}</Paragraph>
          <List size="small" dataSource={impact.items} renderItem={item => (
            <List.Item actions={[
              <Button key="read" size="small" type={readChapters.has(item.chapterIndex) ? 'primary' : 'default'}
                onClick={() => { const next = new Set([...readChapters, item.chapterIndex]); setReadChapters(next); savePosition(impact, next); }}
                icon={<EyeOutlined />}>
                {readChapters.has(item.chapterIndex) ? '已读' : '标记已读'}
              </Button>,
            ]}>
              <List.Item.Meta
                avatar={<ForkOutlined style={{ fontSize: 18,
                  color: item.classification === 'MUST_REPAIR' ? '#cf1322' : item.classification === 'RECHECK' ? '#fa8c16' : '#52c41a' }} />}
                title={<Space size={6}>第 {item.chapterIndex} 章
                  <Tag color={CLASSIFICATION_META[item.classification]?.color} style={{ fontSize: 11 }}>
                    {CLASSIFICATION_META[item.classification]?.label ?? item.classification}
                  </Tag>
                  {item.subjects.length > 0 && <Text type="secondary" style={{ fontSize: 11 }}>涉及：{item.subjects.join('、')}</Text>}
                </Space>}
                description={<ul style={{ margin: 0, paddingLeft: 16, fontSize: 12 }}>{item.reasons.map((r, i) => <li key={i}>{r}</li>)}</ul>} />
            </List.Item>
          )} />
          <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0, marginTop: 8 }}>
            「已读」仅标记你的审阅进度，不等于采纳——采纳用上方「采纳 N 章修订」按钮（整组生效）。
          </Paragraph>
        </Card>
      )}
    </Space>
  );
}

function InputNumberish({ value, min, onChange }: { value: number; min: number; onChange: (v: number | null) => void }) {
  return <Input type="number" value={value} min={min} style={{ width: 72 }}
    onChange={e => onChange(Number(e.target.value) || min)} />;
}

function candidateText(json: string): string {
  try { const value = JSON.parse(json); return value.content ?? JSON.stringify(value, null, 2); }
  catch { return '候选内容无法解析，请重新生成'; }
}
