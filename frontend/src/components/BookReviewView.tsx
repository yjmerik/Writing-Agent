import { useEffect, useState } from 'react';
import { Alert, Button, Card, List, Space, Spin, Tag, Typography } from 'antd';
import { api } from '../api/client';
import EditionsPanel from './EditionsPanel';
import type { VolumeReadbackItem } from '../types';
import type { BookReviewData } from '../api/client';

const { Text } = Typography;

/** p35-05（设计 5.7 第一版）：成书审查覆盖清单与导出。 */
export default function BookReviewView({ projectId }: { projectId: string }) {
  // NCS-020: 卷末回读
  const [readbacks, setReadbacks] = useState<VolumeReadbackItem[]>([]);
  const [readbackBusy, setReadbackBusy] = useState(false);
  useEffect(() => {
    void api.listVolumeReadbacks(projectId).then(r => setReadbacks(r.readbacks ?? [])).catch(() => {});
  }, [projectId]);
  const handleGenerateReadback = async (volume: number) => {
    setReadbackBusy(true);
    try {
      const r = await api.generateVolumeReadback(projectId, volume);
      if (r.generated) {
        const list = await api.listVolumeReadbacks(projectId);
        setReadbacks(list.readbacks ?? []);
      } else { window.alert('生成未完成（输出不可解析），可重试'); }
    } catch (e) { window.alert(e instanceof Error ? e.message : '生成失败'); }
    finally { setReadbackBusy(false); }
  };
  const handleAckReadback = async (volume: number) => {
    setReadbackBusy(true);
    try {
      await api.acknowledgeVolumeReadback(projectId, volume);
      const list = await api.listVolumeReadbacks(projectId);
      setReadbacks(list.readbacks ?? []);
    } finally { setReadbackBusy(false); }
  };

  const [data, setData] = useState<BookReviewData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    api.getBookReview(projectId)
      .then(d => { if (active) { setData(d); setError(''); } })
      .catch(e => { if (active) setError((e as Error).message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [projectId]);

  if (loading) return <Spin style={{ display: 'block', margin: '48px auto' }} />;
  if (error) return <Alert type="error" showIcon message="覆盖清单加载失败" description={error} />;
  if (!data) return <Alert type="warning" message="暂无成书数据" />;

  const unresolved = data.summary.doneChapters < data.summary.totalChapters;

  return (
    <Card title="📕 成书审查（覆盖清单 · 基于当前稿）">
    <Card size="small" title="📖 卷末回读" style={{ marginBottom: 12 }}>
      {readbacks.length === 0 ? (
        <div style={{ fontSize: 12, color: '#888' }}>
          尚无回读报告——卷完成后生成回读（人物变化/节奏/承诺/偏离/建议），未确认的回读会暂停下一卷自动续写。
          <Button size="small" style={{ marginLeft: 8 }} loading={readbackBusy}
            onClick={() => handleGenerateReadback(1)}>生成第 1 卷回读</Button>
        </div>
      ) : readbacks.map(rb => {
        let report: Record<string, string> = {};
        try { report = JSON.parse(rb.report); } catch { /* ignore */ }
        return (
          <div key={rb.volumeIndex} style={{ padding: '6px 0', borderBottom: '1px dashed #eee', fontSize: 12 }}>
            <Space size={6} wrap>
              <Text strong>第 {rb.volumeIndex} 卷回读</Text>
              <Tag color={rb.status === 'PENDING_REVIEW' ? 'orange' : 'green'}
                style={{ fontSize: 10 }}>{rb.status === 'PENDING_REVIEW' ? '待确认（下一卷已暂停）' : '已确认'}</Tag>
              {rb.status === 'PENDING_REVIEW' && (
                <Button size="small" loading={readbackBusy} onClick={() => handleAckReadback(rb.volumeIndex)}>
                  确认并继续下一卷
                </Button>
              )}
              <Button size="small" loading={readbackBusy} onClick={() => handleGenerateReadback(rb.volumeIndex)}>
                重新生成
              </Button>
            </Space>
            <div style={{ marginTop: 4, lineHeight: 1.7 }}>
              {Object.entries(report).map(([k, v]) => (
                <div key={k}><Text type="secondary">{k}：</Text>{String(v)}</div>
              ))}
            </div>
          </div>
        );
      })}
    </Card>

      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Space wrap>
          <Button type="primary" href={api.getBookExportUrl(projectId)} target="_blank">
            导出全书 Markdown（基于当前稿）
          </Button>
          <Text type="secondary">
            {data.summary.doneChapters}/{data.summary.totalChapters} 章完成 · 共{' '}
            {data.summary.totalWords.toLocaleString()} 字 · 目标 {data.targetChapters} 章
          </Text>
        </Space>

        {unresolved && (
          <Alert type="warning" showIcon
            message="覆盖清单未完成"
            description={`${data.summary.totalChapters - data.summary.doneChapters} 章尚处于待人审或生成中状态。成书批准需要全部章节完成检查与人工决定（版本化成书快照将在后续提供）。`} />
        )}

        <List
          size="small"
          header="章节覆盖"
          dataSource={data.chapters}
          renderItem={ch => {
            // p36-11: DONE 只描述生成流程；批准按当前稿件身份精确匹配，缺记录即"尚未作者批准"
            const approved = ch.approved === true;
            const statusLabel = ch.status === 'DONE' ? '正文流程完成'
              : ch.status === 'NEEDS_MANUAL_REVIEW' ? '待人审' : '处理中';
            return (
            <List.Item>
              <Space size={6} wrap>
                <Tag>第 {ch.chapterIndex} 章</Tag>
                <Tag color="purple">v{ch.version}</Tag>
                <Tag color={ch.status === 'DONE' ? 'green'
                  : ch.status === 'NEEDS_MANUAL_REVIEW' ? 'red' : 'processing'}>
                  {statusLabel}
                </Tag>
                {approved
                  ? <Tag color="geekblue" data-approval="approved">作者已批准</Tag>
                  : <Tag data-approval="none">尚未作者批准</Tag>}
                <Tag color={ch.grade === 'A' ? 'green' : ch.grade === 'B' ? 'gold' : 'default'}>{ch.grade} 级</Tag>
                {ch.reportCurrent === false && <Tag color="orange">旧稿报告</Tag>}
                <Text type="secondary">{ch.wordCount.toLocaleString()} 字</Text>
              </Space>
            </List.Item>
            );
          }}
        />

        <List
          size="small"
          header={`未回收承诺（${data.unresolvedPromises.length} 项）`}
          dataSource={data.unresolvedPromises}
          locale={{ emptyText: '无未回收承诺' }}
          renderItem={p => (
            <List.Item>
              <Space size={6} wrap>
                <Tag color={p.mustResolve ? 'red' : p.priority === 'HIGH' ? 'orange' : 'default'}>
                  {p.mustResolve ? '必须回收' : p.priority}
                </Tag>
                <Text style={{ fontSize: 12 }}>{p.description}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  埋第 {p.plantedAtChapter} 章{p.expectedResolveChapter > 0 ? ` → 预计第 ${p.expectedResolveChapter} 章回收` : ' → 尚未安排回收'}
                </Text>
              </Space>
            </List.Item>
          )}
        />
      </Space>
      <EditionsPanel projectId={projectId} />
    </Card>
  );
}
