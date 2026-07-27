import { useState, useEffect, useCallback } from 'react';
import { Card, Button, Space, List, Typography, Tag, Modal, Input, message, Row, Col, Alert, Spin } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, EditOutlined, ReloadOutlined, StepForwardOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import { renderContent } from '../utils/renderContent';
import type { Chapter, Checkpoint, ProjectStatus } from '../types';

const { Text } = Typography;

interface Props {
  projectId: string;
  onChanged: () => void;
}

export default function InteractiveReview({ projectId, onChanged }: Props) {
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [checkpoints, setCheckpoints] = useState<Checkpoint[]>([]);
  const [status, setStatus] = useState<ProjectStatus | null>(null);
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [loading, setLoading] = useState(false);
  const [rewriteModalOpen, setRewriteModalOpen] = useState(false);
  const [rewriteInstructions, setRewriteInstructions] = useState('');
  const [rewriting, setRewriting] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [chs, cps, st] = await Promise.all([
        api.getChapters(projectId),
        api.listCheckpoints(projectId),
        api.getStatus(projectId),
      ]);
      setChapters(chs);
      setCheckpoints(cps);
      setStatus(st);
      if (chs.length > 0 && selectedIdx >= chs.length) {
        setSelectedIdx(chs.length - 1);
      }
    } catch (e) {
      message.error(`加载失败: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [projectId, selectedIdx]);

  useEffect(() => { refresh(); }, [refresh]);

  const isPaused = status?.state === 'PAUSED_AT_CHECKPOINT';
  const hasPendingCheckpoint = checkpoints.length > 0;
  const currentCheckpoint = checkpoints[checkpoints.length - 1];
  const isInteractive = status?.mode === 'INTERACTIVE';
  const currentChapter = chapters[selectedIdx];

  const handleApprove = async () => {
    if (!currentCheckpoint) { message.warning('无待审核项'); return; }
    setLoading(true);
    try {
      await api.approveCheckpoint(projectId, currentCheckpoint.id);
      message.success('已批准');
      await refresh();
      onChanged();
    } catch (e) {
      message.error(`批准失败: ${(e as Error).message}`);
    } finally { setLoading(false); }
  };

  const handleReject = async () => {
    if (!currentCheckpoint) { message.warning('无待审核项'); return; }
    setRewriteModalOpen(true);
    setRewriteInstructions('');
  };

  const handleModify = async () => {
    if (!currentCheckpoint) { message.warning('无待审核项'); return; }
    setRewriteModalOpen(true);
    setRewriteInstructions('');
  };

  const submitRewrite = async (isReject: boolean) => {
    if (!currentCheckpoint) return;
    setRewriting(true);
    try {
      if (isReject) {
        await api.rejectCheckpoint(projectId, currentCheckpoint.id, rewriteInstructions);
        message.success('已拒绝并重写');
      } else {
        await api.modifyCheckpoint(projectId, currentCheckpoint.id, rewriteInstructions);
        message.success('已修改并批准');
      }
      setRewriteModalOpen(false);
      await refresh();
      onChanged();
    } catch (e) {
      message.error(`操作失败: ${(e as Error).message}`);
    } finally { setRewriting(false); }
  };

  const handleWriteNext = async () => {
    setLoading(true);
    try {
      await api.writeChapter(projectId);
      message.success('新章节已完成');
      await refresh();
      onChanged();
    } catch (e) {
      message.error(`写作失败: ${(e as Error).message}`);
    } finally { setLoading(false); }
  };

  const handleRewriteChapter = async () => {
    if (!currentChapter) return;
    setRewriting(true);
    try {
      await api.rewriteChapter(projectId, currentChapter.chapterIndex, rewriteInstructions);
      message.success(`第${currentChapter.chapterIndex}章已重写`);
      setRewriteModalOpen(false);
      await refresh();
    } catch (e) {
      message.error(`重写失败: ${(e as Error).message}`);
    } finally { setRewriting(false); }
  };
  void handleRewriteChapter; // used via Modal footer for standalone rewrite

  const renderChapterContent = (content: string) => {
    if (!content) return <Text type="secondary">（空）</Text>;
    return renderContent(content);
  };

  return (
    <div>
      <Spin spinning={loading}>
        {/* 状态栏 */}
        {isPaused && hasPendingCheckpoint && (
          <Alert
            type="warning"
            message={`⏸ 待审核：${currentCheckpoint.description}`}
            description={
              <Space>
                <Button type="primary" icon={<CheckCircleOutlined />} onClick={handleApprove} loading={loading}>
                  ✅ 批准并继续
                </Button>
                <Button danger icon={<CloseCircleOutlined />} onClick={handleReject} loading={loading}>
                  ❌ 拒绝并重写
                </Button>
                <Button icon={<EditOutlined />} onClick={handleModify} loading={loading}>
                  📝 修改意见重写
                </Button>
              </Space>
            }
            style={{ marginBottom: 16 }}
          />
        )}

        {/* 状态提示 */}
        {status?.state === 'FINAL' && (
          <Alert type="success" message={`✅ 全书已完结，共 ${status.currentChapter} 章`} style={{ marginBottom: 16 }} />
        )}
        {status?.state === 'WRITING' && !isPaused && (
          <Alert type="info" message="📝 写作中 — 可查看和重写已完成的章节" style={{ marginBottom: 16 }} />
        )}

        {/* 写作控制 */}
        {status && (status.state === 'WRITING' || status.state === 'VOLUME_DONE') && (
          <Space style={{ marginBottom: 16 }}>
            <Button type="primary" size="large" icon={<StepForwardOutlined />} onClick={handleWriteNext} loading={loading}>
              {isInteractive ? '📝 写一章（写完自动暂停）' : '📝 写一章'}
            </Button>
          </Space>
        )}

        <Row gutter={16}>
          {/* 章节列表 */}
          <Col span={6}>
            <Card title={`📖 章节 (${chapters.length})`} size="small" style={{ maxHeight: '70vh', overflow: 'auto' }}>
              <List
                size="small"
                dataSource={chapters}
                renderItem={(ch, idx) => (
                  <List.Item
                    onClick={() => setSelectedIdx(idx)}
                    style={{ cursor: 'pointer', background: idx === selectedIdx ? '#e6f7ff' : undefined }}
                  >
                    <List.Item.Meta
                      title={<Text strong>第{ch.chapterIndex}章 {ch.title.replace(/\*\*/g, '')}</Text>}
                      description={<Tag>{ch.wordCount} 字</Tag>}
                    />
                  </List.Item>
                )}
              />
            </Card>
          </Col>

          {/* 章节正文 */}
          <Col span={18}>
            {currentChapter ? (
              <Card
                title={`第${currentChapter.chapterIndex}章 ${currentChapter.title.replace(/\*\*/g, '')}`}
                extra={
                  <Space>
                    <Tag color="blue">{currentChapter.wordCount} 字</Tag>
                    <Tag color="purple">v{currentChapter.version}</Tag>
                    <Button size="small" icon={<EditOutlined />} onClick={() => { setRewriteInstructions(''); setRewriteModalOpen(true); }}>
                      重写此章
                    </Button>
                  </Space>
                }
                style={{ maxHeight: '70vh', overflow: 'auto' }}
              >
                {renderChapterContent(currentChapter.content)}
              </Card>
            ) : (
              <Card><Text type="secondary">暂无章节。点击"写一章"开始创作。</Text></Card>
            )}
          </Col>
        </Row>

        {/* 刷新按钮 */}
        <Button icon={<ReloadOutlined />} onClick={refresh} style={{ marginTop: 16 }} loading={loading}>
          刷新
        </Button>
      </Spin>

      {/* 重写/拒绝 对话框 */}
      <Modal
        title="修改意见"
        open={rewriteModalOpen}
        onCancel={() => setRewriteModalOpen(false)}
        footer={[
          <Button key="cancel" onClick={() => setRewriteModalOpen(false)}>取消</Button>,
          <Button key="modify" type="primary" loading={rewriting} onClick={() => submitRewrite(false)}>
            重写后批准
          </Button>,
          <Button key="reject" danger loading={rewriting} onClick={() => submitRewrite(true)}>
            重写（拒绝当前版本）
          </Button>,
        ]}
      >
        <Input.TextArea
          rows={4}
          placeholder="请输入修改意见，如：增加对话、减少打斗、补充人物心理..."
          value={rewriteInstructions}
          onChange={e => setRewriteInstructions(e.target.value)}
        />
      </Modal>
    </div>
  );
}
