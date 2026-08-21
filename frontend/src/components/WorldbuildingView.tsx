import { useState, useEffect, useCallback } from 'react';
import { Card, Tag, Empty, Spin, Row, Col, Descriptions, Divider, Button, Space, Input, message, List, Typography } from 'antd';
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { KnowledgeEntry, Character } from '../types';

const { Text } = Typography;

const TYPE_LABELS: Record<string, string> = {
  GEOGRAPHY: '地理',
  FACTION: '势力',
  POWER_SYSTEM: '力量体系',
  HISTORY: '历史',
  RULE: '规则',
  ITEM: '物品',
  CUSTOM: '其他',
  CULTURE: '文化',
};

const TYPE_COLORS: Record<string, string> = {
  GEOGRAPHY: 'green',
  FACTION: 'orange',
  POWER_SYSTEM: 'red',
  HISTORY: 'blue',
  RULE: 'purple',
  ITEM: 'gold',
  CUSTOM: 'default',
  CULTURE: 'cyan',
};

export default function WorldbuildingView({ projectId }: { projectId: string }) {
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [characters, setCharacters] = useState<Character[]>([]);
  const [facts, setFacts] = useState<Array<{ id: string; subject: string; assertion: string; factType: string }>>([]);
  const [newFact, setNewFact] = useState('');
  const [newWorld, setNewWorld] = useState({ type: 'FACTION', name: '', content: '' });
  const [newChar, setNewChar] = useState({ name: '', role: '配角', ability: '' });
  const [loading, setLoading] = useState(true);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [e, c, f] = await Promise.all([
        api.getWorldbuilding(projectId),
        api.getCharacters(projectId),
        api.getFacts(projectId).catch(() => []),
      ]);
      setEntries(e || []);
      setCharacters(c || []);
      setFacts(f || []);
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
      <Space style={{ marginBottom: 12 }} wrap>
        <Input style={{ width: 100 }} value={newWorld.type} onChange={(e) => setNewWorld({ ...newWorld, type: e.target.value })} placeholder="类型" />
        <Input style={{ width: 140 }} value={newWorld.name} onChange={(e) => setNewWorld({ ...newWorld, name: e.target.value })} placeholder="名称" />
        <Input style={{ width: 240 }} value={newWorld.content} onChange={(e) => setNewWorld({ ...newWorld, content: e.target.value })} placeholder="内容" />
        <Button size="small" type="primary" onClick={async () => {
          if (!newWorld.name.trim() || !newWorld.content.trim()) return;
          try {
            await api.createWorldbuilding(projectId, newWorld);
            message.success('设定已添加并写入真典');
            setNewWorld({ type: 'FACTION', name: '', content: '' });
            load();
          } catch (err) {
            message.error((err as Error).message);
          }
        }}>添加设定</Button>
      </Space>
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
                    <Input.TextArea
                      rows={3}
                      value={drafts[e.id] ?? e.content}
                      onChange={(ev) => setDrafts((d) => ({ ...d, [e.id]: ev.target.value }))}
                      style={{ marginTop: 6 }}
                    />
                    <Button size="small" type="link" icon={<SaveOutlined />} loading={saving === e.id}
                      onClick={async () => {
                        setSaving(e.id);
                        try {
                          await api.updateWorldbuilding(projectId, e.id, {
                            name: e.name, type: e.type, content: drafts[e.id] ?? e.content,
                          });
                          message.success('已保存并写入真典');
                          await load();
                        } catch (err) {
                          message.error((err as Error).message);
                        } finally { setSaving(null); }
                      }}>保存</Button>
                  </div>
                ))}
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <Divider />
      <h2>👥 人物卡</h2>
      <Space style={{ marginBottom: 12 }} wrap>
        <Input style={{ width: 140 }} value={newChar.name} onChange={(e) => setNewChar({ ...newChar, name: e.target.value })} placeholder="姓名" />
        <Input style={{ width: 100 }} value={newChar.role} onChange={(e) => setNewChar({ ...newChar, role: e.target.value })} placeholder="定位" />
        <Input style={{ width: 200 }} value={newChar.ability} onChange={(e) => setNewChar({ ...newChar, ability: e.target.value })} placeholder="能力" />
        <Button size="small" type="primary" onClick={async () => {
          if (!newChar.name.trim()) return;
          try {
            await api.createCharacter(projectId, newChar);
            message.success('人物已添加并写入真典');
            setNewChar({ name: '', role: '配角', ability: '' });
            load();
          } catch (err) {
            message.error((err as Error).message);
          }
        }}>添加人物</Button>
      </Space>
      {characters.length === 0 ? (
        <Empty description="暂无人物" />
      ) : (
        <Row gutter={[16, 16]}>
          {characters.map((c) => (
            <Col span={12} key={c.id}>
              <Card size="small" title={c.name} extra={<Tag>{c.role}</Tag>}>
                <Descriptions column={1} size="small">
                  {([
                    ['性格', 'personality', c.personality],
                    ['能力', 'ability', c.ability],
                    ['动机', 'motivation', c.motivation],
                    ['外貌', 'appearance', c.appearance],
                    ['关系', 'relationships', c.relationships],
                    ['成长弧', 'growthArc', c.growthArc],
                  ] as const).map(([label, field, val]) => (
                    <Descriptions.Item key={field} label={label}>
                      <Input.TextArea
                        size="small"
                        rows={2}
                        defaultValue={val || ''}
                        onBlur={async (ev) => {
                          const v = ev.target.value;
                          if (v === (val || '')) return;
                          try {
                            await api.updateCharacter(projectId, c.id, { [field]: v });
                            message.success(`${c.name} ${label}已写入真典`);
                            load();
                          } catch (err) {
                            message.error((err as Error).message);
                          }
                        }}
                      />
                    </Descriptions.Item>
                  ))}
                </Descriptions>
              </Card>
            </Col>
          ))}
        </Row>
      )}

      <Divider />
      <h2>📜 真典</h2>
      <Space style={{ marginBottom: 8 }} wrap>
        <Input
          style={{ width: 360 }}
          placeholder="新增铁律，如：嘉靖年间不得出现望远镜"
          value={newFact}
          onChange={(e) => setNewFact(e.target.value)}
        />
        <Button size="small" type="primary" onClick={async () => {
          if (!newFact.trim()) return;
          try {
            await api.addFact(projectId, { factType: 'WORLD_RULE', subject: '人补丁', assertion: newFact.trim() });
            message.success('真典已写入');
            setNewFact('');
            load();
          } catch (err) {
            message.error((err as Error).message);
          }
        }}>添加</Button>
      </Space>
      {facts.length === 0 ? <Empty description="暂无真典条目" /> : (
        <List
          size="small"
          dataSource={facts}
          renderItem={(f) => (
            <List.Item>
              <Tag>{f.factType}</Tag>
              <Text strong>{f.subject}</Text>
              <span style={{ marginLeft: 8 }}>{f.assertion}</span>
            </List.Item>
          )}
        />
      )}
    </div>
  );
}
