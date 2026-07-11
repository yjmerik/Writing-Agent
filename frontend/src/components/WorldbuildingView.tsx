import { useState, useEffect, useCallback } from 'react';
import { Card, Tag, Empty, Spin, Row, Col, Descriptions, Divider, Button, Space } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { KnowledgeEntry, Character } from '../types';

const TYPE_LABELS: Record<string, string> = {
  GEOGRAPHY: '地理',
  FACTION: '势力',
  POWER_SYSTEM: '力量体系',
  HISTORY: '历史',
  RULE: '规则',
  ITEM: '物品',
  CUSTOM: '其他',
};

const TYPE_COLORS: Record<string, string> = {
  GEOGRAPHY: 'green',
  FACTION: 'orange',
  POWER_SYSTEM: 'red',
  HISTORY: 'blue',
  RULE: 'purple',
  ITEM: 'gold',
  CUSTOM: 'default',
};

export default function WorldbuildingView({ projectId }: { projectId: string }) {
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [characters, setCharacters] = useState<Character[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [e, c] = await Promise.all([
        api.getWorldbuilding(projectId),
        api.getCharacters(projectId),
      ]);
      setEntries(e || []);
      setCharacters(c || []);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { load(); }, [load]);

  if (loading) return <Spin />;

  // 按类型分组
  const grouped = entries.reduce<Record<string, KnowledgeEntry[]>>((acc, e) => {
    (acc[e.type] = acc[e.type] || []).push(e);
    return acc;
  }, {});

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button size="small" icon={<ReloadOutlined />} onClick={load}>刷新</Button>
      </Space>
      <h2>🌍 世界观设定</h2>
      {entries.length === 0 ? (
        <Empty description="暂无世界观设定，请先运行「构思」阶段" />
      ) : (
        <Row gutter={[16, 16]}>
          {Object.entries(grouped).map(([type, items]) => (
            <Col span={12} key={type}>
              <Card
                size="small"
                title={<Tag color={TYPE_COLORS[type] || 'default'}>{TYPE_LABELS[type] || type}</Tag>}
              >
                {items.map((e) => (
                  <div key={e.id} style={{ marginBottom: 12 }}>
                    <strong>{e.name}</strong>
                    <p style={{ margin: '4px 0 0', color: '#666' }}>{e.content}</p>
                  </div>
                ))}
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <Divider />
      <h2>👥 人物卡</h2>
      {characters.length === 0 ? (
        <Empty description="暂无人物" />
      ) : (
        <Row gutter={[16, 16]}>
          {characters.map((c) => (
            <Col span={12} key={c.id}>
              <Card size="small" title={c.name} extra={<Tag>{c.role}</Tag>}>
                <Descriptions column={1} size="small">
                  {c.personality && <Descriptions.Item label="性格">{c.personality}</Descriptions.Item>}
                  {c.ability && <Descriptions.Item label="能力">{c.ability}</Descriptions.Item>}
                  {c.motivation && <Descriptions.Item label="动机">{c.motivation}</Descriptions.Item>}
                  {c.appearance && <Descriptions.Item label="外貌">{c.appearance}</Descriptions.Item>}
                  {c.relationships && <Descriptions.Item label="关系">{c.relationships}</Descriptions.Item>}
                  {c.growthArc && <Descriptions.Item label="成长弧">{c.growthArc}</Descriptions.Item>}
                </Descriptions>
              </Card>
            </Col>
          ))}
        </Row>
      )}
    </div>
  );
}
