import { useState, useEffect, useCallback } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Switch, Tag, Space, message, Popconfirm, Card, Alert, Tooltip, Typography } from 'antd';
import { PlusOutlined, DeleteOutlined, EditOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { LlmModelEntry, LlmModelOverview, LlmProtocolType } from '../types';

const { Title, Text } = Typography;

/** 来源标签：PAGE=页面配置（可删除）；YML=配置文件；FALLBACK=跟随默认 */
const SOURCE_META: Record<string, { text: string; color: string }> = {
  PAGE: { text: '页面配置', color: 'purple' },
  YML: { text: '配置文件', color: 'blue' },
  FALLBACK: { text: '跟随默认', color: 'default' },
};

const PROTOCOL_SHORT: Record<LlmProtocolType, string> = {
  OPENAI_CHAT_COMPLETIONS: 'OpenAI Chat',
  OPENAI_RESPONSES: 'OpenAI Responses',
  ANTHROPIC_MESSAGES: 'Anthropic Messages',
};

interface FormValues {
  protocol: LlmProtocolType;
  baseUrl: string;
  model: string;
  apiKey?: string;
  temperature: number;
  maxTokens: number;
  contextWindow: number;
  streaming: boolean;
  requestTimeoutSeconds: number;
}

export default function ModelConfigPage() {
  const [overview, setOverview] = useState<LlmModelOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [isCreate, setIsCreate] = useState(false); // true = 新增（可选范围），false = 编辑（固定范围）
  const [modalScope, setModalScope] = useState<string | null>(null);
  const [form] = Form.useForm<FormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setOverview(await api.getLlmModels());
    } catch (e) {
      message.error(`加载失败: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const prefill = (entry: LlmModelEntry) => {
    form.setFieldsValue({
      protocol: entry.protocol,
      baseUrl: entry.baseUrl,
      model: entry.model,
      apiKey: '',
      temperature: entry.temperature,
      maxTokens: entry.maxTokens,
      contextWindow: entry.contextWindow,
      streaming: entry.streaming,
      requestTimeoutSeconds: entry.requestTimeoutSeconds,
    });
  };

  const openEdit = (entry: LlmModelEntry) => {
    setIsCreate(false);
    setModalScope(entry.scope);
    prefill(entry);
    setModalOpen(true);
  };

  /** 新增：为尚无页面覆盖的范围创建配置（默认预填该范围当前生效值） */
  const openCreate = () => {
    if (!overview) return;
    const candidates = overview.entries.filter((e) => e.source !== 'PAGE');
    if (candidates.length === 0) {
      message.info('所有范围均已有页面配置，可直接在列表中编辑');
      return;
    }
    setIsCreate(true);
    setModalScope(candidates[0].scope);
    prefill(candidates[0]);
    setModalOpen(true);
  };

  const modalEntry = overview?.entries.find((e) => e.scope === modalScope) ?? null;

  const handleScopeChange = (scope: string) => {
    const entry = overview?.entries.find((e) => e.scope === scope);
    setModalScope(scope);
    if (entry) prefill(entry);
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    if (!modalScope) return;
    const label = modalEntry?.label ?? modalScope;
    setSaving(true);
    try {
      await api.updateLlmModel(modalScope, {
        protocol: values.protocol,
        baseUrl: values.baseUrl,
        model: values.model,
        // 空 = 保留现有 key（后端语义）
        apiKey: values.apiKey?.trim() ? values.apiKey.trim() : '',
        temperature: values.temperature,
        maxTokens: values.maxTokens,
        contextWindow: values.contextWindow,
        streaming: values.streaming,
        requestTimeoutSeconds: values.requestTimeoutSeconds,
      });
      message.success(`「${label}」配置已保存并即时生效`);
      setModalOpen(false);
      load();
    } catch (e) {
      message.error(`保存失败: ${(e as Error).message}`);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (entry: LlmModelEntry) => {
    try {
      await api.deleteLlmModel(entry.scope);
      message.success(`「${entry.label}」已回退配置文件值`);
      load();
    } catch (e) {
      message.error(`删除失败: ${(e as Error).message}`);
    }
  };

  const protocolOptions = overview?.protocols?.length
    ? overview.protocols.map((p) => ({ value: p.value, label: p.label }))
    : Object.entries(PROTOCOL_SHORT).map(([value, label]) => ({ value, label }));

  /** 新增时可选范围 = 尚无页面覆盖的范围（编辑时固定） */
  const scopeOptions = (overview?.entries ?? [])
    .filter((e) => e.source !== 'PAGE' || e.scope === modalScope)
    .map((e) => ({
      value: e.scope,
      label: `${e.label}（${SOURCE_META[e.source]?.text ?? e.source}）`,
    }));

  const columns = [
    {
      title: '配置范围',
      dataIndex: 'label',
      key: 'label',
      render: (_: unknown, entry: LlmModelEntry) => (
        <Space direction="vertical" size={0}>
          <Text strong>{entry.label}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{entry.scope}</Text>
        </Space>
      ),
    },
    {
      title: '协议',
      dataIndex: 'protocol',
      key: 'protocol',
      width: 150,
      render: (protocol: LlmProtocolType) => (
        <Tag color={protocol === 'OPENAI_CHAT_COMPLETIONS' ? 'geekblue' : protocol === 'OPENAI_RESPONSES' ? 'cyan' : 'orange'}>
          {PROTOCOL_SHORT[protocol] ?? protocol}
        </Tag>
      ),
    },
    {
      title: '模型',
      dataIndex: 'model',
      key: 'model',
      width: 150,
      render: (model: string) => <Text code>{model}</Text>,
    },
    {
      title: 'Base URL',
      dataIndex: 'baseUrl',
      key: 'baseUrl',
      ellipsis: true,
      render: (url: string) => (
        <Text copyable={{ text: url }} ellipsis style={{ maxWidth: 260 }}>{url}</Text>
      ),
    },
    {
      title: 'API Key',
      dataIndex: 'apiKeyMasked',
      key: 'apiKeyMasked',
      width: 130,
      render: (masked: string, entry: LlmModelEntry) =>
        entry.hasApiKey ? <Text code>{masked}</Text> : <Text type="secondary">未配置</Text>,
    },
    {
      title: '温度',
      dataIndex: 'temperature',
      key: 'temperature',
      width: 70,
    },
    {
      title: 'max_tokens',
      dataIndex: 'maxTokens',
      key: 'maxTokens',
      width: 110,
    },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 100,
      render: (source: string) => {
        const meta = SOURCE_META[source];
        return <Tag color={meta?.color}>{meta?.text ?? source}</Tag>;
      },
    },
    {
      title: '操作',
      key: 'actions',
      width: 150,
      render: (_: unknown, entry: LlmModelEntry) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(entry)}>
            编辑
          </Button>
          {entry.source === 'PAGE' && (
            <Popconfirm
              title="删除页面配置"
              description={`将回退到配置文件中的「${entry.label}」值`}
              onConfirm={() => handleDelete(entry)}
            >
              <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card
      title={<Title level={4} style={{ margin: 0 }}>🤖 模型配置</Title>}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增配置</Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="页面配置即时生效并持久化（data/config/llm-models.json），重启后保持"
        description={
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            <li>「配置文件」来源 = application.yml 中的现有配置；编辑后会生成页面覆盖，「删除」即可回退</li>
            <li>API Key 仅显示掩码；编辑时留空表示保留现有 Key</li>
            <li>协议支持 OpenAI Chat Completions / OpenAI Responses API / Anthropic Messages API</li>
          </ul>
        }
      />
      <Table<LlmModelEntry>
        rowKey="scope"
        columns={columns}
        dataSource={overview?.entries ?? []}
        loading={loading}
        pagination={false}
        size="middle"
      />

      <Modal
        title={isCreate ? '新增模型配置' : modalEntry ? `配置「${modalEntry.label}」` : '配置'}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        width={640}
        destroyOnClose
      >
        <Form form={form} layout="vertical" initialValues={{ streaming: true }}>
          <Form.Item label="配置范围" required>
            <Select
              options={scopeOptions}
              value={modalScope ?? undefined}
              disabled={!isCreate}
              onChange={handleScopeChange}
            />
          </Form.Item>
          <Form.Item
            name="protocol"
            label="协议"
            rules={[{ required: true }]}
          >
            <Select options={protocolOptions} />
          </Form.Item>
          <Form.Item
            name="baseUrl"
            label="Base URL"
            rules={[
              { required: true, message: '请输入 base_url' },
              { pattern: /^https?:\/\//, message: '必须是 http(s) 地址' },
            ]}
          >
            <Input placeholder="https://api.example.com/v1" />
          </Form.Item>
          <Form.Item
            name="model"
            label="模型 ID"
            rules={[{ required: true, message: '请输入 model_id' }]}
          >
            <Input placeholder="qwen3.8-flash / claude-sonnet-4 / gpt-5.2 等" />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label={
              <Tooltip title="留空 = 保留现有 Key；本地/内网端点可留空">
                API Key{modalEntry?.hasApiKey ? `（当前 ${modalEntry.apiKeyMasked}，留空保持不变）` : ''}
              </Tooltip>
            }
          >
            <Input.Password placeholder={modalEntry?.hasApiKey ? '留空保持不变' : 'sk-...'} autoComplete="new-password" />
          </Form.Item>
          <Space size="large" style={{ display: 'flex' }} wrap>
            <Form.Item
              name="temperature"
              label="温度"
              rules={[{ required: true }]}
              style={{ minWidth: 120 }}
            >
              <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="maxTokens"
              label="max_tokens"
              rules={[{ required: true }]}
              style={{ minWidth: 140 }}
            >
              <InputNumber min={1} max={200000} step={1000} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="contextWindow"
              label={<Tooltip title="0 = 使用默认预算">上下文窗口</Tooltip>}
              style={{ minWidth: 140 }}
            >
              <InputNumber min={0} max={1000000} step={8000} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Space size="large" style={{ display: 'flex' }} wrap>
            <Form.Item name="requestTimeoutSeconds" label="请求超时（秒）" style={{ minWidth: 140 }}>
              <InputNumber min={1} max={86400} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="streaming" label="流式输出" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </Card>
  );
}
