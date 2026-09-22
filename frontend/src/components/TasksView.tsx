import { useEffect, useState } from 'react';
import { Alert, Button, Card, Modal, Space, Table, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { PersistedWritingTask } from '../api/client';
import type { Chapter, ChapterPage, ChapterVersion, CollaborationStatus } from '../types';
import EditionsPanel from './EditionsPanel';

const statusLabels: Record<PersistedWritingTask['status'], string> = {
  RUNNING: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已停止',
};
const typeLabels: Record<PersistedWritingTask['type'], string> = {
  CONCEIVE: '构思作品', WRITE_CHAPTER: '写作单章', WRITE_ALL: '连续写作', AUTO_RUN: '全书协作',
};
const dateText = (value: string) => new Date(value).toLocaleString();

export default function TasksView({ projectId, onChanged }: { projectId: string; onChanged: () => void }) {
  const navigate = useNavigate();
  const [tasks, setTasks] = useState<PersistedWritingTask[]>([]);
  const [status, setStatus] = useState<CollaborationStatus | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [chapterPage, setChapterPage] = useState<ChapterPage | null>(null);
  const [page, setPage] = useState(1);
  const [chapterError, setChapterError] = useState('');
  const [chapterLoading, setChapterLoading] = useState(true);
  const [selected, setSelected] = useState<Chapter | null>(null);
  const [versions, setVersions] = useState<ChapterVersion[]>([]);
  const [versionError, setVersionError] = useState('');
  const [versionLoading, setVersionLoading] = useState(false);

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      try {
        const [history, current] = await Promise.all([api.listWritingTasks(projectId), api.collaboration(projectId)]);
        if (active) { setTasks(history); setStatus(current); setError(''); }
      } catch (e) {
        if (active) { setError(e instanceof Error ? e.message : '读取任务失败'); setStatus(null); }
      } finally {
        if (active) { setLoading(false); timer = setTimeout(load, 3000); }
      }
    };
    void load();
    return () => { active = false; clearTimeout(timer); };
  }, [projectId, refresh]);

  useEffect(() => {
    let active = true;
    setChapterLoading(true);
    void api.getChapterPage(projectId, page - 1, 10).then(result => {
      if (active) { setChapterPage(result); setChapterError(''); }
    }).catch(e => {
      if (active) setChapterError(e instanceof Error ? e.message : '读取章节失败');
    }).finally(() => { if (active) setChapterLoading(false); });
    return () => { active = false; };
  }, [projectId, page, refresh]);

  useEffect(() => {
    if (!selected) return;
    let active = true;
    setVersions([]); setVersionError(''); setVersionLoading(true);
    void api.getChapterVersions(projectId, selected.chapterIndex).then(result => {
      if (active) setVersions(result);
    }).catch(e => {
      if (active) setVersionError(e instanceof Error ? e.message : '读取历史版本失败');
    }).finally(() => { if (active) setVersionLoading(false); });
    return () => { active = false; };
  }, [projectId, selected]);

  const act = async (stop: boolean) => {
    setBusy(true);
    try {
      if (stop) await api.stopCollaboration(projectId);
      else await api.startCollaboration(projectId);
      setError(''); setStatus(null); setRefresh(n => n + 1); onChanged();
    } catch (e) { setError(e instanceof Error ? e.message : '操作失败'); }
    finally { setBusy(false); }
  };
  // NCS-073: 终止暂停任务——PAUSED→CANCELLED 并释放项目工作权（NCS-072 后端语义）
  const [terminating, setTerminating] = useState<string | null>(null);
  const terminate = async (taskId: string) => {
    setTerminating(taskId);
    try {
      await api.cancelWritingTask(projectId, taskId);
      setError(''); setStatus(null); setRefresh(n => n + 1); onChanged();
    } catch (e) { setError(e instanceof Error ? e.message : '终止失败'); }
    finally { setTerminating(null); }
  };
  const openChapter = (chapterIndex: number, version?: number) => {
    navigate(`/project/${projectId}/chapter/${chapterIndex}${version == null ? '' : `?version=${version}`}`);
  };

  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Card title="任务记录" extra={<Button onClick={() => setRefresh(n => n + 1)}>刷新记录</Button>}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} />}
        <Space wrap>
          <Button type="primary" loading={busy} disabled={!status?.canContinue || !!error}
            onClick={() => void act(false)}>从当前进度继续</Button>
          <Button danger loading={busy} disabled={!status?.busy || status.stopRequested || !!error}
            onClick={() => void act(true)}>{status?.stopRequested ? '正在停止' : '停止当前任务'}</Button>
          {status?.blockedReason && <Typography.Text type="secondary">{status.blockedReason}</Typography.Text>}
        </Space>
        <Typography.Text type="secondary">继续会从作品当前进度新建协作任务，保留已有任务记录。费用未按任务记录时显示“未记录”。</Typography.Text>
        <Table<PersistedWritingTask> rowKey="id" size="small" loading={loading} dataSource={tasks}
          pagination={{ pageSize: 10 }} scroll={{ x: 1050 }} locale={{ emptyText: '尚无任务记录' }} columns={[
            { title: '目标', width: 230, render: (_, task) => <Space direction="vertical" size={0}>
              <Typography.Text strong>{typeLabels[task.type]}</Typography.Text>
              <Typography.Text>{task.instruction || '未附加创作要求'}</Typography.Text>
              <Typography.Text type="secondary">{task.id.slice(0, 8)}</Typography.Text>
            </Space> },
            { title: '状态', width: 100, render: (_, task) => <Tag color={task.status === 'COMPLETED' ? 'green' : task.status === 'FAILED' ? 'red' : 'blue'}>
              {task.status === 'RUNNING' && task.stopRequested ? '正在停止' : statusLabels[task.status]}</Tag> },
            { title: '进度', width: 200, render: (_, task) => <Space direction="vertical" size={0}>
              <span>本任务已写 {task.chaptersWritten} 章 · {task.totalWords} 字</span>
              <span>当前第 {task.currentChapter} 章 · 目标 {task.totalChapters > 0 ? `${task.totalChapters} 章` : '未指定'}</span>
            </Space> },
            { title: '等待 / 结束原因', width: 220, render: (_, task) => task.errorMessage ||
              (task.stopRequested ? '作者请求停止' : status?.task?.id === task.id && !status.busy && status.blockedReason
                ? status.blockedReason : '未记录') },
            { title: '费用', width: 90, render: () => '未记录' },
            { title: '更新时间', dataIndex: 'updatedAt', render: dateText },
            // NCS-073: PAUSED 任务占坑死锁的界面出路——显式终止（后端终态转移并释放工作权）
            { title: '操作', width: 110, render: (_, task) => task.status === 'PAUSED' ? (
              <Button size="small" danger loading={terminating === task.id}
                onClick={() => void terminate(task.id)}>终止任务</Button>
            ) : null },
          ]} />
      </Space>
    </Card>
    <Card title="章稿版本">
      {chapterError && <Alert type="error" showIcon message={chapterError} />}
      <Table<Chapter> rowKey="chapterIndex" size="small" loading={chapterLoading}
        dataSource={chapterPage?.chapters ?? []} locale={{ emptyText: '尚无章稿' }}
        pagination={{ current: page, pageSize: 10, total: chapterPage?.total ?? 0, showSizeChanger: false, onChange: setPage }} columns={[
          { title: '章节', render: (_, chapter) => `第 ${chapter.chapterIndex} 章 · ${chapter.title}` },
          { title: '当前稿', dataIndex: 'version', render: version => `第 ${version} 稿` },
          { title: '操作', render: (_, chapter) => <Space>
            <Button size="small" onClick={() => openChapter(chapter.chapterIndex)}>阅读当前稿</Button>
            <Button size="small" onClick={() => setSelected(chapter)}>章稿历史</Button>
          </Space> },
        ]} />
    </Card>
    <EditionsPanel projectId={projectId} />
    <Modal open={!!selected} title={selected ? `第 ${selected.chapterIndex} 章 · 历史章稿` : ''}
      footer={null} onCancel={() => setSelected(null)} width={720}>
      {versionError && <Alert type="error" showIcon message={versionError} />}
      <Table<ChapterVersion> rowKey="id" size="small" loading={versionLoading} dataSource={versions}
        locale={{ emptyText: '此章尚无历史章稿' }} columns={[
          { title: '版本', dataIndex: 'version', render: version => `第 ${version} 稿` },
          { title: '标题', dataIndex: 'title' },
          { title: '记录时间', dataIndex: 'createdAt', render: dateText },
          { title: '操作', render: (_, version) => <Button size="small"
            onClick={() => openChapter(version.chapterIndex, version.version)}>只读查看</Button> },
        ]} />
    </Modal>
  </Space>;
}
