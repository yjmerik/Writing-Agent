import { useEffect, useRef, useState } from 'react';
import { Card, Button, Tag, Alert, Divider, Typography, Space, Empty, Spin, message, Rate, List } from 'antd';
import { BookOutlined } from '@ant-design/icons';

const { Title, Paragraph, Text } = Typography;

interface ReaderReport {
  plot: { score: number; strengths: string[]; weaknesses: string[]; pacing: string; hookAppeal: string };
  continuity: { score: number; smoothAreas: string[]; confusingAreas: string[]; characterConsistency: string };
  artistry: { score: number; highlights: string[]; weakSpots: string[]; dialogueQuality: string; proseQuality: string };
  chapterHighlights: { chapterIndex: number; highlight: string; concern: string }[];
  overall: { verdict: string; topSuggestion: string };
}

interface StoredReport {
  id: string;
  chaptersReviewed: number;
  totalChapters: number;
  coveragePercent: number;
  reportJson: string;
  createdAt: string;
}

/**
 * NCS-138: 读者视角评审卡片——剧情/连续性/艺术性三维度 + 逐章亮点/隐患。
 * 只读：不参与门禁/重写，帮作者在继续写作前获得读者外部视角。
 */
export default function ReaderReviewCard({ projectId }: { projectId: string }) {
  const [report, setReport] = useState<StoredReport | null>(null);
  const [parsed, setParsed] = useState<ReaderReport | null>(null);
  const [running, setRunning] = useState(false);
  const [jobId, setJobId] = useState('');
  const [error, setError] = useState('');
  const [messageApi, ctx] = message.useMessage();
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const parseReport = (raw: StoredReport | null) => {
    setReport(raw);
    if (!raw) { setParsed(null); return; }
    try { setParsed(JSON.parse(raw.reportJson)); } catch { setParsed(null); }
  };

  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/projects/${projectId}/reader-review`, { signal: controller.signal })
      .then(r => r.ok ? r.json() : null)
      .then(parseReport)
      .catch(() => {});
    return () => { controller.abort(); if (pollRef.current) clearInterval(pollRef.current); };
  }, [projectId]);

  const startPoll = (id: string) => {
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(async () => {
      try {
        const resp = await fetch(`/api/projects/${projectId}/chapters/1/long-tasks/${id}`);
        if (!resp.ok) return;
        const job = await resp.json();
        if (job.status === 'DONE') {
          clearInterval(pollRef.current!);
          pollRef.current = null;
          setRunning(false);
          setJobId('');
          // 重新拉取最新报告
          fetch(`/api/projects/${projectId}/reader-review`).then(r => r.ok ? r.json() : null).then(parseReport);
          messageApi.success('读者评审完成');
        } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
          clearInterval(pollRef.current!);
          pollRef.current = null;
          setRunning(false);
          setJobId('');
          setError(job.error || '评审失败');
          messageApi.error(`读者评审失败：${job.error || '未知原因'}`);
        }
      } catch { /* 网络抖动继续轮询 */ }
    }, 5000);
  };

  const handleReview = async () => {
    setRunning(true);
    setError('');
    try {
      const resp = await fetch(`/api/projects/${projectId}/reader-review`, { method: 'POST' });
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${resp.status}`);
      }
      const { jobId: id } = await resp.json();
      setJobId(id);
      startPoll(id);
      messageApi.info('读者评审已提交，正在阅读全文…');
    } catch (e) {
      setRunning(false);
      setError(e instanceof Error ? e.message : '提交失败');
    }
  };

  const scoreColor = (s: number) => s >= 7 ? '#52c41a' : s >= 5 ? '#faad14' : '#ff4d4f';

  const DimensionBlock = ({ title, score, items }: { title: string; score: number; items: { label: string; values: string[] }[] }) => (
    <div style={{ flex: 1, minWidth: 280 }}>
      <Title level={5}>{title} <Text style={{ color: scoreColor(score), fontSize: 24 }}>{score}</Text><Text type="secondary">/10</Text></Title>
      <Rate disabled value={score / 2} style={{ fontSize: 14, marginBottom: 8 }} />
      {items.map(({ label: l, values }) => values.length > 0 && (
        <div key={l} style={{ marginBottom: 8 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>{l}：</Text>
          {values.map((v, i) => <Paragraph key={i} style={{ margin: '2px 0 4px 12px', fontSize: 13 }}>{v}</Paragraph>)}
        </div>
      ))}
    </div>
  );

  return (
    <Card title={
      <Space>
        <BookOutlined />
        <span>📖 读者视角评审（剧情 / 连续性 / 艺术性）</span>
        {report && <Tag color="cyan">覆盖 {report.chaptersReviewed}/{report.totalChapters} 章 ({Math.round(report.coveragePercent)}%)</Tag>}
      </Space>
    }
    extra={
      <Space>
        {report?.createdAt && <Text type="secondary" style={{ fontSize: 12 }}>{new Date(report.createdAt).toLocaleString()}</Text>}
        <Button
          type="primary"
          icon={<BookOutlined />}
          loading={running}
          disabled={running}
          onClick={handleReview}
          aria-label="开始读者评审"
        >
          {running ? '读者评审中…' : '📖 开始读者评审'}
        </Button>
      </Space>
    }
    style={{ marginBottom: 16 }}
    >
      {ctx}
      <Alert
        message="像真实读者一样读完全书，从三个维度给出读后反馈——不参与质检门禁，不触发重写。"
        description="剧情：主线/节奏/悬念推进感。连续性：读者读感（不知道幕后设定能否跟上）。艺术性：文字画面感/意象/对话鲜活度。"
        type="info"
        style={{ marginBottom: 16 }}
      />

      {running && (
        <div aria-live="polite" style={{ textAlign: 'center', padding: '30px 0' }}>
          <Spin tip="读者正在阅读全书…" size="large"><div style={{ height: 48 }} /></Spin>
          {jobId && <Paragraph type="secondary" style={{ marginTop: 12, fontSize: 12 }}>任务 {jobId}（异步执行，可离开此页）</Paragraph>}
        </div>
      )}

      {error && !running && <Alert type="error" showIcon message="评审失败" description={error} style={{ marginBottom: 16 }} />}
      {!running && !parsed && !error && <Empty description="尚未进行读者评审——写几章后试试，看看读者怎么说" />}

      {parsed && !running && (
        <div>
          <Alert type="success" showIcon message={parsed.overall.verdict} description={
            <Text strong>给作者的建议：</Text>
          } style={{ marginBottom: 16 }} />
          <Alert type="warning" showIcon message={parsed.overall.topSuggestion} style={{ marginBottom: 24 }} />

          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
            <DimensionBlock title="剧情" score={parsed.plot.score} items={[
              { label: '亮点', values: parsed.plot.strengths },
              { label: '问题', values: parsed.plot.weaknesses },
              { label: '节奏', values: parsed.plot.pacing ? [parsed.plot.pacing] : [] },
              { label: '悬念', values: parsed.plot.hookAppeal ? [parsed.plot.hookAppeal] : [] },
            ]} />
            <DimensionBlock title="连续性" score={parsed.continuity.score} items={[
              { label: '顺畅', values: parsed.continuity.smoothAreas },
              { label: '困惑', values: parsed.continuity.confusingAreas },
              { label: '人物', values: parsed.continuity.characterConsistency ? [parsed.continuity.characterConsistency] : [] },
            ]} />
            <DimensionBlock title="艺术性" score={parsed.artistry.score} items={[
              { label: '亮点', values: parsed.artistry.highlights },
              { label: '短板', values: parsed.artistry.weakSpots },
              { label: '对话', values: parsed.artistry.dialogueQuality ? [parsed.artistry.dialogueQuality] : [] },
              { label: '文字', values: parsed.artistry.proseQuality ? [parsed.artistry.proseQuality] : [] },
            ]} />
          </div>

          {parsed.chapterHighlights.length > 0 && (
            <>
              <Divider />
              <Title level={5}>逐章亮点与隐患</Title>
              <List
                size="small"
                dataSource={parsed.chapterHighlights}
                renderItem={(item) => (
                  <List.Item>
                    <List.Item.Meta
                      title={<Space><Tag color="blue">第{item.chapterIndex}章</Tag><Text>{item.highlight}</Text></Space>}
                      description={item.concern && item.concern !== '无' ? <Text type="warning" style={{ fontSize: 12 }}>隐患：{item.concern}</Text> : undefined}
                    />
                  </List.Item>
                )}
              />
            </>
          )}
        </div>
      )}
    </Card>
  );
}
