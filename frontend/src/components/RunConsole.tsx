import { useState, useCallback, useEffect, useRef } from 'react';
import { Steps, Button, Space, message, Alert, Card, Popconfirm, Input, Progress, Tag } from 'antd';
import { PlayCircleOutlined, FileAddOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { ProjectStatus, NovelState, Chapter, SseState } from '../types';

const STATES: NovelState[] = ['CONCEIVING', 'PLANNING', 'WRITING', 'VOLUME_DONE', 'FINAL'];
const STATE_TEXT: Record<string, string> = {
  CONCEIVING: '构思',
  PLANNING: '规划',
  WRITING: '创作中',
  VOLUME_DONE: '卷完结',
  FINAL: '完结',
  NEEDS_REVISION: '待修订',
  PAUSED_AT_CHECKPOINT: '暂停审核',
};

const SSE_TEXT: Record<SseState, { text: string; color: string }> = {
  connected: { text: '实时通道已连接', color: 'green' },
  reconnecting: { text: '实时通道重连中…', color: 'orange' },
  fallback: { text: '实时通道不可用（结果以任务轮询为准）', color: 'default' },
};

export default function RunConsole({ projectId, projectState, onChanged }: { projectId: string; projectState: NovelState; onChanged: () => void }) {
  const [running, setRunning] = useState(false);
  const [writing, setWriting] = useState(false);
  const [batchWriting, setBatchWriting] = useState(false);
  const [activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const busy = running || writing || batchWriting;
  const [lastResult, setLastResult] = useState<string | null>(null);
  // p18-03: 写前指令（作者对下一章/本次批写的定向要求）
  const [instruction, setInstruction] = useState('');
  // p18-04: SSE 实时逐章进度
  const [liveProgress, setLiveProgress] = useState<{ chapter: number; words: number; failed?: string } | null>(null);
  const [liveLog, setLiveLog] = useState<string[]>([]);
  const unsubRef = useRef<(() => void) | null>(null);
  // p21-08: SSE 连接状态展示
  const [sseState, setSseState] = useState<SseState | null>(null);
  // p21-03: 终态门禁——FINAL 禁用全部写作控制；NEEDS_REVISION 只允许修订与重审
  const isFinal = projectState === 'FINAL';
  const needsRevision = projectState === 'NEEDS_REVISION';
  const terminal = isFinal || needsRevision;
  const [auditing, setAuditing] = useState(false);

  useEffect(() => {
    return () => { unsubRef.current?.(); };
  }, []);

  const handleFinalAudit = useCallback(async () => {
    setAuditing(true);
    try {
      const r = await api.finalAudit(projectId);
      if (r.passed) {
        message.success('评审通过，全书已完结');
        setLastResult('✅ 成书门禁重审通过，项目进入 FINAL');
      } else {
        message.warning('评审未通过，请继续修订');
        setLastResult('⛔ 成书门禁重审未通过：请继续修订设定/正文后再次重审');
      }
      onChanged();
    } catch (e) {
      message.error(`重审失败: ${(e as Error).message}`);
    } finally {
      setAuditing(false);
    }
  }, [projectId, onChanged]);

  const handleRun = useCallback(async () => {
    setRunning(true);
    setLastResult(null);
    try {
      const status: ProjectStatus = await api.run(projectId);
      message.success(`已推进到：${STATE_TEXT[status.state] || status.state}`);
      const tips: Record<string, string> = {
        PLANNING: '\n💡 设定已生成！切换到「世界观与人物」「大纲」Tab 查看',
        WRITING: '\n💡 现在可以点「写一章」生成正文',
        PAUSED_AT_CHECKPOINT: '\n💡 交互模式：设定已生成，已暂停等待审核',
      };
      setLastResult(`当前状态：${STATE_TEXT[status.state] || status.state} | 已写 ${status.currentChapter} 章${tips[status.state] || ''}`);
      onChanged();
    } catch (e) {
      message.error(`运行失败: ${(e as Error).message}`);
    } finally {
      setRunning(false);
    }
  }, [projectId, onChanged, instruction]);

  const handleWriteChapter = useCallback(async () => {
    setWriting(true);
    setLastResult(null);
    try {
      const chapter: Chapter = await api.writeChapter(projectId, instruction.trim() || undefined);
      if (chapter) {
        message.success(`第${chapter.chapterIndex}章「${chapter.title}」已完成（${chapter.wordCount}字）`);
        setLastResult(`新章节：第${chapter.chapterIndex}章「${chapter.title}」（${chapter.wordCount}字）\n💡 切换到「章节阅读」Tab 查看正文`);
      } else {
        message.info('当前卷已写完，无更多章节');
        setLastResult('当前卷已写完');
      }
      onChanged();
    } catch (e) {
      message.error(`写作失败: ${(e as Error).message}`);
    } finally {
      setWriting(false);
    }
  }, [projectId, onChanged]);

  const handleWriteAll = useCallback(async () => {
    setBatchWriting(true);
    setLastResult(null);
    setLiveProgress(null);
    setLiveLog([]);
    // p18-04: SSE 实时进度（不支持时静默降级到轮询）
    unsubRef.current?.();
    unsubRef.current = api.subscribeEvents(projectId, (name, data) => {
      if (name === 'CHAPTER_WRITTEN') {
        setLiveProgress({ chapter: data.chapterIndex ?? data.chapter ?? 0, words: data.wordCount ?? 0 });
        setLiveLog((prev) => [...prev.slice(-6), `✅ 第${data.chapterIndex ?? '?'}章完成（${data.wordCount ?? '?'} 字）`]);
      } else if (name === 'CHAPTER_FAILED') {
        setLiveLog((prev) => [...prev.slice(-6), `⚠️ 第${data.chapterIndex ?? '?'}章失败：${(data.errorClass || '') + ' ' + (data.message || '').slice(0, 60)}`]);
      } else if (name === 'QUALITY_CIRCUIT_BREAKER') {
        setLiveLog((prev) => [...prev.slice(-6), `🛑 质量熔断：连续 ${data.consecutiveHighIssues ?? '?'} 章 HIGH`]);
      }
    }, (state) => setSseState(state));
    try {
      const result = await api.writeAll(projectId, 50, setActiveTaskId, instruction.trim() || undefined);
      const words = result.totalWords;
      message.success(`连续写作完成！共写 ${result.chaptersWritten} 章，当前第 ${result.currentChapter} 章 ${words} 字`);
      setLastResult(
        `✅ 连续写作${result.status === 'CANCELLED' ? '已取消' : '完成'}\n` +
        `本次新写：${result.chaptersWritten} 章\n` +
        `当前章节：${result.currentChapter} 章\n` +
        `总字数：${words.toLocaleString()} 字\n` +
        `任务状态：${result.status}\n` +
        `💡 切换到「章节管理」Tab 查看正文`,
      );
      onChanged();
    } catch (e) {
      message.error(`连续写作失败: ${(e as Error).message}`);
    } finally {
      setActiveTaskId(null);
      setBatchWriting(false);
      unsubRef.current?.();
      unsubRef.current = null;
    }
  }, [projectId, onChanged, instruction]);

  return (
    <Card>
      <Steps
        size="small"
        current={projectState === 'FINAL'
          ? STATES.length  // 全部完成
          : projectState === 'PAUSED_AT_CHECKPOINT'
            ? STATES.indexOf('PLANNING')
            : STATES.indexOf(projectState) >= 0
              ? STATES.indexOf(projectState)
              : 0}
        items={STATES.map((s) => ({ title: STATE_TEXT[s] }))}
        style={{ marginBottom: 24 }}
      />

      <Alert
        message="操作说明"
        description={
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            <li><b>推进</b>：从当前状态前进一步（构思→规划→写作）。首次运行会生成世界观/人物/大纲。</li>
            <li><b>写一章</b>：在写作阶段，生成下一章正文。每次调用 LLM，可能耗时 30-60 秒。</li>
            <li>交互模式下，运行后会在检查点暂停，等待你确认。</li>
          </ul>
        }
        type="info"
        style={{ marginBottom: 24 }}
      />

      {/* p21-03: 终态门禁——服务端强制 + 前端同步禁用 */}
      {isFinal && (
        <Alert
          type="success"
          message="✅ 全书已完结（FINAL）"
          description="全书成书门禁已通过。写作命令已停用；如需修改请重写单章（章节管理 Tab）。"
          style={{ marginBottom: 24 }}
        />
      )}
      {needsRevision && (
        <Alert
          type="error"
          message="⛔ 成书门禁未通过（NEEDS_REVISION）"
          description="存在未达标的硬性门禁（如 mustResolve 伏笔未回收、近 60 章 HIGH 级矛盾超限）。请修订设定或正文，然后点击「重新评审」再次触发成书门禁。"
          style={{ marginBottom: 24 }}
          action={
            <Button type="primary" danger loading={auditing} onClick={handleFinalAudit} disabled={busy}>
              重新评审
            </Button>
          }
        />
      )}

      <div style={{ marginBottom: 12 }}>
            <Input.TextArea
              rows={1}
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              placeholder="写前指令（可选）：对本章/本次连续写作的定向要求，如『本章重点写主角与师父的诀别』"
              maxLength={200}
              style={{ marginBottom: 8 }}
            />
            {batchWriting && liveProgress && (
              <div>
                <Progress
                  percent={Math.min(99, Math.round((liveProgress.chapter % 50) / 50 * 100))}
                  size="small"
                  status="active"
                  format={() => `已写到第 ${liveProgress.chapter} 章（${liveProgress.words?.toLocaleString?.() ?? liveProgress.words} 字）`}
                />
                {liveLog.map((line, i) => (
                  <div key={i} style={{ fontSize: 12, color: '#666' }}>{line}</div>
                ))}
              </div>
            )}
          </div>
      <Space size="large" wrap align="center">
        <Button
          type="primary"
          size="large"
          icon={<PlayCircleOutlined />}
          loading={running}
          disabled={busy || terminal}
          onClick={handleRun}
        >
          {running ? '运行中（调 LLM）…' : '推进状态'}
        </Button>
        <Button
          size="large"
          icon={<FileAddOutlined />}
          loading={writing}
          disabled={busy || terminal}
          onClick={handleWriteChapter}
        >
          {writing ? '写作中（调 LLM）…' : '写一章'}
        </Button>
        <Popconfirm
          title="连续写作"
          description="将连续写作最多 50 章（或到目标章数），可能耗时数十分钟。确定开始？"
          onConfirm={handleWriteAll}
          okText="开始连续写作"
          cancelText="取消"
          disabled={busy || terminal}
        >
          <Button
            danger
            size="large"
            icon={<ThunderboltOutlined />}
            loading={batchWriting}
            disabled={busy || terminal}
          >
            {batchWriting ? '连续写作中（每章 1-2 分钟）…' : '⚡ 连续写作（最多 50 章）'}
          </Button>
        </Popconfirm>
        {batchWriting && activeTaskId && (
          <Button danger onClick={async () => {
            try {
              await api.cancelWritingTask(projectId, activeTaskId);
              message.info('已发送取消');
            } catch (e) {
              message.error((e as Error).message);
            }
          }}>取消连续写作</Button>
        )}
        {/* p21-08: SSE 连接状态 */}
        {batchWriting && sseState && (
          <Tag color={SSE_TEXT[sseState].color} aria-live="polite">{SSE_TEXT[sseState].text}</Tag>
        )}
      </Space>

      {lastResult && (
        <Alert
          message="上次操作结果"
          description={lastResult}
          type="success"
          style={{ marginTop: 24 }}
        />
      )}
    </Card>
  );
}
