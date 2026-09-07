import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Input, Select, Space, Tag, Typography } from 'antd';
import { api } from '../api/client';
import type { CollaborationStatus, ReviewPolicy } from '../types';

const STAGES: Record<string, string> = {
  CONCEIVING: '搭建世界、人物与全书框架', PLANNING: '准备章节计划',
  WRITING: '撰写与检查正文', VOLUME_DONE: '准备下一卷',
  FINAL: '全书已完成', NEEDS_REVISION: '终审待修订', PAUSED_AT_CHECKPOINT: '等待作者审阅',
};

export default function CollaborationBar({ projectId, onChanged, onReview }: {
  projectId: string; onChanged: () => void; onReview: () => void;
}) {
  const [status, setStatus] = useState<CollaborationStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);
  const [instruction, setInstruction] = useState('');
  const [dirty, setDirty] = useState(false);
  const [retry, setRetry] = useState(0);
  const changedRef = useRef(onChanged);
  changedRef.current = onChanged;
  const mountedRef = useRef(true);
  const refreshRef = useRef(0);

  useEffect(() => {
    mountedRef.current = true;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    let previous = '';
    setStatus(null);
    const poll = async () => {
      const sequence = ++refreshRef.current;
      try {
        const next = await api.collaboration(projectId);
        if (!active || sequence !== refreshRef.current) return;
        setStatus(next); setError(null);
        const signature = `${next.state}:${next.currentChapter}:${next.busy}:${next.pendingReviews}`;
        if (previous && previous !== signature) changedRef.current();
        previous = signature;
      } catch (e) {
        if (active && sequence === refreshRef.current) setError((e as Error).message);
      } finally {
        if (active) timer = setTimeout(poll, 2000);
      }
    };
    void poll();
    return () => { active = false; mountedRef.current = false; clearTimeout(timer); };
  }, [projectId, retry]);

  useEffect(() => {
    if (!dirty) setInstruction(status?.task?.instruction ?? '');
  }, [status?.task?.instruction, dirty]);

  const act = async (work: () => Promise<unknown>, clearDirty = false) => {
    setActing(true); setError(null);
    ++refreshRef.current;
    try {
      await work();
      const next = await api.collaboration(projectId);
      if (!mountedRef.current) return;
      setStatus(next);
      if (clearDirty) setDirty(false);
      changedRef.current();
    } catch (e) {
      if (mountedRef.current) setError((e as Error).message);
    } finally {
      if (mountedRef.current) setActing(false);
    }
  };

  const busy = status?.busy ?? false;
  const stopped = !busy && status?.state !== 'FINAL' && status?.task?.status === 'PAUSED';
  const failed = !busy && status?.state !== 'FINAL' && status?.task?.status === 'FAILED';
  const label = busy ? status?.stopRequested ? '正在停下' : 'Agent 正在创作'
    : status?.state === 'FINAL' ? '全书已完成'
    : status?.pendingReviews ? '等待你审阅'
    : failed ? '创作遇到问题' : stopped ? '已交给作者' : '准备协作';

  return (
    <Card data-collaboration-bar style={{ marginBottom: 16 }} size="small">
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <div style={{ display: 'flex', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
          <div aria-live="polite">
            <Typography.Text strong style={{ fontSize: 17 }}>一起完成这本书</Typography.Text>
            <Tag style={{ marginLeft: 12 }} color={failed ? 'error' : busy ? 'processing' : 'green'}>{label}</Tag>
            <div style={{ marginTop: 6, color: '#666' }}>
              {status ? `${STAGES[status.state]} · 已写 ${status.currentChapter} / 目标 ${status.targetChapters} 章 · ${status.totalWords.toLocaleString()} 字` : '正在读取创作进度…'}
            </div>
          </div>
          <Space wrap>
            {busy ? (
              <Button data-collaboration-stop danger loading={acting} disabled={status?.stopRequested || !status?.task}
                onClick={() => void act(() => api.stopCollaboration(projectId))}>
                {!status?.task ? '等待内容处理完成' : status.stopRequested ? '当前步骤结束后交给你' : '我要接手编辑'}
              </Button>
            ) : (
              <Button data-collaboration-start type="primary" loading={acting}
                disabled={!status?.canContinue || !!error}
                onClick={() => void act(() => api.startCollaboration(projectId, dirty ? instruction : undefined), true)}>
                {status?.task ? '继续协作' : '开始协作'}
              </Button>
            )}
            <Button onClick={onReview}>审阅内容{status?.pendingReviews ? `（${status.pendingReviews} 项待审核）` : ''}</Button>
          </Space>
        </div>
        <div style={{ color: '#666', fontSize: 13 }}>
          {busy ? status?.stopRequested
            ? '正在完成当前创作步骤并保存内容。交接完成后即可保存修改，无需切换工作模式。'
            : 'Agent 会自动搭建框架、撰写正文并检查质量。你可以阅读已有内容，随时接手修改。'
            : '你可以直接编辑世界观、人物、大纲和正文，也可以让 Agent 按要求修改。审阅完成后继续协作。'}
        </div>
        {!busy && status?.blockedReason && <Alert type="warning" showIcon message={status.blockedReason} />}
        {(failed || stopped) && status?.task?.errorMessage && <Alert type={failed ? 'error' : 'info'} message={status.task.errorMessage} />}
        {error && <Alert type="error" message="协作状态或操作未完成" description={error}
          action={<Button onClick={() => setRetry(v => v + 1)}>重试读取</Button>} />}
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
          <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>审核节奏
            <Select<ReviewPolicy> aria-label="审核节奏" value={status?.reviewPolicy} disabled={busy || acting || !status || !!error}
              style={{ width: 160 }} onChange={policy => void act(() => api.setReviewPolicy(projectId, policy))}
              options={[{ value: 'MANUAL', label: '仅主动接手' }, { value: 'CHAPTER', label: '每章审阅' }, { value: 'VOLUME', label: '卷末审阅' }]} />
          </label>
          <Input.TextArea aria-label="创作要求" placeholder="给 Agent 的创作要求（继续时生效，留空可清除）"
            value={instruction} onChange={e => { setInstruction(e.target.value); setDirty(true); }}
            disabled={acting} autoSize={{ minRows: 1, maxRows: 4 }} maxLength={2000} style={{ flex: '1 1 280px', minWidth: 0 }} />
        </div>
      </Space>
    </Card>
  );
}
