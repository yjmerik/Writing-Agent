import { useEffect, useRef, useState } from 'react';
import { Card, Button, Spin, Alert, Tag, Typography, Space, Form, Input, Collapse, Empty, message, Divider } from 'antd';
import { AuditOutlined, BookOutlined, RocketOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { AuthorIntent, AestheticProfileData, DelegationRules } from '../types';

const { Paragraph, Text } = Typography;

const DELEGATION_LABELS: Record<string, string> = {
  WORDING: '措辞润色', FACT_LOCAL: '单章修复', MOTIVE: '人物动机与命运', CROSS_CHAPTER: '跨章改动',
};

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

  // NCS-022: 审美档案
  const [aesthetic, setAesthetic] = useState<AestheticProfileData | null>(null);
  const [aestheticSaving, setAestheticSaving] = useState(false);
  useEffect(() => {
    void api.getAestheticProfile(projectId).then(setAesthetic).catch(() => setAesthetic(null));
  }, [projectId]);
  const handleAestheticSave = async (values: { likedSnippetChapter?: string; likedSnippetExcerpt?: string; likedSnippetReason?: string; dislikedPatterns?: string; narrativeDistance?: string; languageDensity?: string; humorStyle?: string; imageryPreference?: string }) => {
    setAestheticSaving(true);
    try {
      const snippets = (values.likedSnippetExcerpt?.trim() && values.likedSnippetReason?.trim())
        ? [{ chapterRef: values.likedSnippetChapter?.trim() || '未标注', excerpt: values.likedSnippetExcerpt.trim(), reason: values.likedSnippetReason.trim() }]
        : [];
      const saved = await api.saveAestheticProfile(projectId, {
        likedSnippets: [...(aesthetic?.likedSnippets ?? []), ...snippets],
        dislikedPatterns: values.dislikedPatterns ?? aesthetic?.dislikedPatterns ?? '',
        narrativeDistance: values.narrativeDistance ?? aesthetic?.narrativeDistance ?? '',
        languageDensity: values.languageDensity ?? aesthetic?.languageDensity ?? '',
        humorStyle: values.humorStyle ?? aesthetic?.humorStyle ?? '',
        imageryPreference: values.imageryPreference ?? aesthetic?.imageryPreference ?? '',
      });
      setAesthetic(saved);
      messageApi.success(`审美档案已保存为第 ${saved.revision} 版（写作时向此校准）`);
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '保存失败');
    } finally { setAestheticSaving(false); }
  };

  // NCS-025: 委托策略
  const [delegation, setDelegation] = useState<DelegationRules>({});
  const [delegationSaving, setDelegationSaving] = useState(false);
  useEffect(() => {
    void api.getDelegationPolicy(projectId).then(r => setDelegation(r.rules ?? {})).catch(() => {});
  }, [projectId]);
  const toggleDelegation = async (changeClass: string) => {
    setDelegationSaving(true);
    try {
      const next = { ...delegation, [changeClass]: delegation[changeClass] === 'AUTO' ? 'CONFIRM' : 'AUTO' };
      const saved = await api.saveDelegationPolicy(projectId, next);
      setDelegation(saved.rules ?? next);
      messageApi.success('委托策略已更新');
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '保存失败');
    } finally { setDelegationSaving(false); }
  };

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

      <Card title="🎨 作者审美档案" size="small" extra={
        aesthetic && aesthetic.revision > 0 ? <Tag>第 {aesthetic.revision} 版</Tag> : <Tag color="gold">未建立</Tag>
      }>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          「喜欢这段的什么」比「文笔细腻」更可执行。认可片段与偏好会注入后续写作（校准信号，非硬规则）；
          单次选择不会自动升级为全书禁令。
        </Typography.Paragraph>
        {aesthetic && aesthetic.likedSnippets.length > 0 && (
          <div style={{ marginBottom: 8 }}>
            {aesthetic.likedSnippets.map((sn, i) => (
              <div key={i} style={{ fontSize: 12, padding: '2px 0' }}>
                <Text>"{sn.excerpt}"</Text>
                <Text type="secondary"> —— {sn.chapterRef}；认可理由：{sn.reason}</Text>
              </div>
            ))}
          </div>
        )}
        <Form layout="vertical" onFinish={handleAestheticSave}>
          <Form.Item name="likedSnippetExcerpt" label="认可的语言片段" style={{ marginBottom: 6 }}
            extra={<Text type="secondary" style={{ fontSize: 11 }}>贴一段你喜欢的文字（自己写的或喜欢的都行）</Text>}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Space size={12} wrap>
            <Form.Item name="likedSnippetChapter" label="出处" style={{ marginBottom: 6 }}>
              <Input style={{ width: 120 }} placeholder="如：第 8 章" />
            </Form.Item>
            <Form.Item name="likedSnippetReason" label="喜欢它什么" style={{ marginBottom: 6, minWidth: 260 }}>
              <Input placeholder="如：动作代替了对痛苦的直接描写" />
            </Form.Item>
          </Space>
          <Divider style={{ margin: '6px 0' }} />
          <Form.Item name="dislikedPatterns" label="希望减少的表达" style={{ marginBottom: 6 }}
            initialValue={aesthetic?.dislikedPatterns}>
            <Input placeholder="如：解释性独白；巧合解围" />
          </Form.Item>
          <Space size={12} wrap>
            <Form.Item name="narrativeDistance" label="叙述距离" style={{ marginBottom: 6 }} initialValue={aesthetic?.narrativeDistance}>
              <Input style={{ width: 200 }} placeholder="贴近但不说破" />
            </Form.Item>
            <Form.Item name="languageDensity" label="语言密度" style={{ marginBottom: 6 }} initialValue={aesthetic?.languageDensity}>
              <Input style={{ width: 200 }} placeholder="中等，留白多于铺陈" />
            </Form.Item>
          </Space>
          <Space size={12} wrap>
            <Form.Item name="humorStyle" label="幽默方式" style={{ marginBottom: 6 }} initialValue={aesthetic?.humorStyle}>
              <Input style={{ width: 200 }} placeholder="冷幽默，不刻意" />
            </Form.Item>
            <Form.Item name="imageryPreference" label="意象偏好" style={{ marginBottom: 6 }} initialValue={aesthetic?.imageryPreference}>
              <Input style={{ width: 260 }} placeholder="渡口水汽、火光、旧纸的质感" />
            </Form.Item>
          </Space>
          <Button htmlType="submit" loading={aestheticSaving}>保存审美档案</Button>
        </Form>
      </Card>

      <Card title="⚖ 委托策略" size="small" extra={
        <Text type="secondary" style={{ fontSize: 11 }}>AUTO=自动处理留痕；CONFIRM=暂停等你确认</Text>
      }>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          哪些修改可以让 Agent 自动完成、哪些必须先问你。会暂停的条件：
          {Object.entries(delegation).filter(([, v]) => v === 'CONFIRM')
            .map(([k]) => DELEGATION_LABELS[k] ?? k).join('、') || '（无）'}。
        </Typography.Paragraph>
        <Space wrap>
          {Object.entries(DELEGATION_LABELS).map(([key, label]) => (
            <Button key={key} size="small"
              type={delegation[key] === 'AUTO' ? 'primary' : 'default'}
              loading={delegationSaving}
              onClick={() => toggleDelegation(key)}>
              {label}：{delegation[key] === 'AUTO' ? '自动' : '需确认'}
            </Button>
          ))}
        </Space>
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
