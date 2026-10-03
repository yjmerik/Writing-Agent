import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { Layout, Tabs, Button, Descriptions, Tag, Space, message, Spin, Collapse, Input, Slider, Switch } from 'antd';
import { ArrowLeftOutlined, CloseOutlined, MenuUnfoldOutlined, PlusOutlined, SettingOutlined, SendOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { Project } from '../types';
import RunConsole from '../components/RunConsole';
import IntentView from '../components/IntentView';
import IdentityBar from '../components/IdentityBar';
import RevisionWorkspace from '../components/RevisionWorkspace';
import WorldbuildingView from '../components/WorldbuildingView';
import OutlineView from '../components/OutlineView';
import InteractiveReview from '../components/InteractiveReview';
import QualityView from '../components/QualityView';
import CollaborationBar from '../components/CollaborationBar';
import TasksView from '../components/TasksView';
import BookReviewView from '../components/BookReviewView';

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

/** p36-08: 页签是路由权威的一部分——合法页签写入查询参数，非法/缺省回退 review */
const VALID_TABS = ['review', 'world', 'outline', 'intent', 'revisions', 'book', 'quality', 'run', 'tasks'] as const;
type WorkspaceTab = (typeof VALID_TABS)[number];

const isWorkspaceTab = (value: string | null): value is WorkspaceTab =>
  value != null && (VALID_TABS as readonly string[]).includes(value);

export default function ProjectWorkspace() {
  const { id } = useParams<{ id: string }>();
  // p34-05: 深链接恢复章节定位（#/project/:id/chapter/:n）
  const params = useParams<{ id: string; chapterIndex?: string }>();
  const routeChapter = params.chapterIndex ? Number(params.chapterIndex) : undefined;
  const navigate = useNavigate();
  // p36-08: 页签与历史版本都是路由权威——后退/前进与刷新必须还原
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const activeTab: WorkspaceTab = isWorkspaceTab(tabParam) ? tabParam : 'review';
  const versionParam = searchParams.get('version');
  const historyView = versionParam != null && Number.isFinite(Number(versionParam))
    ? { version: Number(versionParam), anchor: searchParams.get('anchor') ?? undefined }
    : undefined;
  // NCS-071: ?adjudicate=ISSUE_ID 深链——预开对应问题的裁决表单
  const adjudicateIssue = searchParams.get('adjudicate') ?? undefined;
  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);
  const [prefs, setPrefs] = useState<Record<string, unknown> | null>(null);
  const [refreshSignal, setRefreshSignal] = useState(0);
  // p33-04: 待办直达——状态栏待审按钮聚焦到指定章节
  const [focusChapter, setFocusChapter] = useState<number | undefined>(routeChapter);
  const [chatMsg, setChatMsg] = useState('');
  const [chatReply, setChatReply] = useState('');
  const [chatting, setChatting] = useState(false);
  const [narrowLayout, setNarrowLayout] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [reviewPanelOpen, setReviewPanelOpen] = useState(false);
  const [readingMode, setReadingMode] = useState(false);

  useEffect(() => { setReviewPanelOpen(false); setReadingMode(false); }, [id]);

  // p36-08: 路由参数变化（后退/前进/深链接）必须响应，不能只在初次渲染读取
  useEffect(() => { setFocusChapter(routeChapter); }, [routeChapter]);

  /** p36-08: 目录点击/待办跳转统一更新路由（页签保留在查询参数中）。 */
  const handleSelectChapter = useCallback((chapterIndex: number) => {
    if (!id) return;
    const search = activeTab !== 'review' ? `?tab=${activeTab}` : '';
    navigate(`/project/${id}/chapter/${chapterIndex}${search}`);
  }, [activeTab, id, navigate]);

  const handleTabChange = useCallback((tab: string) => {
    if (tab !== 'review') setReviewPanelOpen(false);
    const params = new URLSearchParams();
    if (tab !== 'review') params.set('tab', tab);
    setSearchParams(params, { replace: false });
  }, [setSearchParams]);

  const load = useCallback(async () => {
    if (!id) return;
    try {
      const [p, pr] = await Promise.all([
        api.getProject(id),
        api.getPreferences(id).catch(() => null),
      ]);
      setProject(p);
      setRefreshSignal(v => v + 1);
      setPrefs(pr);
    } catch (e) {
      message.error(`加载项目失败: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

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
    <Layout data-review-workspace style={{ background: 'transparent', minHeight: '100vh', position: 'relative' }}>
      <style>{'body:has([data-review-workspace]) { margin: 0; }'}</style>
      {narrowLayout && sidebarCollapsed && !readingMode && !reviewPanelOpen && (
        <Button
          data-sidebar-toggle
          type="primary"
          icon={<MenuUnfoldOutlined />}
          aria-label="打开项目侧栏"
          onClick={() => setSidebarCollapsed(false)}
          style={{ position: 'fixed', top: 76, left: 8, zIndex: 1001, width: 44, height: 44 }}
        />
      )}
      {/* p21-06: 响应式侧栏——小于 lg 自动折叠，375px 下无页面级横向溢出 */}
      <Sider
        width={240}
        breakpoint="lg"
        collapsedWidth="0"
        collapsible
        collapsed={sidebarCollapsed}
        trigger={null}
        onBreakpoint={(broken) => {
          setNarrowLayout(broken);
          setSidebarCollapsed(broken);
        }}
        onCollapse={setSidebarCollapsed}
        theme="light"
        style={{
          display: readingMode ? 'none' : undefined,
          padding: narrowLayout && sidebarCollapsed ? 0 : '16px 12px',
          background: '#fafaf8',
          borderRight: narrowLayout && sidebarCollapsed ? 'none' : '1px solid #eee',
          ...(narrowLayout ? {
            position: 'fixed',
            top: 64,
            bottom: 0,
            left: 0,
            zIndex: 1000,
            overflowY: 'auto',
            boxShadow: sidebarCollapsed ? 'none' : '4px 0 18px rgba(0,0,0,0.16)',
            // NCS-085（听雪刀 P2-3）：塌缩到 width=0 后固定层不再拦截内容点击
            // （恢复按钮为独立兄弟元素，zIndex 1001，不受影响）
            pointerEvents: sidebarCollapsed ? 'none' : undefined,
          } : {}),
        }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          {/* 顶部操作 */}
          <div style={{ display: 'flex', gap: 8 }}>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/')} style={{ flex: 1, minHeight: narrowLayout ? 44 : undefined }}>
              返回列表
            </Button>
            <Button icon={<PlusOutlined />} onClick={() => navigate('/?new=1')} style={{ flex: 1, minHeight: narrowLayout ? 44 : undefined }}>
              新建
            </Button>
            {narrowLayout && (
              <Button
                icon={<CloseOutlined />}
                aria-label="关闭项目侧栏"
                onClick={() => setSidebarCollapsed(true)}
                style={{ flex: '0 0 44px', width: 44, height: 44 }}
              />
            )}
          </div>

          {/* 项目信息 */}
          <div style={{ background: '#fff', borderRadius: 10, padding: '16px', border: '1px solid #eee' }}>
            <div style={{ fontWeight: 800, fontSize: 17, color: '#1a1a2e', marginBottom: 8 }}>
              {displayTitle}
            </div>
            <Descriptions column={1} size="small" colon={false} style={{ marginBottom: 0 }}>
              <Descriptions.Item label={<Tag color={STATE_COLOR[project.state] || 'default'} style={{ margin: 0 }}>{STATE_TEXT[project.state] || project.state}</Tag>}>
                <Tag color="purple">人机协作</Tag>
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
                          } catch (e) {
                            message.error(`对话密度保存失败: ${(e as Error).message}`);
                          }
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
                            } catch (e) {
                              message.error(`爽点轮换保存失败: ${(e as Error).message}`);
                            }
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
                            } catch (e) {
                              message.error(`角色声音保存失败: ${(e as Error).message}`);
                            }
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
                            } catch (e) {
                              message.error(`引用素材保存失败: ${(e as Error).message}`);
                            }
                          }}
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

      <Content style={{ padding: narrowLayout ? '52px 0 0' : '0 16px', minWidth: 0, width: '100%' }}>
        <CollaborationBar projectId={project.id} onChanged={load} onReview={() => { handleTabChange('review'); setReviewPanelOpen(true); }}
          onSelectChapter={(chapterIndex) => {
            navigate(`/project/${project.id}/chapter/${chapterIndex}`);
            setReviewPanelOpen(true);
          }} />
        <IdentityBar projectId={project.id} onOpenCandidates={(chapterIndex) => {
          // NCS-176: 状态条候选计数一键直达首个候选章节的审阅面板
          if (chapterIndex != null) navigate(`/project/${project.id}/chapter/${chapterIndex}`);
          handleTabChange('review');
          setReviewPanelOpen(true);
        }} />
        <Tabs
          activeKey={activeTab}
          onChange={handleTabChange}
          destroyInactiveTabPane
          items={[
            { key: 'tasks', label: '任务与版本', children: <TasksView key={project.id} projectId={project.id} onChanged={load} /> },
            { key: 'run', label: '创作工具', children: <RunConsole projectId={project.id} projectState={project.state} onChanged={load} /> },
            { key: 'world', label: '🌍 世界观与人物', children: <WorldbuildingView key={`world-${project.id}`} projectId={project.id} currentChapter={project.currentChapterIndex} /> },
            { key: 'outline', label: '📋 大纲', children: <OutlineView key={`outline-${project.id}`} projectId={project.id} currentChapter={project.currentChapterIndex} /> },
            { key: 'review', label: '📖 章节管理', children: <InteractiveReview key={`review-${project.id}`} refreshSignal={refreshSignal} projectId={project.id} onChanged={load} onNavigateTab={handleTabChange} focusChapter={focusChapter} onSelectChapter={handleSelectChapter} historyView={historyView} adjudicateIssue={adjudicateIssue} reviewPanelOpen={reviewPanelOpen} onReviewPanelChange={setReviewPanelOpen} readingMode={readingMode} onReadingModeChange={setReadingMode} /> },
            { key: 'quality', label: '🔍 质量评价', children: <QualityView projectId={project.id} /> },
            { key: 'revisions', label: '🛠 修订', children: <RevisionWorkspace key={`rev-${project.id}`} projectId={project.id} currentChapter={project.currentChapterIndex} /> },
            { key: 'intent', label: '📜 创作约定', children: <IntentView key={`intent-${project.id}`} projectId={project.id} projectState={project.state} currentChapter={project.currentChapterIndex} /> },
                        { key: 'book', label: '📕 成书审查', children: <BookReviewView projectId={project.id} /> },
          ]}
        />
      </Content>
    </Layout>
  );
}
