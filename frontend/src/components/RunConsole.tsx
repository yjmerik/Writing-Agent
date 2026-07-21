import { useState, useCallback } from 'react';
import { Steps, Button, Space, message, Alert, Card, Popconfirm } from 'antd';
import { PlayCircleOutlined, FileAddOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { ProjectStatus, NovelState, Chapter } from '../types';

const STATES: NovelState[] = ['CONCEIVING', 'PLANNING', 'WRITING', 'VOLUME_DONE', 'FINAL'];
const STATE_TEXT: Record<string, string> = {
  CONCEIVING: '构思',
  PLANNING: '规划',
  WRITING: '创作中',
  VOLUME_DONE: '卷完结',
  FINAL: '完结',
  PAUSED_AT_CHECKPOINT: '暂停审核',
};

export default function RunConsole({ projectId, projectState, onChanged }: { projectId: string; projectState: NovelState; onChanged: () => void }) {
  const [running, setRunning] = useState(false);
  const [writing, setWriting] = useState(false);
  const [batchWriting, setBatchWriting] = useState(false);
  const busy = running || writing || batchWriting;
  const [lastResult, setLastResult] = useState<string | null>(null);

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
  }, [projectId, onChanged]);

  const handleWriteChapter = useCallback(async () => {
    setWriting(true);
    setLastResult(null);
    try {
      const chapter: Chapter = await api.writeChapter(projectId);
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
    try {
      const result = await api.writeAll(projectId);
      const words = result.totalWords;
      message.success(`连续写作完成！共写 ${result.chaptersWritten} 章，全书 ${result.currentChapter} 章 ${words} 字`);
      setLastResult(
        `✅ 连续写作完成\n` +
        `本次新写：${result.chaptersWritten} 章\n` +
        `全书章节：${result.currentChapter} 章\n` +
        `总字数：${words.toLocaleString()} 字\n` +
        `状态：${result.state === 'FINAL' ? '✅ 已完结' : result.state}\n` +
        `💡 切换到「章节阅读」Tab 查看全部正文`,
      );
      onChanged();
    } catch (e) {
      message.error(`连续写作失败: ${(e as Error).message}`);
    } finally {
      setBatchWriting(false);
    }
  }, [projectId, onChanged]);

  return (
    <Card>
      <Steps
        size="small"
        current={projectState === 'PAUSED_AT_CHECKPOINT'
          ? STATES.indexOf('PLANNING')  // 暂停时显示到规划步骤
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

      <Space size="large" wrap>
        <Button
          type="primary"
          size="large"
          icon={<PlayCircleOutlined />}
          loading={running}
          disabled={busy}
          onClick={handleRun}
        >
          {running ? '运行中（调 LLM）…' : '推进状态'}
        </Button>
        <Button
          size="large"
          icon={<FileAddOutlined />}
          loading={writing}
          disabled={busy}
          onClick={handleWriteChapter}
        >
          {writing ? '写作中（调 LLM）…' : '写一章'}
        </Button>
        <Popconfirm
          title="连续写作"
          description="将自动写完所有章节，可能耗时数十分钟。确定开始？"
          onConfirm={handleWriteAll}
          okText="开始连续写作"
          cancelText="取消"
          disabled={busy}
        >
          <Button
            danger
            size="large"
            icon={<ThunderboltOutlined />}
            loading={batchWriting}
            disabled={busy}
          >
            {batchWriting ? '连续写作中（每章 1-2 分钟）…' : '⚡ 连续写作（写完全书）'}
          </Button>
        </Popconfirm>
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
