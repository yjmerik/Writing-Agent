import { useEffect, useRef, useState } from 'react';
import { Space, Tag, Tooltip, Typography } from 'antd';
import { FileTextOutlined, LoadingOutlined, PauseCircleOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { WorkspaceView } from '../types';

const { Text } = Typography;

/**
 * NCS-006（方案 §03/§15/§17）：稿件身份栏 + 任务条。
 * 数据来自 workspace-view 单一读取源：当前对象与稿号、保存/核对状态、
 * 正在执行的动作与等待原因、需作者关注计数、动作可用性与原因。
 * 后台数据刷新不覆盖未提交输入（本组件只读展示）。
 */

const STATE_LABELS: Record<string, string> = {
  CONCEIVING: '构思中', PLANNING: '规划中', WRITING: '创作中', VOLUME_DONE: '卷完成',
  FINAL: '已定稿', NEEDS_REVISION: '待修订', PAUSED_AT_CHECKPOINT: '待审核',
};

const CHAPTER_STATUS_LABELS: Record<string, string> = {
  DRAFTING: '草稿', POST_PROCESSING: '核对中', DONE: '已完成', NEEDS_MANUAL_REVIEW: '待人工复核',
};

const ACTION_LABELS: Record<string, string> = {
  ADVANCE: '推进阶段', WRITE_CHAPTER: '写下一章', WRITE_ALL: '连续写作', FINAL_AUDIT: '重新终审',
};

export default function IdentityBar({ projectId, onOpenCandidates }: {
  projectId: string;
  /** NCS-176: 候选计数一键直达——携带首个候选章节索引打开对应审阅面板 */
  onOpenCandidates?: (chapterIndex?: number) => void;
}) {
  const [view, setView] = useState<WorkspaceView | null>(null);
  const requestRef = useRef(0);

  useEffect(() => {
    let stopped = false;
    const load = () => {
      const requestId = ++requestRef.current;
      void api.getWorkspaceView(projectId).then(v => {
        if (!stopped && requestRef.current === requestId) setView(v);
      }).catch(() => { /* 单一读取源失败保持上一帧，不打断阅读 */ });
    };
    load();
    const timer = window.setInterval(load, 2000);
    return () => { stopped = true; window.clearInterval(timer); };
  }, [projectId]);

  if (!view) {
    return <div style={{ padding: '4px 8px', fontSize: 12, color: '#999' }}>正在读取工作台状态…</div>;
  }

  const current = view.contentStatus.currentChapter;
  const run = view.run;
  const running = run?.status === 'RUNNING';
  // NCS-085（听雪刀 P2-2）：任务未结束但章节已暂停待人审——如实显示暂停态，
  // 不再误报「协作写作进行中」误导轮询判断
  const pausedForReview = running && !run?.stopRequested
    && (view.review.activeBarriers > 0 || view.review.pendingCheckpoints > 0);
  const blockedNext = view.availableActions.find(a => a.action === 'WRITE_CHAPTER' && !a.allowed);
  const allowedNext = view.availableActions.filter(a => a.allowed);

  return (
    <div style={{
      display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center',
      padding: '6px 12px', background: '#fafaf8', borderBottom: '1px solid #eee',
      fontSize: 12, position: 'sticky', top: 0, zIndex: 5,
    }}>
      {/* 稿件身份：当前对象 + 稿号 + 状态 */}
      <Space size={6}>
        <FileTextOutlined style={{ color: '#666' }} />
        {current ? (
          <>
            <Text strong style={{ fontSize: 12 }}>第 {current.index} 章 · {current.title}</Text>
            <Tag style={{ fontSize: 11 }}>第 {current.version} 稿</Tag>
            <Tag color={current.status === 'DONE' ? 'green' : current.status === 'NEEDS_MANUAL_REVIEW' ? 'orange' : 'blue'}
                 style={{ fontSize: 11 }}>
              {CHAPTER_STATUS_LABELS[current.status] ?? current.status}
            </Tag>
          </>
        ) : (
          <Text type="secondary" style={{ fontSize: 12 }}>尚无正文——可先构思或直接建立约定</Text>
        )}
        {view.contentStatus.pendingCandidates > 0 && (
          <Tooltip title="有待采纳的 Agent 修改候选——点击打开对应章节审阅面板（候选线索在「审 查」抽屉内裁决）">
            <Tag color="purple" style={{ fontSize: 11, cursor: 'pointer' }}
              onClick={() => onOpenCandidates?.(view.contentStatus.candidateChapters?.[0])}>
              {view.contentStatus.pendingCandidates} 个待采纳候选 →
            </Tag>
          </Tooltip>
        )}
      </Space>

      {/* 任务条：正在执行/等待原因 */}
      <Space size={6} style={{ flex: 1, justifyContent: 'center' }}>
        {pausedForReview && (
          <><PauseCircleOutlined style={{ color: '#fa8c16' }} />
            <Text type="warning" style={{ fontSize: 12 }}>
              已暂停待人审（待处理 {view.review.pendingCheckpoints + view.review.activeBarriers} 项；批准后写作自动继续）
            </Text></>
        )}
        {running && !run?.stopRequested && !pausedForReview && (
          <><LoadingOutlined style={{ color: '#1677ff' }} />
            <Text style={{ fontSize: 12 }}>{run?.type === 'AUTO_RUN' ? '协作写作进行中' : '写作任务进行中'}（已写 {run?.chaptersWritten ?? 0} 章）</Text></>
        )}
        {running && run?.stopRequested && (
          <><PauseCircleOutlined style={{ color: '#faad14' }} />
            <Text type="warning" style={{ fontSize: 12 }}>正在停下，尚未确认；已写内容会保留</Text></>
        )}
        {!running && run && run.errorMessage && (
          <Tooltip title={run.errorMessage}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              上次任务：{run.status === 'PAUSED' ? '已暂停' : run.status === 'FAILED' ? '失败' : run.status === 'CANCELLED' ? '已取消' : '已完成'}
              （{run.errorMessage.slice(0, 60)}{run.errorMessage.length > 60 ? '…' : ''}）
            </Text>
          </Tooltip>
        )}
        {!running && !run?.errorMessage && (
          <Text type="secondary" style={{ fontSize: 12 }}>无进行中任务</Text>
        )}
      </Space>

      {/* 审阅计数 + 动作可用性 */}
      <Space size={6}>
        {view.review.activeBarriers > 0 && <Tag color="orange" style={{ fontSize: 11 }}>需处理 {view.review.activeBarriers}</Tag>}
        {view.review.pendingCheckpoints > 0 && <Tag color="blue" style={{ fontSize: 11 }}>待审 {view.review.pendingCheckpoints}</Tag>}
        {view.review.unknownChecks > 0 && <Tag color="gold" style={{ fontSize: 11 }}>未查清 {view.review.unknownChecks}</Tag>}
        {view.review.staleIssues > 0 && <Tag style={{ fontSize: 11 }}>过期检查 {view.review.staleIssues}</Tag>}
        <Tag style={{ fontSize: 11 }}>{STATE_LABELS[view.identity.state] ?? view.identity.state}</Tag>
        {allowedNext.length > 0 && (
          <Tooltip title={allowedNext.map(a => ACTION_LABELS[a.action] ?? a.action).join(' / ')}>
            <Tag color="green" style={{ fontSize: 11 }}>可继续</Tag>
          </Tooltip>
        )}
        {blockedNext && (
          <Tooltip title={`写下一章不可用：${blockedNext.reason || '未知原因'}`}>
            <Tag color="default" style={{ fontSize: 11, cursor: 'help' }}>写作受阻</Tag>
          </Tooltip>
        )}
      </Space>
    </div>
  );
}
