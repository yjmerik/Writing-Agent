import { useState, useEffect, useCallback } from 'react';
import { Tree, Spin, Empty, Card, Typography, Button, Space, Input, message } from 'antd';
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import { api } from '../api/client';
import type { ChapterSynopsis, Outline, Volume } from '../types';

export default function OutlineView({ projectId, currentChapter = 0 }: { projectId: string; currentChapter?: number }) {
  const [outline, setOutline] = useState<Outline | null>(null);
  const [loading, setLoading] = useState(true);
  const [premiseDraft, setPremiseDraft] = useState('');
  const [drafts, setDrafts] = useState<Record<string, { title: string; text: string }>>({});
  const [saving, setSaving] = useState<string | null>(null);
  const [replanning, setReplanning] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const o = await api.getOutline(projectId);
      setOutline(o);
      setPremiseDraft(o?.premise || '');
      const next: Record<string, { title: string; text: string }> = {};
      o?.volumes.forEach((v) => {
        next[v.id] = { title: v.title, text: v.summary };
        v.synopses.forEach((s) => {
          next[s.id] = { title: s.title, text: s.synopsis };
        });
      });
      setDrafts(next);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { load(); }, [load]);

  if (loading) return <Spin />;

  return (
    <Card>
      <Space style={{ marginBottom: 16 }}>
        <Button size="small" icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        <Button size="small" loading={replanning} onClick={async () => {
          setReplanning(true);
          try {
            await api.replanOutline(projectId);
            message.success('未写卷已重规划');
            await load();
          } catch (e) {
            message.error((e as Error).message);
          } finally {
            setReplanning(false);
          }
        }}>重规划未写卷</Button>
      </Space>
      {!outline ? (
        <Empty description="暂无大纲，请先运行「构思」阶段（生成后点刷新）" />
      ) : (
        <>
        <div style={{ marginBottom: 16 }}>
          <Typography.Text strong>总纲</Typography.Text>
          <Input.TextArea rows={4} value={premiseDraft} onChange={(e) => setPremiseDraft(e.target.value)} style={{ marginTop: 8 }} />
          <Button size="small" icon={<SaveOutlined />} style={{ marginTop: 8 }}
            onClick={async () => {
              try {
                await api.updateOutline(projectId, { ...outline, premise: premiseDraft });
                message.success('总纲已保存并写入真典');
                load();
              } catch (e) {
                message.error((e as Error).message);
              }
            }}>保存总纲</Button>
        </div>
        <OutlineTree
          outline={outline}
          currentChapter={currentChapter}
          drafts={drafts}
          saving={saving}
          onDraft={(id, patch) => setDrafts((d) => ({ ...d, [id]: { ...d[id], ...patch } }))}
          onSave={async (targetId) => {
            setSaving(targetId);
            try {
              const next: Outline = {
                ...outline,
                volumes: outline.volumes.map((v) => ({
                  ...v,
                  title: drafts[v.id]?.title ?? v.title,
                  summary: drafts[v.id]?.text ?? v.summary,
                  synopses: v.synopses.map((s) => ({
                    ...s,
                    title: drafts[s.id]?.title ?? s.title,
                    synopsis: drafts[s.id]?.text ?? s.synopsis,
                  })),
                })),
              };
              await api.updateOutline(projectId, next);
              message.success('未写规划已保存');
              await load();
            } catch (e) {
              message.error((e as Error).message);
            } finally {
              setSaving(null);
            }
          }}
        />
        </>
      )}
    </Card>
  );
}

function OutlineTree({
  outline, currentChapter, drafts, saving, onDraft, onSave,
}: {
  outline: Outline;
  currentChapter: number;
  drafts: Record<string, { title: string; text: string }>;
  saving: string | null;
  onDraft: (id: string, patch: Partial<{ title: string; text: string }>) => void;
  onSave: (id: string) => void;
}) {
  const treeData: DataNode[] = [
    {
      title: <strong>📖 总纲</strong>,
      key: 'premise',
      children: [
        {
          title: <Typography.Paragraph style={{ marginBottom: 0 }}>{outline.premise}</Typography.Paragraph>,
          key: 'premise-text',
          selectable: false,
        },
      ],
    },
    ...outline.volumes.map((v) => ({
      title: volumeTitle(v, currentChapter, drafts, saving, onDraft, onSave),
      key: v.id,
      children: v.synopses.map((s) => ({
        title: synopsisNode(s, currentChapter, drafts, saving, onDraft, onSave),
        key: s.id,
        selectable: false,
      })),
    })),
  ];

  return (
    <Tree
      treeData={treeData}
      defaultExpandAll
      showLine
      blockNode
    />
  );
}

function volumeUnwritten(volume: Volume, currentChapter: number) {
  return volume.synopses.every((s) => s.chapterIndex > currentChapter);
}

function volumeTitle(
  v: Volume,
  currentChapter: number,
  drafts: Record<string, { title: string; text: string }>,
  saving: string | null,
  onDraft: (id: string, patch: Partial<{ title: string; text: string }>) => void,
  onSave: (id: string) => void,
) {
  if (!volumeUnwritten(v, currentChapter)) {
    return <strong>📚 卷{v.volumeIndex} · {v.title}</strong>;
  }
  const draft = drafts[v.id] ?? { title: v.title, text: v.summary };
  return (
    <span>
      <strong>📚 卷{v.volumeIndex} · </strong>
      <Input size="small" style={{ width: 180 }} value={draft.title}
        onChange={(e) => onDraft(v.id, { title: e.target.value })} />
      <Input size="small" style={{ width: 220, marginLeft: 8 }} value={draft.text}
        onChange={(e) => onDraft(v.id, { text: e.target.value })} />
      <Button size="small" type="link" icon={<SaveOutlined />} loading={saving === v.id}
        onClick={() => onSave(v.id)}>保存</Button>
    </span>
  );
}

function synopsisNode(
  s: ChapterSynopsis,
  currentChapter: number,
  drafts: Record<string, { title: string; text: string }>,
  saving: string | null,
  onDraft: (id: string, patch: Partial<{ title: string; text: string }>) => void,
  onSave: (id: string) => void,
) {
  if (s.chapterIndex <= currentChapter) {
    return (
      <span>
        <strong>第{s.chapterIndex}章 {s.title}</strong>
        <br />
        <span style={{ color: '#666', fontSize: 13 }}>{s.synopsis}</span>
      </span>
    );
  }
  const draft = drafts[s.id] ?? { title: s.title, text: s.synopsis };
  return (
    <div>
      <Space>
        <span>第{s.chapterIndex}章</span>
        <Input size="small" style={{ width: 220 }} value={draft.title}
          onChange={(e) => onDraft(s.id, { title: e.target.value })} />
        <Button size="small" type="link" icon={<SaveOutlined />} loading={saving === s.id}
          onClick={() => onSave(s.id)}>保存</Button>
      </Space>
      <Input.TextArea rows={2} style={{ marginTop: 6 }} value={draft.text}
        onChange={(e) => onDraft(s.id, { text: e.target.value })} />
    </div>
  );
}
