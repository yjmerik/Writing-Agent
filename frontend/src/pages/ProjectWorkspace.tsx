import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Layout, Tabs, Button, Descriptions, Tag, Space, message, Spin } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { Project, ProjectMode } from '../types';
import RunConsole from '../components/RunConsole';
import WorldbuildingView from '../components/WorldbuildingView';
import OutlineView from '../components/OutlineView';
import ChapterReader from '../components/ChapterReader';
import QualityView from '../components/QualityView';

const { Sider, Content } = Layout;

const STATE_TEXT: Record<string, string> = {
  CONCEIVING: '构思中',
  PLANNING: '规划中',
  WRITING: '创作中',
  VOLUME_DONE: '卷完结',
  FINAL: '已完结',
  PAUSED_AT_CHECKPOINT: '待审核',
};

export default function ProjectWorkspace() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('run');

  const load = useCallback(async () => {
    if (!id) return;
    try {
      setProject(await api.getProject(id));
    } catch (e) {
      message.error(`加载项目失败: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const handleModeSwitch = async (mode: ProjectMode) => {
    if (!id) return;
    try {
      await api.switchMode(id, mode);
      message.success(`已切换到${mode === 'SOLO' ? '自动' : '交互'}模式`);
      load();
    } catch (e) {
      message.error(`切换失败: ${(e as Error).message}`);
    }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', marginTop: 100 }} />;
  if (!project) return <div>项目不存在</div>;

  return (
    <Layout style={{ background: 'transparent' }}>
      <Sider width={300} theme="light" style={{ padding: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')}>
            返回列表
          </Button>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="标题">{project.title}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color="blue">{STATE_TEXT[project.state] || project.state}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="模式">
              <Space>
                <Tag color={project.mode === 'SOLO' ? 'default' : 'purple'}>
                  {project.mode === 'SOLO' ? '自动' : '交互'}
                </Tag>
                <Button
                  size="small"
                  onClick={() => handleModeSwitch(project.mode === 'SOLO' ? 'INTERACTIVE' : 'SOLO')}
                >
                  切换
                </Button>
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label="已写章节">{project.currentChapterIndex} 章</Descriptions.Item>
            <Descriptions.Item label="构思">{project.concept}</Descriptions.Item>
          </Descriptions>
        </Space>
      </Sider>
      <Content style={{ padding: '0 16px' }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          destroyInactiveTabPane
          items={[
            { key: 'run', label: '运行控制台', children: <RunConsole projectId={project.id} projectState={project.state} onChanged={load} /> },
            { key: 'world', label: '世界观与人物', children: <WorldbuildingView key={`world-${project.currentChapterIndex}`} projectId={project.id} /> },
            { key: 'outline', label: '大纲', children: <OutlineView key={`outline-${project.state}`} projectId={project.id} /> },
            { key: 'reader', label: '章节阅读', children: <ChapterReader key={`reader-${project.currentChapterIndex}`} projectId={project.id} /> },
            { key: 'quality', label: '质量评价', children: <QualityView projectId={project.id} /> },
          ]}
        />
      </Content>
    </Layout>
  );
}
