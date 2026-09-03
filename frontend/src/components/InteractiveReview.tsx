import { useState, useEffect, useCallback } from 'react';
import { Card, Button, Space, List, Typography, Tag, Modal, Input, message, Row, Col, Alert, Spin, Progress, Collapse } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, EditOutlined, ReloadOutlined, StepForwardOutlined, TrophyOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { CheckpointRevision } from '../api/client';
import { renderContent } from '../utils/renderContent';
import type { Chapter, Checkpoint, ProjectStatus, ChapterQualityReport } from '../types';

const { Text } = Typography;

interface Props {
  projectId: string;
  onChanged: () => void;
}

type RewriteTarget = {
  kind: 'checkpoint';
  checkpoint: Checkpoint;
  action: 'reject' | 'modify';
};

type ReviewResult = RewriteTarget & { revision: CheckpointRevision };

export default function InteractiveReview({ projectId, onChanged }: Props) {
  // p21-05: 元数据一次加载；正文按选中章节懒加载缓存
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [bodies, setBodies] = useState<Record<number, Chapter>>({});
  const [checkpoints, setCheckpoints] = useState<Checkpoint[]>([]);
  const [status, setStatus] = useState<ProjectStatus | null>(null);
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [loading, setLoading] = useState(false);
  const [rewriteModalOpen, setRewriteModalOpen] = useState(false);
  const [rewriteTarget, setRewriteTarget] = useState<RewriteTarget | null>(null);
  const [rewriteInstructions, setRewriteInstructions] = useState('');
  const [rewriting, setRewriting] = useState(false);
  const [reviewResult, setReviewResult] = useState<ReviewResult | null>(null);
  const [qualityReport, setQualityReport] = useState<ChapterQualityReport | null>(null);
  // p11-G2: 段落编辑状态
  const [selectedParagraph, setSelectedParagraph] = useState<{ index: number; text: string } | null>(null);
  const [editMode, setEditMode] = useState<'REWRITE' | 'EXPAND' | 'INSERT'>('REWRITE');
  const [editInstruction, setEditInstruction] = useState('');
  const [editing, setEditing] = useState(false);

  // p21-05 修复: refresh 只依赖 projectId——selectedIdx 变化（点选章节）不得触发
  // 元数据/checkpoints/status 重取（此前 useCallback 依赖 selectedIdx，点章即全量重取）
  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [page, cps, st] = await Promise.all([
        api.getChapterPage(projectId, 0, 1000),
        api.listCheckpoints(projectId),
        api.getStatus(projectId),
      ]);
      const metas = page.chapters || [];
      setChapters(metas);
      setBodies({}); // 元数据刷新后正文缓存失效，由懒加载按需重建
      setCheckpoints(cps);
      setStatus(st);
      setSelectedIdx((idx) => (metas.length > 0 && idx >= metas.length ? metas.length - 1 : idx));
    } catch (e) {
      message.error(`加载失败: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { refresh(); }, [refresh]);

  const isPaused = status?.state === 'PAUSED_AT_CHECKPOINT';
  const hasPendingCheckpoint = checkpoints.length > 0;
  const isInteractive = status?.mode === 'INTERACTIVE';
  const currentMeta = chapters[selectedIdx];

  // p21-05: 选中章节正文懒加载（不重取 checkpoints/status，更不全量取章节正文）
  useEffect(() => {
    if (!currentMeta) return;
    if (bodies[currentMeta.chapterIndex]) return;
    let cancelled = false;
    api.getChapter(projectId, currentMeta.chapterIndex)
      .then((full) => {
        if (cancelled || !full) return;
        setBodies((prev) => ({ ...prev, [currentMeta.chapterIndex]: full }));
      })
      .catch(() => { /* 正文加载失败不阻塞元信息展示 */ });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, currentMeta?.chapterIndex]);

  const currentChapter = currentMeta
    ? (bodies[currentMeta.chapterIndex] ?? currentMeta)
    : undefined;

  // p11-I3: 选中章节时获取质量报告
  useEffect(() => {
    if (!currentMeta) { setQualityReport(null); return; }
    api.getChapterQuality(projectId, currentMeta.chapterIndex)
      .then(setQualityReport)
      .catch(() => setQualityReport(null));
  }, [projectId, currentMeta]);

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

  const handleApprove = async (checkpoint: Checkpoint) => {
    setLoading(true);
    try {
      await api.approveCheckpoint(projectId, checkpoint.id);
      message.success('已批准');
      await refresh();
      onChanged();
    } catch (e) {
      message.error(`批准失败: ${(e as Error).message}`);
    } finally { setLoading(false); }
  };

  const openCheckpointRewrite = (checkpoint: Checkpoint, action: RewriteTarget['action']) => {
    setRewriteTarget({ kind: 'checkpoint', checkpoint, action });
    setRewriteModalOpen(true);
    setRewriteInstructions('');
  };

  const submitCheckpointRewrite = async () => {
    if (!rewriteTarget) return;
    setRewriting(true);
    try {
      const result = rewriteTarget.action === 'reject'
        ? await api.rejectCheckpoint(projectId, rewriteTarget.checkpoint.id, rewriteInstructions)
        : await api.modifyCheckpoint(projectId, rewriteTarget.checkpoint.id, rewriteInstructions);
      if (result.revision) {
        setReviewResult({ ...rewriteTarget, revision: result.revision });
      }
      if (rewriteTarget.action === 'reject') {
        message.success('已拒绝并重写');
      } else {
        message.success('已修改并批准');
      }
      setRewriteModalOpen(false);
      setRewriteTarget(null);
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
        {isPaused && hasPendingCheckpoint && checkpoints.map(cp => {
          let payload: import('../types').CheckpointPayload | null = null;
          try { payload = cp.payload ? JSON.parse(cp.payload) : null; } catch { payload = null; }
          return (
          <Alert
            key={cp.id}
            type="warning"
            message={`⏸ 待审核：${cp.description}`}
            description={
              <div>
              <div style={{ marginBottom: 8, fontSize: 13 }}>
                审核目标：{cp.type}
              </div>
              {payload && (
                <div style={{ marginBottom: 8, fontSize: 13 }}>
                  {payload.score != null && (
                    <span style={{ marginRight: 12 }}>
                      读者评分：<b style={{ color: payload.score >= 8 ? '#52c41a' : payload.score >= 7 ? '#faad14' : '#ff4d4f' }}>{payload.score}/10</b>
                    </span>
                  )}
                  {payload.wordCount != null && <span style={{ marginRight: 12 }}>字数：{payload.wordCount.toLocaleString?.() ?? payload.wordCount}</span>}
                  {payload.unresolvedHigh != null && payload.unresolvedHigh > 0 && (
                    <span style={{ marginRight: 12, color: '#ff4d4f' }}>未解决 HIGH 问题：{payload.unresolvedHigh}</span>
                  )}
                  {payload.preview && (
                    <div style={{ color: '#888', marginTop: 4, maxHeight: 60, overflow: 'hidden' }}>
                      {payload.preview}…
                    </div>
                  )}
                </div>
              )}
              <Space>
                <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => handleApprove(cp)} loading={loading}>
                  ✅ 批准
                </Button>
                <Button danger icon={<CloseCircleOutlined />} onClick={() => openCheckpointRewrite(cp, 'reject')} loading={loading}>
                  ❌ 拒绝并重写
                </Button>
                <Button icon={<EditOutlined />} onClick={() => openCheckpointRewrite(cp, 'modify')} loading={loading}>
                  📝 修改意见重写
                </Button>
              </Space>
              </div>
            }
            style={{ marginBottom: 16 }}
          />
          );
        })}

        {reviewResult && (
          <Alert
            type="success"
            message={`审核操作完成：${reviewResult.checkpoint.description}`}
            description={
              <div>
                <div>审核目标：{reviewResult.revision.targetType}</div>
                <div>修改前摘要（v{reviewResult.revision.beforeVersion}）：{reviewResult.revision.beforeSummary}</div>
                <div>修改后摘要（v{reviewResult.revision.afterVersion}）：{reviewResult.revision.afterSummary}</div>
              </div>
            }
            style={{ marginBottom: 16 }}
          />
        )}

        {/* 状态提示 */}
        {status?.state === 'FINAL' && (
          <Alert type="success" message={`✅ 全书已完结，共 ${status.currentChapter} 章`} style={{ marginBottom: 16 }} />
        )}
        {status?.state === 'NEEDS_REVISION' && (
          <Alert
            type="error"
            message="⛔ 成书门禁未通过（待修订）"
            description="全书存在未达标的硬性门禁（如 mustResolve 伏笔未回收、近 60 章 HIGH 级矛盾超限）。请修订设定或正文后，点击「重新评审」再次触发成书门禁；通过后全书进入「已完结」。"
            style={{ marginBottom: 16 }}
            action={
              <Button
                type="primary"
                danger
                onClick={async () => {
                  try {
                    const r = await api.finalAudit(projectId);
                    if (r.passed) { message.success('评审通过，全书已完结'); } else { message.warning('评审未通过，请继续修订'); }
                    onChanged();
                  } catch (e) { message.error((e as Error).message); }
                }}
              >
                重新评审
              </Button>
            }
          />
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
          {/* 章节列表（p21-06: 小屏堆叠） */}
          <Col xs={24} md={6}>
            <Card title={`📖 章节 (${chapters.length})`} size="small" style={{ maxHeight: '70vh', overflow: 'auto' }}>
              <List
                size="small"
                dataSource={chapters}
                renderItem={(ch, idx) => (
                  <List.Item
                    role="button"
                    tabIndex={0}
                    aria-label={`第${ch.chapterIndex}章 ${ch.title}`}
                    onClick={() => setSelectedIdx(idx)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        setSelectedIdx(idx);
                      }
                    }}
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

          {/* 章节正文（p21-06: 小屏堆叠） */}
          <Col xs={24} md={18}>
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
                {bodies[currentMeta.chapterIndex]
                  ? renderChapterContent(currentChapter.content)
                  : <Spin style={{ display: 'block', margin: '40px auto' }} />}

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
        onCancel={() => { setRewriteModalOpen(false); setRewriteTarget(null); }}
        footer={[
          <Button key="cancel" onClick={() => { setRewriteModalOpen(false); setRewriteTarget(null); }}>取消</Button>,
          rewriteTarget?.action === 'modify' ? (
            <Button key="modify" type="primary" loading={rewriting} onClick={submitCheckpointRewrite}>
              重写后批准
            </Button>
          ) : (
            <Button key="reject" danger loading={rewriting} onClick={submitCheckpointRewrite}>
              重写（拒绝当前版本）
            </Button>
          ),
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
