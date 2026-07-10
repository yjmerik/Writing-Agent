import { useState, useCallback } from 'react';
import { Steps, Button, Space, message, Alert, Card } from 'antd';
import { PlayCircleOutlined, FileAddOutlined } from '@ant-design/icons';
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

export default function RunConsole({ projectId, onChanged }: { projectId: string; onChanged: () => void }) {
  const [running, setRunning] = useState(false);
  const [writing, setWriting] = useState(false);
  const [lastResult, setLastResult] = useState<string | null>(null);

  const handleRun = useCallback(async () => {
    setRunning(true);
    setLastResult(null);
    try {
      const status: ProjectStatus = await api.run(projectId);
      message.success(`已推进到：${STATE_TEXT[status.state] || status.state}`);
      setLastResult(`当前状态：${STATE_TEXT[status.state] || status.state} | 已写 ${status.currentChapter} 章`);
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
        setLastResult(`新章节：第${chapter.chapterIndex}章「${chapter.title}」（${chapter.wordCount}字）`);
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

  return (
    <Card>
      <Steps
        size="small"
        current={STATES.indexOf('FINAL')}
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

      <Space size="large">
        <Button
          type="primary"
          size="large"
          icon={<PlayCircleOutlined />}
          loading={running}
          onClick={handleRun}
        >
          {running ? '运行中（调 LLM）…' : '推进状态'}
        </Button>
        <Button
          size="large"
          icon={<FileAddOutlined />}
          loading={writing}
          onClick={handleWriteChapter}
        >
          {writing ? '写作中（调 LLM）…' : '写一章'}
        </Button>
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
