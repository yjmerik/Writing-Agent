import { useState, useEffect, useCallback } from 'react';
import { Tree, Spin, Empty, Card, Typography, Button, Space, Input, message } from 'antd';
import {
  DeleteOutlined,
  DownOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  UpOutlined,
} from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import { api } from '../api/client';
import type { AgentRevisionTarget } from '../api/client';
import type { ChapterSynopsis, Outline, Volume } from '../types';
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

  return (
    <Card>
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
          <OutlineTree
            outline={draft}
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
}: {
  synopsis: ChapterSynopsis;
  synopsisPosition: number;
  volume: Volume;
  currentChapter: number;
  saving: boolean;
  onUpdate: (synopsisId: string, patch: Partial<Pick<ChapterSynopsis, 'title' | 'synopsis'>>) => void;
  onDelete: (synopsisId: string) => void;
  onMove: (volumeId: string, synopsisIndex: number, direction: -1 | 1) => void;
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
    </div>
  );
}

function volumeContainsWritten(volume: Volume, currentChapter: number) {
  return volume.synopses.some((synopsis) => synopsis.chapterIndex <= currentChapter);
}
