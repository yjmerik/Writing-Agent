import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Layout, Tabs, Button, Descriptions, Tag, Space, message, Spin, Collapse, Input, Slider, Switch, Select } from 'antd';
import { ArrowLeftOutlined, PlusOutlined, SettingOutlined, SendOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { Project, ProjectMode } from '../types';
import RunConsole from '../components/RunConsole';
import WorldbuildingView from '../components/WorldbuildingView';
import OutlineView from '../components/OutlineView';
import InteractiveReview from '../components/InteractiveReview';
import QualityView from '../components/QualityView';

const { Sider, Content } = Layout;

const STATE_TEXT: Record<string, string> = {
  CONCEIVING: '构思中',
  PLANNING: '规划中',
  WRITING: '创作中',
  VOLUME_DONE: '卷完结',
  FINAL: '已完结',
  NEEDS_REVISION: '待修订',
  PAUSED_AT_CHECKPOINT: '待审核',
};

const STATE_COLOR: Record<string, string> = {
  CONCEIVING: 'blue', PLANNING: 'cyan', WRITING: 'processing',
  VOLUME_DONE: 'gold', FINAL: 'green', NEEDS_REVISION: 'red', PAUSED_AT_CHECKPOINT: 'orange',
};

export default function ProjectWorkspace() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('run');
  const [prefs, setPrefs] = useState<Record<string, unknown> | null>(null);
  const [chatMsg, setChatMsg] = useState('');
  const [chatReply, setChatReply] = useState('');
  const [chatting, setChatting] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    try {
      const [p, pr] = await Promise.all([
        api.getProject(id),
        api.getPreferences(id).catch(() => null),
      ]);
      setProject(p);
      setPrefs(pr);
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

  const handleChat = async () => {
    if (!id || !chatMsg.trim()) return;
    setChatting(true);
    setChatReply('');
    try {
      const result = await api.chatPreferences(id, chatMsg.trim());
      setChatReply(result.reply);
      setPrefs(result.preferences);
      setChatMsg('');
    } catch (e) {
      setChatReply(`错误: ${(e as Error).message}`);
    } finally {
      setChatting(false);
    }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', marginTop: 100 }} />;
  if (!project) return <div style={{ textAlign: 'center', marginTop: 100, color: '#999' }}>项目不存在</div>;

  const displayTitle = project.title || project.concept?.slice(0, 12) + '…' || '未命名项目';

  return (
    <Layout style={{ background: 'transparent', minHeight: '100vh' }}>
      {/* p21-06: 响应式侧栏——小于 lg 自动折叠，375px 下无页面级横向溢出 */}
      <Sider
        width={320}
        breakpoint="lg"
        collapsedWidth="0"
        theme="light"
        style={{ padding: '16px 12px', background: '#fafaf8', borderRight: '1px solid #eee' }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          {/* 顶部操作 */}
          <div style={{ display: 'flex', gap: 8 }}>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')} style={{ flex: 1 }}>
              返回列表
            </Button>
            <Button icon={<PlusOutlined />} onClick={() => navigate('/?new=1')} style={{ flex: 1 }}>
              新建
            </Button>
          </div>

          {/* 项目信息 */}
          <div style={{ background: '#fff', borderRadius: 10, padding: '16px', border: '1px solid #eee' }}>
            <div style={{ fontWeight: 800, fontSize: 17, color: '#1a1a2e', marginBottom: 8 }}>
              {displayTitle}
            </div>
            <Descriptions column={1} size="small" colon={false} style={{ marginBottom: 0 }}>
              <Descriptions.Item label={<Tag color={STATE_COLOR[project.state] || 'default'} style={{ margin: 0 }}>{STATE_TEXT[project.state] || project.state}</Tag>}>
                <Space size={4}>
                  <Tag color={project.mode === 'SOLO' ? 'default' : 'purple'} style={{ margin: 0, fontSize: 11 }}>
                    {project.mode === 'SOLO' ? '自动' : '交互'}
                  </Tag>
                  <Button size="small" type="link" style={{ padding: 0, height: 'auto', fontSize: 11 }}
                    onClick={() => handleModeSwitch(project.mode === 'SOLO' ? 'INTERACTIVE' : 'SOLO')}>
                    切换
                  </Button>
                  {project.state !== 'FINAL' && project.state !== 'PAUSED_AT_CHECKPOINT' && (
                    <Button size="small" type="link" style={{ padding: 0, height: 'auto', fontSize: 11 }}
                      onClick={async () => {
                        try { await api.pause(project.id); message.success('已暂停'); load(); }
                        catch (e) { message.error((e as Error).message); }
                      }}>
                      暂停
                    </Button>
                  )}
                  {project.state === 'PAUSED_AT_CHECKPOINT' && (
                    <Button size="small" type="link" style={{ padding: 0, height: 'auto', fontSize: 11 }}
                      onClick={async () => {
                        try { await api.resume(project.id); message.success('已继续'); load(); }
                        catch (e) { message.error((e as Error).message); }
                      }}>
                      续写
                    </Button>
                  )}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label={<span style={{ color: '#999', fontSize: 12 }}>进度</span>}>
                <span style={{ fontWeight: 600 }}>{project.currentChapterIndex} 章</span>
              </Descriptions.Item>
            </Descriptions>
            {project.concept && (
              <div style={{ marginTop: 10, padding: '8px 10px', background: '#f8f6f0', borderRadius: 6, fontSize: 12, color: '#666', lineHeight: 1.6, maxHeight: 80, overflow: 'auto' }}>
                💡 {project.concept}
              </div>
            )}
          </div>

          {/* 写作偏好面板 */}
          <Collapse
            ghost
            items={[{
              key: 'prefs',
              label: <span style={{ fontWeight: 600, fontSize: 13 }}><SettingOutlined /> 写作偏好</span>,
              children: (
                <div style={{ fontSize: 12 }}>
                  {prefs && (
                    <div style={{ marginBottom: 10 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                        <span style={{ color: '#666' }}>对话密度</span>
                        <span style={{ fontWeight: 600 }}>{Math.round((prefs.dialogueDensity as number) * 100)}%</span>
                      </div>
                      <Slider
                        min={10} max={70} step={5}
                        value={Math.round((prefs.dialogueDensity as number) * 100)}
                        onChange={async (v) => {
                          try {
                            setPrefs(await api.updatePreferences(id!, { dialogueDensity: v / 100 }));
                          } catch { /* ignore */ }
                        }}
                      />
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 }}>
                        <span style={{ color: '#666' }}>爽点轮换</span>
                        <Switch
                          size="small"
                          checked={prefs.enableClimaxRotation as boolean}
                          onChange={async (v) => {
                            try {
                              setPrefs(await api.updatePreferences(id!, { enableClimaxRotation: v }));
                            } catch { /* ignore */ }
                          }}
                        />
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 }}>
                        <span style={{ color: '#666' }}>角色声音</span>
                        <Switch
                          size="small"
                          checked={prefs.enableCharacterVoice as boolean}
                          onChange={async (v) => {
                            try {
                              setPrefs(await api.updatePreferences(id!, { enableCharacterVoice: v }));
                            } catch { /* ignore */ }
                          }}
                        />
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 }}>
                        <span style={{ color: '#666' }}>引用素材</span>
                        <Switch
                          size="small"
                          checked={prefs.enableKnowledgeQuotes as boolean}
                          onChange={async (v) => {
                            try {
                              setPrefs(await api.updatePreferences(id!, { enableKnowledgeQuotes: v }));
                            } catch { /* ignore */ }
                          }}
                        />
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 }}>
                        <span style={{ color: '#666' }}>检查点间隔</span>
                        <Select
                          size="small"
                          style={{ width: 110 }}
                          value={(prefs.checkpointSpan as number) ?? 1}
                          onChange={async (v) => {
                            try {
                              setPrefs(await api.updatePreferences(id!, { checkpointSpan: v }));
                            } catch { /* ignore */ }
                          }}
                          options={[
                            { value: 1, label: '每章' },
                            { value: 0, label: '仅手动' },
                            { value: -1, label: '卷末' },
                          ]}
                        />
                      </div>
                    </div>
                  )}
                  <div style={{ display: 'flex', gap: 6 }}>
                    <Input
                      size="small"
                      placeholder="对话调整偏好，如：增加古风对话"
                      value={chatMsg}
                      onChange={(e) => setChatMsg(e.target.value)}
                      onPressEnter={handleChat}
                      style={{ flex: 1 }}
                    />
                    <Button size="small" type="primary" icon={<SendOutlined />} loading={chatting} onClick={handleChat} />
                  </div>
                  {chatReply && (
                    <div style={{ marginTop: 6, padding: '6px 8px', background: '#f0f7ff', borderRadius: 4, fontSize: 11, color: '#1677ff' }}>
                      {chatReply}
                    </div>
                  )}
                </div>
              ),
            }]}
          />
        </Space>
      </Sider>

      <Content style={{ padding: '0 16px' }}>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          destroyInactiveTabPane
          items={[
            { key: 'run', label: '🎮 运行控制台', children: <RunConsole projectId={project.id} projectState={project.state} onChanged={load} /> },
            { key: 'world', label: '🌍 世界观与人物', children: <WorldbuildingView key={`world-${project.currentChapterIndex}`} projectId={project.id} /> },
            { key: 'outline', label: '📋 大纲', children: <OutlineView key={`outline-${project.state}-${project.currentChapterIndex}`} projectId={project.id} currentChapter={project.currentChapterIndex} /> },
            { key: 'review', label: '📖 章节管理', children: <InteractiveReview key={`review-${project.id}-${project.currentChapterIndex}`} projectId={project.id} onChanged={load} /> },
            { key: 'quality', label: '🔍 质量评价', children: <QualityView projectId={project.id} /> },
          ]}
        />
      </Content>
    </Layout>
  );
}
