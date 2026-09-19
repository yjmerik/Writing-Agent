import { useEffect, useRef, useState } from 'react';
import { Card, Select, Slider, Switch, Alert, Tag, Space, Typography, Empty, Spin, Tooltip } from 'antd';
import { EyeOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { Character, CharacterAsOf } from '../types';

const { Text, Paragraph } = Typography;

/**
 * NCS-011（方案 §07/UX07）：人物时点知情视图。
 * 按故事时点读取人物：「作者知道 / 角色知道 / 读者知道」分列；
 * 错误信念显示"X 相信：…"并标注其不知道的客观情况；资料不足显示「尚无可定位依据」；
 * 全书视角带后文提示（将看到 N 之后的内容）。
 */

const POLARITY_LABELS: Record<string, { text: string; color: string }> = {
  positive: { text: '成立', color: 'green' },
  negative: { text: '否定', color: 'red' },
};

const BELIEF_STATUS_HINTS: Record<string, string> = {
  UNSUPPORTED: '该认识的来源证据已失效（来源稿被修订）',
  SUPERSEDED: '客观事实已被后续剧情取代',
  RETIRED: '对应事实已归档',
  UNKNOWN: '对应事实状态未知',
};

export default function CharacterAsOfView({ projectId, currentChapter }: {
  projectId: string;
  currentChapter: number;
}) {
  const [characters, setCharacters] = useState<Character[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [atChapter, setAtChapter] = useState<number>(currentChapter);
  const [wholeBook, setWholeBook] = useState(false);
  const [view, setView] = useState<CharacterAsOf | null>(null);
  const [loading, setLoading] = useState(false);
  const requestRef = useRef(0);

  useEffect(() => {
    void api.getCharacters(projectId).then(cs => {
      setCharacters(cs || []);
      if (cs && cs.length > 0 && !selected) setSelected(cs[0].name);
    }).catch(() => setCharacters([]));
  }, [projectId, selected]);

  useEffect(() => {
    if (!selected) return;
    const requestId = ++requestRef.current;
    setLoading(true);
    const chapter = wholeBook ? Math.max(currentChapter, atChapter) : atChapter;
    void api.getCharacterAsOf(projectId, selected, chapter).then(v => {
      if (requestRef.current === requestId) setView(v);
    }).catch(() => {
      if (requestRef.current === requestId) setView(null);
    }).finally(() => {
      if (requestRef.current === requestId) setLoading(false);
    });
  }, [projectId, selected, atChapter, wholeBook, currentChapter]);

  const effectiveChapter = wholeBook ? Math.max(currentChapter, atChapter) : atChapter;

  return (
    <Card title="🕰 人物时点知情视图" size="small" extra={
      <Space size={12}>
        <Select
          style={{ minWidth: 140 }}
          placeholder="选择人物"
          value={selected || undefined}
          onChange={setSelected}
          options={characters.map(c => ({ value: c.name, label: c.name }))}
          showSearch
        />
        <Space size={4}>
          <Text type="secondary" style={{ fontSize: 12 }}>查看到第</Text>
          <Slider
            style={{ width: 140, margin: '0 4px' }}
            min={0}
            max={Math.max(currentChapter, 1)}
            value={Math.min(atChapter, Math.max(currentChapter, 1))}
            onChange={setAtChapter}
            tooltip={{ formatter: (v) => `第 ${v} 章` }}
          />
          <Text type="secondary" style={{ fontSize: 12 }}>章</Text>
        </Space>
        <Tooltip title="全书视角将包含所选章号之后的内容（后文剧透）">
          <Space size={4}>
            <Text type="secondary" style={{ fontSize: 12 }}>全书视角</Text>
            <Switch size="small" checked={wholeBook} onChange={setWholeBook} />
          </Space>
        </Tooltip>
      </Space>
    }>
      {wholeBook && (
        <Alert type="warning" showIcon style={{ marginBottom: 8 }}
          message="正在以全书视角阅读——将看到后续章节的内容"
          description="用于通读与设定核对；写旧章时请回到该章时点，避免后文信息污染人物行动。" />
      )}

      {!selected ? (
        <Empty description="选择人物后查看其时点状态" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : loading ? (
        <Spin><div style={{ height: 80 }} /></Spin>
      ) : !view ? (
        <Empty description="尚无可定位依据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <div>
            <Space size={6} style={{ marginBottom: 4 }}>
              <EyeOutlined />
              <Text strong>作者知道 · 第 {effectiveChapter} 章时点状态</Text>
            </Space>
            {view.facts.length === 0 ? (
              <Text type="secondary">尚无可定位依据（该时点没有已确立的状态事实）</Text>
            ) : (
              view.facts.map(f => (
                <div key={f.factId} style={{ padding: '2px 0' }}>
                  <Space size={6} wrap>
                    <Text>{f.assertion}</Text>
                    {f.predicate && <Tag style={{ fontSize: 11 }}>{f.predicate}</Tag>}
                    {f.polarity && POLARITY_LABELS[f.polarity] && (
                      <Tag color={POLARITY_LABELS[f.polarity].color} style={{ fontSize: 11 }}>
                        {POLARITY_LABELS[f.polarity].text}
                      </Tag>
                    )}
                    <Tag style={{ fontSize: 11 }}>自第 {f.validFromChapter} 章</Tag>
                  </Space>
                </div>
              ))
            )}
          </div>

          <div>
            <Space size={6} style={{ marginBottom: 4 }}>
              <UserOutlined />
              <Text strong>{selected} 知道</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>（何时、从哪里获知）</Text>
            </Space>
            {view.knows.length === 0 ? (
              <Text type="secondary">尚无可定位依据（没有该时点前的知情记录）</Text>
            ) : (
              view.knows.map((k, i) => (
                <div key={i} style={{ padding: '2px 0' }}>
                  <Text>"{k.quote}"</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}> —— 第 {k.learnedAtChapter} 章获知（来源：第 {k.sourceChapter} 章第 {k.sourceVersion} 稿）</Text>
                </div>
              ))
            )}
          </div>

          {view.believes.length > 0 && (
            <div>
              <Space size={6} style={{ marginBottom: 4 }}>
                <UserOutlined />
                <Text strong>{selected} 相信</Text>
                <Tag color="orange" style={{ fontSize: 11 }}>可能是错误认识</Tag>
              </Space>
              {view.believes.map((b, i) => (
                <div key={i} style={{ padding: '2px 0' }}>
                  <Text>"{b.quote}"</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {' '}{b.learnedAtChapter ? `第 ${b.learnedAtChapter} 章获知；` : ''}
                    {b.beliefStatus && BELIEF_STATUS_HINTS[b.beliefStatus] ? BELIEF_STATUS_HINTS[b.beliefStatus] : '对应事实已变化'}
                    ——{selected}仍这样认为，但其不知道客观情况已变
                  </Text>
                </div>
              ))}
            </div>
          )}

          <div>
            <Space size={6} style={{ marginBottom: 4 }}>
              <TeamOutlined />
              <Text strong>第 {effectiveChapter} 章时点的关系</Text>
            </Space>
            {view.relationships.length === 0 ? (
              <Text type="secondary">该时点没有有效的关系记录</Text>
            ) : (
              view.relationships.map((r, i) => (
                <div key={i} style={{ padding: '2px 0' }}>
                  <Space size={6} wrap>
                    <Text>{r.direction === 'outgoing' ? `${r.source} → ${r.target}` : `${r.target} → ${r.source}`}</Text>
                    <Tag style={{ fontSize: 11 }}>{r.relationship}</Tag>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      第 {r.validFromChapter}–{r.validToChapter || '当前'} 章
                    </Text>
                  </Space>
                </div>
              ))
            )}
          </div>

          <div>
            <Text strong>读者知道 · 已呈现的来源</Text>
            {(view.readerKnows ?? []).length === 0 ? <Paragraph type="secondary">该叙述位置尚无可定位的正文证据</Paragraph>
              : view.readerKnows.map((k, i) => <Paragraph key={i} style={{ marginBottom: 6 }}>
                “{k.quote}” —— <Typography.Link href={`#/project/${projectId}/chapter/${k.sourceChapter}`}>
                  来源：第 {k.sourceChapter} 章第 {k.sourceVersion} 稿
                </Typography.Link>
                {k.evidenceStale && <Tag color="orange">来源稿已更新，请在版本记录核对</Tag>}
              </Paragraph>)}
            <Text type="secondary">读者见过的线索不等于角色已经获知，也不代表该线索已证实为客观事实。</Text>
          </div>
        </Space>
      )}
    </Card>
  );
}
