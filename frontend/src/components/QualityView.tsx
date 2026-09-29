import { useEffect, useRef, useState } from 'react';
import { Card, Button, Spin, Empty, Progress, Tag, Alert, Divider, Typography, Space, message } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { api, type QualityEvaluationResult } from '../api/client';
import ReviewClassificationView from './ReviewClassificationView';
import ReaderReviewCard from './ReaderReviewCard';

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

type EvaluationState = 'NOT_EVALUATED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export default function QualityView({ projectId }: { projectId: string }) {
  const [evaluationState, setEvaluationState] = useState<EvaluationState>('NOT_EVALUATED');
  const [result, setResult] = useState<QualityEvaluationResult | null>(null);
  const [evaluatedAt, setEvaluatedAt] = useState<string>('');
  const [errorMessage, setErrorMessage] = useState('');
  const [messageApi, messageContextHolder] = message.useMessage();
  const requestSequence = useRef(0);
  const running = evaluationState === 'RUNNING';

  useEffect(() => {
    const requestId = ++requestSequence.current;
    setEvaluationState('NOT_EVALUATED');
    setResult(null);
    setEvaluatedAt('');
    setErrorMessage('');
    // p32-11: 挂载时读回最近一次已持久化的评价——切 tab/刷新不再丢失
    void api.getQualityEvaluation(projectId).then((stored) => {
      if (requestSequence.current !== requestId) return;
      if (stored.evaluation) {
        setResult(stored.evaluation);
        setEvaluatedAt(stored.evaluatedAt ?? '');
        setEvaluationState('SUCCEEDED');
      }
    }).catch(() => { /* 读回失败保持未评价态，可手动重新评价 */ });
  }, [projectId]);

  const handleEvaluate = async () => {
    const requestId = ++requestSequence.current;
    setEvaluationState('RUNNING');
    setResult(null);
    setErrorMessage('');
    try {
      const nextResult = await api.evaluate(projectId);
      if (requestSequence.current !== requestId) return;
      setResult(nextResult);
      setEvaluatedAt(new Date().toISOString());
      setEvaluationState('SUCCEEDED');
    } catch (e) {
      if (requestSequence.current !== requestId) return;
      const nextErrorMessage = e instanceof Error ? e.message : '未知错误';
      setResult(null);
      setErrorMessage(nextErrorMessage);
      setEvaluationState('FAILED');
      messageApi.error(`评价失败：${nextErrorMessage}`);
    }
  };

  return (
    <>
      <ReviewClassificationView projectId={projectId} />
      <ReaderReviewCard projectId={projectId} />
    <Card>
      {messageContextHolder}
      <Space style={{ marginBottom: 16 }}>
        <Button
          type="primary"
          size="large"
          icon={<SafetyCertificateOutlined />}
          loading={running}
          disabled={running}
          onClick={handleEvaluate}
        >
          {running ? 'AI 评审中（分析全文）…' : '🔍 开始质量评价'}
        </Button>
      </Space>

      <Alert
        message="质量评价说明"
        description="AI 将从 7 个维度（长程一致性 / 风格统一 / 情节质量 / 人物塑造 / 世界观完整 / 对话质量 / 模式多样性）评价已生成的小说，发现具体问题并给出改进建议。建议在写完若干章节后评价。"
        type="info"
        style={{ marginBottom: 24 }}
      />

      {evaluationState === 'NOT_EVALUATED' && <Empty description="尚未评价" />}

      {running && (
        <div aria-live="polite" style={{ textAlign: 'center', padding: '40px 0' }}>
          <Spin tip="AI 正在阅读全文并评审..." size="large">
            <div style={{ height: 48 }} />
          </Spin>
        </div>
      )}

      {evaluationState === 'FAILED' && (
        <Alert
          type="error"
          showIcon
          message="评价失败"
          description={errorMessage}
          action={<Button aria-label="重试质量评价" onClick={handleEvaluate}>重试</Button>}
        />
      )}

      {evaluationState === 'SUCCEEDED' && result && (
        <div>
          <Alert type="success" showIcon message="评价完成" style={{ marginBottom: 24 }} />
          {/* 总分 */}
          <div style={{ textAlign: 'center', marginBottom: 24 }}>
            <Title level={2}>
              总评分：<span style={{ color: result.overallScore >= 7 ? '#52c41a' : result.overallScore >= 5 ? '#faad14' : '#ff4d4f' }}>
                {result.overallScore}
              </span>
              <span style={{ fontSize: 16, color: '#999' }}> / 10</span>
            </Title>
            {evaluatedAt && (
              <Paragraph style={{ color: '#999', fontSize: 12, margin: '4px 0 0' }}>
                上次评价时间：{new Date(evaluatedAt).toLocaleString()}
              </Paragraph>
            )}
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
    </>
  );
}
