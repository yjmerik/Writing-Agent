import { useState, useEffect, useCallback, useRef } from 'react';
import { Card, Button, Space, List, Typography, Tag, Modal, Input, message, Row, Col, Alert, Spin, Progress, Collapse, Drawer, Grid } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, EditOutlined, HistoryOutlined, PlusOutlined, ReloadOutlined, TrophyOutlined } from '@ant-design/icons';
import { ApiError, api } from '../api/client';
import type { AgentRevisionTarget, CheckpointRevision } from '../api/client';
import { renderContent } from '../utils/renderContent';
import type { Chapter, ChapterVersion, Checkpoint, CheckpointPayload, ProjectStatus, ChapterQualityReport } from '../types';
import AgentRevisionDialog from './AgentRevisionDialog';

const { Text } = Typography;

interface Props {
  projectId: string;
  refreshSignal?: number;
  onChanged: () => void;
  onNavigateTab: (target: 'world' | 'outline') => void;
  /** p33-04: 待办直达——切到指定章节（来自状态栏待审按钮） */
  focusChapter?: number;
}

type RewriteTarget =
  | {
    kind: 'checkpoint';
    checkpoint: Checkpoint;
    action: 'reject' | 'modify';
  }
  | {
    kind: 'chapter';
    chapterIndex: number;
    sourceVersion: number;
  };

type ReviewResult = Extract<RewriteTarget, { kind: 'checkpoint' }> & { revision: CheckpointRevision };
type BodyLoadState = { loading: boolean; error: string };
type ReviewNavigationTarget = 'world' | 'outline';
type ChapterEditorTarget =
  | { mode: 'create' }
  | { mode: 'edit'; chapterIndex: number; expectedVersion: number };

const isReviewNavigationTarget = (target: unknown): target is ReviewNavigationTarget =>
  target === 'world' || target === 'outline';

export const normalizeCheckpointPayload = (value: unknown): CheckpointPayload | null => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const raw = value as Record<string, unknown>;
  const text = (field: string) => typeof raw[field] === 'string' ? raw[field] as string : undefined;
  const number = (field: string) => typeof raw[field] === 'number' && Number.isFinite(raw[field])
    ? raw[field] as number
    : undefined;
  const links: NonNullable<CheckpointPayload['links']> = [];
  if (Array.isArray(raw.links)) {
    for (const candidate of raw.links) {
      if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) continue;
      const link = candidate as Record<string, unknown>;
      if (typeof link.label === 'string' && link.label.trim().length > 0
        && isReviewNavigationTarget(link.target)) {
        links.push({ label: link.label, target: link.target });
      }
    }
  }
  return {
    chapterIndex: number('chapterIndex'),
    title: text('title'),
    wordCount: number('wordCount'),
    preview: text('preview'),
    score: number('score'),
    unresolvedHigh: number('unresolvedHigh'),
    worldSummary: text('worldSummary'),
    characterSummary: text('characterSummary'),
    premiseSummary: text('premiseSummary'),
    links,
  };
};

export default function InteractiveReview({ projectId, onChanged, onNavigateTab, refreshSignal, focusChapter }: Props) {
  // p21-05: 元数据一次加载；正文按选中章节懒加载缓存
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [bodies, setBodies] = useState<Record<number, Chapter>>({});
  const bodiesRef = useRef<Record<number, Chapter>>({});
  const [bodyLoadStates, setBodyLoadStates] = useState<Record<number, BodyLoadState>>({});
  const bodyLoadStatesRef = useRef<Record<number, BodyLoadState>>({});
  const bodyRequestGenerationsRef = useRef<Record<number, number>>({});
  const refreshGenerationRef = useRef(0);
  const projectGenerationRef = useRef(0);
  const activeProjectIdRef = useRef(projectId);
  const mountedRef = useRef(true);
  const [checkpoints, setCheckpoints] = useState<Checkpoint[]>([]);
  const [status, setStatus] = useState<ProjectStatus | null>(null);
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [reviewPanelOpen, setReviewPanelOpen] = useState(false);
  const screens = Grid.useBreakpoint();
  // p33-04: 外部聚焦请求（状态栏待办直达）
  useEffect(() => {
    if (focusChapter == null) return;
    const idx = chapters.findIndex(c => c.chapterIndex === focusChapter);
    if (idx >= 0) { setSelectedIdx(idx); window.scrollTo({ top: 0 }); }
  }, [focusChapter, chapters]);
  const selectedIdxRef = useRef(0);
  const [loading, setLoading] = useState(false);
  const [rewriteModalOpen, setRewriteModalOpen] = useState(false);
  const [rewriteTarget, setRewriteTarget] = useState<RewriteTarget | null>(null);
  const [rewriteInstructions, setRewriteInstructions] = useState('');
  const [rewriteError, setRewriteError] = useState('');
  const [rewriting, setRewriting] = useState(false);
  const [reviewResult, setReviewResult] = useState<ReviewResult | null>(null);
  const [qualityReport, setQualityReport] = useState<ChapterQualityReport | null>(null);
  // p11-G2: 段落编辑状态
  const [selectedParagraph, setSelectedParagraph] = useState<{ index: number; text: string } | null>(null);
  const [editMode, setEditMode] = useState<'REWRITE' | 'EXPAND' | 'INSERT'>('REWRITE');
  const [editInstruction, setEditInstruction] = useState('');
  const [editing, setEditing] = useState(false);
  const [chapterEditorTarget, setChapterEditorTarget] = useState<ChapterEditorTarget | null>(null);
  const [chapterDraftTitle, setChapterDraftTitle] = useState('');
  const [chapterDraftContent, setChapterDraftContent] = useState('');
  const [chapterSaveError, setChapterSaveError] = useState('');
  const [chapterSaving, setChapterSaving] = useState(false);
  const [historyChapter, setHistoryChapter] = useState<Chapter | null>(null);
  const [chapterVersions, setChapterVersions] = useState<ChapterVersion[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [compareVersion, setCompareVersion] = useState<ChapterVersion | null>(null);
  const [restoringVersion, setRestoringVersion] = useState<number | null>(null);
  const [revisionTarget, setRevisionTarget] = useState<AgentRevisionTarget | null>(null);

  if (activeProjectIdRef.current !== projectId) {
    activeProjectIdRef.current = projectId;
    projectGenerationRef.current += 1;
    refreshGenerationRef.current += 1;
    bodyRequestGenerationsRef.current = {};
    bodiesRef.current = {};
    bodyLoadStatesRef.current = {};
    selectedIdxRef.current = 0;
  }

  const cacheBody = useCallback((chapter: Chapter) => {
    const next = { ...bodiesRef.current, [chapter.chapterIndex]: chapter };
    bodiesRef.current = next;
    setBodies(next);
  }, []);

  const retainCurrentBodies = useCallback((metas: Chapter[]) => {
    const currentVersions = new Map(metas.map((chapter) => [chapter.chapterIndex, chapter.version]));
    const next = Object.fromEntries(Object.entries(bodiesRef.current)
      .filter(([chapterIndex, chapter]) => currentVersions.get(Number(chapterIndex)) === chapter.version));
    bodiesRef.current = next;
    setBodies(next);
  }, []);

  const setBodyLoadState = useCallback((chapterIndex: number, state: BodyLoadState) => {
    const next = { ...bodyLoadStatesRef.current, [chapterIndex]: state };
    bodyLoadStatesRef.current = next;
    setBodyLoadStates(next);
  }, []);

  const loadBody = useCallback(async (chapterIndex: number, force = false) => {
    if (!force && (bodiesRef.current[chapterIndex]
      || bodyLoadStatesRef.current[chapterIndex]?.loading)) return;
    const requestProjectId = projectId;
    const requestProjectGeneration = projectGenerationRef.current;
    const requestGeneration = (bodyRequestGenerationsRef.current[chapterIndex] ?? 0) + 1;
    bodyRequestGenerationsRef.current[chapterIndex] = requestGeneration;
    const isLatestRequest = () => mountedRef.current
      && activeProjectIdRef.current === requestProjectId
      && projectGenerationRef.current === requestProjectGeneration
      && bodyRequestGenerationsRef.current[chapterIndex] === requestGeneration;

    setBodyLoadState(chapterIndex, { loading: true, error: '' });
    try {
      const chapter = await api.getChapter(projectId, chapterIndex);
      if (!isLatestRequest()) return;
      if (!chapter) throw new Error('未找到章节正文');
      cacheBody(chapter);
    } catch (e) {
      if (!isLatestRequest()) return;
      const error = `加载正文失败: ${(e as Error).message}`;
      setBodyLoadState(chapterIndex, { loading: false, error });
      message.error(error);
      return;
    }
    if (isLatestRequest()) setBodyLoadState(chapterIndex, { loading: false, error: '' });
  }, [cacheBody, projectId, setBodyLoadState]);

  // p21-05 修复: refresh 只依赖 projectId——selectedIdx 变化（点选章节）不得触发
  // 元数据/checkpoints/status 重取（此前 useCallback 依赖 selectedIdx，点章即全量重取）
  const refresh = useCallback(async (forceSelectedBody = false, preferredChapterIndex?: number) => {
    const requestProjectId = projectId;
    const requestProjectGeneration = projectGenerationRef.current;
    const requestGeneration = refreshGenerationRef.current + 1;
    refreshGenerationRef.current = requestGeneration;
    const isLatestRefresh = () => mountedRef.current
      && activeProjectIdRef.current === requestProjectId
      && projectGenerationRef.current === requestProjectGeneration
      && refreshGenerationRef.current === requestGeneration;

    setLoading(true);
    try {
      const [page, cps, st] = await Promise.all([
        api.getChapterPage(projectId, 0, 1000),
        api.listCheckpoints(projectId),
        api.getStatus(projectId),
      ]);
      if (!isLatestRefresh()) return;
      const metas = page.chapters || [];
      const preferredIndex = preferredChapterIndex == null
        ? -1
        : metas.findIndex((chapter) => chapter.chapterIndex === preferredChapterIndex);
      const nextSelectedIdx = preferredIndex >= 0
        ? preferredIndex
        : metas.length > 0 ? Math.min(selectedIdxRef.current, metas.length - 1) : 0;
      const selectedMeta = metas[nextSelectedIdx];
      const cachedSelectedBody = selectedMeta ? bodiesRef.current[selectedMeta.chapterIndex] : undefined;
      const versionChanged = Boolean(selectedMeta && cachedSelectedBody && cachedSelectedBody.version !== selectedMeta.version);
      setChapters(metas);
      // 保留章节号和版本均仍有效的正文；版本变化时淘汰后显式重读当前章。
      retainCurrentBodies(metas);
      setCheckpoints(cps);
      setStatus(st);
      selectedIdxRef.current = nextSelectedIdx;
      setSelectedIdx(nextSelectedIdx);
      if (selectedMeta && (forceSelectedBody || !cachedSelectedBody || versionChanged)) {
        await loadBody(selectedMeta.chapterIndex, forceSelectedBody || versionChanged);
      }
    } catch (e) {
      if (!isLatestRefresh()) return;
      message.error(`加载失败: ${(e as Error).message}`);
    } finally {
      if (isLatestRefresh()) setLoading(false);
    }
  }, [loadBody, projectId, retainCurrentBodies]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      projectGenerationRef.current += 1;
      refreshGenerationRef.current += 1;
    };
  }, []);

  useEffect(() => {
    setBodies({});
    setBodyLoadStates({});
    setSelectedIdx(0);
    setChapterEditorTarget(null);
    setHistoryChapter(null);
    setChapterVersions([]);
    setCompareVersion(null);
    setRevisionTarget(null);
  }, [projectId]);

  useEffect(() => {
    // Refresh persisted metadata without unmounting author drafts or revision dialogs.
    if (!chapterEditorTarget && !rewriteModalOpen && !selectedParagraph && !revisionTarget) {
      void refresh();
    }
  }, [refreshSignal, chapterEditorTarget, rewriteModalOpen, selectedParagraph, revisionTarget, refresh]);

  const isPaused = status?.state === 'PAUSED_AT_CHECKPOINT';
  const hasPendingCheckpoint = checkpoints.length > 0;
  const currentMeta = chapters[selectedIdx];
  const currentBody = currentMeta && bodies[currentMeta.chapterIndex]?.version === currentMeta.version
    ? bodies[currentMeta.chapterIndex]
    : undefined;
  const currentBodyLoadState = currentMeta ? bodyLoadStates[currentMeta.chapterIndex] : undefined;

  useEffect(() => {
    selectedIdxRef.current = selectedIdx;
  }, [selectedIdx]);

  // p21-05: 选中章节正文懒加载（不重取 checkpoints/status，更不全量取章节正文）
  useEffect(() => {
    if (!currentMeta) return;
    const cached = bodiesRef.current[currentMeta.chapterIndex];
    if (cached?.version === currentMeta.version) return;
    void loadBody(currentMeta.chapterIndex, Boolean(cached));
  }, [currentMeta?.chapterIndex, currentMeta?.version, loadBody]);

  const currentChapter = currentMeta
    ? (currentBody ?? currentMeta)
    : undefined;

  // p11-I3: 选中章节时获取质量报告
  useEffect(() => {
    if (!currentMeta) { setQualityReport(null); return; }
    api.getChapterQuality(projectId, currentMeta.chapterIndex)
      .then(setQualityReport)
      .catch(() => setQualityReport(null));
  }, [projectId, currentMeta]);

  // p11-G2: 段落编辑
  const handleParagraphClick = (index: number, text: string) => {
    setSelectedParagraph({ index, text });
    setEditInstruction('');
  };

  const handleEditPassage = async () => {
    if (!currentChapter || !selectedParagraph) return;
    setEditing(true);
    try {
      await api.editPassage(projectId, currentChapter.chapterIndex, {
        paragraphIndex: selectedParagraph.index,
        selectedText: selectedParagraph.text,
        instruction: editInstruction,
        mode: editMode,
      });
      message.success(`段落${editMode === 'REWRITE' ? '重写' : editMode === 'EXPAND' ? '扩充' : '插入'}完成`);
      setSelectedParagraph(null);
      await refresh();
    } catch (e) {
      message.error('段落编辑失败: ' + (e as Error).message);
    } finally {
      setEditing(false);
    }
  };

  const replaceChapterInView = (chapter: Chapter) => {
    cacheBody(chapter);
    setChapters((previous) => previous
      .map((item) => item.chapterIndex === chapter.chapterIndex ? chapter : item)
      .sort((a, b) => a.chapterIndex - b.chapterIndex));
  };

  const openCreateChapter = () => {
    setChapterEditorTarget({ mode: 'create' });
    setChapterDraftTitle('');
    setChapterDraftContent('');
    setChapterSaveError('');
  };

  const openEditChapter = (chapter: Chapter) => {
    setChapterEditorTarget({
      mode: 'edit',
      chapterIndex: chapter.chapterIndex,
      expectedVersion: chapter.version,
    });
    setChapterDraftTitle(chapter.title);
    setChapterDraftContent(chapter.content);
    setChapterSaveError('');
  };

  const closeChapterEditor = () => {
    if (chapterSaving) return;
    setChapterEditorTarget(null);
    setChapterSaveError('');
  };

  const saveChapter = async () => {
    if (!chapterEditorTarget) return;
    setChapterSaving(true);
    setChapterSaveError('');
    try {
      if (chapterEditorTarget.mode === 'create') {
        const chapter = await api.createChapter(projectId, {
          title: chapterDraftTitle,
          content: chapterDraftContent,
        });
        cacheBody(chapter);
        await refresh(false, chapter.chapterIndex);
        message.success(`已新增第${chapter.chapterIndex}章草稿`);
      } else {
        const chapter = await api.updateChapter(projectId, chapterEditorTarget.chapterIndex, {
          expectedVersion: chapterEditorTarget.expectedVersion,
          title: chapterDraftTitle,
          content: chapterDraftContent,
        });
        replaceChapterInView(chapter);
        if (chapter.derivedRefreshPending) {
          message.warning(`第${chapter.chapterIndex}章正文已保存为 v${chapter.version}，但后续处理（摘要/索引）未完成，稍后自动重算，无需重新保存`);
        } else {
          message.success(`第${chapter.chapterIndex}章已保存为 v${chapter.version}`);
        }
      }
      setChapterEditorTarget(null);
      onChanged();
    } catch (error) {
      if (chapterEditorTarget.mode === 'edit' && error instanceof ApiError && error.status === 409
          && error.code === 'WORK_OWNERSHIP_CONFLICT') {
        // p32-12: 所有权占用不是版本冲突——不要诱导按旧稿重试
        setChapterSaveError('Agent 正在处理本项目，请等待当前步骤完成或先接手编辑');
        message.warning('Agent 正在处理本项目，请等待当前步骤完成或先接手编辑');
      } else if (chapterEditorTarget.mode === 'edit' && error instanceof ApiError && error.status === 409) {
        const latest = await api.getChapter(projectId, chapterEditorTarget.chapterIndex);
        if (latest) {
          replaceChapterInView(latest);
          setChapterEditorTarget({
            mode: 'edit',
            chapterIndex: latest.chapterIndex,
            expectedVersion: latest.version,
          });
          setChapterDraftTitle(latest.title);
          setChapterDraftContent(latest.content);
        }
        setChapterSaveError('章节已被更新，请重新确认');
        message.warning('章节已被更新，请重新确认');
      } else {
        setChapterSaveError((error as Error).message);
      }
    } finally {
      setChapterSaving(false);
    }
  };

  const openChapterHistory = async (chapter: Chapter) => {
    setHistoryChapter(chapter);
    setChapterVersions([]);
    setCompareVersion(null);
    setHistoryLoading(true);
    try {
      setChapterVersions(await api.getChapterVersions(projectId, chapter.chapterIndex));
    } catch (error) {
      message.error(`版本记录加载失败: ${(error as Error).message}`);
      setHistoryChapter(null);
    } finally {
      setHistoryLoading(false);
    }
  };

  const restoreChapterVersion = (version: ChapterVersion) => {
    if (!historyChapter) return;
    Modal.confirm({
      title: '确认回退',
      content: `将第${historyChapter.chapterIndex}章回退到 v${version.version}，当前版本会自动保留在版本记录中。`,
      okText: '确认回退',
      cancelText: '取消',
      onOk: async () => {
        setRestoringVersion(version.version);
        try {
          const restored = await api.restoreChapter(
            projectId,
            historyChapter.chapterIndex,
            version.version,
            historyChapter.version,
          );
          replaceChapterInView(restored);
          setHistoryChapter(null);
          setCompareVersion(null);
          message.success(`已回退到 v${version.version}，当前为 v${restored.version}`);
          onChanged();
        } catch (error) {
          if (error instanceof ApiError && error.status === 409
              && error.code === 'WORK_OWNERSHIP_CONFLICT') {
            // p32-12: 所有权占用——提示等待/接手，而非版本冲突重试
            message.warning('Agent 正在处理本项目，请等待当前步骤完成或先接手编辑后再回退');
        } else if (error instanceof ApiError && error.status === 409) {
            const latest = await api.getChapter(projectId, historyChapter.chapterIndex);
            if (latest) replaceChapterInView(latest);
            message.warning('章节已被更新，请重新确认');
          } else {
            message.error(`回退失败: ${(error as Error).message}`);
          }
          throw error;
        } finally {
          setRestoringVersion(null);
        }
      },
    });
  };

  const handleApprove = async (checkpoint: Checkpoint) => {
    setLoading(true);
    try {
      await api.approveCheckpoint(projectId, checkpoint.id);
      message.success('已批准');
      await refresh(true);
      onChanged();
    } catch (e) {
      message.error(`批准失败: ${(e as Error).message}`);
    } finally { setLoading(false); }
  };

  const openCheckpointRewrite = (
    checkpoint: Checkpoint,
    action: Extract<RewriteTarget, { kind: 'checkpoint' }>['action'],
  ) => {
    setRewriteTarget({ kind: 'checkpoint', checkpoint, action });
    setRewriteModalOpen(true);
    setRewriteInstructions('');
    setRewriteError('');
  };

  const closeRewriteModal = () => {
    setRewriteModalOpen(false);
    setRewriteTarget(null);
    setRewriteInstructions('');
    setRewriteError('');
  };

  const submitCheckpointRewrite = async () => {
    if (!rewriteTarget || rewriteTarget.kind !== 'checkpoint') return;
    setRewriting(true);
    setRewriteError('');
    try {
      const result = rewriteTarget.action === 'reject'
        ? await api.rejectCheckpoint(projectId, rewriteTarget.checkpoint.id, rewriteInstructions)
        : await api.modifyCheckpoint(projectId, rewriteTarget.checkpoint.id, rewriteInstructions);
      if (result.revision) {
        setReviewResult({ ...rewriteTarget, revision: result.revision });
      }
      if (rewriteTarget.action === 'reject') {
        message.success('已拒绝并重写');
      } else {
        message.success('已修改并批准');
      }
      closeRewriteModal();
      await refresh();
      onChanged();
    } catch (e) {
      message.error(`操作失败: ${(e as Error).message}`);
    } finally { setRewriting(false); }
  };



  const submitChapterRewrite = async (target: Extract<RewriteTarget, { kind: 'chapter' }>) => {
    setRewriting(true);
    setRewriteError('');
    try {
      const chapter = await api.rewriteChapter(projectId, target.chapterIndex, rewriteInstructions);
      cacheBody(chapter);
      setChapters((previous) => previous.map((item) =>
        item.chapterIndex === chapter.chapterIndex ? chapter : item,
      ));
      message.success(`第${chapter.chapterIndex}章已从 v${target.sourceVersion} 重写为 v${chapter.version}`);
      closeRewriteModal();
      onChanged();
    } catch (e) {
      setRewriteError(`重写失败: ${(e as Error).message}`);
    } finally { setRewriting(false); }
  };

  const submitRewrite = async () => {
    if (!rewriteTarget) return;
    if (rewriteTarget.kind === 'chapter') {
      await submitChapterRewrite(rewriteTarget);
      return;
    }
    await submitCheckpointRewrite();
  };

  const renderChapterContent = (content: string) => {
    if (!content) return <Text type="secondary">（空）</Text>;
    const lines = content.split('\n');
    return renderContent(content).map((node, index) => {
      const trimmed = lines[index]?.trim() ?? '';
      const isParagraph = Boolean(trimmed)
        && !trimmed.startsWith('# ')
        && !trimmed.startsWith('## ')
        && trimmed !== '---'
        && trimmed !== '***';
      if (!isParagraph || !currentChapter) return node;
      return (
        <div
          key={`passage-action-${index}`}
          data-passage-action-index={index}
          style={{ display: 'flex', alignItems: 'flex-start', gap: 8, flexWrap: 'wrap' }}
        >
          <div style={{ flex: '1 1 240px', minWidth: 0 }}>{node}</div>
          <Space wrap size={4} style={{ marginLeft: 'auto' }}>
            <Button
              type="text"
              icon={<EditOutlined />}
              aria-label={`编辑第 ${index + 1} 段`}
              onClick={() => handleParagraphClick(index, trimmed)}
              style={{ minHeight: 44 }}
            >编辑段落</Button>
            <Button
              type="text"
              aria-label={`让 Agent 修改第 ${index + 1} 段`}
              onClick={() => setRevisionTarget({
                projectId,
                targetType: 'PASSAGE',
                targetId: currentChapter.id,
                chapterIndex: currentChapter.chapterIndex,
                passageIndex: index,
                label: `第${currentChapter.chapterIndex}章 · 第${index + 1}段`,
              })}
              style={{ minHeight: 44 }}
            >让 Agent 修改</Button>
          </Space>
        </div>
      );
    });
  };

  return (
    <div>
      <Spin spinning={loading}>
        {/* 状态栏 */}
        {isPaused && hasPendingCheckpoint && checkpoints.map(cp => {
          let payload: CheckpointPayload | null = null;
          try { payload = cp.payload ? normalizeCheckpointPayload(JSON.parse(cp.payload)) : null; } catch { payload = null; }
          const navigationLinks = payload?.links ?? [];
          return (
          <Alert
            key={cp.id}
            type="warning"
            message={`⏸ 待审核：${cp.description}`}
            description={
              <div>
              <div style={{ marginBottom: 8, fontSize: 13 }}>
                审核目标：{cp.type}
              </div>
              {payload && (
                <div style={{ marginBottom: 8, fontSize: 13 }}>
                  {payload.score != null && (
                    <span style={{ marginRight: 12 }}>
                      读者评分：<b style={{ color: payload.score >= 8 ? '#52c41a' : payload.score >= 7 ? '#faad14' : '#ff4d4f' }}>{payload.score}/10</b>
                    </span>
                  )}
                  {payload.wordCount != null && <span style={{ marginRight: 12 }}>字数：{payload.wordCount.toLocaleString?.() ?? payload.wordCount}</span>}
                  {payload.unresolvedHigh != null && payload.unresolvedHigh > 0 && (
                    <span style={{ marginRight: 12, color: '#ff4d4f' }}>未解决 HIGH 问题：{payload.unresolvedHigh}</span>
                  )}
                  {payload.preview && (
                    <div style={{ color: '#888', marginTop: 4, maxHeight: 60, overflow: 'hidden' }}>
                      {payload.preview}…
                    </div>
                  )}
                  {payload.worldSummary && (
                    <div style={{ marginTop: 8 }}>
                      <Text strong>世界观摘要</Text>
                      <div style={{ color: '#666', whiteSpace: 'pre-wrap' }}>{payload.worldSummary}</div>
                    </div>
                  )}
                  {payload.characterSummary && (
                    <div style={{ marginTop: 8 }}>
                      <Text strong>人物摘要</Text>
                      <div style={{ color: '#666', whiteSpace: 'pre-wrap' }}>{payload.characterSummary}</div>
                    </div>
                  )}
                  {payload.premiseSummary && (
                    <div style={{ marginTop: 8 }}>
                      <Text strong>原旨与大纲摘要</Text>
                      <div style={{ color: '#666', whiteSpace: 'pre-wrap' }}>{payload.premiseSummary}</div>
                    </div>
                  )}
                  {navigationLinks.length > 0 && (
                    <Space wrap size={4} style={{ marginTop: 8 }}>
                      {navigationLinks.map(link => (
                        <Button
                          key={`${link.target}-${link.label}`}
                          type="link"
                          size="small"
                          onClick={() => onNavigateTab(link.target)}
                        >
                          {link.label}
                        </Button>
                      ))}
                    </Space>
                  )}
                </div>
              )}
              <Space>
                <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => handleApprove(cp)} loading={loading}>
                  ✅ 批准
                </Button>
                <Button danger icon={<CloseCircleOutlined />} onClick={() => openCheckpointRewrite(cp, 'reject')} loading={loading}>
                  ❌ 拒绝并重写
                </Button>
                <Button icon={<EditOutlined />} onClick={() => openCheckpointRewrite(cp, 'modify')} loading={loading}>
                  📝 修改意见重写
                </Button>
              </Space>
              </div>
            }
            style={{ marginBottom: 16 }}
          />
          );
        })}

        {reviewResult && (
          <Alert
            type="success"
            message={`审核操作完成：${reviewResult.checkpoint.description}`}
            description={
              <div>
                <div>审核目标：{reviewResult.revision.targetType}</div>
                <div>修改前摘要（v{reviewResult.revision.beforeVersion}）：{reviewResult.revision.beforeSummary}</div>
                <div>修改后摘要（v{reviewResult.revision.afterVersion}）：{reviewResult.revision.afterSummary}</div>
              </div>
            }
            style={{ marginBottom: 16 }}
          />
        )}

        {/* 状态提示 */}
        {status?.state === 'FINAL' && (
          <Alert type="success" message={`✅ 全书已完结，共 ${status.currentChapter} 章`} style={{ marginBottom: 16 }} />
        )}
        {status?.state === 'NEEDS_REVISION' && (
          <Alert
            type="error"
            message="⛔ 成书门禁未通过（待修订）"
            description="全书存在未达标的硬性门禁（如 mustResolve 伏笔未回收、近 60 章 HIGH 级矛盾超限）。请修订设定或正文后，点击「重新评审」再次触发成书门禁；通过后全书进入「已完结」。"
            style={{ marginBottom: 16 }}
            action={
              <Button
                type="primary"
                danger
                onClick={async () => {
                  try {
                    const r = await api.finalAudit(projectId);
                    if (r.passed) { message.success('评审通过，全书已完结'); } else { message.warning('评审未通过，请继续修订'); }
                    onChanged();
                  } catch (e) { message.error((e as Error).message); }
                }}
              >
                重新评审
              </Button>
            }
          />
        )}
        {status?.state === 'WRITING' && !isPaused && (
          <Alert type="info" message="📝 写作中 — 可查看和重写已完成的章节" style={{ marginBottom: 16 }} />
        )}

        {/* 写作与人工章节控制 */}
        <Space wrap style={{ marginBottom: 16 }}>

          <Button size="large" icon={<PlusOutlined />} onClick={openCreateChapter} disabled={loading}>
            新增章节
          </Button>
        </Space>

        <Row gutter={16}>
          {/* 章节列表（p21-06: 小屏堆叠） */}
          <Col xs={24} md={5}>
            <Card title={`📖 章节 (${chapters.length})`} size="small" style={{ maxHeight: '70vh', overflow: 'auto' }}>
              <List
                size="small"
                dataSource={chapters}
                renderItem={(ch, idx) => (
                  <List.Item
                    data-chapter-index={ch.chapterIndex}
                    role="button"
                    tabIndex={0}
                    aria-label={`第${ch.chapterIndex}章 ${ch.title}`}
                    onClick={() => setSelectedIdx(idx)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        setSelectedIdx(idx);
                      }
                    }}
                    style={{ cursor: 'pointer', background: idx === selectedIdx ? '#e6f7ff' : undefined }}
                  >
                    <List.Item.Meta
                      title={<Text strong>第{ch.chapterIndex}章 {ch.title.replace(/\*\*/g, '')}</Text>}
                      description={
                        <Space size={4}>
                          <Tag>{ch.wordCount} 字</Tag>
                          {ch.status === 'NEEDS_MANUAL_REVIEW' && <Tag color="red">待人审</Tag>}
                          {(ch.status === 'POST_PROCESSING' || ch.status === 'DRAFTING') && <Tag color="processing">生成中</Tag>}
                        </Space>
                      }
                    />
                  </List.Item>
                )}
              />
            </Card>
          </Col>

          {/* 章节正文（p33-04 三栏：中栏） */}
          <Col xs={24} md={13}>
            {currentChapter ? (
              <Card
                title={`第${currentChapter.chapterIndex}章 ${currentChapter.title.replace(/\*\*/g, '')}`}
                extra={
                  <Space wrap>
                    <Tag color="blue">{currentChapter.wordCount} 字</Tag>
                    <Tag color="purple">v{currentChapter.version}</Tag>
                    {!screens.md && (
                      <Button size="small" onClick={() => setReviewPanelOpen(true)}>审查</Button>
                    )}
                    {currentChapter.status === 'NEEDS_MANUAL_REVIEW' && (
                      <Button size="small" type="primary" onClick={async () => {
                        try {
                          await api.resolveManualReview(projectId, currentChapter.chapterIndex);
                          message.success('已标为处理完毕');
                          void refresh();
                          onChanged();
                        } catch (err) {
                          message.error((err as Error).message);
                        }
                      }}>已处理</Button>
                    )}
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      disabled={!currentBody}
                      onClick={() => currentBody && openEditChapter(currentBody)}
                    >
                      编辑正文
                    </Button>
                    <Button
                      size="small"
                      icon={<HistoryOutlined />}
                      disabled={!currentBody}
                      onClick={() => currentBody && void openChapterHistory(currentBody)}
                    >
                      版本记录
                    </Button>
                    <Button
                      size="small"
                      disabled={!currentBody}
                      aria-label={`让 Agent 修改第${currentChapter.chapterIndex}章`}
                      onClick={() => currentBody && setRevisionTarget({
                        projectId,
                        targetType: 'CHAPTER',
                        targetId: currentBody.id,
                        chapterIndex: currentBody.chapterIndex,
                        label: `第${currentBody.chapterIndex}章 · ${currentBody.title}`,
                      })}
                    >让 Agent 修改</Button>
                    <Button size="small" icon={<EditOutlined />} onClick={() => {
                      setRewriteTarget({
                        kind: 'chapter',
                        chapterIndex: currentChapter.chapterIndex,
                        sourceVersion: currentChapter.version,
                      });
                      setRewriteInstructions('');
                      setRewriteError('');
                      setRewriteModalOpen(true);
                    }}>
                      重写此章
                    </Button>
                  </Space>
                }
                style={{ maxHeight: '70vh', overflow: 'auto' }}
              >
                {currentBody ? (
                  <>
                    {renderChapterContent(currentChapter.content)}
                    {currentBodyLoadState?.error && (
                      <Alert
                        type="error"
                        message={currentBodyLoadState.error}
                        action={<Button size="small" onClick={() => void loadBody(currentMeta.chapterIndex, true)}>重试</Button>}
                        style={{ marginTop: 16 }}
                      />
                    )}
                  </>
                ) : currentBodyLoadState?.loading ? (
                  <Spin style={{ display: 'block', margin: '40px auto' }} />
                ) : currentBodyLoadState?.error ? (
                  <Alert
                    type="error"
                    message={currentBodyLoadState.error}
                    action={<Button size="small" onClick={() => void loadBody(currentMeta.chapterIndex, true)}>重试</Button>}
                  />
                ) : (
                  <Alert
                    type="warning"
                    message="正文尚未加载"
                    action={<Button size="small" onClick={() => void loadBody(currentMeta.chapterIndex, true)}>加载正文</Button>}
                  />
                )}

                {/* p11-G2: 段落编辑面板 */}
                {selectedParagraph && (
                  <Card size="small" title="段落编辑" style={{ marginTop: 16, borderColor: '#1890ff' }}
                    extra={<Button type="text" aria-label="关闭段落编辑" onClick={() => setSelectedParagraph(null)} style={{ width: 44, height: 44 }}>✕</Button>}>
                    <div style={{ marginBottom: 8, padding: 8, background: '#f6f8fa', borderRadius: 4, fontSize: 13 }}>
                      <Text type="secondary">选中段落（第 {selectedParagraph.index} 段）：</Text>
                      <div style={{ marginTop: 4 }}>{selectedParagraph.text.substring(0, 100)}
                        {selectedParagraph.text.length > 100 ? '...' : ''}</div>
                    </div>
                    <div style={{ marginBottom: 6, color: '#888', fontSize: 12 }}>编辑方式（选好后再点右下角执行）</div>
                    <Space style={{ marginBottom: 8 }}>
                      {(['REWRITE', 'EXPAND', 'INSERT'] as const).map(m => (
                        <Button key={m} size="small" type={editMode === m ? 'primary' : 'default'}
                          onClick={() => {
                            setEditMode(m);
                            if (editMode === m) {
                              message.info('已是该方式，请填写指令后点「执行」');
                            }
                          }}>
                          {m === 'REWRITE' ? '改写' : m === 'EXPAND' ? '扩充' : '在其后插入'}
                        </Button>
                      ))}
                    </Space>
                    <Input.TextArea
                      rows={3}
                      value={editInstruction}
                      onChange={e => setEditInstruction(e.target.value)}
                      placeholder={editMode === 'REWRITE' ? '改写指令，如：增加对话、改善节奏...' :
                        editMode === 'EXPAND' ? '扩充指令，如：增加感官细节、心理描写...' :
                        '插入内容描述，如：插入一段角色对话...'}
                    />
                    <div style={{ marginTop: 8, textAlign: 'right' }}>
                      <Button type="primary" size="small" loading={editing} onClick={handleEditPassage}
                        disabled={!editInstruction.trim()}>
                        执行{editMode === 'REWRITE' ? '改写' : editMode === 'EXPAND' ? '扩充' : '插入'}
                      </Button>
                    </div>
                  </Card>
                )}
              </Card>
            ) : (
              <Card><Text type="secondary">暂无章节。点击"写一章"开始创作。</Text></Card>
            )}
          </Col>

          {/* p33-04: 审查面板（右栏；窄屏抽屉） */}
          <Col xs={24} md={6}>
            <Card title="🔍 审查面板" size="small" style={{ maxHeight: '76vh', overflow: 'auto' }}>
              {currentChapter?.status === 'NEEDS_MANUAL_REVIEW' && (
                <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                  message={`第 ${currentChapter.chapterIndex} 章第 v${currentChapter.version} 稿待你审查`}
                  description="处理完成后再继续写作。正文头部的「已处理」按钮可放行本稿。" />
              )}
              {!currentChapter && <Text type="secondary">选择左侧章节查看审查信息。</Text>}
              {qualityReport ? (
                <div>
                  <Typography.Title level={5} style={{ marginTop: 0 }}>
                    <TrophyOutlined /> 本章质量
                    <Tag color={qualityReport.grade === 'A' ? 'green' : qualityReport.grade === 'B' ? 'gold' : 'red'} style={{ marginLeft: 8 }}>
                      {qualityReport.grade} 级
                    </Tag>
                  </Typography.Title>
                  {qualityReport.review && (
                    <Row gutter={8} style={{ marginBottom: 12 }}>
                      <Col span={8}>
                        <div style={{ textAlign: 'center' }}>
                          <div style={{ fontSize: 24, fontWeight: 700, color: qualityReport.review.overallScore >= 7 ? '#52c41a' : '#faad14' }}>
                            {qualityReport.review.overallScore}
                          </div>
                          <Text type="secondary" style={{ fontSize: 12 }}>综合评分</Text>
                        </div>
                      </Col>
                      {[
                        { label: '吸引力', val: qualityReport.review.engagement },
                        { label: '连贯性', val: qualityReport.review.coherence },
                        { label: '角色塑造', val: qualityReport.review.character },
                        { label: '章末钩子', val: qualityReport.review.hook },
                      ].map(d => (
                        <Col span={4} key={d.label}>
                          <Text type="secondary" style={{ fontSize: 12 }}>{d.label}</Text>
                          <Progress percent={d.val * 10} size="small" format={() => `${d.val}/10`} />
                        </Col>
                      ))}
                    </Row>
                  )}
                  {qualityReport.review?.summary && (
                    <Alert type="info" message={qualityReport.review.summary} style={{ marginBottom: 8 }} />
                  )}
                  {qualityReport.review?.improvement && (
                    <Alert type="warning" message="改进建议" description={qualityReport.review.improvement} style={{ marginBottom: 8 }} />
                  )}
                  {qualityReport.issues.length > 0 && (
                    <Collapse
                      size="small"
                      items={[{
                        key: 'issues',
                        label: `检测问题（${qualityReport.issues.length} 项）`,
                        children: (
                          <List
                            size="small"
                            dataSource={qualityReport.issues}
                            renderItem={item => (
                              <List.Item>
                                <Tag color={item.severity === 'HIGH' ? 'red' : item.severity === 'MEDIUM' ? 'orange' : 'default'}>
                                  {item.severity}
                                </Tag>
                                <Tag>{item.type}</Tag>
                                <Text style={{ fontSize: 12 }}>{item.description}</Text>
                              </List.Item>
                            )}
                          />
                        ),
                      }]}
                    />
                  )}
                  {qualityReport.clicheCount > 0 && (
                    <div style={{ fontSize: 12, color: '#999' }}>
                      套路词出现 {qualityReport.clicheCount} 次
                      {qualityReport.topCliches.length > 0 && `：${qualityReport.topCliches.join('、')}`}
                    </div>
                  )}
                </div>
              ) : (
                currentChapter && <Text type="secondary">本章暂无质量报告。</Text>
              )}
            </Card>
          </Col>
        </Row>

        {/* p33-04: 窄屏审查面板抽屉 */}
        <Drawer title="🔍 审查面板" placement="right" width="85%" open={reviewPanelOpen}
          onClose={() => setReviewPanelOpen(false)}>
          {currentChapter?.status === 'NEEDS_MANUAL_REVIEW' && (
            <Alert type="warning" showIcon style={{ marginBottom: 12 }}
              message={`第 ${currentChapter.chapterIndex} 章第 v${currentChapter.version} 稿待你审查`} />
          )}
          {qualityReport && (
            <Alert type="info" message={`本章质量 ${qualityReport.grade} 级 · ${qualityReport.issues.length} 项问题`}
              description={qualityReport.review?.summary ?? ''} />
          )}
          {!qualityReport && currentChapter && <Text type="secondary">本章暂无质量报告。</Text>}
        </Drawer>

        {/* 刷新按钮 */}
        <Button icon={<ReloadOutlined />} onClick={() => void refresh()} style={{ marginTop: 16 }} loading={loading}>
          刷新
        </Button>
      </Spin>

      {/* 重写/拒绝 对话框 */}
      <Modal
        title="修改意见"
        open={rewriteModalOpen}
        onCancel={closeRewriteModal}
        footer={[
          <Button key="cancel" onClick={closeRewriteModal}>取消</Button>,
          rewriteTarget?.kind === 'chapter' ? (
            <Button key="chapter" type="primary" loading={rewriting} onClick={submitRewrite}>
              重写此章
            </Button>
          ) : rewriteTarget?.action === 'modify' ? (
            <Button key="modify" type="primary" loading={rewriting} onClick={submitRewrite}>
              重写后批准
            </Button>
          ) : (
            <Button key="reject" danger loading={rewriting} onClick={submitRewrite}>
              重写（拒绝当前版本）
            </Button>
          ),
        ]}
      >
        {rewriteError && <Alert type="error" message={rewriteError} showIcon style={{ marginBottom: 12 }} />}
        <Input.TextArea
          rows={4}
          placeholder="请输入修改意见，如：增加对话、减少打斗、补充人物心理..."
          value={rewriteInstructions}
          onChange={e => setRewriteInstructions(e.target.value)}
        />
      </Modal>

      <Modal
        title={chapterEditorTarget?.mode === 'create' ? '新增章节' : '编辑正文'}
        open={chapterEditorTarget !== null}
        onCancel={closeChapterEditor}
        maskClosable={!chapterSaving}
        closable={!chapterSaving}
        footer={[
          <Button key="cancel" onClick={closeChapterEditor} disabled={chapterSaving}>取消</Button>,
          <Button
            key="save"
            type="primary"
            loading={chapterSaving}
            disabled={!chapterDraftTitle.trim()
              || (chapterEditorTarget?.mode === 'edit' && !chapterDraftContent.trim())}
            onClick={() => void saveChapter()}
          >
            {chapterEditorTarget?.mode === 'create' ? '创建草稿' : '保存正文'}
          </Button>,
        ]}
      >
        {chapterSaveError && (
          <Alert type="warning" showIcon message={chapterSaveError} style={{ marginBottom: 12 }} />
        )}
        <Input
          aria-label={chapterEditorTarget?.mode === 'create' ? '新增章节标题' : '章节标题'}
          value={chapterDraftTitle}
          onChange={(event) => setChapterDraftTitle(event.target.value)}
          placeholder="章节标题"
          disabled={chapterSaving}
          style={{ marginBottom: 12 }}
        />
        <Input.TextArea
          aria-label={chapterEditorTarget?.mode === 'create' ? '新增章节正文' : '章节正文'}
          value={chapterDraftContent}
          onChange={(event) => setChapterDraftContent(event.target.value)}
          placeholder={chapterEditorTarget?.mode === 'create'
            ? '可留空，稍后继续编辑正文'
            : '请输入章节正文'}
          disabled={chapterSaving}
          autoSize={{ minRows: 10, maxRows: 18 }}
        />
      </Modal>

      <Modal
        title={historyChapter ? `第${historyChapter.chapterIndex}章 · 版本记录` : '版本记录'}
        open={historyChapter !== null}
        onCancel={() => {
          if (restoringVersion == null) {
            setHistoryChapter(null);
            setCompareVersion(null);
          }
        }}
        footer={<Button onClick={() => setHistoryChapter(null)} disabled={restoringVersion != null}>关闭</Button>}
        width={900}
      >
        <Spin spinning={historyLoading}>
          {chapterVersions.length > 0 ? (
            <List
              size="small"
              dataSource={chapterVersions}
              renderItem={(version) => (
                <List.Item
                  data-chapter-version={version.version}
                  actions={[
                    <Button key="compare" type="link" onClick={() => setCompareVersion(version)}>比较</Button>,
                    <Button
                      key="restore"
                      type="link"
                      danger
                      loading={restoringVersion === version.version}
                      onClick={() => restoreChapterVersion(version)}
                    >
                      回退到此版本
                    </Button>,
                  ]}
                >
                  <List.Item.Meta
                    title={`v${version.version} · ${version.title}`}
                    description={`${version.source} · ${new Date(version.createdAt).toLocaleString()}`}
                  />
                </List.Item>
              )}
            />
          ) : !historyLoading ? (
            <Alert type="info" message="暂无历史版本" />
          ) : null}

          {compareVersion && historyChapter && (
            <Row gutter={16} style={{ marginTop: 16 }}>
              <Col xs={24} md={12}>
                <Text strong>历史 v{compareVersion.version}</Text>
                <pre data-compare-side="old" style={{ whiteSpace: 'pre-wrap', marginTop: 8, padding: 12, background: '#f5f5f5' }}>
                  {compareVersion.title}{'\n\n'}{compareVersion.content}
                </pre>
              </Col>
              <Col xs={24} md={12}>
                <Text strong>当前 v{historyChapter.version}</Text>
                <pre data-compare-side="current" style={{ whiteSpace: 'pre-wrap', marginTop: 8, padding: 12, background: '#f5f5f5' }}>
                  {historyChapter.title}{'\n\n'}{historyChapter.content}
                </pre>
              </Col>
            </Row>
          )}
        </Spin>
      </Modal>

      <AgentRevisionDialog
        open={revisionTarget !== null}
        target={revisionTarget}
        onApplied={async () => {
          const chapterIndex = revisionTarget?.chapterIndex;
          setRevisionTarget(null);
          setSelectedParagraph(null);
          await refresh(true, chapterIndex);
          onChanged();
        }}
        onCancel={() => setRevisionTarget(null)}
      />
    </div>
  );
}
