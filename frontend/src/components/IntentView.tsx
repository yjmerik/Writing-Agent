import { useEffect, useRef, useState } from 'react';
import { Card, Button, Spin, Alert, Tag, Typography, Space, Form, Input, Collapse, Empty, message } from 'antd';
import { AuditOutlined, BookOutlined, RocketOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { AuthorIntent } from '../types';

const { Paragraph, Text } = Typography;

/**
 * NCS-004（方案 §03/§05/§17）：创作约定工作区。
 * 空契约是合法状态——空项目显示具体下一步指引（先确定故事方向/让 Agent 构思/补充开篇人物），
 * 不显示空白页；保存生成新修订，历史可读；未确定项保留，不以空覆盖。
 */

interface FieldDef {
  key: keyof Pick<AuthorIntent, 'premise' | 'genreExpectation' | 'themes' | 'pov'
    | 'languageSamples' | 'lengthRange' | 'autonomy' | 'unwantedPatterns'>;
  label: string;
  hint: string;
  rows: number;
}

const FIELDS: FieldDef[] = [
  { key: 'premise', label: '作品原旨', hint: '想讲一个什么样的故事？给一两段自己的话，不用完美。', rows: 4 },
  { key: 'genreExpectation', label: '类型与读者预期', hint: '题材、读者与阅读感受期待（例：武侠·成年读者·沉静克制）。', rows: 2 },
  { key: 'themes', label: '主题张力', hint: '希望贯穿全书的主题或反复出现的张力（例：信任的代价）。', rows: 2 },
  { key: 'pov', label: '叙述视角', hint: '视角与人称约定（例：第三人称限制视角，主要跟随沈昭）。', rows: 2 },
  { key: 'languageSamples', label: '语言样本', hint: '贴一段你喜欢的文字（自己写的或喜欢的都行），Agent 会向它校准。', rows: 5 },
  { key: 'lengthRange', label: '篇幅区间', hint: '大致篇幅（例：60–90 章）。之后仍可调整。', rows: 1 },
  { key: 'autonomy', label: '允许的自主程度', hint: '哪些可以自动处理、哪些必须先问你（例：措辞可自动，人物命运必须问我）。', rows: 2 },
  { key: 'unwantedPatterns', label: '不想要的模式', hint: '明确不希望出现的写法（例：解释性独白；巧合解围）。', rows: 2 },
];

export default function IntentView({ projectId, currentChapter }: {
  projectId: string;
  projectState: string;
  currentChapter: number;
}) {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [intent, setIntent] = useState<AuthorIntent | null>(null);
  const [history, setHistory] = useState<AuthorIntent[]>([]);
  const [form] = Form.useForm();
  const [messageApi, messageContextHolder] = message.useMessage();
  const requestRef = useRef(0);

  useEffect(() => {
    const requestId = ++requestRef.current;
    setLoading(true);
    void Promise.all([api.getIntent(projectId), api.getIntentHistory(projectId)])
      .then(([current, hist]) => {
        if (requestRef.current !== requestId) return;
        setIntent(current);
        setHistory(hist.revisions ?? []);
        form.setFieldsValue(current);
      })
      .catch(() => { if (requestRef.current === requestId) setIntent(null); })
      .finally(() => { if (requestRef.current === requestId) setLoading(false); });
    return () => { requestRef.current++; };
  }, [projectId, form]);

  const handleSave = async (values: Partial<AuthorIntent>) => {
    setSaving(true);
    try {
      const saved = await api.saveIntent(projectId, values);
      setIntent(saved);
      setHistory(await api.getIntentHistory(projectId).then(h => h.revisions ?? []).catch(() => history));
      messageApi.success(`已保存为第 ${saved.revision} 版创作约定`);
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '保存失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <Card><Spin><div style={{ height: 120 }} /></Spin></Card>;
  }

  const emptyContract = (intent?.revision ?? 0) === 0;
  const noChapters = currentChapter <= 0;

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      {messageContextHolder}
      <Card>
        <Space direction="vertical" style={{ width: '100%' }} size="small">
          <Space wrap>
            <AuditOutlined />
            <Text strong>创作约定</Text>
            <Tag>{intent ? `第 ${intent.revision} 版` : '未建立'}</Tag>
            {emptyContract && <Tag color="gold">空契约——尚未填写，可以先创作再补</Tag>}
            {!emptyContract && intent?.updatedAt && <Text type="secondary">更新于 {intent.updatedAt.slice(0, 19).replace('T', ' ')}</Text>}
          </Space>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            这是你与 Agent 的创作契约：想写什么、喜欢什么、不能做什么。每一版都保留历史；
            未确定的项目可以先留空，之后随时补充。
          </Paragraph>
        </Space>
      </Card>

      {emptyContract && noChapters && (
        <Alert
          type="info"
          showIcon
          message="从零开始：先让故事有个方向"
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              <li><BookOutlined /> 在下方写下<strong>作品原旨</strong>（一段话即可），也可以先补类型与语言样本</li>
              <li><RocketOutlined /> 到「创作工具」让 Agent <strong>构思世界观与大纲</strong>，回来再比较和修改</li>
              <li>补充开篇需要的人物与设定后，接受近期计划即可试写第一章</li>
            </ul>
          }
        />
      )}
      {!emptyContract && noChapters && (
        <Alert
          type="success"
          showIcon
          message="约定已建立，下一步"
          description="到「创作工具」运行构思并审核初始设定，或到「大纲」确认近期章节计划；试写第一章后再回来校准语言样本。"
        />
      )}

      <Card title="约定内容" extra={
        <Space>
          <Button onClick={() => intent && form.setFieldsValue(intent)} disabled={saving}>还原当前版</Button>
          <Button type="primary" htmlType="submit" form="intent-form" loading={saving}>
            保存为新版本
          </Button>
        </Space>
      }>
        <Form id="intent-form" form={form} layout="vertical" onFinish={handleSave}>
          {FIELDS.map(field => (
            <Form.Item key={field.key} name={field.key} label={field.label}
              extra={<Text type="secondary" style={{ fontSize: 12 }}>{field.hint}</Text>}>
              <Input.TextArea rows={field.rows} placeholder={field.key === 'languageSamples'
                ? '例：江砚把密信凑近烛火，纸角卷起来，像一声没出口的叹息。' : ''} />
            </Form.Item>
          ))}
        </Form>
      </Card>

      <Card title="版本历史" size="small">
        {history.length === 0 ? (
          <Empty description="尚无历史版本——保存第一版后会出现在这里" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Collapse items={[...history].reverse().map(rev => ({
            key: rev.revision,
            label: `第 ${rev.revision} 版 · ${rev.updatedAt.slice(0, 19).replace('T', ' ')} · ${
              rev.premise ? rev.premise.slice(0, 40) + (rev.premise.length > 40 ? '…' : '') : '（未写原旨）'}`,
            children: (
              <Space direction="vertical" size="small" style={{ width: '100%' }}>
                {FIELDS.map(f => rev[f.key]
                  ? <Text key={f.key}><Text type="secondary">{f.label}：</Text>{rev[f.key]}</Text>
                  : null)}
              </Space>
            ),
          }))} />
        )}
      </Card>
    </Space>
  );
}
