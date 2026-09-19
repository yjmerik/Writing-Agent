import { useState, useEffect, useCallback, useRef } from 'react';
import { Tree, Spin, Empty, Card, Typography, Button, Space, Input, InputNumber, Alert, Select, Collapse, Tag, message } from 'antd';
import {
  DeleteOutlined,
  DownOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  UpOutlined,
} from '@ant-design/icons';
import type { PlanTargetPreview, PromiseDecisionInput } from '../api/client';
import type { DataNode } from 'antd/es/tree';

const { Text } = Typography;

const CONTRACT_FIELD_LABELS: Record<string, string> = {
  purpose: '场景目的', entryState: '进入状态', actors: '行动者/POV', actorsKnow: '行动者所知',
  conflictGoals: '冲突目标', cost: '代价', expectedChange: '预期变化', readerGains: '读者获得',
  readerMustNotKnow: '读者不应知道', protectedContent: '保护内容',
};
import { api } from '../api/client';
import type { AgentRevisionTarget } from '../api/client';
import type { ChapterSynopsis, Outline, Volume, PlanDeviationItem } from '../types';
import AgentRevisionDialog from './AgentRevisionDialog';

let draftSequence = 0;

const draftId = (kind: 'volume' | 'chapter') => {
  draftSequence += 1;
  return `draft-${kind}-${Date.now()}-${draftSequence}`;
};

const cloneOutline = (outline: Outline): Outline => ({
  ...outline,
  volumes: outline.volumes.map((volume) => ({
    ...volume,
    synopses: volume.synopses.map((synopsis) => ({ ...synopsis })),
  })),
});

export default function OutlineView({ projectId, currentChapter = 0 }: { projectId: string; currentChapter?: number }) {
  // p35-03: 篇幅调整与影响预览
  // p36-10: 预览绑定其目标数字——输入变化立即禁用确认；序号丢弃乱序返回
  const [newTarget, setNewTarget] = useState<number | ''>('');
  const [targetPreview, setTargetPreview] = useState<PlanTargetPreview | null>(null);
  const [previewTargetNumber, setPreviewTargetNumber] = useState<number | null>(null);
  const [appliedTargetNumber, setAppliedTargetNumber] = useState<number | null>(null);
  const [targetBusy, setTargetBusy] = useState(false);
  // p38-04: 受影响承诺的逐项处置（MIGRATE 章号 / OPEN_END 理由）——未处置齐不得确认
  const [promiseDecisions, setPromiseDecisions] = useState<Record<string, PromiseDecisionInput | null>>({});
  const previewRequestSeqRef = useRef(0);
  const previewTarget = async () => {
    if (newTarget === '' || Number(newTarget) < 1) return;
    const requested = Number(newTarget);
    const sequence = ++previewRequestSeqRef.current;
    setTargetBusy(true);
    try {
      const result = await api.getPlanTargetPreview(projectId, requested);
      // 迟到的旧请求不得覆盖当前输入对应的预览
      if (sequence !== previewRequestSeqRef.current) return;
      setTargetPreview(result);
      setPreviewTargetNumber(requested);
      setAppliedTargetNumber(null);
      setPromiseDecisions({});
    } catch (err) {
      if (sequence === previewRequestSeqRef.current) message.error((err as Error).message);
    } finally {
      if (sequence === previewRequestSeqRef.current) setTargetBusy(false);
    }
  };
  const applyTarget = async () => {
    // 只有确认按钮（绑定当前输入且未生效）能进入这里
    if (newTarget === '' || Number(newTarget) < 1) return;
    if (targetPreview == null || targetPreview.applied || previewTargetNumber !== Number(newTarget)) return;
    if (!targetPreview.previewId || !targetPreview.sourcePlanHash) {
      message.error('预览缺少凭证，请重新预览');
      return;
    }
    setTargetBusy(true);
    try {
      const result = await api.applyPlanTarget(projectId, Number(newTarget), {
        previewId: targetPreview.previewId,
        sourcePlanHash: targetPreview.sourcePlanHash,
        decisions: Object.values(promiseDecisions).filter((d): d is PromiseDecisionInput => d != null),
      });
      setTargetPreview(result);
      setPreviewTargetNumber(Number(newTarget));
      setAppliedTargetNumber(Number(newTarget));
      message.success('目标章数已更新为 ' + result.targetChapters + '，大纲已裁剪');
      // p36-10: 确认成功后重读权威大纲，清掉裁剪前的 draft/persistedOutline，
      // 防止「保存总纲/保存未写大纲」把旧章纲写回
      setPersistedOutline(null);
      setDraft(null);
      await load();
    } catch (err) {
      message.error((err as Error).message);
    } finally {
      setTargetBusy(false);
    }
  };
  const [persistedOutline, setPersistedOutline] = useState<Outline | null>(null);
  const [draft, setDraft] = useState<Outline | null>(null);
  const [loading, setLoading] = useState(true);
  const [premiseDraft, setPremiseDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [replanning, setReplanning] = useState(false);
  const [revisionTarget, setRevisionTarget] = useState<AgentRevisionTarget | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const outline = await api.getOutline(projectId);
      setPersistedOutline(outline);
      setDraft(outline ? cloneOutline(outline) : null);
      setPremiseDraft(outline?.premise || '');
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { void load(); }, [load]);

  const updateDraft = (change: (outline: Outline) => Outline) => {
    setDraft((current) => current ? change(current) : current);
  };

  const normalizeStructure = (volumes: Volume[]) => {
    const lockedVolumes = volumes.filter((volume) => volumeContainsWritten(volume, currentChapter));
    let nextVolumeIndex = Math.max(0, ...lockedVolumes.map((volume) => volume.volumeIndex)) + 1;
    let nextChapterIndex = Math.max(
      currentChapter,
      ...volumes.flatMap((volume) => volume.synopses)
        .filter((synopsis) => synopsis.chapterIndex <= currentChapter)
        .map((synopsis) => synopsis.chapterIndex),
    ) + 1;

    return volumes.map((volume) => ({
      ...volume,
      volumeIndex: volumeContainsWritten(volume, currentChapter) ? volume.volumeIndex : nextVolumeIndex++,
      synopses: volume.synopses.map((synopsis) => synopsis.chapterIndex <= currentChapter
        ? synopsis
        : { ...synopsis, chapterIndex: nextChapterIndex++ }),
    }));
  };

  const updateVolume = (volumeId: string, patch: Partial<Pick<Volume, 'title' | 'summary'>>) => {
    updateDraft((outline) => ({
      ...outline,
      volumes: outline.volumes.map((volume) => volume.id === volumeId ? { ...volume, ...patch } : volume),
    }));
  };

  const updateSynopsis = (synopsisId: string, patch: Partial<Pick<ChapterSynopsis, 'title' | 'synopsis'>>) => {
    updateDraft((outline) => ({
      ...outline,
      volumes: outline.volumes.map((volume) => ({
        ...volume,
        synopses: volume.synopses.map((synopsis) => synopsis.id === synopsisId
          ? { ...synopsis, ...patch }
          : synopsis),
      })),
    }));
  };

  const addVolume = () => {
    updateDraft((outline) => {
      const volume: Volume = {
        id: draftId('volume'),
        projectId,
        volumeIndex: outline.volumes.length + 1,
        title: '新卷',
        summary: '',
        synopses: [],
      };
      return { ...outline, volumes: normalizeStructure([...outline.volumes, volume]) };
    });
  };

  const deleteVolume = (volumeId: string) => {
    updateDraft((outline) => ({
      ...outline,
      volumes: normalizeStructure(outline.volumes.filter((volume) => volume.id !== volumeId)),
    }));
  };

  const moveVolume = (volumeIndex: number, direction: -1 | 1) => {
    updateDraft((outline) => {
      const targetIndex = volumeIndex + direction;
      if (targetIndex < 0 || targetIndex >= outline.volumes.length) return outline;
      const source = outline.volumes[volumeIndex];
      const target = outline.volumes[targetIndex];
      if (volumeContainsWritten(source, currentChapter) || volumeContainsWritten(target, currentChapter)) return outline;
      const volumes = [...outline.volumes];
      [volumes[volumeIndex], volumes[targetIndex]] = [volumes[targetIndex], volumes[volumeIndex]];
      return { ...outline, volumes: normalizeStructure(volumes) };
    });
  };

  const addChapter = (volumeId: string) => {
    updateDraft((outline) => {
      const synopsis: ChapterSynopsis = {
        id: draftId('chapter'),
        projectId,
        chapterIndex: currentChapter + 1,
        title: '新章节',
        synopsis: '',
        writingNotes: null,
      };
      return {
        ...outline,
        volumes: normalizeStructure(outline.volumes.map((volume) => volume.id === volumeId
          ? { ...volume, synopses: [...volume.synopses, synopsis] }
          : volume)),
      };
    });
  };

  const deleteChapter = (synopsisId: string) => {
    updateDraft((outline) => ({
      ...outline,
      volumes: normalizeStructure(outline.volumes.map((volume) => ({
        ...volume,
        synopses: volume.synopses.filter((synopsis) => synopsis.id !== synopsisId),
      }))),
    }));
  };

  const moveChapter = (volumeId: string, synopsisIndex: number, direction: -1 | 1) => {
    updateDraft((outline) => ({
      ...outline,
      volumes: normalizeStructure(outline.volumes.map((volume) => {
        if (volume.id !== volumeId) return volume;
        const targetIndex = synopsisIndex + direction;
        if (targetIndex < 0 || targetIndex >= volume.synopses.length) return volume;
        const source = volume.synopses[synopsisIndex];
        const target = volume.synopses[targetIndex];
        if (source.chapterIndex <= currentChapter || target.chapterIndex <= currentChapter) return volume;
        const synopses = [...volume.synopses];
        [synopses[synopsisIndex], synopses[targetIndex]] = [synopses[targetIndex], synopses[synopsisIndex]];
        return { ...volume, synopses };
      })),
    }));
  };

  const saveStructure = async () => {
    if (!draft) return;
    if (draft.volumes.flatMap((volume) => volume.synopses).length === 0) {
      message.error('大纲至少需要一章');
      return;
    }
    setSaving(true);
    try {
      const saved = await api.updateOutline(projectId, draft);
      setPersistedOutline(saved);
      setDraft(cloneOutline(saved));
      message.success('未写大纲已保存');
    } catch (error) {
      message.error((error as Error).message);
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <Spin />;

  const hasUnsavedStructure = Boolean(draft && persistedOutline
    && JSON.stringify(draft.volumes) !== JSON.stringify(persistedOutline.volumes));

  // p38-04: 每个受影响承诺都有有效处置才允许确认
  const affectedPromiseList = targetPreview?.preview?.affectedPromises
    ?? targetPreview?.affectedPromises ?? [];
  const allPromisesDecided = affectedPromiseList.every((p) => {
    const d = p.id ? promiseDecisions[p.id] : null;
    if (!p.id || !d) return !p.id ? true : false;
    if (d.action === 'MIGRATE') return d.targetChapter != null && d.targetChapter >= 1;
    return Boolean(d.reason && d.reason.trim());
  });

  return (
    <Card>
      <Card size="small" style={{ marginBottom: 16 }} title="📐 篇幅与计划">
        <Space wrap style={{ marginBottom: 8 }}>
          <InputNumber min={1} placeholder="目标章数" aria-label="目标章数" value={newTarget}
            disabled={targetBusy && appliedTargetNumber == null}
            onChange={v => {
              // p36-10: 输入变化即失效旧预览——确认只在 newTarget 与预览目标一致且未生效时可用
              setNewTarget(v ?? '');
            }} style={{ width: 140 }} />
          <Button onClick={() => void previewTarget()} loading={targetBusy && appliedTargetNumber == null}>影响预览</Button>
          <Button type="primary"
            disabled={targetPreview == null || targetPreview.applied
              || newTarget === '' || previewTargetNumber !== Number(newTarget)
              || !allPromisesDecided}
            loading={targetBusy} onClick={() => void applyTarget()}>确认生效</Button>
        </Space>
        {targetPreview && (
          <Alert
            type={targetPreview.applied ? 'success' : 'warning'}
            showIcon
            message={targetPreview.applied
              ? '已生效：目标 ' + targetPreview.targetChapters + ' 章'
                + (targetPreview.preview
                  ? '（原 ' + targetPreview.preview.oldTarget + '），已裁剪 ' + targetPreview.preview.prunedChapters.length + ' 章'
                  : '（预览凭证已消费）')
              : '影响预览：将裁剪 ' + (targetPreview.preview?.prunedChapters ?? targetPreview.prunedChapters ?? []).length
                + ' 章（已写 ' + targetPreview.preview?.writtenChapters + ' 章保留）'}
            description={(
              <div>
                {((targetPreview.preview?.prunedChapters ?? targetPreview.prunedChapters) ?? []).length > 0 && (
                  <div>裁剪章节：第 {((targetPreview.preview?.prunedChapters ?? targetPreview.prunedChapters) ?? []).join('、')} 章</div>
                )}
                {(targetPreview.preview?.affectedPromises ?? targetPreview.affectedPromises ?? []).map(p => (
                  <div key={p.id ?? p.description} style={{ marginBottom: 8 }}>
                    ⚠ 「{p.description}」原定第 {p.expectedResolveChapter} 章回收，超出新目标——
                    该承诺将失去承载位置。请逐项处置：
                    {p.id && (
                      <Space wrap style={{ marginTop: 4 }}>
                        <Select
                          aria-label={`处置承诺 ${p.description}`}
                          size="small"
                          style={{ width: 150 }}
                          placeholder="选择处置"
                          value={promiseDecisions[p.id]?.action}
                          onChange={(action: 'MIGRATE' | 'OPEN_END') => setPromiseDecisions(prev => ({
                            ...prev,
                            [p.id!]: { id: `${p.id}-decision`, promiseId: p.id!, action,
                              targetChapter: action === 'MIGRATE' ? (prev[p.id!]?.targetChapter ?? undefined) : undefined,
                              reason: action === 'OPEN_END' ? (prev[p.id!]?.reason ?? '') : undefined },
                          }))}
                          options={[
                            { value: 'MIGRATE', label: '迁移到保留章节' },
                            { value: 'OPEN_END', label: '接受开放结局' },
                          ]}
                        />
                        {promiseDecisions[p.id]?.action === 'MIGRATE' && (
                          <InputNumber
                            aria-label={`迁移目标章号 ${p.description}`}
                            size="small" min={1}
                            max={Number(newTarget) || undefined}
                            placeholder="目标章号"
                            value={promiseDecisions[p.id]?.targetChapter}
                            onChange={(v) => setPromiseDecisions(prev => ({
                              ...prev,
                              [p.id!]: { ...(prev[p.id!] as PromiseDecisionInput), targetChapter: v ?? undefined },
                            }))}
                          />
                        )}
                        {promiseDecisions[p.id]?.action === 'OPEN_END' && (
                          <Input
                            aria-label={`开放结局理由 ${p.description}`}
                            size="small"
                            placeholder="接受开放结局的理由"
                            value={promiseDecisions[p.id]?.reason ?? ''}
                            onChange={(e) => setPromiseDecisions(prev => ({
                              ...prev,
                              [p.id!]: { ...(prev[p.id!] as PromiseDecisionInput), reason: e.target.value },
                            }))}
                          />
                        )}
                      </Space>
                    )}
                  </div>
                ))}
              </div>
            )}
          />
        )}
      </Card>
      <Space wrap style={{ marginBottom: 16 }}>
        <Button size="small" icon={<ReloadOutlined />} disabled={saving} onClick={() => void load()}>刷新</Button>
        <Button size="small" loading={replanning} disabled={saving} onClick={async () => {
          setReplanning(true);
          try {
            await api.replanOutline(projectId);
            message.success('未写卷已重规划');
            await load();
          } catch (error) {
            message.error((error as Error).message);
          } finally {
            setReplanning(false);
          }
        }}>重规划未写卷</Button>
      </Space>
      {!draft || !persistedOutline ? (
        <Empty description="暂无大纲，请先运行「构思」阶段（生成后点刷新）" />
      ) : (
        <>
          <div style={{ marginBottom: 16 }}>
            <Typography.Text strong>总纲</Typography.Text>
            <Input.TextArea
              aria-label="总纲内容"
              rows={4}
              value={premiseDraft}
              onChange={(event) => setPremiseDraft(event.target.value)}
              style={{ marginTop: 8 }}
            />
            <Button
              size="small"
              icon={<SaveOutlined />}
              style={{ marginTop: 8 }}
              onClick={async () => {
                try {
                  const saved = await api.updateOutline(projectId, { ...persistedOutline, premise: premiseDraft });
                  setPersistedOutline(saved);
                  setDraft((current) => current ? { ...current, premise: saved.premise } : cloneOutline(saved));
                  message.success('总纲已保存并写入真典');
                } catch (error) {
                  message.error((error as Error).message);
                }
              }}
              aria-label="保存总纲"
              disabled={saving}
            >保存总纲</Button>
          </div>
          <Space wrap style={{ marginBottom: 16 }}>
            <Button icon={<PlusOutlined />} onClick={addVolume} aria-label="新增卷" disabled={saving}>新增卷</Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={saving}
              onClick={() => void saveStructure()}
              aria-label="保存未写大纲"
            >保存未写大纲</Button>
          </Space>
          <PlanDeviationPanel projectId={projectId} currentChapter={currentChapter} />
          <OutlineTree
            outline={draft}
            projectId={projectId}
            currentChapter={currentChapter}
            saving={saving}
            hasUnsavedStructure={hasUnsavedStructure}
            onUpdateVolume={updateVolume}
            onUpdateSynopsis={updateSynopsis}
            onAddChapter={addChapter}
            onDeleteChapter={deleteChapter}
            onMoveChapter={moveChapter}
            onDeleteVolume={deleteVolume}
            onMoveVolume={moveVolume}
            onReviseVolume={(volume) => setRevisionTarget({
              projectId,
              targetType: 'VOLUME',
              targetId: volume.id,
              label: `卷${volume.volumeIndex} · ${volume.title}`,
            })}
          />
        </>
      )}
      <AgentRevisionDialog
        open={revisionTarget !== null}
        target={revisionTarget}
        onApplied={async () => {
          setRevisionTarget(null);
          await load();
        }}
        onCancel={() => setRevisionTarget(null)}
      />
    </Card>
  );
}

function OutlineTree({
  outline,
  projectId,
  currentChapter,
  saving,
  hasUnsavedStructure,
  onUpdateVolume,
  onUpdateSynopsis,
  onAddChapter,
  onDeleteChapter,
  onMoveChapter,
  onDeleteVolume,
  onMoveVolume,
  onReviseVolume,
}: {
  outline: Outline;
  projectId: string;
  currentChapter: number;
  saving: boolean;
  hasUnsavedStructure: boolean;
  onUpdateVolume: (volumeId: string, patch: Partial<Pick<Volume, 'title' | 'summary'>>) => void;
  onUpdateSynopsis: (synopsisId: string, patch: Partial<Pick<ChapterSynopsis, 'title' | 'synopsis'>>) => void;
  onAddChapter: (volumeId: string) => void;
  onDeleteChapter: (synopsisId: string) => void;
  onMoveChapter: (volumeId: string, synopsisIndex: number, direction: -1 | 1) => void;
  onDeleteVolume: (volumeId: string) => void;
  onMoveVolume: (volumeIndex: number, direction: -1 | 1) => void;
  onReviseVolume: (volume: Volume) => void;
}) {
  const lastWrittenVolumePosition = outline.volumes.reduce(
    (last, volume, index) => volumeContainsWritten(volume, currentChapter) ? index : last,
    -1,
  );
  const treeData: DataNode[] = outline.volumes.map((volume, volumePosition) => {
    const locked = volumeContainsWritten(volume, currentChapter);
    const previousLocked = volumePosition === 0 || volumeContainsWritten(outline.volumes[volumePosition - 1], currentChapter);
    const nextLocked = volumePosition === outline.volumes.length - 1 || volumeContainsWritten(outline.volumes[volumePosition + 1], currentChapter);
    return {
      title: (
        <div data-outline-volume-id={volume.id}>
          <Space wrap>
            <strong>📚 卷{volume.volumeIndex}</strong>
            {locked ? (
              <>
                <Typography.Text>{volume.title}</Typography.Text>
                <Typography.Text type="secondary" aria-label={`卷${volume.volumeIndex}已锁定`}>含已写章节，锁定</Typography.Text>
              </>
            ) : (
              <>
                <Input
                  size="small"
                  aria-label={`卷${volume.volumeIndex}标题`}
                  style={{ width: 180 }}
                  value={volume.title}
                  disabled={saving}
                  onChange={(event) => onUpdateVolume(volume.id, { title: event.target.value })}
                />
                <Input
                  size="small"
                  aria-label={`卷${volume.volumeIndex}简介`}
                  style={{ width: 220 }}
                  value={volume.summary}
                  disabled={saving}
                  onChange={(event) => onUpdateVolume(volume.id, { summary: event.target.value })}
                />
              </>
            )}
            <Button
              size="small"
              aria-label={`让 Agent 修改卷${volume.volumeIndex}`}
              onClick={() => onReviseVolume(volume)}
              disabled={saving || locked || hasUnsavedStructure || volume.id.startsWith('draft-volume-')}
              title={hasUnsavedStructure ? '请先保存或刷新当前大纲修改' : locked ? '含已写章节的卷不可修改' : undefined}
            >让 Agent 修改</Button>
            <Button
              size="small"
              icon={<PlusOutlined />}
              aria-label={`新增章节到卷${volume.volumeIndex}`}
              onClick={() => onAddChapter(volume.id)}
              disabled={saving || volumePosition < lastWrittenVolumePosition}
            >新增章节</Button>
            <Button
              size="small"
              icon={<UpOutlined />}
              aria-label={`上移卷${volume.volumeIndex}`}
              onClick={() => onMoveVolume(volumePosition, -1)}
              disabled={saving || locked || previousLocked}
            >上移卷</Button>
            <Button
              size="small"
              icon={<DownOutlined />}
              aria-label={`下移卷${volume.volumeIndex}`}
              onClick={() => onMoveVolume(volumePosition, 1)}
              disabled={saving || locked || nextLocked}
            >下移卷</Button>
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              aria-label={`删除卷${volume.volumeIndex}`}
              onClick={() => onDeleteVolume(volume.id)}
              disabled={saving || locked}
            >删除卷</Button>
          </Space>
        </div>
      ),
      key: volume.id,
      children: volume.synopses.map((synopsis, synopsisPosition) => ({
        title: (
          <SynopsisRow
            key={synopsis.id}
            synopsis={synopsis}
            synopsisPosition={synopsisPosition}
            volume={volume}
            currentChapter={currentChapter}
            saving={saving}
            onUpdate={onUpdateSynopsis}
            onDelete={onDeleteChapter}
            onMove={onMoveChapter}
            projectId={projectId}
      />
        ),
        key: synopsis.id,
        selectable: false,
      })),
    };
  });

  return (
    <Tree
      treeData={treeData}
      expandedKeys={outline.volumes.map((volume) => volume.id)}
      showLine
      blockNode
    />
  );
}

function SynopsisRow({
  synopsis,
  synopsisPosition,
  volume,
  currentChapter,
  saving,
  onUpdate,
  onDelete,
  onMove,
  projectId,
}: {
  synopsis: ChapterSynopsis;
  synopsisPosition: number;
  volume: Volume;
  currentChapter: number;
  saving: boolean;
  onUpdate: (synopsisId: string, patch: Partial<Pick<ChapterSynopsis, 'title' | 'synopsis'>>) => void;
  onDelete: (synopsisId: string) => void;
  onMove: (volumeId: string, synopsisIndex: number, direction: -1 | 1) => void;
  projectId: string;
}) {
  const locked = synopsis.chapterIndex <= currentChapter;
  const previousLocked = synopsisPosition === 0 || volume.synopses[synopsisPosition - 1].chapterIndex <= currentChapter;
  const isLast = synopsisPosition === volume.synopses.length - 1;
  const isNew = synopsis.id.startsWith('draft-chapter-');

  return (
    <div
      data-outline-chapter-id={synopsis.id}
      data-outline-chapter-index={synopsis.chapterIndex}
      data-outline-new-chapter={isNew ? 'true' : undefined}
      style={{ padding: '4px 0' }}
    >
      <Space wrap>
        <strong>第{synopsis.chapterIndex}章</strong>
        {locked ? (
          <>
            <Typography.Text>{synopsis.title}</Typography.Text>
            <Typography.Text type="secondary" aria-label={`第${synopsis.chapterIndex}章已锁定`}>已写，锁定</Typography.Text>
          </>
        ) : (
          <Input
            size="small"
            aria-label={`第${synopsis.chapterIndex}章标题`}
            style={{ width: 220 }}
            value={synopsis.title}
            disabled={saving}
            onChange={(event) => onUpdate(synopsis.id, { title: event.target.value })}
          />
        )}
        <Button
          size="small"
          icon={<UpOutlined />}
          aria-label={`上移第${synopsis.chapterIndex}章`}
          onClick={() => onMove(volume.id, synopsisPosition, -1)}
          disabled={saving || locked || previousLocked}
        >上移</Button>
        <Button
          size="small"
          icon={<DownOutlined />}
          aria-label={`下移第${synopsis.chapterIndex}章`}
          onClick={() => onMove(volume.id, synopsisPosition, 1)}
          disabled={saving || locked || isLast}
        >下移</Button>
        <Button
          danger
          size="small"
          icon={<DeleteOutlined />}
          aria-label={`删除第${synopsis.chapterIndex}章`}
          onClick={() => onDelete(synopsis.id)}
          disabled={saving || locked}
        >删除章节</Button>
      </Space>
      {locked ? (
        <div style={{ color: '#666', fontSize: 13, marginTop: 6 }}>{synopsis.synopsis}</div>
      ) : (
        <Input.TextArea
          rows={2}
          aria-label={`第${synopsis.chapterIndex}章梗概`}
          style={{ marginTop: 6 }}
          value={synopsis.synopsis}
          disabled={saving}
          onChange={(event) => onUpdate(synopsis.id, { synopsis: event.target.value })}
        />
      )}
      <SceneContractBlock projectId={projectId} chapterIndex={synopsis.chapterIndex} />
    </div>
  );
}

function PlanDeviationPanel({ projectId, currentChapter }: { projectId: string; currentChapter: number }) {
  const [deviations, setDeviations] = useState<PlanDeviationItem[]>([]);
  const [busy, setBusy] = useState<number | null>(null);
  const [messageApi, messageContextHolder] = message.useMessage();

  const load = () => {
    void api.listPlanDeviations(projectId).then(r => setDeviations(r.deviations ?? [])).catch(() => {});
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [projectId]);

  const handleAnalyze = async (chapter: number) => {
    setBusy(chapter);
    try {
      const r = await api.analyzePlanDeviation(projectId, chapter);
      if (r.analyzed) { messageApi.success(`第 ${chapter} 章偏离分析完成`); load(); }
      else messageApi.warning('分析未完成（输出不可解析）');
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '分析失败');
    } finally { setBusy(null); }
  };

  const handleDecide = async (chapter: number, action: string) => {
    setBusy(chapter);
    try {
      await api.decidePlanDeviation(projectId, chapter, action);
      messageApi.success(action === 'UPDATE_PLAN_FROM_TEXT' ? '计划已按正文更新（已写章不动）'
        : action === 'REVISE_TEXT' ? '已创建修订请求（到修订工作区继续）' : '已挂起（稍后可改）');
      load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '决定失败');
    } finally { setBusy(null); }
  };

  if (currentChapter <= 0) return null;
  return (
    <Card size="small" title="🧭 计划偏离对照" style={{ marginBottom: 12 }}
      extra={<Button size="small" loading={busy === currentChapter}
        onClick={() => handleAnalyze(currentChapter)}>分析第 {currentChapter} 章</Button>}>
      {messageContextHolder}
      {deviations.length === 0 ? (
        <Text type="secondary" style={{ fontSize: 12 }}>尚无偏离记录——对已写章节运行分析，比较计划与实际正文</Text>
      ) : (
        deviations.map(d => {
          let dev: Record<string, string> = {};
          try { dev = JSON.parse(d.deviation); } catch { /* keep empty */ }
          return (
            <div key={d.chapterIndex} style={{ padding: '6px 0', borderBottom: '1px dashed #eee', fontSize: 12 }}>
              <Space size={6} wrap>
                <Text strong>第 {d.chapterIndex} 章</Text>
                <Tag style={{ fontSize: 10 }}>{d.decision ?? '待决定'}</Tag>
                {dev.evidence && <Text type="secondary">证据："{
                  String(dev.evidence).slice(0, 30)}{String(dev.evidence).length > 30 ? '…' : ''}"</Text>}
              </Space>
              <div style={{ marginTop: 4, lineHeight: 1.7 }}>
                {dev.unrealized && <div><Text type="secondary">未实现：</Text>{String(dev.unrealized)}</div>}
                {dev.replaced && <div><Text type="secondary">自然替代：</Text>{String(dev.replaced)}</div>}
                {dev.newOpportunity && <div><Text type="secondary">新机会：</Text>{String(dev.newOpportunity)}</div>}
              </div>
              <Space style={{ marginTop: 6 }} size={6}>
                <Button size="small" disabled={!!d.decision && d.decision !== 'DEFER'}
                  loading={busy === d.chapterIndex}
                  onClick={() => handleDecide(d.chapterIndex, 'UPDATE_PLAN_FROM_TEXT')}>按正文更新计划</Button>
                <Button size="small" loading={busy === d.chapterIndex}
                  onClick={() => handleDecide(d.chapterIndex, 'REVISE_TEXT')}>修订正文</Button>
                <Button size="small" loading={busy === d.chapterIndex}
                  onClick={() => handleDecide(d.chapterIndex, 'DEFER')}>稍后决定</Button>
              </Space>
            </div>
          );
        })
      )}
    </Card>
  );
}

function SceneContractBlock({ projectId, chapterIndex }: { projectId: string; chapterIndex: number }) {
  const [contract, setContract] = useState<Record<string, string> | null>(null);
  const [editing, setEditing] = useState(false);
  const [draftJson, setDraftJson] = useState('');
  const [busy, setBusy] = useState(false);
  const [messageApi, messageContextHolder] = message.useMessage();

  useEffect(() => {
    void api.getSceneContract(projectId, chapterIndex).then(r => setContract(r.contract)).catch(() => {});
  }, [projectId, chapterIndex]);

  const handleGenerate = async () => {
    setBusy(true);
    try {
      const r = await api.generateSceneContract(projectId, chapterIndex);
      if (r.generated) { setContract(r.contract); messageApi.success('合同已生成并保存'); }
      else messageApi.warning('生成未完成（输出不可解析），未落库');
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '生成失败');
    } finally { setBusy(false); }
  };

  const handleSave = async () => {
    setBusy(true);
    try {
      const r = await api.saveSceneContract(projectId, chapterIndex, draftJson);
      setContract(r.contract); setEditing(false);
      messageApi.success('合同已保存');
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '保存失败（检查 JSON 格式）');
    } finally { setBusy(false); }
  };

  const fields = contract ? Object.entries(contract) : [];
  return (
    <Collapse size="small" style={{ marginTop: 6 }} items={[{
      key: 'contract',
      label: <span style={{ fontSize: 12 }}>📋 场景合同{contract ? '（已定义）' : '（未定义）'}</span>,
      children: (
        <div>
          {messageContextHolder}
          {!editing ? (
            fields.length > 0 ? (
              <div style={{ fontSize: 12, lineHeight: 1.8 }}>
                {fields.map(([k, v]) => (
                  <div key={k}><Text type="secondary">{CONTRACT_FIELD_LABELS[k] ?? k}：</Text>{String(v)}</div>
                ))}
              </div>
            ) : <Text type="secondary" style={{ fontSize: 12 }}>未定义——生成或手动编辑（写入后本章写作注入合同约束）</Text>
          ) : (
            <Input.TextArea rows={6} value={draftJson} onChange={e => setDraftJson(e.target.value)}
              placeholder='{"purpose":"...","cost":"..."}' />
          )}
          <Space style={{ marginTop: 6 }}>
            <Button size="small" loading={busy} onClick={handleGenerate}>AI 生成合同</Button>
            {!editing ? (
              <Button size="small" onClick={() => { setDraftJson(contract ? JSON.stringify(contract, null, 1) : '{}'); setEditing(true); }}>编辑</Button>
            ) : (
              <Button size="small" type="primary" loading={busy} onClick={handleSave}>保存合同</Button>
            )}
          </Space>
        </div>
      ),
    }]} />
  );
}

function volumeContainsWritten(volume: Volume, currentChapter: number) {
  return volume.synopses.some((synopsis) => synopsis.chapterIndex <= currentChapter);
}
