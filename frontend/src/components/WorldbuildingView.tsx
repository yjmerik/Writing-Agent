import { useState, useEffect, useCallback } from 'react';
import { Card, Tag, Empty, Spin, Row, Col, Descriptions, Divider, Button, Space, Input, Select, message, List, Typography } from 'antd';
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { AgentRevisionApplyResult, AgentRevisionTarget } from '../api/client';
import type { KnowledgeEntry, Character, Foreshadowing } from '../types';
import AgentRevisionDialog from './AgentRevisionDialog';

const { Text } = Typography;

const WORLD_TYPE_OPTIONS = [
  { value: 'GEOGRAPHY', label: '地理' },
  { value: 'POWER_SYSTEM', label: '力量体系' },
  { value: 'FACTION', label: '势力' },
  { value: 'HISTORY', label: '历史' },
  { value: 'RULE', label: '规则' },
  { value: 'CULTURE', label: '文化' },
  { value: 'ECONOMY', label: '经济' },
  { value: 'RACE', label: '种族' },
  { value: 'CUSTOM', label: '其他' },
] as const;

const TYPE_LABELS: Record<string, string> = {
  GEOGRAPHY: '地理',
  FACTION: '势力',
  POWER_SYSTEM: '力量体系',
  HISTORY: '历史',
  RULE: '规则',
  ITEM: '物品',
  CUSTOM: '其他',
  CULTURE: '文化',
  ECONOMY: '经济',
  RACE: '种族',
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
  ECONOMY: 'gold',
  RACE: 'magenta',
};

type WorldDraft = Pick<KnowledgeEntry, 'type' | 'name' | 'content'>;
type CharacterField = 'name' | 'role' | 'personality' | 'ability' | 'motivation' | 'appearance' | 'relationships' | 'growthArc';
type CharacterDraft = Record<CharacterField, string>;

const CHARACTER_FIELDS: Array<{ field: CharacterField; label: string; rows: number }> = [
  { field: 'name', label: '姓名', rows: 1 },
  { field: 'role', label: '角色定位', rows: 1 },
  { field: 'personality', label: '性格', rows: 2 },
  { field: 'ability', label: '能力', rows: 2 },
  { field: 'motivation', label: '动机', rows: 2 },
  { field: 'appearance', label: '外貌', rows: 2 },
  { field: 'relationships', label: '关系', rows: 2 },
  { field: 'growthArc', label: '成长弧', rows: 2 },
];

function worldDraft(entry: KnowledgeEntry): WorldDraft {
  return { type: entry.type, name: entry.name, content: entry.content };
}

function characterDraft(character: Character): CharacterDraft {
  return {
    name: character.name,
    role: character.role,
    personality: character.personality || '',
    ability: character.ability || '',
    motivation: character.motivation || '',
    appearance: character.appearance || '',
    relationships: character.relationships || '',
    growthArc: character.growthArc || '',
  };
}

export default function WorldbuildingView({ projectId }: { projectId: string }) {
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [characters, setCharacters] = useState<Character[]>([]);
  const [facts, setFacts] = useState<Array<{ id: string; subject: string; assertion: string; factType: string }>>([]);
  const [foreshadowings, setForeshadowings] = useState<Foreshadowing[]>([]);
  const [newFact, setNewFact] = useState('');
  const [newForeshadow, setNewForeshadow] = useState('');
  const [newWorld, setNewWorld] = useState({ type: 'FACTION', name: '', content: '' });
  const [newChar, setNewChar] = useState({ name: '', role: '配角', ability: '' });
  const [loading, setLoading] = useState(true);
  const [worldDrafts, setWorldDrafts] = useState<Record<string, WorldDraft>>({});
  const [characterDrafts, setCharacterDrafts] = useState<Record<string, CharacterDraft>>({});
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState<string | null>(null);
  const [revisionTarget, setRevisionTarget] = useState<AgentRevisionTarget | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [e, c, f, fs] = await Promise.all([
        api.getWorldbuilding(projectId),
        api.getCharacters(projectId),
        api.getFacts(projectId).catch(() => []),
        api.getForeshadowings(projectId).catch(() => []),
      ]);
      setEntries(e || []);
      setCharacters(c || []);
      setFacts(f || []);
      setForeshadowings(fs || []);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { load(); }, [load]);

  const handleAgentApplied = async (_result: AgentRevisionApplyResult) => {
    if (revisionTarget?.targetType === 'WORLD') {
      setWorldDrafts((current) => {
        const next = { ...current };
        delete next[revisionTarget.targetId];
        return next;
      });
    }
    if (revisionTarget?.targetType === 'CHARACTER') {
      setCharacterDrafts((current) => {
        const next = { ...current };
        delete next[revisionTarget.targetId];
        return next;
      });
    }
    setRevisionTarget(null);
    await load();
  };

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
        <Select
          aria-label="新增世界观类型"
          style={{ width: 130 }}
          value={newWorld.type}
          options={[...WORLD_TYPE_OPTIONS]}
          onChange={(type) => setNewWorld({ ...newWorld, type })}
        />
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
        <Button size="small" onClick={async () => {
          try {
            await api.expandWorld(projectId, '请追加一条尚未出现的势力或规则，不要重复已有名称。');
            message.success('Agent 已增量补设定');
            load();
          } catch (err) {
            message.error((err as Error).message);
          }
        }}>请 Agent 补设定</Button>
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
                  <div key={e.id} data-world-entry-id={e.id} style={{ marginBottom: 12 }}>
                    <Descriptions column={1} size="small">
                      <Descriptions.Item label="类型">
                        <Select
                          aria-label={`${e.name} 世界观类型`}
                          style={{ width: '100%' }}
                          value={(worldDrafts[e.id] ?? worldDraft(e)).type}
                          options={[...WORLD_TYPE_OPTIONS]}
                          onChange={(type) => setWorldDrafts((current) => ({
                            ...current,
                            [e.id]: { ...(current[e.id] ?? worldDraft(e)), type },
                          }))}
                        />
                      </Descriptions.Item>
                      <Descriptions.Item label="名称">
                        <Input
                          aria-label={`${e.name} 世界观名称`}
                          value={(worldDrafts[e.id] ?? worldDraft(e)).name}
                          onChange={(event) => setWorldDrafts((current) => ({
                            ...current,
                            [e.id]: { ...(current[e.id] ?? worldDraft(e)), name: event.target.value },
                          }))}
                        />
                      </Descriptions.Item>
                      <Descriptions.Item label="正文">
                        <Input.TextArea
                          aria-label={`${e.name} 世界观正文`}
                          rows={3}
                          value={(worldDrafts[e.id] ?? worldDraft(e)).content}
                          onChange={(event) => setWorldDrafts((current) => ({
                            ...current,
                            [e.id]: { ...(current[e.id] ?? worldDraft(e)), content: event.target.value },
                          }))}
                        />
                      </Descriptions.Item>
                    </Descriptions>
                    <Space>
                      <Button size="small" type="link" icon={<SaveOutlined />} loading={saving === e.id}
                        onClick={async () => {
                          setSaving(e.id);
                          try {
                            const saved = await api.updateWorldbuilding(projectId, e.id, worldDrafts[e.id] ?? worldDraft(e));
                            setEntries((current) => current.map((entry) => entry.id === saved.id ? saved : entry));
                            setWorldDrafts((current) => {
                              const next = { ...current };
                              delete next[e.id];
                              return next;
                            });
                            message.success('已保存并写入真典');
                          } catch (err) {
                            message.error((err as Error).message);
                          } finally { setSaving(null); }
                        }}>保存设定</Button>
                      <Button
                        size="small"
                        disabled={saving === e.id || Boolean(worldDrafts[e.id])}
                        title={worldDrafts[e.id] ? '请先保存或刷新当前手工修改' : undefined}
                        aria-label={`让 Agent 修改世界观 ${e.name}`}
                        onClick={() => setRevisionTarget({
                          projectId,
                          targetType: 'WORLD',
                          targetId: e.id,
                          label: `世界观 · ${e.name}`,
                        })}
                      >让 Agent 修改</Button>
                    </Space>
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
        <Button size="small" onClick={async () => {
          try {
            await api.expandCharacters(projectId, '补充一名有独立动机的配角，姓名不得与已有人物重复。');
            message.success('Agent 已增量补人物');
            load();
          } catch (err) {
            message.error((err as Error).message);
          }
        }}>请 Agent 补人物</Button>
      </Space>
      {characters.length === 0 ? (
        <Empty description="暂无人物" />
      ) : (
        <Row gutter={[16, 16]}>
          {characters.map((c) => (
            <Col span={12} key={c.id} data-character-id={c.id}>
              <Card size="small" title={c.name} extra={<Tag>{c.role}</Tag>}>
                <Descriptions column={1} size="small">
                  {CHARACTER_FIELDS.map(({ label, field, rows }) => (
                    <Descriptions.Item key={field} label={label}>
                      {rows === 1 ? (
                        <Input
                          size="small"
                          aria-label={`${c.name} ${label}`}
                          value={(characterDrafts[c.id] ?? characterDraft(c))[field]}
                          onChange={(event) => setCharacterDrafts((current) => ({
                            ...current,
                            [c.id]: { ...(current[c.id] ?? characterDraft(c)), [field]: event.target.value },
                          }))}
                        />
                      ) : (
                        <Input.TextArea
                          size="small"
                          rows={rows}
                          aria-label={`${c.name} ${label}`}
                          value={(characterDrafts[c.id] ?? characterDraft(c))[field]}
                          onChange={(event) => setCharacterDrafts((current) => ({
                            ...current,
                            [c.id]: { ...(current[c.id] ?? characterDraft(c)), [field]: event.target.value },
                          }))}
                        />
                      )}
                    </Descriptions.Item>
                  ))}
                </Descriptions>
                <Space>
                  <Button
                    size="small"
                    type="primary"
                    icon={<SaveOutlined />}
                    loading={saving === c.id}
                    onClick={async () => {
                      setSaving(c.id);
                      try {
                        const saved = await api.updateCharacter(projectId, c.id, characterDrafts[c.id] ?? characterDraft(c));
                        setCharacters((current) => current.map((character) => character.id === saved.id ? saved : character));
                        setCharacterDrafts((current) => {
                          const next = { ...current };
                          delete next[c.id];
                          return next;
                        });
                        message.success(`${saved.name} 已保存并写入真典`);
                      } catch (err) {
                        message.error((err as Error).message);
                      } finally {
                        setSaving(null);
                      }
                    }}
                  >保存人物</Button>
                  <Button
                    size="small"
                    disabled={saving === c.id || Boolean(characterDrafts[c.id])}
                    title={characterDrafts[c.id] ? '请先保存或刷新当前手工修改' : undefined}
                    aria-label={`让 Agent 修改人物 ${c.name}`}
                    onClick={() => setRevisionTarget({
                      projectId,
                      targetType: 'CHARACTER',
                      targetId: c.id,
                      label: `人物 · ${c.name}`,
                    })}
                  >让 Agent 修改</Button>
                </Space>
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
            <List.Item
              actions={[
                <Button key="save" size="small" type="link" onClick={async () => {
                  try {
                    await api.updateFact(projectId, f.id, drafts[f.id] ?? f.assertion);
                    message.success('真典已更新');
                    load();
                  } catch (err) {
                    message.error((err as Error).message);
                  }
                }}>保存</Button>,
                <Button key="retire" size="small" type="link" danger onClick={async () => {
                  try {
                    await api.retireFact(projectId, f.id);
                    message.success('已废止');
                    load();
                  } catch (err) {
                    message.error((err as Error).message);
                  }
                }}>废止</Button>,
              ]}
            >
              <Tag>{f.factType}</Tag>
              <Text strong>{f.subject}</Text>
              <Input.TextArea
                rows={2}
                style={{ marginTop: 6 }}
                value={drafts[f.id] ?? f.assertion}
                onChange={(ev) => setDrafts((d) => ({ ...d, [f.id]: ev.target.value }))}
              />
            </List.Item>
          )}
        />
      )}

      <Divider />
      <h2>🧵 伏笔</h2>
      <Space style={{ marginBottom: 8 }} wrap>
        <Input
          style={{ width: 360 }}
          placeholder="人種伏笔，如：账册缺页尚未对上人"
          value={newForeshadow}
          onChange={(e) => setNewForeshadow(e.target.value)}
        />
        <Button size="small" type="primary" onClick={async () => {
          if (!newForeshadow.trim()) return;
          try {
            await api.addForeshadowing(projectId, { content: newForeshadow.trim(), mustResolve: true });
            message.success('伏笔已种下');
            setNewForeshadow('');
            load();
          } catch (err) {
            message.error((err as Error).message);
          }
        }}>人種</Button>
      </Space>
      {foreshadowings.length === 0 ? <Empty description="暂无伏笔" /> : (
        <List
          size="small"
          dataSource={foreshadowings}
          renderItem={(item) => (
            <List.Item
              actions={item.resolved ? [] : [
                <Button key="resolve" size="small" type="link" onClick={async () => {
                  try {
                    await api.resolveForeshadowing(projectId, item.id);
                    message.success('已回收');
                    load();
                  } catch (err) {
                    message.error((err as Error).message);
                  }
                }}>人收</Button>,
              ]}
            >
              <Tag color={item.resolved ? 'default' : 'red'}>{item.resolved ? '已回收' : '未回收'}</Tag>
              {item.mustResolve && <Tag color="orange">必须回收</Tag>}
              <span>{item.description}</span>
            </List.Item>
          )}
        />
      )}
      <AgentRevisionDialog
        open={revisionTarget !== null}
        target={revisionTarget}
        onApplied={handleAgentApplied}
        onCancel={() => setRevisionTarget(null)}
      />
    </div>
  );
}
