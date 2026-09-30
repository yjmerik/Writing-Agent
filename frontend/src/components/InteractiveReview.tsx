import { useState, useEffect, useCallback, useRef } from 'react';
import { Card, Button, Space, List, Typography, Tag, Modal, Input, InputNumber, message, Row, Col, Alert, Spin, Progress, Collapse, Drawer, Grid, Select, Tooltip } from 'antd';
import {
  saveDraft, loadDraft, loadConflictDraft, clearDraft, clearConflictDraft,
  contentHashHex, localStorageAdapter,
} from '../lib/draftStore';
import type { StoredDraft } from '../lib/draftStore';
import type { AuthorDirectiveView, DirectiveEvidenceView, ForeshadowCandidateView, ReviewContext } from '../api/client';
import { CheckCircleOutlined, CloseCircleOutlined, EditOutlined, HistoryOutlined, PlusOutlined, ReloadOutlined, SafetyOutlined, TrophyOutlined } from '@ant-design/icons';
import { ApiError, api } from '../api/client';
import type { AgentRevisionTarget, CheckpointRevision } from '../api/client';
import { renderContent } from '../utils/renderContent';
import type { Chapter, ChapterVersion, Checkpoint, CheckpointPayload, ProjectStatus, ChapterQualityReport, ReviewClassification, ReviewCard } from '../types';
import AgentRevisionDialog from './AgentRevisionDialog';

const { Text } = Typography;

// NCS-075: 收尾窗口批准的自动重试——同一幂等键安全重发，等待 worker 退出
const APPROVE_FINALIZING_MAX_ATTEMPTS = 3;
const APPROVE_FINALIZING_RETRY_DELAY_MS = 15000;

interface Props {
  projectId: string;
  reviewPanelOpen: boolean;
  onReviewPanelChange: (open: boolean) => void;
  readingMode: boolean;
  onReadingModeChange: (enabled: boolean) => void;
  refreshSignal?: number;
  onChanged: () => void;
  onNavigateTab: (target: 'world' | 'outline') => void;
  /** p33-04: 待办直达——切到指定章节（来自状态栏待审按钮） */
  focusChapter?: number;
  /** p36-08: 目录选中章经路由权威同步（点击/Enter/Space/待办跳转都更新路由） */
  onSelectChapter?: (chapterIndex: number) => void;
  /** p36-08: ?version=V&anchor=HASH 深链接——打开对应历史正文；缺失/指纹不符明确提示 */
  historyView?: { version: number; anchor?: string };
  /** NCS-071: ?adjudicate=ISSUE_ID 深链接——打开对应问题的裁决表单（来自质量分层跳转） */
  adjudicateIssue?: string;
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

type ReviewResult = Extract<RewriteTarget, { kind: 'checkpoint' }> & {
  revision: CheckpointRevision & { previewId?: string };
};
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

export default function InteractiveReview({ projectId, onChanged, onNavigateTab, refreshSignal, focusChapter, onSelectChapter, historyView, adjudicateIssue, reviewPanelOpen, onReviewPanelChange: setReviewPanelOpen, readingMode, onReadingModeChange }: Props) {
  // p21-05: 元数据一次加载；正文按选中章节懒加载缓存
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [bodies, setBodies] = useState<Record<number, Chapter>>({});
  const bodiesRef = useRef<Record<number, Chapter>>({});
  const [bodyLoadStates, setBodyLoadStates] = useState<Record<number, BodyLoadState>>({});
  const bodyLoadStatesRef = useRef<Record<number, BodyLoadState>>({});
  const bodyRequestGenerationsRef = useRef<Record<number, number>>({});
  // p36-05: 审阅上下文按章缓存——批准 token 只与已加载正文 identity 一致时启用
  const [reviewContexts, setReviewContexts] = useState<Record<number, ReviewContext>>({});
  const reviewContextGenerationsRef = useRef<Record<number, number>>({});
  // p36-05: 批准意图的幂等键——同一稿件的网络重试复用，成功/换稿/409 后重新生成
  const approvalIntentRef = useRef<{ chapterIndex: number; version: number; idempotencyKey: string } | null>(null);
  const [approving, setApproving] = useState(false);
  const [reviewConflict, setReviewConflict] = useState<string | null>(null);
  // NCS-081: 批准满足性门禁阻塞原因（未解决 HIGH 等）——常驻显示在批准按钮附近
  const [approveBlockReason, setApproveBlockReason] = useState<string | null>(null);
  const refreshGenerationRef = useRef(0);
  const projectGenerationRef = useRef(0);
  const activeProjectIdRef = useRef(projectId);
  const mountedRef = useRef(true);
  const [checkpoints, setCheckpoints] = useState<Checkpoint[]>([]);
  const [status, setStatus] = useState<ProjectStatus | null>(null);
  const [selectedIdx, setSelectedIdx] = useState(0);
  // p35-01: 意见面板状态
  const [directives, setDirectives] = useState<AuthorDirectiveView[]>([]);
  const [directiveContent, setDirectiveContent] = useState('');
  const [directiveKind, setDirectiveKind] = useState<'ONCE' | 'HARD' | 'PREFERENCE'>('ONCE');
  const [directiveChapterAll, setDirectiveChapterAll] = useState(false);
  const [directiveBusy, setDirectiveBusy] = useState(false);
  // p35-02: 候选线索状态
  const [candidates, setCandidates] = useState<ForeshadowCandidateView[]>([]);
  const screens = Grid.useBreakpoint();
  const selectedIdxRef = useRef(0);
  const [loading, setLoading] = useState(false);
  const [rewriteModalOpen, setRewriteModalOpen] = useState(false);
  const [rewriteTarget, setRewriteTarget] = useState<RewriteTarget | null>(null);
  const [rewriteInstructions, setRewriteInstructions] = useState('');
  const [rewriteError, setRewriteError] = useState('');
  const [rewriting, setRewriting] = useState(false);
  const [reviewResult, setReviewResult] = useState<ReviewResult | null>(null);
  const [qualityReport, setQualityReport] = useState<ChapterQualityReport | null>(null);
  // NCS-071: HIGH 问题裁决——表单状态与处置台账（issueId → 动作）
  const [adjudication, setAdjudication] = useState<{ issueId: string; description: string } | null>(null);
  const [adjAction, setAdjAction] = useState<'FALSE_POSITIVE' | 'EXCEPTION' | 'DEFER'>('FALSE_POSITIVE');
  const [adjReason, setAdjReason] = useState('');
  const [adjDeferUntil, setAdjDeferUntil] = useState<number | null>(null);
  const [adjudicating, setAdjudicating] = useState(false);
  const [dispositionsByIssue, setDispositionsByIssue] = useState<Record<string, string>>({});
  const consumedAdjudicateRef = useRef<string | null>(null);
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
  // p36-09: 草稿恢复/冲突/存储失败提示与未保存离开保护
  const [draftNotice, setDraftNotice] = useState<string | null>(null);
  const [draftStorageWarning, setDraftStorageWarning] = useState<string | null>(null);
  const [conflictDraftInfo, setConflictDraftInfo] = useState<{
    draft: StoredDraft;
    conflictCopy: StoredDraft | null;
    currentVersion: number;
  } | null>(null);
  const [draftPersisted, setDraftPersisted] = useState(true);
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

  // p36-05: 审阅上下文与正文同代次纪律加载——按请求代次抛弃迟到结果
  const loadReviewContext = useCallback(async (chapterIndex: number) => {
    const requestProjectId = projectId;
    const requestProjectGeneration = projectGenerationRef.current;
    const requestGeneration = (reviewContextGenerationsRef.current[chapterIndex] ?? 0) + 1;
    reviewContextGenerationsRef.current[chapterIndex] = requestGeneration;
    const isLatestRequest = () => mountedRef.current
      && activeProjectIdRef.current === requestProjectId
      && projectGenerationRef.current === requestProjectGeneration
      && reviewContextGenerationsRef.current[chapterIndex] === requestGeneration;
    try {
      const context = await api.getReviewContext(projectId, chapterIndex);
      if (!isLatestRequest()) return;
      setReviewContexts((previous) => ({ ...previous, [chapterIndex]: context }));
    } catch {
      if (!isLatestRequest()) return;
      // 上下文不可用时不展示旧 token：清除后批准按钮禁用
      setReviewContexts((previous) => {
        const next = { ...previous };
        delete next[chapterIndex];
        return next;
      });
    }
  }, [projectId]);

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
    setReviewContexts({});
    approvalIntentRef.current = null;
    setReviewConflict(null);
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

  // p36-09: 暂存失败或冲突未处理时启用离开保护——刷新/关闭前浏览器先确认
  useEffect(() => {
    if (chapterEditorTarget == null) return;
    const guard = (event: BeforeUnloadEvent) => {
      if (!draftPersisted || conflictDraftInfo) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [chapterEditorTarget, draftPersisted, conflictDraftInfo]);

  // p36-08: 路由是选中章的权威——非法章号明确提示并回退存在章，轮询不抢选中
  const [routeNotice, setRouteNotice] = useState<string | null>(null);
  const [historyNotice, setHistoryNotice] = useState<string | null>(null);
  const historyViewKeyRef = useRef('');
  useEffect(() => {
    if (focusChapter == null) { setRouteNotice(null); return; }
    if (chapters.length === 0) return;
    const idx = chapters.findIndex(c => c.chapterIndex === focusChapter);
    if (idx >= 0) {
      setRouteNotice(null);
      if (selectedIdxRef.current !== idx) { setSelectedIdx(idx); window.scrollTo({ top: 0 }); }
    } else {
      setRouteNotice(`路由指向的第 ${focusChapter} 章不存在，已选择第 ${chapters[0].chapterIndex} 章`);
      if (selectedIdxRef.current !== 0) setSelectedIdx(0);
    }
  }, [focusChapter, chapters]);

  // p36-08: ?version=V&anchor=HASH 深链接——打开对应历史正文；缺失或指纹不符明确提示，
  // 绝不退回当前稿冒充历史版。
  useEffect(() => {
    if (!historyView) { setHistoryNotice(null); historyViewKeyRef.current = ''; return; }
    if (!currentBody || !currentMeta) return;
    const key = `${currentMeta.chapterIndex}:${historyView.version}:${historyView.anchor ?? ''}`;
    if (historyViewKeyRef.current === key) return;
    historyViewKeyRef.current = key;
    void (async () => {
      try {
        const versions = await api.getChapterVersions(projectId, currentMeta.chapterIndex);
        const target = versions.find(v => v.version === historyView.version);
        if (!target) {
          setHistoryNotice(`第 ${historyView.version} 版历史不存在：该章尚未保存此版本。未显示当前稿冒充。`);
          return;
        }
        if (historyView.anchor) {
          const actual = await contentHashHex(target.title, target.content);
          if (actual !== historyView.anchor.toLowerCase()) {
            setHistoryNotice(`第 ${historyView.version} 版内容指纹与 anchor 不符，已拒绝展示。`);
            return;
          }
        }
        setHistoryNotice(null);
        setChapterVersions(versions);
        setHistoryChapter(currentBody);
        setCompareVersion(target);
      } catch (e) {
        setHistoryNotice(`历史版本加载失败: ${(e as Error).message}`);
      }
    })();
  }, [historyView, currentBody, currentMeta, projectId]);

  // p21-05: 选中章节正文懒加载（不重取 checkpoints/status，更不全量取章节正文）
  useEffect(() => {
    if (!currentMeta) return;
    const cached = bodiesRef.current[currentMeta.chapterIndex];
    if (cached?.version === currentMeta.version) return;
    void loadBody(currentMeta.chapterIndex, Boolean(cached));
  }, [currentMeta?.chapterIndex, currentMeta?.version, loadBody]);

  // p36-05: 选中章/稿变化即重取审阅上下文——旧 token 在版本变化后立即失配（按钮禁用）
  useEffect(() => {
    if (!currentMeta) return;
    void loadReviewContext(currentMeta.chapterIndex);
  }, [currentMeta?.chapterIndex, currentMeta?.version, loadReviewContext]);

  // NCS-081: 切章/换稿后清除上一次的批准阻塞提示——旧原因不得跨章残留
  useEffect(() => {
    setApproveBlockReason(null);
  }, [currentMeta?.chapterIndex, currentMeta?.version]);

  const currentChapter = currentMeta
    ? (currentBody ?? currentMeta)
    : undefined;

  // p36-05: 批准就绪 = 已加载正文且其版本与审阅上下文 identity 一致
  const currentReviewContext = currentMeta ? reviewContexts[currentMeta.chapterIndex] : undefined;
  const currentApproveReady = Boolean(currentBody && currentReviewContext
    && currentReviewContext.identity.version === currentBody.version);

  // p11-I3: 选中章节时获取质量报告
  useEffect(() => {
    if (!currentMeta) { setQualityReport(null); return; }
    api.getChapterQuality(projectId, currentMeta.chapterIndex)
      .then(setQualityReport)
      .catch(() => setQualityReport(null));
  }, [projectId, currentMeta]);

  // NCS-071: 处置台账随审阅上下文加载——问题行显示既有裁决
  const identity = currentReviewContext?.identity;
  useEffect(() => {
    if (!identity) { setDispositionsByIssue({}); return; }
    let active = true;
    api.listDispositions(projectId, identity.chapterIndex, identity.version, identity.contentHash)
      .then(rows => {
        if (!active) return;
        const map: Record<string, string> = {};
        for (const row of rows) map[row.issueId] = row.action;
        setDispositionsByIssue(map);
      })
      .catch(() => { if (active) setDispositionsByIssue({}); });
    return () => { active = false; };
  }, [projectId, identity?.chapterIndex, identity?.version, identity?.contentHash]);

  // NCS-071: 打开裁决表单（问题行按钮或质量分层深链）
  const openAdjudication = useCallback((issueId: string, description: string) => {
    setAdjAction('FALSE_POSITIVE');
    setAdjReason('');
    setAdjDeferUntil(null);
    setAdjudication({ issueId, description });
  }, []);

  // NCS-076: 检查点卡片直达——正文未加载时引导作者打开本章（加载后批准按钮即就绪）
  const openChapterForReview = useCallback((chapterIndex: number) => {
    const idx = chapters.findIndex(c => c.chapterIndex === chapterIndex);
    if (idx < 0) return;
    if (onSelectChapter) onSelectChapter(chapterIndex);
    else setSelectedIdx(idx);
    window.scrollTo({ top: 0 });
  }, [chapters, onSelectChapter]);

  // NCS-071: 深链 ?adjudicate=ISSUE_ID——质量报告加载后自动预开对应表单
  useEffect(() => {
    if (!adjudicateIssue || consumedAdjudicateRef.current === adjudicateIssue) return;
    if (!qualityReport) return;
    const issue = qualityReport.issues.find(i => i.id === adjudicateIssue);
    if (!issue) return;
    consumedAdjudicateRef.current = adjudicateIssue;
    openAdjudication(issue.id as string, issue.description);
  }, [adjudicateIssue, qualityReport, openAdjudication]);

  // NCS-071: 提交裁决——理由强制；暂挂需目标章且不解除当前章批准门禁
  const submitAdjudication = async () => {
    if (!adjudication || !identity) return;
    if (!adjReason.trim()) { message.warning('裁决必须说明理由'); return; }
    if (adjAction === 'DEFER' && (adjDeferUntil == null || adjDeferUntil < 1)) {
      message.warning('暂挂必须指定目标章号'); return;
    }
    setAdjudicating(true);
    try {
      await api.createDisposition(projectId, {
        issueId: adjudication.issueId,
        action: adjAction,
        reason: adjReason.trim(),
        deferUntilChapter: adjAction === 'DEFER' ? (adjDeferUntil ?? undefined) : undefined,
        identity,
      });
      message.success('裁决已记入处置台账');
      setAdjudication(null);
      const refreshed = await api.getChapterQuality(projectId, identity.chapterIndex).catch(() => null);
      if (refreshed) setQualityReport(refreshed);
      void loadReviewContext(identity.chapterIndex);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '裁决提交失败');
    } finally {
      setAdjudicating(false);
    }
  };

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

  // p35-01: 意见列表加载与动作
  const loadDirectives = async () => {
    try { setDirectives(await api.getDirectives(projectId)); } catch { /* 面板不影响主流程 */ }
  };
  useEffect(() => { void loadDirectives(); }, [projectId]);
  const loadCandidates = async () => {
    try { setCandidates(await api.getForeshadowCandidates(projectId)); } catch { /* 面板不影响主流程 */ }
  };
  useEffect(() => { void loadCandidates(); }, [projectId]);
  const promoteCandidate = async (id: string) => {
    try {
      await api.promoteForeshadowCandidate(projectId, id);
      await Promise.all([loadCandidates(), refresh()]);
      message.success('已提升为正式伏笔（HIGH）');
    } catch (err) { message.error((err as Error).message); }
  };
  const rejectCandidate = async (id: string) => {
    try {
      await api.rejectForeshadowCandidate(projectId, id);
      await loadCandidates();
    } catch (err) { message.error((err as Error).message); }
  };
  const submitDirective = async () => {
    if (!directiveContent.trim()) return;
    setDirectiveBusy(true);
    try {
      await api.createDirective(projectId, {
        kind: directiveKind,
        chapterIndex: directiveChapterAll ? undefined
          : (chapterEditorTarget?.mode === 'edit' ? chapterEditorTarget.chapterIndex : currentChapter?.chapterIndex),
        content: directiveContent.trim(),
      });
      setDirectiveContent('');
      await loadDirectives();
      message.success('意见已登记');
    } catch (err) { message.error((err as Error).message); }
    finally { setDirectiveBusy(false); }
  };
  const isDirectiveConfirmed = (d: AuthorDirectiveView) => d.status === 'CONFIRMED'
    || (d.evidence ?? []).some(e => e.status === 'CONFIRMED' && e.directiveRevision === d.revision
      && (d.kind === 'ONCE' || (e.chapterIndex === currentChapter?.chapterIndex
        && e.chapterVersion === currentBody?.version)));
  const confirmEvidence = async (d: AuthorDirectiveView, e: DirectiveEvidenceView) => {
    const context = reviewContexts[e.chapterIndex];
    if (!context || context.identity.version !== e.chapterVersion || context.identity.contentHash !== e.contentHash) return;
    setDirectiveBusy(true);
    try {
      await api.confirmDirectiveEvidence(projectId, d.id, e.id, context.identity, d.revision);
      await loadDirectives(); await loadReviewContext(e.chapterIndex); onChanged();
      message.success('已确认此稿证据，单次要求不会重复消费');
    } catch (err) { message.error((err as Error).message); }
    finally { setDirectiveBusy(false); }
  };
  const dismissDirective = async (id: string) => {
    try { await api.dismissDirective(projectId, id); await loadDirectives(); }
    catch (err) { message.error((err as Error).message); }
  };

  const openEditChapter = async (chapter: Chapter) => {
    // p36-09: 冲突判定以服务器当前稿为准——前端缓存可能落后于其他窗口的保存
    let latest = chapter;
    try {
      const remote = await api.getChapter(projectId, chapter.chapterIndex);
      if (remote) {
        latest = remote;
        if (remote.version !== chapter.version) replaceChapterInView(remote);
      }
    } catch { /* 网络失败时退回缓存版本 */ }
    setChapterEditorTarget({
      mode: 'edit',
      chapterIndex: latest.chapterIndex,
      expectedVersion: latest.version,
    });
    setChapterDraftTitle(latest.title);
    setChapterDraftContent(latest.content);
    setChapterSaveError('');
    setDraftStorageWarning(null);
    setConflictDraftInfo(null);
    // p36-09: 恢复两字段草稿；基准变化保留冲突副本并排呈现；旧格式仅提示核对
    const currentHash = await contentHashHex(latest.title, latest.content);
    const result = loadDraft(localStorageAdapter, projectId, latest.chapterIndex,
      latest.version, currentHash);
    if (result.kind === 'matching') {
      const draft = result.draft;
      if (draft.legacy) {
        setDraftNotice(`发现旧格式草稿（${draft.savedAt.slice(11, 19)} 暂存，无基准指纹）——请核对内容后再保存，系统未自动覆盖。`);
      } else {
        setDraftNotice(`已恢复未保存草稿（基于 v${latest.version}，${draft.savedAt.slice(11, 19)} 暂存）。保存前不会进入版本历史。`);
      }
      setChapterDraftTitle(draft.title);
      setChapterDraftContent(draft.content);
    } else if (result.kind === 'conflict') {
      // 基准已变化：编辑器展示当前稿，冲突副本并排可复制/放弃，不静默隐藏或覆盖
      setConflictDraftInfo({
        draft: result.draft,
        conflictCopy: result.conflictCopy ?? loadConflictDraft(localStorageAdapter, projectId, latest.chapterIndex),
        currentVersion: latest.version,
      });
    } else if (result.kind === 'unavailable') {
      setDraftStorageWarning(`本地草稿读取失败：${result.error}。本次编辑不会被暂存，请勿依赖刷新恢复。`);
    } else {
      setDraftNotice(null);
    }
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
        // p36-09: 正式保存成功后才清除该章本地草稿（已进入版本历史）
        if (chapterEditorTarget.mode === 'edit') {
          clearDraft(localStorageAdapter, projectId, chapterEditorTarget.chapterIndex);
        }
      }
      setChapterEditorTarget(null);
      setDraftNotice(null);
      setDraftStorageWarning(null);
      setDraftPersisted(true);
      onChanged();
    } catch (error) {
      if (chapterEditorTarget.mode === 'edit' && error instanceof ApiError && error.status === 409
          && error.code === 'WORK_OWNERSHIP_CONFLICT') {
        // p32-12: 所有权占用不是版本冲突——不要诱导按旧稿重试
        setChapterSaveError('Agent 正在处理本项目，请等待当前步骤完成或先接手编辑');
        message.warning('Agent 正在处理本项目，请等待当前步骤完成或先接手编辑');
      } else if (chapterEditorTarget.mode === 'edit' && error instanceof ApiError && error.status === 409) {
        // p36-09: 409 保留作者输入——不把本地内容替换成新稿；刷新基准后重试
        const latest = await api.getChapter(projectId, chapterEditorTarget.chapterIndex);
        if (latest) {
          replaceChapterInView(latest);
          setChapterEditorTarget({
            mode: 'edit',
            chapterIndex: latest.chapterIndex,
            expectedVersion: latest.version,
          });
        }
        setChapterSaveError('章节已被更新，请重新确认；你输入的标题与正文已保留，请核对后再保存');
        message.warning('章节已被更新，请重新确认；你输入的内容已保留');
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

  // p36-05: 统一「批准当前稿」——章节卡、章节类检查点卡与窄屏抽屉共用同一 handler；
  // 服务端做原子比较提交（身份 + 待审集合指纹 + 幂等键），409 时保留草稿强制重读。
  const [rechecking, setRechecking] = useState(false);
  // NCS-074: 补查占坑期间的进度展示与取消——不再让作者面对无解释的长时间等待
  const [recheckElapsed, setRecheckElapsed] = useState(0);
  const recheckStartedAtRef = useRef(0);
  const recheckCancelledRef = useRef(false);

  useEffect(() => {
    if (!rechecking) return;
    const timer = setInterval(() =>
      setRecheckElapsed(Math.round((Date.now() - recheckStartedAtRef.current) / 1000)), 1000);
    return () => clearInterval(timer);
  }, [rechecking]);

  const recheckCurrentDraft = async () => {
    if (!currentChapter || !currentBody || rechecking) return;
    const context = reviewContexts[currentChapter.chapterIndex];
    if (!context || context.identity.version !== currentBody.version) return;
    setRechecking(true);
    recheckCancelledRef.current = false;
    recheckStartedAtRef.current = Date.now();
    setRecheckElapsed(0);
    try {
      await api.recheckChapter(projectId, currentChapter.chapterIndex, context.identity);
      if (recheckCancelledRef.current) {
        // 作者已取消等待：检查若完成仍按稿件身份落库，静默刷新即可
        await refresh(true, currentChapter.chapterIndex);
        onChanged();
        return;
      }
      await refresh(true, currentChapter.chapterIndex);
      await loadReviewContext(currentChapter.chapterIndex);
      onChanged();
      message.success('当前稿连续性补查完成；其他报告仍按各自稿号显示');
    } catch (e) {
      if (!recheckCancelledRef.current) message.error(`补查未完成：${(e as Error).message}`);
    } finally { setRechecking(false); }
  };

  const cancelRecheckWait = async () => {
    recheckCancelledRef.current = true;
    try {
      await api.cancelActiveWork(projectId);
      setRechecking(false);
      message.info('已取消等待并释放项目工作权；检查若稍后完成，结果仍按稿件版本落库');
    } catch (e) {
      message.error(`取消失败：${(e as Error).message}`);
    }
  };

  const approveChapterDraft = async (chapterIndex: number) => {
    if (approving) return;
    const meta = chapters.find(c => c.chapterIndex === chapterIndex);
    const cachedBody = bodies[chapterIndex];
    const body = meta && cachedBody?.version === meta.version ? cachedBody : undefined;
    const context = reviewContexts[chapterIndex];
    if (!meta || !body || !context) return;
    if (context.identity.version !== body.version) {
      // token 与已加载正文不一致：不批准，先重读再由作者确认
      message.warning('审阅上下文与当前正文版本不一致，已重新读取，请确认后再批准');
      await refresh(true, chapterIndex);
      void loadReviewContext(chapterIndex);
      return;
    }
    setApproving(true);
    setReviewConflict(null);
    setApproveBlockReason(null);
    const intent = approvalIntentRef.current
      && approvalIntentRef.current.chapterIndex === chapterIndex
      && approvalIntentRef.current.version === body.version
      ? approvalIntentRef.current
      : { chapterIndex, version: body.version, idempotencyKey: crypto.randomUUID() };
    approvalIntentRef.current = intent;
    try {
      // NCS-075: 收尾窗口冲突（WORKER_FINALIZING）以同一幂等键自动重试——批准可安全重发；
      // REVIEW_CONFLICT 保持"重读+人工确认"，其余冲突如实展示服务端原因
      for (let attempt = 1; ; attempt++) {
        try {
          const result = await api.approveChapterRevision(projectId, chapterIndex, {
            identity: context.identity,
            reviewSetHash: context.reviewSetHash,
            idempotencyKey: intent.idempotencyKey,
            reason: '作者确认当前稿',
          });
          approvalIntentRef.current = null;
          setReviewConflict(null);
          message.success(result.replayed
            ? '批准已生效（同一请求重试返回原结果）'
            : `已批准第${chapterIndex}章 v${body.version}`);
          await refresh(true, chapterIndex);
          void loadReviewContext(chapterIndex);
          onChanged();
          return;
        } catch (error) {
          if (error instanceof ApiError && error.status === 409
            && error.code === 'WORKER_FINALIZING'
            && attempt < APPROVE_FINALIZING_MAX_ATTEMPTS) {
            message.warning(`Agent 正在收尾上一章质量检查，${APPROVE_FINALIZING_RETRY_DELAY_MS / 1000} 秒后自动重试批准（第 ${attempt}/${APPROVE_FINALIZING_MAX_ATTEMPTS - 1} 次重试）…`);
            await new Promise(resolve => setTimeout(resolve, APPROVE_FINALIZING_RETRY_DELAY_MS));
            if (!mountedRef.current) return;
            continue;
          }
          approvalIntentRef.current = null;
          if (error instanceof ApiError && error.status === 409 && error.code === 'REVIEW_CONFLICT') {
            // 409 版本/待审冲突：保留草稿并强制重读正文与审阅上下文，不自动重试批准
            setReviewConflict(error.message);
            message.warning(`批准未生效：${error.message}（已重新读取，请确认新稿后再批准）`);
            await refresh(true, chapterIndex);
            void loadReviewContext(chapterIndex);
            onChanged();
          } else if (error instanceof ApiError && error.status === 409) {
            // NCS-081: 其余 409（未解决 HIGH 等满足性门禁）——原因常驻显示在批准按钮附近，
            // 不再只有 3 秒 toast（听雪刀 P1-2：作者需要知道"为什么批不了、差什么"）
            setApproveBlockReason(error.message);
            message.warning(`批准未生效：${error.message}`);
            await refresh(true, chapterIndex);
            void loadReviewContext(chapterIndex);
            onChanged();
          } else {
            message.error(`批准失败: ${(error as Error).message}`);
          }
          return;
        }
      }
    } finally {
      setApproving(false);
    }
  };

  // ===== NCS-163: 被拒提示内联逐条裁决入口（走既有 dispositions 端点，无新后端） =====
  const [inlineAdjData, setInlineAdjData] = useState<ReviewClassification | null>(null);
  const [inlineAdjForm, setInlineAdjForm] = useState<
    { card: ReviewCard; action: 'FALSE_POSITIVE' | 'EXCEPTION' | 'DEFER' } | null>(null);
  const [inlineAdjReason, setInlineAdjReason] = useState('');
  const [inlineAdjDeferUntil, setInlineAdjDeferUntil] = useState<number>(1);
  const [inlineAdjBusy, setInlineAdjBusy] = useState(false);

  // 被拒提示出现时拉取分类卡，供逐条裁决（仅当前章、未裁决、现行版本可内联处置）
  useEffect(() => {
    if (!approveBlockReason || !currentChapter) return;
    let cancelled = false;
    api.getReviewClassification(projectId)
      .then((data) => { if (!cancelled) setInlineAdjData(data); })
      .catch(() => { if (!cancelled) setInlineAdjData(null); });
    return () => { cancelled = true; };
  }, [approveBlockReason, currentChapter?.chapterIndex, projectId]);

  const inlineAdjItems: ReviewCard[] = (() => {
    if (!inlineAdjData || !currentChapter) return [];
    return inlineAdjData.mustFix.filter((item) =>
      item.kind === 'ISSUE' && item.issueId
      && item.chapterIndex === currentChapter.chapterIndex
      && item.adjudicated !== true && item.stale !== true);
  })();

  const submitInlineAdjudication = async () => {
    if (!inlineAdjForm || !currentChapter || !currentReviewContext) return;
    setInlineAdjBusy(true);
    try {
      await api.createDisposition(projectId, {
        issueId: inlineAdjForm.card.issueId!,
        action: inlineAdjForm.action,
        reason: inlineAdjReason,
        deferUntilChapter: inlineAdjForm.action === 'DEFER' ? inlineAdjDeferUntil : undefined,
        identity: {
          projectId,
          chapterIndex: currentReviewContext.identity.chapterIndex,
          version: currentReviewContext.identity.version,
          contentHash: currentReviewContext.identity.contentHash,
        },
      });
      message.success('已裁决并记入处置台账，可重试批准');
      setInlineAdjForm(null);
      setInlineAdjReason('');
      setApproveBlockReason(null);
      await refresh(true, currentChapter.chapterIndex);
      void loadReviewContext(currentChapter.chapterIndex);
      onChanged();
    } catch (e) {
      message.error(`裁决失败: ${(e as Error).message}`);
    } finally { setInlineAdjBusy(false); }
  };

  const actionText = (action: 'FALSE_POSITIVE' | 'EXCEPTION' | 'DEFER') =>
    action === 'FALSE_POSITIVE' ? '误报' : action === 'EXCEPTION' ? '创作例外' : '暂挂';

  const renderInlineAdjudication = () => {
    if (!approveBlockReason || !currentChapter) return null;
    if (inlineAdjItems.length === 0) return null;
    return (
      <div style={{ marginTop: 8 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>逐条裁决（记入处置台账，裁决后重试批准）：</Typography.Text>
        {inlineAdjItems.map((item) => (
          <div key={item.issueId} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginTop: 4 }}>
            <Typography.Text style={{ fontSize: 12, flex: 1 }}>
              <Tag color="red" style={{ fontSize: 10 }}>{item.severity}</Tag>{item.description}
            </Typography.Text>
            <Space size={4}>
              {(['FALSE_POSITIVE', 'EXCEPTION', 'DEFER'] as const).map((action) => (
                <Button key={action} size="small" style={{ fontSize: 11 }}
                  onClick={() => {
                    setInlineAdjForm({ card: item, action });
                    setInlineAdjReason('');
                    setInlineAdjDeferUntil(currentChapter.chapterIndex + 1);
                  }}>{actionText(action)}</Button>
              ))}
            </Space>
          </div>
        ))}
      </div>
    );
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
        // NCS-155: 修订带人物卡预览时补取 previewId（预览不随响应返回自身 id）
        let revision: CheckpointRevision & { previewId?: string } = result.revision;
        if (revision.characterRevisions && revision.characterRevisions.length > 0) {
          try {
            const pending = await api.pendingCharacterRevisions(projectId);
            if (pending.previewId) {
              revision = { ...revision, previewId: pending.previewId };
            }
          } catch { /* 预览查询失败不阻断主流程，确认时再取 */ }
        }
        setReviewResult({ ...rewriteTarget, revision });
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

  // NCS-155: 人物卡修订预览卡片区——逐字段原/候选对照 + 确认/放弃（确认前不落库）
  const [characterRevisionBusy, setCharacterRevisionBusy] = useState(false);
  const renderCharacterRevisionPreview = () => {
    const cards = reviewResult?.revision.characterRevisions;
    if (!cards || cards.length === 0) return null;
    const settle = (applied: boolean, summaryText: string) => {
      setReviewResult((current) => current
        ? { ...current, revision: { ...current.revision, characterRevisions: null } }
        : current);
      if (applied) message.success(summaryText);
      onChanged();
    };
    const withPreviewId = async <T,>(action: (previewId: string) => Promise<T>): Promise<T> => {
      let previewId = reviewResult?.revision.previewId;
      if (!previewId) {
        const pending = await api.pendingCharacterRevisions(projectId);
        previewId = pending.previewId;
      }
      if (!previewId) throw new Error('待确认预览不存在或已失效，请重新发起修订');
      return action(previewId);
    };
    return (
      <div style={{ marginTop: 8 }}>
        <Typography.Text strong>人物卡修订预览（{cards.length} 张，待作者确认后生效）</Typography.Text>
        {cards.map((card) => (
          <div key={card.characterId} style={{ margin: '8px 0', padding: '8px', border: '1px solid #f0f0f0', borderRadius: 4 }}>
            <Typography.Text strong>{card.name}</Typography.Text>
            {card.changes.map((change) => (
              <div key={change.field} style={{ fontSize: 12, marginTop: 2 }}>
                <Tag>{change.field}</Tag>
                <Typography.Text type="secondary" delete={!!change.before}>
                  {change.before || '（原为空）'}
                </Typography.Text>
                {' → '}
                <Typography.Text>{change.after}</Typography.Text>
              </div>
            ))}
          </div>
        ))}
        <Space>
          <Button
            type="primary"
            size="small"
            loading={characterRevisionBusy}
            onClick={async () => {
              setCharacterRevisionBusy(true);
              try {
                const res = await withPreviewId((previewId) =>
                  api.confirmCharacterRevisions(projectId, previewId));
                settle(true, res.summary || '人物卡修订已应用');
              } catch (e) {
                message.error(`确认失败: ${(e as Error).message}`);
              } finally { setCharacterRevisionBusy(false); }
            }}
          >确认修订</Button>
          <Button
            size="small"
            loading={characterRevisionBusy}
            onClick={async () => {
              setCharacterRevisionBusy(true);
              try {
                await withPreviewId((previewId) =>
                  api.discardCharacterRevisions(projectId, previewId));
                settle(false, '已放弃人物卡修订');
              } catch (e) {
                message.error(`放弃失败: ${(e as Error).message}`);
              } finally { setCharacterRevisionBusy(false); }
            }}
          >放弃</Button>
        </Space>
      </div>
    );
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
      const reason = (e as Error).message || '';
      // NCS-073: 占坑冲突给出可行动指引——终止暂停任务后再重写
      const guidance = reason.includes('已有进行中的写作任务')
        ? '；可到「任务与版本」终止已暂停的任务后重试'
        : '';
      setRewriteError(`重写失败: ${reason}${guidance}`);
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
          style={{ marginBottom: 12 }}
        >
          <div style={{ minWidth: 0, fontSize: 17, overflowWrap: 'anywhere' }}>{node}</div>
          {!readingMode && <Space wrap size={4} style={{ display: 'flex', justifyContent: 'flex-end' }}>
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
          </Space>}
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
          // p36-05: 章节类检查点与正文/抽屉共用「批准当前稿」；token 只与已加载正文一致时启用
          const checkpointChapterIndex = payload?.chapterIndex;
          const isChapterClassCheckpoint = checkpointChapterIndex != null
            && (cp.type === 'AFTER_CHAPTER' || cp.type === 'ON_CONTINUITY_FIX');
          const checkpointMeta = isChapterClassCheckpoint
            ? chapters.find(c => c.chapterIndex === checkpointChapterIndex)
            : undefined;
          const checkpointBody = checkpointMeta
            && bodies[checkpointMeta.chapterIndex]?.version === checkpointMeta.version
            ? bodies[checkpointMeta.chapterIndex]
            : undefined;
          const checkpointContext = isChapterClassCheckpoint && checkpointChapterIndex != null
            ? reviewContexts[checkpointChapterIndex]
            : undefined;
          const checkpointApproveReady = Boolean(checkpointBody && checkpointContext
            && checkpointContext.identity.version === checkpointBody.version);
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
              {isChapterClassCheckpoint && checkpointMeta && (
                <div style={{ marginBottom: 8, fontSize: 13 }}>
                  当前稿：第 {checkpointMeta.chapterIndex} 章 <b>v{checkpointMeta.version}</b>
                  {!checkpointBody && '（正文未加载——打开本章审阅后才能批准）'}
                </div>
              )}
              {payload && (
                <div style={{ marginBottom: 8, fontSize: 13 }}>
                  {payload.volumeSummary && (
                    <Alert
                      type="success"
                      showIcon
                      style={{ marginBottom: 8 }}
                      message={`🎉 第 ${payload.volumeSummary.volumeIndex} 卷《${payload.volumeSummary.volumeTitle}》完`}
                      description={`第 ${payload.volumeSummary.firstChapter}-${payload.volumeSummary.lastChapter} 章 · 共 ${payload.volumeSummary.totalWords.toLocaleString()} 字 · 本卷埋设伏笔 ${payload.volumeSummary.foreshadowPlanted} 条 / 回收 ${payload.volumeSummary.foreshadowResolved} 条`}
                    />
                  )}
                  {payload.score != null && (
                    <span style={{ marginRight: 12 }}>
                      读者评分：<b style={{ color: payload.score >= 8 ? '#52c41a' : payload.score >= 7 ? '#faad14' : '#ff4d4f' }}>{payload.score}/10</b>
                    </span>
                  )}
                  {payload.wordCount != null && <span style={{ marginRight: 12 }}>字数：{payload.wordCount.toLocaleString?.() ?? payload.wordCount}</span>}
                  {payload.wordTrendWarning && (
                    <Tooltip title={`本章字数低于前 3 章均值（${payload.previousAvgWordCount?.toLocaleString?.() ?? payload.previousAvgWordCount} 字）的 50%——全书篇幅可能正在滑坡，建议审阅时关注`}>
                      <Tag color="volcano" style={{ fontSize: 11 }}>篇幅骤降</Tag>
                    </Tooltip>
                  )}
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
              <Space wrap>
                {isChapterClassCheckpoint ? (
                  <>
                    {checkpointMeta && !checkpointBody && checkpointChapterIndex != null && (
                      <Button icon={<EditOutlined />}
                        aria-label={`打开第${checkpointChapterIndex}章正文`}
                        onClick={() => openChapterForReview(checkpointChapterIndex)}>
                        打开第 {checkpointChapterIndex} 章正文
                      </Button>
                    )}
                    <Button type="primary" icon={<CheckCircleOutlined />} loading={approving}
                      disabled={!checkpointApproveReady}
                      aria-label="批准当前稿"
                      title={checkpointApproveReady ? '批准当前加载的稿件' : '请先打开本章正文，审阅上下文就绪后才能批准'}
                      onClick={() => checkpointChapterIndex != null && void approveChapterDraft(checkpointChapterIndex)}>
                      批准当前稿
                    </Button>
                  </>
                ) : (
                  <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => handleApprove(cp)} loading={loading}>
                    ✅ 批准
                  </Button>
                )}
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
                {renderCharacterRevisionPreview()}
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

        {/* p36-05: 版本/待审集合冲突提示——保留草稿，强制重读后由作者显式重新批准 */}
        {reviewConflict && (
          <Alert type="warning" showIcon style={{ marginBottom: 16 }}
            message="批准未生效：审阅对象已变化"
            description={`${reviewConflict} 正文与审阅上下文已重新读取；确认新稿后请再次点击「批准当前稿」。系统不会自动重试批准。`} />
        )}

        {/* p36-08: 路由/历史深链接的明确提示——不静默回退、不用当前稿冒充 */}
        {routeNotice && (
          <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={routeNotice} />
        )}
        {historyNotice && (
          <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={historyNotice} />
        )}

        <Row gutter={[16, 16]}>
          {/* 章节列表（p21-06: 小屏堆叠） */}
          <Col xs={24} md={5} style={{ display: readingMode ? 'none' : undefined }}>
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
                    onClick={() => onSelectChapter
                      ? onSelectChapter(ch.chapterIndex)
                      : setSelectedIdx(idx)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        onSelectChapter
                          ? onSelectChapter(ch.chapterIndex)
                          : setSelectedIdx(idx);
                      }
                    }}
                    style={{ cursor: 'pointer', background: idx === selectedIdx ? '#e6f7ff' : undefined }}
                  >
                    <List.Item.Meta
                      title={<Text strong>第{ch.chapterIndex}章 {ch.title.replace(/\*\*/g, '')}</Text>}
                      description={
                        <Space wrap size={4}>
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

          {/* 章节正文：主阅读区 */}
          <Col xs={24} md={readingMode ? 24 : 19}>
            {currentChapter ? (
              <Card data-chapter-reader styles={{ body: { padding: screens.md ? 24 : 16 } }}>
                <div style={{ maxWidth: readingMode ? 1000 : undefined, margin: '0 auto' }}>
                <Typography.Title level={3} style={{ marginTop: 0, whiteSpace: 'normal', overflowWrap: 'anywhere' }}>
                  第{currentChapter.chapterIndex}章 {currentChapter.title.replace(/\*\*/g, '')}
                </Typography.Title>
                  {/* NCS-076: 人工复核屏障引导——说明屏障含义与解除路径（批准即解除） */}
                  {currentChapter.status === 'NEEDS_MANUAL_REVIEW' && (
                    <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                      data-manual-review-banner
                      message={`第 ${currentChapter.chapterIndex} 章第 v${currentChapter.version} 稿待人工复核，后续写作已暂停`}
                      description="质量守卫未给出可放行的结论，本章被屏障保护。请审阅正文与质量报告：确认无需处理后点击「确认已复核并批准」解除屏障放行本稿；如检查缺项，可先「补查当前稿连续性」再确认。" />
                  )}
                  <Space wrap style={{ marginBottom: 24 }}>
                    <Button size="small" onClick={() => onReadingModeChange(!readingMode)}>
                      {readingMode ? '退出专注阅读' : '专注阅读'}
                    </Button>
                    <Tag color="blue">{currentChapter.wordCount} 字</Tag>
                    <Tag color="purple">v{currentChapter.version}</Tag>
                    <Button size="small" onClick={() => setReviewPanelOpen(true)}>审查</Button>
                    <Button size="small" loading={rechecking} disabled={!currentApproveReady}
                      onClick={() => void recheckCurrentDraft()}>补查当前稿连续性</Button>
                    {rechecking && (
                      <>
                        <span data-recheck-elapsed style={{ fontSize: 12, color: '#888' }}>
                          补查进行中（已 {recheckElapsed < 90 ? `${recheckElapsed} 秒` : `${Math.max(1, Math.round(recheckElapsed / 60))} 分钟`}）
                        </span>
                        <Button size="small" danger onClick={() => void cancelRecheckWait()}>取消等待</Button>
                      </>
                    )}
                    {['DONE', 'NEEDS_MANUAL_REVIEW'].includes(currentChapter.status ?? '') && (
                      <Button size="small" type="primary" loading={approving}
                        disabled={!currentApproveReady}
                        aria-label={currentChapter.status === 'NEEDS_MANUAL_REVIEW' ? '确认已复核并批准' : '批准当前稿'}
                        title={currentApproveReady
                          ? (currentChapter.status === 'NEEDS_MANUAL_REVIEW'
                            ? '确认已复核本稿：批准将解除人工复核屏障并放行'
                            : '批准当前加载的稿件')
                          : '正文或审阅上下文尚未就绪'}
                        onClick={() => void approveChapterDraft(currentChapter.chapterIndex)}>
                        {currentChapter.status === 'NEEDS_MANUAL_REVIEW' ? '确认已复核并批准' : '批准当前稿'}
                      </Button>
                    )}
                    {['DONE', 'NEEDS_MANUAL_REVIEW'].includes(currentChapter.status ?? '') && !currentApproveReady && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        批准未就绪：正文或审阅上下文尚未加载/版本未对齐，请先在上方打开正文完成审阅
                      </Text>
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
                  {approveBlockReason && currentChapter && (
                    <Alert
                      type="warning" showIcon style={{ marginTop: 8, marginBottom: 8 }}
                      message="批准未生效：当前稿存在阻塞项"
                      description={<>
                        {`${approveBlockReason}
处理路径：到「质量」页查看未解决项（重新检查 / 作为创作例外保留），或修订正文后重试批准；处理完成后本提示自动消失。`}
                        {renderInlineAdjudication()}
                      </>
                      }
                    />
                  )}
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
                </div>
              </Card>
            ) : (
              <Card><Text type="secondary">暂无章节。点击「新增章节」开始创作。</Text></Card>
            )}
          </Col>

        </Row>

        <Drawer title="审查面板" zIndex={1100} placement="right" width={screens.md ? 480 : '100%'} open={reviewPanelOpen}
          onClose={() => setReviewPanelOpen(false)}
          extra={<Button onClick={() => setReviewPanelOpen(false)}>返回正文</Button>}
          styles={{ body: { padding: screens.md ? 24 : 16, overflowWrap: 'anywhere' } }}>
          <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.8 }}>
            {currentChapter && <Typography.Title level={5} style={{ marginTop: 0, overflowWrap: 'anywhere' }}>
              第{currentChapter.chapterIndex}章 · {currentChapter.title} · v{currentChapter.version}
            </Typography.Title>}
              {/* p35-01: 意见面板 */}
              <Collapse
                size="small"
                style={{ marginBottom: 12 }}
                items={[{
                  key: 'directives',
                  label: `📝 意见（${directives.filter(d => !isDirectiveConfirmed(d) && (d.status === 'OPEN' || d.status === 'APPLIED_SYSTEM')).length} 项待办）`,
                  children: (
                    <div>
                      <Input.TextArea
                        aria-label="登记意见"
                        placeholder="对当前章节提出修改意见（如：为旧发夹提前加铺垫）"
                        value={directiveContent}
                        onChange={e => setDirectiveContent(e.target.value)}
                        autoSize={{ minRows: 2, maxRows: 4 }}
                        style={{ marginBottom: 8 }}
                      />
                      <Space wrap style={{ marginBottom: 8 }}>
                        <Select<'ONCE' | 'HARD' | 'PREFERENCE'> value={directiveKind}
                          onChange={setDirectiveKind} style={{ width: 120 }}
                          options={[
                            { value: 'ONCE', label: '单次意见' },
                            { value: 'HARD', label: '硬性要求' },
                            { value: 'PREFERENCE', label: '偏好' },
                          ]} />
                        {!directiveChapterAll && currentChapter && (
                          <Tag>范围：第 {currentChapter.chapterIndex} 章</Tag>
                        )}
                        <Button size="small" onClick={() => setDirectiveChapterAll(v => !v)}>
                          {directiveChapterAll ? '改为本章' : '改为全书'}
                        </Button>
                        <Button size="small" type="primary" loading={directiveBusy}
                          disabled={!directiveContent.trim()} onClick={() => void submitDirective()}>
                          登记意见
                        </Button>
                      </Space>
                      {directives.length === 0 && <Text type="secondary">暂无意见。</Text>}
                      <List
                        size="small"
                        dataSource={directives}
                        renderItem={d => (
                          <List.Item style={{ flexDirection: 'column', alignItems: 'stretch', gap: 8 }}
                            actions={[
                              !isDirectiveConfirmed(d) && d.kind !== 'HARD' ? (
                                <Button key="d" size="small" onClick={() => void dismissDirective(d.id)}>撤销</Button>
                              ) : null,
                            ].filter(Boolean) as JSX.Element[]}
                          >
                            <List.Item.Meta
                              title={
                                <Space wrap size={4}>
                                  <Tag color={d.kind === 'HARD' ? 'red' : d.kind === 'ONCE' ? 'blue' : 'default'}>
                                    {d.kind === 'HARD' ? '硬性' : d.kind === 'ONCE' ? '单次' : '偏好'}
                                  </Tag>
                                  {d.chapterIndex != null && <Tag>第 {d.chapterIndex} 章</Tag>}
                                  <Tag color={isDirectiveConfirmed(d) ? 'green'
                                    : d.status === 'APPLIED_SYSTEM' ? 'gold'
                                    : d.status === 'DISMISSED' ? 'default' : 'processing'}>
                                    {isDirectiveConfirmed(d) ? '已确认' : d.status === 'OPEN' ? '待处理'
                                      : d.status === 'APPLIED_SYSTEM' ? '系统已落实，待你确认'
                                      : d.status === 'CONFIRMED' ? '已确认' : '已撤销'}
                                  </Tag>
                                </Space>
                              }
                              description={
                                <div>
                                  <div style={{ fontSize: 12 }}>{d.content}</div>
                                  {(d.evidence ?? []).map(e => <div key={e.id} style={{ padding: '6px 0' }}>
                                    <Text>第 {e.chapterIndex} 章 v{e.chapterVersion} · {e.status === 'CONFIRMED' ? '已确认此稿' : e.status === 'APPLIED_SYSTEM' ? '系统证据，待核对' : e.status === 'STALE' ? '旧稿证据' : '尚未核实'}</Text>
                                    <div>{e.quote ? `“${e.quote}”` : e.reason || '没有可定位引文'}</div>
                                    {e.status === 'APPLIED_SYSTEM' && <Button size="small" loading={directiveBusy}
                                      disabled={!currentApproveReady || e.chapterIndex !== currentChapter?.chapterIndex
                                        || e.chapterVersion !== currentBody?.version
                                        || e.contentHash !== currentReviewContext?.identity.contentHash
                                        || e.directiveRevision !== d.revision}
                                      onClick={() => void confirmEvidence(d, e)}>确认此稿证据</Button>}
                                  </div>)}
                                  {d.systemEvidence && (
                                    <div style={{ fontSize: 12, color: '#999' }}>落实证据：{d.systemEvidence}</div>
                                  )}
                                </div>
                              }
                            />
                          </List.Item>
                        )}
                      />
                    </div>
                  ),
                }]}
              />
              {/* p35-02: 候选线索面板 */}
              <Collapse
                size="small"
                style={{ marginBottom: 12 }}
                items={[{
                  key: 'candidates',
                  label: `🧵 候选线索（${candidates.filter(c => c.status === 'PENDING').length} 项待裁决）`,
                  children: (
                    <div>
                      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>
                        细节线索先入候选层；提升后成为正式伏笔（HIGH），拒绝则留痕不再提示。
                      </Text>
                      {candidates.length === 0 && <Text type="secondary">暂无候选线索。</Text>}
                      <List
                        size="small"
                        dataSource={candidates}
                        renderItem={c => (
                          <List.Item style={{ flexDirection: 'column', alignItems: 'stretch', gap: 8 }}
                            actions={
                              c.status === 'PENDING' ? [
                                <Button key="p" size="small" type="primary"
                                  onClick={() => void promoteCandidate(c.id)}>提升为伏笔</Button>,
                                <Button key="r" size="small" onClick={() => void rejectCandidate(c.id)}>拒绝</Button>,
                              ] : []
                            }
                          >
                            <List.Item.Meta
                              title={
                                <Space wrap size={4}>
                                  <Tag color={c.status === 'PROMOTED' ? 'green'
                                    : c.status === 'REJECTED' ? 'default' : 'gold'}>
                                    {c.status === 'PROMOTED' ? '已提升' : c.status === 'REJECTED' ? '已拒绝' : '待裁决'}
                                  </Tag>
                                  <Tag>第 {c.plantedAtChapter} 章</Tag>
                                </Space>
                              }
                              description={<div style={{ fontSize: 12 }}>{c.description}</div>}
                            />
                          </List.Item>
                        )}
                      />
                    </div>
                  ),
                }]}
              />
              {currentChapter?.status === 'NEEDS_MANUAL_REVIEW' && (
                <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                  message={`第 ${currentChapter.chapterIndex} 章第 v${currentChapter.version} 稿待人工复核`}
                  description="后续写作已暂停。确认本稿无需处理后，点击下方「确认已复核并批准」解除屏障并放行；如检查缺项，可先补查当前稿连续性。" />
              )}
              {!currentChapter && <Text type="secondary">暂无章节。可关闭面板后新增章节。</Text>}
              {qualityReport ? (
                <div>
                  <Typography.Title level={5} style={{ marginTop: 0 }}>
                    <TrophyOutlined /> 本章质量
                    <Tag color={qualityReport.grade === 'A' ? 'green' : qualityReport.grade === 'B' ? 'gold' : 'red'} style={{ marginLeft: 8 }}>
                      {qualityReport.grade} 级
                    </Tag>
                  </Typography.Title>
                  {/* p34-02（UI 验收 A3）：旧稿报告显式提示，不得冒充当前审查 */}
                  {qualityReport.reportCurrent === false && qualityReport.reportForVersion != null && (
                    <Alert type="warning" showIcon style={{ marginBottom: 8 }}
                      message={`旧稿报告：以下内容针对第 v${qualityReport.reportForVersion} 稿`}
                      description="当前稿已更新且尚未重新检查。该报告不代表当前稿的审查结论，保存/应用修改后会自动触发重新检查。" />
                  )}
                  {qualityReport.review && (
                    <div style={{ marginBottom: 16 }}>
                      <Text strong>综合评分 {qualityReport.review.overallScore} / 10</Text>
                      {[
                        { label: '吸引力', val: qualityReport.review.engagement },
                        { label: '连贯性', val: qualityReport.review.coherence },
                        { label: '角色塑造', val: qualityReport.review.character },
                        { label: '章末钩子', val: qualityReport.review.hook },
                      ].map(d => (
                        <div key={d.label} style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 8 }}>
                          <Text style={{ flex: '0 0 72px' }}>{d.label}</Text>
                          <Progress percent={d.val * 10} size="small" format={() => `${d.val}/10`} style={{ flex: 1, minWidth: 0, margin: 0 }} />
                        </div>
                      ))}
                    </div>
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
                            renderItem={item => {
                              const dispositionAction = item.id ? dispositionsByIssue[item.id] : undefined;
                              const dispositionLabel = dispositionAction === 'FALSE_POSITIVE' ? '已裁决：误报'
                                : dispositionAction === 'EXCEPTION' ? '已裁决：创作例外'
                                : dispositionAction === 'DEFER' ? '已裁决：暂挂' : null;
                              return (
                              <List.Item style={{ display: 'block' }}>
                                <Space wrap size={[4, 8]} style={{ marginBottom: 8, maxWidth: '100%' }}>
                                <Tag color={item.severity === 'HIGH' ? 'red' : item.severity === 'MEDIUM' ? 'orange' : 'default'}>
                                  {item.severity}
                                </Tag>
                                <Tag style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>{item.type}</Tag>
                                {dispositionLabel && <Tag color="green">{dispositionLabel}</Tag>}
                                {item.id && item.severity === 'HIGH' && !dispositionLabel && (
                                  <Button size="small" icon={<SafetyOutlined />}
                                    onClick={() => openAdjudication(item.id as string, item.description)}>裁决</Button>
                                )}
                                </Space>
                                <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', lineHeight: 1.8 }}>{item.description}</div>
                              </List.Item>
                              );
                            }}
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

            {currentChapter && ['DONE', 'NEEDS_MANUAL_REVIEW'].includes(currentChapter.status ?? '') && (
              <Button type="primary" block loading={approving} disabled={!currentApproveReady}
                aria-label={currentChapter.status === 'NEEDS_MANUAL_REVIEW' ? '确认已复核并批准' : '批准当前稿'}
                onClick={() => void approveChapterDraft(currentChapter.chapterIndex)}>
                {currentChapter.status === 'NEEDS_MANUAL_REVIEW' ? '确认已复核并批准' : '批准当前稿'}
              </Button>
            )}
            {approveBlockReason && currentChapter && (
              <Alert
                type="warning" showIcon style={{ marginTop: 8 }}
                message="批准未生效：当前稿存在阻塞项"
                description={<>
                  {approveBlockReason}
                  {renderInlineAdjudication()}
                </>
                }
              />
            )}
            {currentChapter && ['DONE', 'NEEDS_MANUAL_REVIEW'].includes(currentChapter.status ?? '') && !currentApproveReady && (
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
                批准未就绪：正文或审阅上下文尚未加载/版本未对齐
              </Text>
            )}
          </div>
        </Drawer>

        {/* 刷新按钮 */}
        <Button icon={<ReloadOutlined />} onClick={() => void refresh()} style={{ marginTop: 16 }} loading={loading}>
          刷新
        </Button>
      </Spin>

      {/* NCS-071: HIGH 问题裁决对话框——理由强制；暂挂不解除当前章批准门禁 */}
      <Modal
        title="问题裁决"
        open={adjudication != null}
        onCancel={() => setAdjudication(null)}
        footer={[
          <Button key="cancel" onClick={() => setAdjudication(null)}>取消</Button>,
          <Button key="submit" type="primary" loading={adjudicating} onClick={() => void submitAdjudication()}>
            记入处置台账
          </Button>,
        ]}
      >
        {adjudication && (
          <div>
            <Alert
              type="info" showIcon style={{ marginBottom: 12 }}
              message={`针对第 ${currentChapter?.chapterIndex ?? ''} 章第 v${currentReviewContext?.identity.version ?? currentChapter?.version ?? ''} 稿`}
              description={<div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{adjudication.description}</div>}
            />
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Select
                aria-label="裁决类型"
                style={{ width: 300 }}
                value={adjAction}
                onChange={v => setAdjAction(v)}
                options={[
                  { value: 'FALSE_POSITIVE', label: '误报——问题不成立' },
                  { value: 'EXCEPTION', label: '创作例外——有依据的保留' },
                  { value: 'DEFER', label: '暂挂——推迟到后续章处理' },
                ]}
              />
              <Input.TextArea
                aria-label="裁决理由"
                rows={3}
                placeholder="裁决必须说明理由（记入处置台账，作者可查）"
                value={adjReason}
                onChange={e => setAdjReason(e.target.value)}
              />
              {adjAction === 'DEFER' && (
                <>
                  <Space wrap>
                    <Text>暂挂到第</Text>
                    <Input
                      aria-label="暂挂目标章号"
                      type="number" min={1} style={{ width: 90 }}
                      value={adjDeferUntil ?? ''}
                      onChange={e => setAdjDeferUntil(e.target.value ? Number(e.target.value) : null)}
                    />
                    <Text>章</Text>
                  </Space>
                  <Alert type="warning" showIcon
                    message="暂挂不解除当前章的批准门禁——仅登记后续处理计划。" />
                </>
              )}
              <Text type="secondary">裁决提交后不可撤销；批准门禁按台账豁免误报与创作例外。</Text>
            </Space>
          </div>
        )}
      </Modal>

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
        {draftNotice && (
          <Alert type="info" showIcon message={draftNotice} style={{ marginBottom: 12 }} />
        )}
        {draftStorageWarning && (
          <Alert type="error" showIcon message="草稿未暂存" description={draftStorageWarning}
            style={{ marginBottom: 12 }} />
        )}
        {conflictDraftInfo && (
          <Alert
            type="warning" showIcon
            message="发现未保存草稿与当前稿基准不一致"
            description={
              <div>
                <div style={{ marginBottom: 8 }}>
                  草稿基于 v{conflictDraftInfo.draft.baseVersion}（{conflictDraftInfo.draft.savedAt.slice(11, 19)} 暂存），
                  当前稿为 v{conflictDraftInfo.currentVersion}。草稿已保留为冲突副本，不会被覆盖或静默丢弃。
                </div>
                {conflictDraftInfo.conflictCopy && (
                  <div>
                    <pre data-conflict-side="draft" style={{ whiteSpace: 'pre-wrap', maxHeight: 140, overflow: 'auto', padding: 8, background: '#f5f5f5', fontSize: 12 }}>
                      {conflictDraftInfo.conflictCopy.title}{'\n\n'}{conflictDraftInfo.conflictCopy.content}
                    </pre>
                    <Space style={{ marginTop: 8 }}>
                      <Button size="small" onClick={() => {
                        const copy = conflictDraftInfo.conflictCopy;
                        if (!copy) return;
                        const text = `${copy.title}\n\n${copy.content}`;
                        const notifyFallback = () =>
                          message.info('已尝试复制冲突草稿；若剪贴板不可用，请在下方文本框手动全选复制');
                        // 剪贴板权限被拒（如 headless）时 promise 异步 reject——必须接住，避免未处理异常
                        if (navigator.clipboard?.writeText) {
                          navigator.clipboard.writeText(text)
                            .then(() => message.success('冲突草稿已复制到剪贴板'))
                            .catch(() => notifyFallback());
                        } else {
                          notifyFallback();
                        }
                      }}>复制草稿内容</Button>
                      <Button size="small" danger onClick={() => {
                        if (chapterEditorTarget?.mode !== 'edit') return;
                        clearConflictDraft(localStorageAdapter, projectId,
                          chapterEditorTarget.chapterIndex);
                        setConflictDraftInfo(null);
                        message.info('已放弃冲突草稿副本');
                      }}>放弃冲突副本</Button>
                    </Space>
                  </div>
                )}
              </div>
            }
            style={{ marginBottom: 12 }}
          />
        )}
        <Input
          aria-label={chapterEditorTarget?.mode === 'create' ? '新增章节标题' : '章节标题'}
          value={chapterDraftTitle}
          onChange={(event) => {
            setChapterDraftTitle(event.target.value);
            // p36-09: 标题与正文同样持续暂存；失败即提示未暂存
            if (chapterEditorTarget?.mode === 'edit') {
              setDraftPersisted(saveDraft(localStorageAdapter, projectId,
                chapterEditorTarget.chapterIndex, chapterEditorTarget.expectedVersion,
                undefined, event.target.value, chapterDraftContent));
            }
          }}
          placeholder="章节标题"
          disabled={chapterSaving}
          style={{ marginBottom: 12 }}
        />
        <Input.TextArea
          aria-label={chapterEditorTarget?.mode === 'create' ? '新增章节正文' : '章节正文'}
          value={chapterDraftContent}
          onChange={(event) => {
            setChapterDraftContent(event.target.value);
            // p36-09: 编辑过程持续暂存两字段（保存成功后清除）；存储失败提示未暂存
            if (chapterEditorTarget?.mode === 'edit') {
              setDraftPersisted(saveDraft(localStorageAdapter, projectId,
                chapterEditorTarget.chapterIndex, chapterEditorTarget.expectedVersion,
                undefined, chapterDraftTitle, event.target.value));
            }
          }}
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
      {/* NCS-163: 内联裁决表单 */}
      <Modal
        title={`裁决：${inlineAdjForm ? actionText(inlineAdjForm.action) : ''}`}
        open={!!inlineAdjForm}
        confirmLoading={inlineAdjBusy}
        onOk={() => void submitInlineAdjudication()}
        onCancel={() => setInlineAdjForm(null)}
        okButtonProps={{ disabled: !inlineAdjReason.trim() || (inlineAdjForm?.action === 'DEFER' && !inlineAdjDeferUntil) }}
      >
        {inlineAdjForm && (
          <div>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
              {inlineAdjForm.card.description}
            </Typography.Paragraph>
            <Input.TextArea
              aria-label="裁决理由"
              placeholder="裁决理由（必填，记入处置台账）"
              value={inlineAdjReason}
              onChange={(e) => setInlineAdjReason(e.target.value)}
              rows={3}
            />
            {inlineAdjForm.action === 'DEFER' && (
              <div style={{ marginTop: 8 }}>
                <Typography.Text style={{ fontSize: 12 }}>暂挂至章：</Typography.Text>
                <InputNumber min={(currentChapter?.chapterIndex || 0) + 1}
                  value={inlineAdjDeferUntil}
                  onChange={(v) => setInlineAdjDeferUntil(Number(v) || 1)} />
              </div>
            )}
          </div>
        )}
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
