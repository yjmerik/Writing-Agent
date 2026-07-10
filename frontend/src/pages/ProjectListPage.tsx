import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Button, Modal, Form, Input, Select, Tag, Space, message, Popconfirm, Card } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { Project, CreateProjectRequest, ProjectMode, NovelState } from '../types';

const STATE_LABELS: Record<NovelState, { text: string; color: string }> = {
  CONCEIVING: { text: '构思中', color: 'blue' },
  PLANNING: { text: '规划中', color: 'cyan' },
  WRITING: { text: '创作中', color: 'processing' },
  VOLUME_DONE: { text: '卷完结', color: 'gold' },
  FINAL: { text: '已完结', color: 'green' },
  PAUSED_AT_CHECKPOINT: { text: '待审核', color: 'orange' },
};

export default function ProjectListPage() {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm<CreateProjectRequest>();
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setProjects(await api.listProjects());
    } catch (e) {
      message.error(`加载失败: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async () => {
    const values = await createForm.validateFields();
    setCreating(true);
    try {
      const project = await api.createProject(values);
      message.success(`项目「${project.title}」创建成功`);
      setCreateOpen(false);
      createForm.resetFields();
      navigate(`/project/${project.id}`);
    } catch (e) {
      message.error(`创建失败: ${(e as Error).message}`);
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await api.deleteProject(id);
      message.success('已删除');
      load();
    } catch (e) {
      message.error(`删除失败: ${(e as Error).message}`);
    }
  };

  const columns = [
    {
      title: '标题',
      dataIndex: 'title',
      render: (text: string, record: Project) => (
        <a onClick={() => navigate(`/project/${record.id}`)}>{text}</a>
      ),
    },
    {
      title: '构思',
      dataIndex: 'concept',
      ellipsis: true,
    },
    {
      title: '标签',
      dataIndex: 'tags',
      render: (tags: string) =>
        tags?.split(',').filter(Boolean).map((t) => (
          <Tag key={t}>{t.trim()}</Tag>
        )),
    },
    {
      title: '状态',
      dataIndex: 'state',
      render: (state: NovelState) => {
        const info = STATE_LABELS[state] || { text: state, color: 'default' };
        return <Tag color={info.color}>{info.text}</Tag>;
      },
    },
    {
      title: '模式',
      dataIndex: 'mode',
      render: (mode: ProjectMode) => (
        <Tag color={mode === 'SOLO' ? 'default' : 'purple'}>
          {mode === 'SOLO' ? '自动' : '交互'}
        </Tag>
      ),
    },
    {
      title: '进度',
      dataIndex: 'currentChapterIndex',
      render: (n: number) => `${n} 章`,
    },
    {
      title: '操作',
      render: (_: unknown, record: Project) => (
        <Popconfirm
          title="确定删除此项目？"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button danger size="small" icon={<DeleteOutlined />}>删除</Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ marginBottom: 16 }}>
        <Space>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建项目
          </Button>
          <Button onClick={load}>刷新</Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={projects}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 10 }}
      />

      <Modal
        title="新建小说项目"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        confirmLoading={creating}
        okText="创建并进入"
        cancelText="取消"
        width={600}
      >
        <Form form={createForm} layout="vertical" initialValues={{ mode: 'SOLO' as ProjectMode }}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如：剑骨天尊" />
          </Form.Item>
          <Form.Item name="concept" label="构思简介" rules={[{ required: true, message: '请输入构思' }]}>
            <Input.TextArea rows={4} placeholder="描述你的故事构思……" />
          </Form.Item>
          <Form.Item name="tags" label="类型标签">
            <Input placeholder="逗号分隔，如：玄幻,热血,复仇" />
          </Form.Item>
          <Form.Item name="mode" label="运行模式">
            <Select>
              <Select.Option value="SOLO">自动模式（全自动生成）</Select.Option>
              <Select.Option value="INTERACTIVE">交互模式（逐步审核）</Select.Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
