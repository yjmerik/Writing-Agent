import { useState } from 'react';
import { Card, Button, Spin, Empty, Progress, Tag, Alert, Divider, Typography, Space } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { api } from '../api/client';

const { Paragraph, Title } = Typography;

const DIMENSION_LABELS: Record<string, string> = {
  consistency: '长程一致性',
  style: '风格统一',
  plot: '情节质量',
  characters: '人物塑造',
  worldbuilding: '世界观完整',
  dialogue: '对话质量',
  pattern_diversity: '模式多样性',
};

const SEVERITY_CONFIG: Record<string, { color: string; text: string }> = {
  high: { color: 'red', text: '严重' },
  medium: { color: 'orange', text: '中等' },
  low: { color: 'blue', text: '轻微' },
};

interface EvalResult {
  scores: Record<string, number>;
  overallScore: number;
  summary: string;
  issues: { dimension: string; severity: string; description: string }[];
}

export default function QualityView({ projectId }: { projectId: string }) {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<EvalResult | null>(null);

  const handleEvaluate = async () => {
    setLoading(true);
    setResult(null);
    try {
      setResult(await api.evaluate(projectId));
    } catch (e) {
      setResult(null);
      // eslint-disable-next-line no-alert
      alert(`评价失败: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card>
      <Space style={{ marginBottom: 16 }}>
        <Button
          type="primary"
          size="large"
          icon={<SafetyCertificateOutlined />}
          loading={loading}
          onClick={handleEvaluate}
        >
          {loading ? 'AI 评审中（分析全文）…' : '🔍 开始质量评价'}
        </Button>
      </Space>

      <Alert
        message="质量评价说明"
        description="AI 将从 7 个维度（长程一致性 / 风格统一 / 情节质量 / 人物塑造 / 世界观完整 / 对话质量 / 模式多样性）评价已生成的小说，发现具体问题并给出改进建议。建议在写完若干章节后评价。"
        type="info"
        style={{ marginBottom: 24 }}
      />

      {loading && <Spin tip="AI 正在阅读全文并评审..." size="large" style={{ display: 'block', margin: '40px auto' }} />}

      {result && !loading && (
        <div>
          {/* 总分 */}
          <div style={{ textAlign: 'center', marginBottom: 24 }}>
            <Title level={2}>
              总评分：<span style={{ color: result.overallScore >= 7 ? '#52c41a' : result.overallScore >= 5 ? '#faad14' : '#ff4d4f' }}>
                {result.overallScore}
              </span>
              <span style={{ fontSize: 16, color: '#999' }}> / 10</span>
            </Title>
            <Paragraph style={{ color: '#666', maxWidth: 600, margin: '0 auto' }}>
              {result.summary}
            </Paragraph>
          </div>

          <Divider />

          {/* 各维度评分 */}
          <Title level={4}>📊 各维度评分</Title>
          {Object.entries(result.scores || {}).map(([dim, score]) => (
            <div key={dim} style={{ marginBottom: 12 }}>
              <span style={{ display: 'inline-block', width: 100 }}>
                {DIMENSION_LABELS[dim] || dim}
              </span>
              <Progress
                percent={score * 10}
                format={() => `${score}/10`}
                strokeColor={score >= 7 ? '#52c41a' : score >= 5 ? '#faad14' : '#ff4d4f'}
                style={{ display: 'inline-block', width: 'calc(100% - 110px)' }}
              />
            </div>
          ))}

          {/* 发现的问题 */}
          <Divider />
          <Title level={4}>⚠️ 发现的问题（{result.issues?.length || 0} 个）</Title>
          {result.issues && result.issues.length > 0 ? (
            result.issues.map((issue, i) => {
              const sev = SEVERITY_CONFIG[issue.severity] || SEVERITY_CONFIG.low;
              return (
                <Alert
                  key={i}
                  type={issue.severity === 'high' ? 'error' : issue.severity === 'medium' ? 'warning' : 'info'}
                  style={{ marginBottom: 8 }}
                  message={
                    <Space>
                      <Tag color={sev.color}>{sev.text}</Tag>
                      <Tag>{DIMENSION_LABELS[issue.dimension] || issue.dimension}</Tag>
                    </Space>
                  }
                  description={issue.description}
                />
              );
            })
          ) : (
            <Empty description="未发现问题" />
          )}
        </div>
      )}
    </Card>
  );
}
