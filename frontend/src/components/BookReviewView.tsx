import { useEffect, useState } from 'react';
import { Alert, Button, Card, List, Space, Spin, Tag, Typography } from 'antd';
import { api } from '../api/client';
import type { BookReviewData } from '../api/client';

const { Text } = Typography;

/** p35-05（设计 5.7 第一版）：成书审查覆盖清单与导出。 */
export default function BookReviewView({ projectId }: { projectId: string }) {
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
          renderItem={ch => (
            <List.Item>
              <Space size={6} wrap>
                <Tag>第 {ch.chapterIndex} 章</Tag>
                <Tag color="purple">v{ch.version}</Tag>
                <Tag color={ch.status === 'DONE' ? 'green'
                  : ch.status === 'NEEDS_MANUAL_REVIEW' ? 'red' : 'processing'}>
                  {ch.status === 'DONE' ? '已批准流程完成' : ch.status === 'NEEDS_MANUAL_REVIEW' ? '待人审' : '处理中'}
                </Tag>
                <Tag color={ch.grade === 'A' ? 'green' : ch.grade === 'B' ? 'gold' : 'default'}>{ch.grade} 级</Tag>
                {ch.reportCurrent === false && <Tag color="orange">旧稿报告</Tag>}
                <Text type="secondary">{ch.wordCount.toLocaleString()} 字</Text>
              </Space>
            </List.Item>
          )}
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
    </Card>
  );
}
