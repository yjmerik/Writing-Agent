import { useEffect, useRef, useState } from 'react';
import { Card, Button, InputNumber, Space, Tag, Typography, Empty, Spin, message } from 'antd';
import { ForkOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { CharacterDecisionItem } from '../types';

const { Text } = Typography;

/**
 * NCS-012（方案 §07/F16）：经历与选择视图——人物关键决策的追踪与来源跳转。
 * 决策（触发/所知/选择/代价/承诺冲突/证据引文）按章展示，可跳转来源章；
 * 按需提取按钮幂等触发单章提取。
 */
export default function CharacterDecisionsView({ projectId, currentChapter }: {
  projectId: string;
  currentChapter: number;
}) {
  const [decisions, setDecisions] = useState<CharacterDecisionItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [extracting, setExtracting] = useState(false);
  const [extractChapter, setExtractChapter] = useState<number>(Math.max(currentChapter, 1));
  const navigate = useNavigate();
  const [messageApi, messageContextHolder] = message.useMessage();
  const requestRef = useRef(0);

  const load = () => {
    const requestId = ++requestRef.current;
    void api.getCharacterDecisions(projectId).then(d => {
      if (requestRef.current === requestId) setDecisions(d?.decisions ?? []);
    }).catch(() => {}).finally(() => {
      if (requestRef.current === requestId) setLoading(false);
    });
  };

  useEffect(() => { load(); /* eslint-disable-next-line */ }, [projectId]);

  const handleExtract = async () => {
    setExtracting(true);
    try {
      const result = await api.extractCharacterDecisions(projectId, extractChapter);
      if (result.status === 'UNKNOWN' || result.status === 'INVALID') {
        messageApi.warning(`第 ${extractChapter} 章提取未完成（${result.status}）——已记录为尚未查清，不当作无决策`);
      } else {
        messageApi.success(`第 ${extractChapter} 章提取完成：${result.extracted} 条决策（同章同稿重复提取不重复落库）`);
      }
      load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '提取失败');
    } finally {
      setExtracting(false);
    }
  };

  return (
    <Card title="🧭 经历与选择 · 关键决策" size="small" extra={
      <Space>
        <InputNumber min={1} max={Math.max(currentChapter, 1)} value={extractChapter}
          onChange={v => setExtractChapter(v ?? 1)} style={{ width: 80 }} />
        <Button size="small" icon={<ThunderboltOutlined />} loading={extracting} onClick={handleExtract}>
          提取本章决策
        </Button>
      </Space>
    }>
      {messageContextHolder}
      {loading ? <Spin><div style={{ height: 60 }} /></Spin>
        : decisions.length === 0 ? (
          <Empty description="尚无关键决策记录——对已写章节点击「提取本章决策」生成" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          decisions.map(d => (
            <div key={d.id} style={{ padding: '6px 0', borderBottom: '1px dashed #eee' }}>
              <Space size={6} wrap style={{ marginBottom: 2 }}>
                <ForkOutlined />
                <Text strong>{d.character}</Text>
                <Tag style={{ fontSize: 11 }}>第 {d.chapterIndex} 章 · 第 {d.chapterVersion} 稿</Tag>
                <a onClick={() => navigate(`/project/${projectId}/chapter/${d.chapterIndex}`)}
                   style={{ fontSize: 12 }}>跳到来源章</a>
              </Space>
              <div style={{ fontSize: 12, lineHeight: 1.8 }}>
                <div><Text type="secondary">触发：</Text>{d.triggerEvent}</div>
                <div><Text type="secondary">当时所知：</Text>{d.knewThen}</div>
                <div><Text type="secondary">可选：</Text>{(d.options ?? []).join(' / ')}</div>
                <div><Text type="secondary">选择：</Text><Text strong>{d.chosenAction}</Text>
                  {d.cost && <><Text type="secondary"> · 代价：</Text>{d.cost}</>}</div>
                {d.conflictWithCommitment && (
                  <div><Tag color="orange" style={{ fontSize: 11 }}>承诺冲突</Tag>{d.conflictWithCommitment}</div>
                )}
                {d.evidenceQuote && (
                  <div><Text type="secondary">证据：</Text>"{d.evidenceQuote}"</div>
                )}
              </div>
            </div>
          ))
        )}
    </Card>
  );
}
