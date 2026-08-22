import { useState, useEffect, useCallback } from 'react';
import { Card, Button, Space, List, Typography, Tag, Modal, Input, message, Row, Col, Alert, Spin, Progress, Collapse } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, EditOutlined, ReloadOutlined, StepForwardOutlined, TrophyOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import { renderContent } from '../utils/renderContent';
import type { Chapter, Checkpoint, ProjectStatus, ChapterQualityReport } from '../types';

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
  const [qualityReport, setQualityReport] = useState<ChapterQualityReport | null>(null);
  // p11-G2: 段落编辑状态
  const [selectedParagraph, setSelectedParagraph] = useState<{ index: number; text: string } | null>(null);
  const [editMode, setEditMode] = useState<'REWRITE' | 'EXPAND' | 'INSERT'>('REWRITE');
  const [editInstruction, setEditInstruction] = useState('');
  const [editing, setEditing] = useState(false);

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

  // p11-I3: 选中章节时获取质量报告
  useEffect(() => {
    if (!currentChapter) { setQualityReport(null); return; }
    api.getChapterQuality(projectId, currentChapter.chapterIndex)
      .then(setQualityReport)
      .catch(() => setQualityReport(null));
  }, [projectId, currentChapter]);

  // p11-G2: 段落编辑
  const handleParagraphClick = (index: number, text: string) => {
    setSelectedParagraph({ index, text });
    setEditInstruction('');
  };

  const handleEditPassage = async () => {
    if (!currentChapter || !selectedParagraph) return;
    setEditing(true);
    try {
      await api.editPassage(projectId, currentChapter.chapterIndex, {
        paragraphIndex: selectedParagraph.index,
        selectedText: selectedParagraph.text,
        instruction: editInstruction,
        mode: editMode,
      });
      message.success(`段落${editMode === 'REWRITE' ? '重写' : editMode === 'EXPAND' ? '扩充' : '插入'}完成`);
      setSelectedParagraph(null);
      await refresh();
    } catch (e) {
      message.error('段落编辑失败: ' + (e as Error).message);
    } finally {
      setEditing(false);
    }
  };

  const handleApprove = async (checkpointId?: string) => {
    const id = checkpointId ?? currentCheckpoint?.id;
    if (!id) { message.warning('无待审核项'); return; }
    setLoading(true);
    try {
      await api.approveCheckpoint(projectId, id);
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
    return renderContent(content, handleParagraphClick);
  };

  return (
    <div>
      <Spin spinning={loading}>
        {/* 状态栏 */}
        {isPaused && hasPendingCheckpoint && checkpoints.map(cp => (
          <Alert
            key={cp.id}
            type="warning"
            message={`⏸ 待审核：${cp.description}`}
            description={
              <Space>
                <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => handleApprove(cp.id)} loading={loading}>
                  ✅ 批准
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
        ))}

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
                      description={
                        <Space size={4}>
                          <Tag>{ch.wordCount} 字</Tag>
                          {ch.status === 'NEEDS_MANUAL_REVIEW' && <Tag color="red">待人审</Tag>}
                        </Space>
                      }
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
                    {currentChapter.status === 'NEEDS_MANUAL_REVIEW' && (
                      <Button size="small" type="primary" onClick={async () => {
                        try {
                          await api.resolveManualReview(projectId, currentChapter.chapterIndex);
                          message.success('已标为处理完毕');
                          refresh();
                          onChanged();
                        } catch (err) {
                          message.error((err as Error).message);
                        }
                      }}>已处理</Button>
                    )}
                    <Button size="small" icon={<EditOutlined />} onClick={() => { setRewriteInstructions(''); setRewriteModalOpen(true); }}>
                      重写此章
                    </Button>
                  </Space>
                }
                style={{ maxHeight: '70vh', overflow: 'auto' }}
              >
                {renderChapterContent(currentChapter.content)}

                {/* p11-G2: 段落编辑面板 */}
                {selectedParagraph && (
                  <Card size="small" title="段落编辑" style={{ marginTop: 16, borderColor: '#1890ff' }}
                    extra={<Button size="small" type="text" onClick={() => setSelectedParagraph(null)}>✕</Button>}>
                    <div style={{ marginBottom: 8, padding: 8, background: '#f6f8fa', borderRadius: 4, fontSize: 13 }}>
                      <Text type="secondary">选中段落（第 {selectedParagraph.index} 段）：</Text>
                      <div style={{ marginTop: 4 }}>{selectedParagraph.text.substring(0, 100)}
                        {selectedParagraph.text.length > 100 ? '...' : ''}</div>
                    </div>
                    <div style={{ marginBottom: 6, color: '#888', fontSize: 12 }}>编辑方式（选好后再点右下角执行）</div>
                    <Space style={{ marginBottom: 8 }}>
                      {(['REWRITE', 'EXPAND', 'INSERT'] as const).map(m => (
                        <Button key={m} size="small" type={editMode === m ? 'primary' : 'default'}
                          onClick={() => {
                            setEditMode(m);
                            if (editMode === m) {
                              message.info('已是该方式，请填写指令后点「执行」');
                            }
                          }}>
                          {m === 'REWRITE' ? '改写' : m === 'EXPAND' ? '扩充' : '在其后插入'}
                        </Button>
                      ))}
                    </Space>
                    <Input.TextArea
                      rows={3}
                      value={editInstruction}
                      onChange={e => setEditInstruction(e.target.value)}
                      placeholder={editMode === 'REWRITE' ? '改写指令，如：增加对话、改善节奏...' :
                        editMode === 'EXPAND' ? '扩充指令，如：增加感官细节、心理描写...' :
                        '插入内容描述，如：插入一段角色对话...'}
                    />
                    <div style={{ marginTop: 8, textAlign: 'right' }}>
                      <Button type="primary" size="small" loading={editing} onClick={handleEditPassage}
                        disabled={!editInstruction.trim()}>
                        执行{editMode === 'REWRITE' ? '改写' : editMode === 'EXPAND' ? '扩充' : '插入'}
                      </Button>
                    </div>
                  </Card>
                )}
                {qualityReport && (
                  <div style={{ marginTop: 24, borderTop: '1px solid #f0f0f0', paddingTop: 16 }}>
                    <Typography.Title level={5}>
                      <TrophyOutlined /> 章节质量报告
                      <Tag color={qualityReport.grade === 'A' ? 'green' : qualityReport.grade === 'B' ? 'gold' : 'red'} style={{ marginLeft: 8 }}>
                        {qualityReport.grade} 级
                      </Tag>
                    </Typography.Title>

                    {/* 读者评分 */}
                    {qualityReport.review && (
                      <Row gutter={16} style={{ marginBottom: 16 }}>
                        <Col span={4}>
                          <div style={{ textAlign: 'center' }}>
                            <div style={{ fontSize: 28, fontWeight: 700, color: qualityReport.review.overallScore >= 7 ? '#52c41a' : '#faad14' }}>
                              {qualityReport.review.overallScore}
                            </div>
                            <Text type="secondary">综合评分</Text>
                          </div>
                        </Col>
                        {[
                          { label: '吸引力', val: qualityReport.review.engagement },
                          { label: '连贯性', val: qualityReport.review.coherence },
                          { label: '角色塑造', val: qualityReport.review.character },
                          { label: '章末钩子', val: qualityReport.review.hook },
                        ].map(d => (
                          <Col span={5} key={d.label}>
                            <Text type="secondary">{d.label}</Text>
                            <Progress percent={d.val * 10} size="small" format={() => `${d.val}/10`} />
                          </Col>
                        ))}
                      </Row>
                    )}

                    {/* 读者简评 */}
                    {qualityReport.review?.summary && (
                      <Alert type="info" message={qualityReport.review.summary} style={{ marginBottom: 8 }} />
                    )}

                    {/* 改进建议 */}
                    {qualityReport.review?.improvement && (
                      <Alert type="warning" message="改进建议" description={qualityReport.review.improvement} style={{ marginBottom: 8 }} />
                    )}

                    {/* 问题列表 */}
                    {qualityReport.issues.length > 0 && (
                      <Collapse
                        size="small"
                        style={{ marginBottom: 8 }}
                        items={[{
                          key: 'issues',
                          label: `检测问题（${qualityReport.issues.length} 项）`,
                          children: (
                            <List
                              size="small"
                              dataSource={qualityReport.issues}
                              renderItem={item => (
                                <List.Item>
                                  <Tag color={item.severity === 'HIGH' ? 'red' : item.severity === 'MEDIUM' ? 'orange' : 'default'}>
                                    {item.severity}
                                  </Tag>
                                  <Tag>{item.type}</Tag>
                                  <Text style={{ fontSize: 12 }}>{item.description}</Text>
                                </List.Item>
                              )}
                            />
                          ),
                        }]}
                      />
                    )}

                    {/* 套路词统计 */}
                    {qualityReport.clicheCount > 0 && (
                      <div style={{ fontSize: 12, color: '#999' }}>
                        套路词出现 {qualityReport.clicheCount} 次
                        {qualityReport.topCliches.length > 0 && `：${qualityReport.topCliches.join('、')}`}
                      </div>
                    )}
                  </div>
                )}
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
