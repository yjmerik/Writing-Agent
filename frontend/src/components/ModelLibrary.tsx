import { useEffect, useRef, useState, useCallback } from 'react';
import {
  Card, Button, Table, Modal, Form, Input, InputNumber, Switch, Select,
  Tag, Space, Typography, Popconfirm, message, Empty, Alert, Tooltip, Badge,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, LinkOutlined,
  DatabaseOutlined, SaveOutlined,
} from '@ant-design/icons';

const { Text } = Typography;

interface LibraryModel {
  name: string; label: string; protocol: string; baseUrl: string;
  model: string; apiKeyMasked: string; hasApiKey: boolean;
  temperature: number; maxTokens: number; contextWindow: number;
  streaming: boolean; requestTimeoutSeconds: number;
  usedByTasks: string[];
}

interface LibraryData {
  models: LibraryModel[];
  taskTypes: string[];
  taskLabels: Record<string, string>;
  routing: Record<string, string>;
  defaultProvider: string | null;
}

const PROTOCOLS = [
  { value: 'OPENAI_CHAT_COMPLETIONS', label: 'OpenAI Chat Completions' },
  { value: 'OPENAI_RESPONSES', label: 'OpenAI Responses API' },
  { value: 'ANTHROPIC_MESSAGES', label: 'Anthropic Messages API' },
];

/**
 * 模型库 + 任务路由管理。
 * 模型保存一次（baseURL/apiKey/模型名…），任务类型从下拉选择即生效。
 */
export default function ModelLibrary() {
  const [data, setData] = useState<LibraryData | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingName, setEditingName] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [messageApi, ctx] = message.useMessage();
  const [form] = Form.useForm();
  const reloadRef = useRef<() => void>(() => {});

  const reload = useCallback(() => {
    fetch('/api/llm/library')
      .then(r => r.ok ? r.json() : Promise.reject(new Error('加载失败')))
      .then(setData)
      .catch(e => messageApi.error(`加载模型库失败: ${e.message}`));
  }, [messageApi]);
  reloadRef.current = reload;

  useEffect(() => { reload(); }, [reload]);

  // === 模型 CRUD ===
  const openAdd = () => {
    setEditingName(null);
    form.resetFields();
    form.setFieldsValue({
      protocol: 'OPENAI_CHAT_COMPLETIONS', temperature: 0.7, maxTokens: 8192,
      contextWindow: 64000, streaming: true, requestTimeoutSeconds: 300,
    });
    setModalOpen(true);
  };

  const openEdit = (m: LibraryModel) => {
    setEditingName(m.name);
    form.resetFields();
    form.setFieldsValue({
      protocol: m.protocol, baseUrl: m.baseUrl, apiKey: m.apiKeyMasked,
      model: m.model, temperature: m.temperature, maxTokens: m.maxTokens,
      contextWindow: m.contextWindow, streaming: m.streaming,
      requestTimeoutSeconds: m.requestTimeoutSeconds,
    });
    setModalOpen(true);
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const name = editingName || values.name;
      const resp = await fetch(`/api/llm/library/${encodeURIComponent(name)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      });
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({}));
        throw new Error(body.error || body.message || `HTTP ${resp.status}`);
      }
      messageApi.success(`模型「${name}」已保存`);
      setModalOpen(false);
      reload();
    } catch (e) {
      if (e instanceof Error && e.message) messageApi.error(e.message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (name: string) => {
    try {
      const resp = await fetch(`/api/llm/library/${encodeURIComponent(name)}`, { method: 'DELETE' });
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({}));
        throw new Error(body.error || body.message || `HTTP ${resp.status}`);
      }
      messageApi.success(`模型「${name}」已删除`);
      reload();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '删除失败');
    }
  };

  // === 路由 ===
  const handleRoute = async (taskType: string, providerName: string) => {
    try {
      const resp = await fetch(`/api/llm/library/routing/${taskType}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ providerName: providerName || '' }),
      });
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({}));
        throw new Error(body.error || body.message || `HTTP ${resp.status}`);
      }
      messageApi.success(`${taskType} → ${providerName || '默认'}`);
      reload();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '路由失败');
    }
  };

  const handleDefaultRoute = async (providerName: string) => {
    try {
      const resp = await fetch('/api/llm/library/routing/default', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ providerName: providerName || '' }),
      });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      messageApi.success(`默认模型 → ${providerName || '未设置'}`);
      reload();
    } catch (e) {
      messageApi.error('设置默认失败');
    }
  };

  if (!data) return <Card loading title="模型库" />;

  const modelColumns = [
    {
      title: '模型名',
      dataIndex: 'name',
      key: 'name',
      width: 160,
      render: (name: string, r: LibraryModel) => (
        <Space>
          <DatabaseOutlined />
          <Text strong>{name}</Text>
          {r.usedByTasks.length > 0 && (
            <Tooltip title={`被 ${r.usedByTasks.length} 个任务使用`}>
              <Badge count={r.usedByTasks.length} size="small" style={{ backgroundColor: '#1890ff' }} />
            </Tooltip>
          )}
        </Space>
      ),
    },
    { title: '协议', dataIndex: 'protocol', key: 'protocol', width: 140,
      render: (v: string) => <Tag>{PROTOCOLS.find(p => p.value === v)?.label || v}</Tag> },
    { title: '端点', dataIndex: 'baseUrl', key: 'baseUrl', width: 220, ellipsis: true },
    { title: '模型 ID', dataIndex: 'model', key: 'model', width: 140 },
    { title: '温度', dataIndex: 'temperature', key: 'temperature', width: 70,
      render: (v: number) => <Tag color={v <= 0.3 ? 'blue' : v <= 0.7 ? 'green' : 'orange'}>{v}</Tag> },
    { title: 'Max Tokens', dataIndex: 'maxTokens', key: 'maxTokens', width: 100 },
    { title: 'API Key', dataIndex: 'apiKeyMasked', key: 'apiKeyMasked', width: 120,
      render: (v: string, r: LibraryModel) => r.hasApiKey ? <Tag color="green">{v}</Tag> : <Tag>未配置</Tag> },
    {
      title: '操作',
      key: 'actions',
      width: 120,
      render: (_: unknown, r: LibraryModel) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} aria-label={`编辑模型 ${r.name}`} />
          <Popconfirm title={`确定删除「${r.name}」？`} onConfirm={() => handleDelete(r.name)}
            disabled={r.usedByTasks.length > 0}>
            <Button size="small" danger icon={<DeleteOutlined />}
              disabled={r.usedByTasks.length > 0}
              aria-label={`删除模型 ${r.name}`} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const routingColumns = [
    {
      title: '任务类型',
      dataIndex: 'taskType',
      key: 'taskType',
      width: 140,
      render: (taskType: string) => <Text strong>{data.taskLabels[taskType] || taskType}</Text>,
    },
    {
      title: '使用模型',
      key: 'provider',
      render: (_: unknown, record: { taskType: string; provider: string }) => (
        <Select
          style={{ width: 260 }}
          value={record.provider || undefined}
          placeholder="跟随默认模型"
          allowClear
          onChange={(v) => handleRoute(record.taskType, v || '')}
          options={data.models.map(m => ({
            value: m.name,
            label: `${m.name} (${m.model})`,
          }))}
        />
      ),
    },
  ];

  const routingData = data.taskTypes.map(taskType => ({
    key: taskType,
    taskType,
    provider: data.routing[taskType] || '',
  }));

  return (
    <Card
      title={
        <Space>
          <DatabaseOutlined />
          <span>模型库与任务路由</span>
          <Tag color="cyan">{data.models.length} 个模型</Tag>
        </Space>
      }
      extra={
        <Space>
          <Button icon={<PlusOutlined />} type="primary" onClick={openAdd} aria-label="新增模型">
            新增模型
          </Button>
          <Button icon={<SaveOutlined />} onClick={reload} aria-label="刷新模型库">
            刷新
          </Button>
        </Space>
      }
    >
      {ctx}
      <Alert
        message="模型只需配置一次连接信息，任务类型从下拉选择即生效"
        description="在上方模型库中保存命名模型（baseURL / API Key / 模型 ID / 参数），然后在下方任务路由中为每种任务类型选择使用哪个模型。修改立即生效，无需重启。"
        type="info"
        style={{ marginBottom: 16 }}
      />

      {data.models.length === 0 ? (
        <Empty description="模型库为空——点击「新增模型」保存第一个模型配置" />
      ) : (
        <Table
          columns={modelColumns}
          dataSource={data.models.map(m => ({ ...m, key: m.name }))}
          size="small"
          pagination={false}
          style={{ marginBottom: 24 }}
        />
      )}

      <Card size="small" title={
        <Space>
          <LinkOutlined />
          <span>任务路由</span>
        </Space>
      }>
        <Space style={{ marginBottom: 12 }}>
          <Text>默认模型（未路由的任务使用）：</Text>
          <Select
            style={{ width: 260 }}
            value={data.defaultProvider || undefined}
            placeholder="未设置"
            allowClear
            onChange={handleDefaultRoute}
            options={data.models.map(m => ({ value: m.name, label: `${m.name} (${m.model})` }))}
          />
        </Space>
        <Table
          columns={routingColumns}
          dataSource={routingData}
          size="small"
          pagination={false}
        />
      </Card>

      <Modal
        title={editingName ? `编辑模型：${editingName}` : '新增模型'}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={560}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          {!editingName && (
            <Form.Item
              name="name" label="模型名（唯一标识，如 qwen3.8-flash、deepseek-v3）"
              rules={[{ required: true, message: '请输入模型名' },
                      { pattern: /^[A-Za-z0-9_\-.]{1,64}$/, message: '只能包含字母/数字/_/-/.' }]}
            >
              <Input placeholder="my-model" />
            </Form.Item>
          )}
          <Form.Item name="protocol" label="API 协议" rules={[{ required: true }]}>
            <Select options={PROTOCOLS} />
          </Form.Item>
          <Form.Item name="baseUrl" label="Base URL" rules={[{ required: true, message: '请输入端点地址' }]}>
            <Input placeholder="https://api.example.com/v1" />
          </Form.Item>
          <Form.Item name="apiKey" label="API Key（留空保留原值）">
            <Input.Password placeholder="sk-..." />
          </Form.Item>
          <Form.Item name="model" label="模型 ID" rules={[{ required: true, message: '请输入模型 ID' }]}>
            <Input placeholder="qwen3.8-flash / deepseek-chat / glm-4.5" />
          </Form.Item>
          <Space size="large">
            <Form.Item name="temperature" label="Temperature" rules={[{ required: true }]}>
              <InputNumber min={0} max={2} step={0.1} style={{ width: 100 }} />
            </Form.Item>
            <Form.Item name="maxTokens" label="Max Tokens" rules={[{ required: true }]}>
              <InputNumber min={100} max={200000} step={100} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="contextWindow" label="Context Window">
              <InputNumber min={0} max={1000000} step={1000} style={{ width: 130 }} />
            </Form.Item>
          </Space>
          <Space size="large">
            <Form.Item name="requestTimeoutSeconds" label="超时（秒）">
              <InputNumber min={10} max={86400} style={{ width: 100 }} />
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
