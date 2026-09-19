import { useEffect, useRef, useState } from 'react';
import { Card, Tag, Space, Typography, Spin, Button, Tooltip } from 'antd';
import { AlertOutlined, EditOutlined, ForkOutlined, QuestionCircleOutlined, ReadOutlined, SafetyOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { ReviewClassification, ReviewCard } from '../types';

const { Text, Paragraph } = Typography;

/**
 * NCS-023（方案 §13/UX09）：三类信息分层审阅——必须处理 / 尚未查清 / 文学建议。
 * 文字+图标区分（不只靠颜色）；问题卡含章稿版本与过期标注；
 * 不提供无证据的"标记已解决"（复核后关闭走人工复核流）。
 */
export default function ReviewClassificationView({ projectId }: { projectId: string }) {
  const [data, setData] = useState<ReviewClassification | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();
  const requestRef = useRef(0);

  useEffect(() => {
    const requestId = ++requestRef.current;
    void api.getReviewClassification(projectId).then(d => {
      if (requestRef.current === requestId) setData(d);
    }).catch(() => {}).finally(() => {
      if (requestRef.current === requestId) setLoading(false);
    });
  }, [projectId]);

  if (loading) return <Card size="small"><Spin><div style={{ height: 60 }} /></Spin></Card>;

  const toReview = (chapterIndex: number) => {
    navigate(`/project/${projectId}/chapter/${chapterIndex}`);
  };

  const column = (title: string, icon: React.ReactNode, color: string, cards: ReviewCard[],
                  hint: string) => (
    <div style={{ flex: 1, minWidth: 260, background: '#fafaf8', borderRadius: 6, padding: 10 }}>
      <Space size={6} style={{ marginBottom: 8 }}>
        {icon}
        <Text strong>{title}</Text>
        <Tag color={color} style={{ fontSize: 11 }}>{cards.length}</Tag>
      </Space>
      <Paragraph type="secondary" style={{ fontSize: 11, marginBottom: 8 }}>{hint}</Paragraph>
      {cards.length === 0 ? (
        <Text type="secondary" style={{ fontSize: 12 }}>（空——没有此类条目时如实显示为空，不显示"0 个问题"的完成假象）</Text>
      ) : cards.map((c, i) => (
        <div key={i} style={{ background: '#fff', border: '1px solid #eee', borderRadius: 4,
          padding: '6px 8px', marginBottom: 6, fontSize: 12 }}>
          <Paragraph style={{ marginBottom: 4, fontSize: 12 }}>{c.description}</Paragraph>
          <Space size={6} wrap>
            <Tag style={{ fontSize: 10 }}>第 {c.chapterIndex} 章</Tag>
            {c.chapterVersion != null && <Tag style={{ fontSize: 10 }}>v{c.chapterVersion} 稿</Tag>}
            {c.stale && <Tooltip title="该问题针对旧稿——正文已推进，复核前结论可能过期">
              <Tag color="default" style={{ fontSize: 10 }}>过期</Tag></Tooltip>}
            {c.severity && <Tag color={c.severity === 'HIGH' ? 'red' : 'orange'} style={{ fontSize: 10 }}>
              {c.severity === 'HIGH' ? '严重' : '中等'}</Tag>}
          </Space>
          <div style={{ marginTop: 6 }}>
            <Space size={6} wrap>
              {c.kind === 'UNKNOWN_CHECK' ? (
                <Tooltip title={c.reason ?? ''}>
                  <Button size="small" icon={<QuestionCircleOutlined />}>重新检查</Button>
                </Tooltip>
              ) : (
                <>
                  <Button size="small" icon={<EditOutlined />} onClick={() => toReview(c.chapterIndex)}>我来编辑</Button>
                  <Tooltip title="到修订工作区登记修订请求">
                    <Button size="small" icon={<ForkOutlined />}
                      onClick={() => navigate(`/project/${projectId}?tab=revisions`)}>生成修订建议</Button>
                  </Tooltip>
                  <Tooltip title="有依据的创作例外（记入处置台账，附理由）">
                    <Button size="small" icon={<SafetyOutlined />}
                      onClick={() => navigate(`/project/${projectId}/chapter/${c.chapterIndex}`)}>作为创作例外保留</Button>
                  </Tooltip>
                </>
              )}
            </Space>
          </div>
        </div>
      ))}
    </div>
  );

  return (
    <Card size="small" title="🗂 三类信息分层审阅" style={{ marginBottom: 12 }}
      extra={<Text type="secondary" style={{ fontSize: 11 }}>必须处理 ≠ 文学建议；未查清 ≠ 无问题</Text>}>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {column('必须处理', <AlertOutlined style={{ color: '#cf1322' }} />, 'red', data?.mustFix ?? [],
          '有证据的严重冲突或硬约束违背——阻断续写/成书')}
        {column('尚未查清', <QuestionCircleOutlined style={{ color: '#fa8c16' }} />, 'orange', data?.unknown ?? [],
          '检查未完成或不可判定——不能当作无问题，需重新检查或补证据')}
        {column('文学建议', <ReadOutlined style={{ color: '#389e0d' }} />, 'green', data?.suggestions ?? [],
          '节奏/语言方面的可讨论意见——可拒绝，不影响完成度')}
      </div>
    </Card>
  );
}
