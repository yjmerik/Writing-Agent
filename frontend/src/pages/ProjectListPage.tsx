import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Table, Button, Modal, Form, Input, Select, Tag, Space, message, Popconfirm, Card, Progress, Grid } from 'antd';
import { PlusOutlined, DeleteOutlined, ReadOutlined, RocketOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { ProjectListItem, CreateProjectRequest, ProjectMode, NovelState } from '../types';

const STATE_LABELS: Record<NovelState, { text: string; color: string }> = {
  CONCEIVING: { text: '构思中', color: 'blue' },
  PLANNING: { text: '规划中', color: 'cyan' },
  WRITING: { text: '创作中', color: 'processing' },
  VOLUME_DONE: { text: '卷完结', color: 'gold' },
  FINAL: { text: '已完结', color: 'green' },
  NEEDS_REVISION: { text: '待修订', color: 'red' },
  PAUSED_AT_CHECKPOINT: { text: '待审核', color: 'orange' },
};

const MODE_CONFIG: Record<ProjectMode, { text: string; color: string; bg: string }> = {
  SOLO: { text: '主动接手', color: '#666', bg: '#f5f5f5' },
  INTERACTIVE: { text: '定期审阅', color: '#722ed1', bg: '#f9f0ff' },
};

export default function ProjectListPage() {
  const navigate = useNavigate();
  const screens = Grid.useBreakpoint();
  const isMobile = screens.sm === false;
  const [searchParams, setSearchParams] = useSearchParams();
  const [projects, setProjects] = useState<ProjectListItem[]>([]);
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

  useEffect(() => {
    if (searchParams.get('new') === '1') {
      setCreateOpen(true);
      searchParams.delete('new');
      setSearchParams(searchParams, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  const handleCreate = async () => {
    const values = await createForm.validateFields();
    setCreating(true);
    try {
      const project = await api.createProject(values);
      message.success(`项目「${project.title || values.concept?.slice(0, 10)}」创建成功`);
      setCreateOpen(false);
      createForm.resetFields();
      navigate(`/project/${project.id}`);
    } catch (e) {
      message.error(`创建失败: ${(e as Error).message}`);
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (id: string, e?: React.MouseEvent) => {
    e?.stopPropagation();
    try {
      await api.deleteProject(id);
      message.success('已删除');
      load();
    } catch (e2) {
      message.error(`删除失败: ${(e2 as Error).message}`);
    }
  };

  const getDisplayTitle = (p: ProjectListItem) =>
    p.title || p.concept?.slice(0, 16) + (p.concept && p.concept.length > 16 ? '…' : '') || '未命名项目';

  const columns = [
    {
      title: '项目',
      dataIndex: 'title',
      width: 220,
      render: (_: string, record: ProjectListItem) => {
        const stateInfo = STATE_LABELS[record.state] || { text: record.state, color: 'default' };
        const modeInfo = MODE_CONFIG[record.mode] || MODE_CONFIG.SOLO;
        return (
          <div
            style={{ cursor: 'pointer', padding: '4px 0' }}
            onClick={() => navigate(`/project/${record.id}`)}
          >
            <div style={{ fontWeight: 700, fontSize: 15, color: '#1a1a2e', marginBottom: 4 }}>
              {getDisplayTitle(record)}
            </div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              <Tag color={stateInfo.color} style={{ margin: 0, fontSize: 11 }}>{stateInfo.text}</Tag>
              <Tag style={{ margin: 0, fontSize: 11, background: modeInfo.bg, color: modeInfo.color, border: 'none' }}>
                {modeInfo.text}
              </Tag>
            </div>
          </div>
        );
      },
    },
    {
      title: '构思',
      dataIndex: 'concept',
      ellipsis: true,
      render: (text: string, record: ProjectListItem) => (
        <div
          style={{ cursor: 'pointer', color: '#555', fontSize: 13, lineHeight: 1.6 }}
          onClick={() => navigate(`/project/${record.id}`)}
        >
          {text || '—'}
        </div>
      ),
    },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 200,
      render: (tags: string, record: ProjectListItem) => (
        <div style={{ cursor: 'pointer' }} onClick={() => navigate(`/project/${record.id}`)}>
          {tags?.split(',').filter(Boolean).map((t) => (
            <Tag key={t} style={{ margin: '2px 4px 2px 0', fontSize: 11, borderRadius: 10 }}>{t.trim()}</Tag>
          ))}
        </div>
      ),
    },
    {
      title: '进度',
      dataIndex: 'currentChapterIndex',
      width: 170,
      render: (n: number, record: ProjectListItem) => {
        const stateInfo = STATE_LABELS[record.state] || { text: record.state, color: 'default' };
        return (
          <div style={{ cursor: 'pointer' }} onClick={() => navigate(`/project/${record.id}`)}>
            <div style={{ fontSize: 13, color: '#333', marginBottom: 4 }}>
              {n} / {record.targetChapters} 章 · {record.progressPercent}%
            </div>
            <Progress
              percent={record.progressPercent}
              size="small"
              strokeColor={stateInfo.color === 'processing' ? '#1677ff' : stateInfo.color === 'green' ? '#52c41a' : stateInfo.color === 'gold' ? '#faad14' : '#1677ff'}
              showInfo={false}
              style={{ margin: 0 }}
            />
          </div>
        );
      },
    },
    {
      title: '操作',
      width: 140,
      render: (_: unknown, record: ProjectListItem) => (
        <Space size="small" onClick={(e) => e.stopPropagation()}>
          <Button
            type="primary"
            size="small"
            icon={<ReadOutlined />}
            onClick={() => navigate(`/project/${record.id}`)}
            style={{ borderRadius: 6 }}
          >
            进入
          </Button>
          <Popconfirm
            title="确定删除此项目？"
            onConfirm={(e) => handleDelete(record.id, e)}
            onCancel={(e) => e?.stopPropagation()}
          >
            <Button danger size="small" icon={<DeleteOutlined />} style={{ borderRadius: 6 }}
              aria-label={`删除项目「${record.title || record.concept || record.id}」`} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ minHeight: '100vh', background: 'linear-gradient(180deg, #f8f6f0 0%, #f0ece4 100%)' }}>
      <div style={{ maxWidth: 1100, margin: '0 auto', padding: isMobile ? '24px 0' : '32px 24px' }}>
        {/* Header */}
        <div
          data-project-list-header
          style={{
            marginBottom: 28,
            display: 'flex',
            flexDirection: isMobile ? 'column' : 'row',
            justifyContent: 'space-between',
            alignItems: isMobile ? 'stretch' : 'center',
            gap: isMobile ? 16 : 0,
            minWidth: 0,
          }}
        >
          <div>
            <h1
              data-project-list-title
              style={{
                fontSize: isMobile ? 22 : 26,
                fontWeight: 800,
                color: '#1a1a2e',
                margin: 0,
                letterSpacing: isMobile ? 0 : 1,
                whiteSpace: 'nowrap',
              }}
            >
              📖 长篇小说写作系统
            </h1>
            <p style={{ color: '#888', fontSize: 13, margin: '6px 0 0' }}>
              AI 驱动的多 Agent 协作创作 · 已有 {projects.length} 个项目
            </p>
          </div>
          <div data-project-list-actions style={{ display: 'flex', gap: 8, width: isMobile ? '100%' : 'auto' }}>
            <Button onClick={load} style={{ borderRadius: 8, flex: isMobile ? 1 : undefined, height: isMobile ? 44 : undefined }}>刷新</Button>
            <Button
              type="primary"
              icon={<RocketOutlined />}
              onClick={() => setCreateOpen(true)}
              style={{ borderRadius: 8, fontWeight: 600, padding: '4px 20px', height: isMobile ? 44 : 38, flex: isMobile ? 1 : undefined }}
            >
              新建项目
            </Button>
          </div>
        </div>

        {/* Project cards / table */}
        {projects.length === 0 && !loading ? (
          <Card
            style={{ borderRadius: 16, textAlign: 'center', padding: '60px 20px', border: '2px dashed #ddd', background: '#fff' }}
          >
            <div style={{ fontSize: 48, marginBottom: 16 }}>📝</div>
            <h3 style={{ color: '#999', fontWeight: 600 }}>还没有项目</h3>
            <p style={{ color: '#bbb', marginBottom: 24 }}>点击「新建项目」开始你的第一部长篇小说</p>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)} size="large" style={{ borderRadius: 8 }}>
              创建第一个项目
            </Button>
          </Card>
        ) : (
          <Card style={{ borderRadius: 16, border: 'none', boxShadow: '0 2px 12px rgba(0,0,0,0.06)' }}>
            <Table
              columns={columns}
              dataSource={projects}
              rowKey="id"
              loading={loading}
              pagination={false}
              scroll={{ x: true }}
              onRow={(record) => ({
                onClick: () => navigate(`/project/${record.id}`),
                // p21-07: 行键盘可激活
                role: 'button',
                tabIndex: 0,
                'aria-label': `打开项目 ${record.title || record.concept?.slice(0, 12) || record.id}`,
                onKeyDown: (e: React.KeyboardEvent) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    navigate(`/project/${record.id}`);
                  }
                },
                style: { cursor: 'pointer', transition: 'background 0.2s' },
                onMouseEnter: (e) => { (e.currentTarget as HTMLElement).style.background = '#faf8f4'; },
                onMouseLeave: (e) => { (e.currentTarget as HTMLElement).style.background = ''; },
              })}
            />
          </Card>
        )}

        {/* Create modal */}
        <Modal
          title={<span style={{ fontWeight: 700, fontSize: 17 }}>✨ 新建小说项目</span>}
          open={createOpen}
          onOk={handleCreate}
          onCancel={() => setCreateOpen(false)}
          confirmLoading={creating}
          okText="创建并进入"
          cancelText="取消"
          width={620}
          styles={{ body: { padding: '24px 28px' } }}
        >
          <Form form={createForm} layout="vertical" initialValues={{ mode: 'SOLO' as ProjectMode }}>
            <Form.Item name="title" label="小说标题" rules={[{ required: true, message: '请输入标题' }]}>
              <Input placeholder="如：凤鸣长安、剑骨天尊" size="large" />
            </Form.Item>
            <Form.Item name="concept" label="故事构思" rules={[{ required: true, message: '请输入构思' }]}>
              <Input.TextArea
                rows={4}
                placeholder="描述你的故事构思——主角是谁？核心冲突是什么？故事走向哪里？越详细越好。"
                showCount
                maxLength={500}
              />
            </Form.Item>
            <Form.Item name="tags" label="类型标签">
              <Input placeholder="逗号分隔，如：穿越,历史,权谋,朝堂" />
            </Form.Item>
            <Form.Item name="preset" label="题材预设（可选 — 按题材优化对话密度/文风/引用）">
              <Select size="large" allowClear placeholder="自动推断（不选则按标签推断题材）">
                <Select.Option value="wuxia">🗡️ 武侠江湖 — 招式对决、门派恩仇、侠义精神</Select.Option>
                <Select.Option value="romance">💕 古风言情 — 高对话密度、情感节拍、诗词典故</Select.Option>
                <Select.Option value="cultivation">⚔️ 修仙玄幻 — 境界体系、爽点轮换、战斗张力</Select.Option>
                <Select.Option value="mystery">🔍 悬疑推理 — 线索管理、伏笔密度、逻辑链</Select.Option>
                <Select.Option value="historical">📜 历史权谋 — 时代锚点、典章语感、朝堂博弈</Select.Option>
                <Select.Option value="thriller">⚡ 惊悚 — 高张力、危机节奏</Select.Option>
                <Select.Option value="general">📖 通用 — 不做题材特化</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="targetChapters" label="篇幅">
              <Select size="large" allowClear placeholder="默认 24 章（短篇验证）">
                <Select.Option value={24}>24 章 · 短篇</Select.Option>
                <Select.Option value={100}>100 章 · 中篇</Select.Option>
                <Select.Option value={200}>200 章 · 长篇</Select.Option>
                <Select.Option value={400}>400 章 · 百万字</Select.Option>
              </Select>
            </Form.Item>
            <Form.Item name="mode" label="审核节奏">
              <Select size="large">
                <Select.Option value="SOLO">仅主动接手 — Agent 持续创作，你随时介入</Select.Option>
                <Select.Option value="INTERACTIVE">每章审阅 — Agent 完成后等你检查</Select.Option>
              </Select>
            </Form.Item>
          </Form>
        </Modal>
      </div>
    </div>
  );
}
