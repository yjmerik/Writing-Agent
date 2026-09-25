import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Input, List, Modal, Row, Space, Tag, Typography, message } from 'antd';
import { ApiError, api } from '../api/client';
import { useRevisionInstructionDraft } from '../hooks/useRevisionInstructionDraft';
import type {
  AgentRevisionApplyResult,
  AgentRevisionPreview,
  AgentRevisionTarget,
  RevisionCandidateRecord,
} from '../api/client';

const { Paragraph, Text } = Typography;

interface Props {
  open: boolean;
  target: AgentRevisionTarget | null;
  onApplied: (result: AgentRevisionApplyResult) => void | Promise<void>;
  onCancel: () => void;
}

/**
 * p37-05: 作者可读的字段对比——按对象类型渲染关键字段文本，
 * JSON 只作为未知字段的兜底展示，不再是主界面。
 */
const FIELD_LABELS: Record<string, string> = {
  name: '名称', content: '内容', type: '类型', title: '标题', summary: '摘要',
  personality: '性格', ability: '能力', motivation: '动机', appearance: '外貌',
  relationships: '人物关系', growthArc: '成长弧', speakingStyle: '说话风格',
  role: '定位', synopsis: '梗概',
};

const renderSnapshot = (
  targetType: string,
  value: Record<string, unknown> | null | undefined,
): string => {
  if (!value || typeof value !== 'object') return '（空）';
  const lines: string[] = [];
  const used = new Set<string>();
  const pushField = (key: string) => {
    const raw = value[key];
    if (raw == null) return;
    used.add(key);
    const text = typeof raw === 'string' ? raw : JSON.stringify(raw);
    lines.push(`【${FIELD_LABELS[key] ?? key}】`);
    lines.push(text);
    lines.push('');
  };
  if (targetType === 'CHAPTER') {
    pushField('title');
    pushField('content');
  } else if (targetType === 'PASSAGE') {
    pushField('content');
  } else if (targetType === 'WORLD') {
    pushField('type');
    pushField('name');
    pushField('content');
  } else if (targetType === 'CHARACTER') {
    for (const key of ['name', 'role', 'personality', 'ability', 'motivation',
      'appearance', 'relationships', 'growthArc', 'speakingStyle']) {
      pushField(key);
    }
  } else if (targetType === 'VOLUME') {
    pushField('title');
    pushField('summary');
  }
  // 未知字段兜底（不作为主展示）
  const rest = Object.keys(value).filter(k => !used.has(k) && k !== 'id' && k !== 'projectId');
  if (rest.length > 0) {
    lines.push('【其他字段】');
    for (const key of rest) {
      const raw = value[key];
      lines.push(`${FIELD_LABELS[key] ?? key}: ${typeof raw === 'string' ? raw : JSON.stringify(raw)}`);
    }
  }
  return lines.join('\n').trim() || '（空）';
};

export default function AgentRevisionDialog({ open, target, onApplied, onCancel }: Props) {
  const [preview, setPreview] = useState<AgentRevisionPreview | null>(null);
  const [generating, setGenerating] = useState(false);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState('');
  const [stale, setStale] = useState(false);
  const [latest, setLatest] = useState<Record<string, unknown> | null>(null);
  // p37-04: 持久候选恢复入口（刷新/重启后候选仍在 DB）
  const [candidates, setCandidates] = useState<RevisionCandidateRecord[]>([]);
  const requestSequence = useRef(0);

  const targetKey = target
    ? JSON.stringify([target.projectId, target.targetType, target.targetId, target.chapterIndex ?? null, target.passageIndex ?? null])
    : '';
  const { instructions, setInstructions, clearInstructions, draftUnsaved } = useRevisionInstructionDraft(targetKey, open);

  useEffect(() => {
    requestSequence.current += 1;
    setPreview(null);
    setStale(false); setLatest(null);
    setGenerating(false);
    setApplying(false);
    setError('');
    setCandidates([]);
  }, [open, targetKey]);

  // p37-04: 打开时查询该目标的 READY 持久候选
  useEffect(() => {
    if (!open || !target) return;
    let active = true;
    api.listRevisionCandidates(target.projectId, target.targetId, 'READY')
      .then(list => { if (active) setCandidates(list); })
      .catch(() => { if (active) setCandidates([]); });
    return () => { active = false; };
  }, [open, targetKey, target]);

  const cancel = () => {
    if (applying) return;
    requestSequence.current += 1;
    setPreview(null);
    setStale(false); setLatest(null);
    setError('');
    onCancel();
  };

  const generatePreview = async () => {
    if (!target || !instructions.trim()) return;
    const requestId = ++requestSequence.current;
    setGenerating(true);
    setPreview(null);
    setStale(false); setLatest(null);
    setError('');
    try {
      const result = await api.previewAgentRevision(target, instructions.trim());
      if (requestSequence.current === requestId) setPreview(result);
    } catch (cause) {
      if (requestSequence.current === requestId) {
        setError(`预览生成失败：${(cause as Error).message}`);
      }
    } finally {
      if (requestSequence.current === requestId) setGenerating(false);
    }
  };

  /** p37-04: 从持久候选恢复预览（章节类走候选原子应用；其他类型恢复入口待 p39-07/08）。 */
  const restoreCandidate = (record: RevisionCandidateRecord, replaceInstructions = false) => {
    if (!target) return;
    const chapterLike = record.targetType === 'CHAPTER' || record.targetType === 'PASSAGE';
    if (!chapterLike) {
      message.info('非章节候选的恢复入口将在后续版本提供（p39-07/08）；当前仅可查看');
      return;
    }
    if (!replaceInstructions && instructions !== '' && instructions !== (record.instructions ?? '')) {
      const requestId = requestSequence.current;
      Modal.confirm({
        title: '候选使用了较早的修改要求',
        content: '当前输入与候选要求不同。保留当前要求，或明确选择使用候选要求后再核对预览。',
        okText: '使用候选要求',
        cancelText: '保留当前要求',
        onOk: () => {
          if (requestSequence.current === requestId) restoreCandidate(record, true);
        },
      });
      return;
    }
    try {
      const before = JSON.parse(record.beforeJson) as Record<string, unknown>;
      const after = JSON.parse(record.afterJson) as Record<string, unknown>;
      setInstructions(record.instructions ?? '');
      setStale(false); setLatest(null);
      setPreview({
        previewId: record.id,
        targetType: record.targetType,
        targetId: record.targetId,
        sourceVersion: record.sourceHash,
        before,
        after,
        summary: record.summary ?? '恢复的持久候选',
      });
      setError('');
      message.success('已恢复未应用的候选（应用走持久候选原子提交）');
    } catch {
      message.error('候选内容解析失败，无法恢复');
    }
  };

  const applyPreview = async () => {
    if (!target || !preview || stale) return;
    setApplying(true);
    setError('');
    let result: AgentRevisionApplyResult;
    try {
      result = await api.applyAgentRevision(target.projectId, preview.previewId);
    } catch (cause) {
      const conflict = cause instanceof ApiError && cause.status === 409;
      // p32-12: 所有权占用与内容变化分开提示
      setError(conflict && (cause as ApiError).code === 'WORK_OWNERSHIP_CONFLICT'
        ? `Agent 正在处理本项目，请等待当前步骤完成或先接手编辑：${(cause as Error).message}`
        : conflict
        ? `当前内容已变化，此候选不可应用。请对照最新稿并重新生成预览：${(cause as Error).message}`
        : `应用失败：${(cause as Error).message}`);
      if (conflict && (cause as ApiError).code !== 'WORK_OWNERSHIP_CONFLICT') {
        setStale(true);
        if (target.chapterIndex != null) {
          try {
            const current = await api.getChapter(target.projectId, target.chapterIndex);
            if (current) setLatest({ title: current.title, content: current.content, version: current.version });
          } catch { /* 保留旧候选与错误；重新生成会从服务端读取最新稿。 */ }
        }
      }
      setApplying(false);
      return;
    }

    // The server has committed; even a later page-refresh failure must not resurrect this draft.
    clearInstructions();
    if (result.derivedRefreshPending) {
      message.warning(`${target.label}的修改已保存，但后续处理（摘要/索引）未完成，稍后会自动重算，无需重新应用`);
    } else {
      message.success(`${target.label}已应用 Agent 修改`);
    }
    try {
      await onApplied(result);
    } catch (cause) {
      message.warning(`修改已应用，但页面刷新失败：${(cause as Error).message}`);
    } finally {
      setApplying(false);
      setCandidates([]);
      // NCS-085（听雪刀 P2-3）：服务端已提交——无论父组件刷新是否成功，弹窗必须关闭，
      // 不再依赖父回调链路（onCancel 幂等：只清空目标状态）
      onCancel();
    }
  };

  return (
    <Modal
      title={target ? `让 Agent 修改 · ${target.label}` : '让 Agent 修改'}
      open={open}
      width={920}
      onCancel={cancel}
      maskClosable={!applying}
      closable={!applying}
      footer={[
        <Button key="cancel" onClick={cancel} disabled={applying}>取消</Button>,
        preview ? (
          <Button
            key="regenerate"
            onClick={() => void generatePreview()}
            loading={generating}
            disabled={applying || !instructions.trim()}
          >重新生成预览</Button>
        ) : null,
        preview ? (
          <Button key="apply" type="primary" loading={applying} disabled={stale || generating} onClick={() => void applyPreview()}>
            确认应用
          </Button>
        ) : (
          <Button
            key="preview"
            type="primary"
            loading={generating}
            disabled={!target || !instructions.trim()}
            onClick={() => void generatePreview()}
          >生成预览</Button>
        ),
      ]}
    >
      <Paragraph type="secondary" style={{ marginBottom: 12 }}>
        只处理当前选中的对象。预览与取消不会写入正文；修改要求会在本浏览器暂存，确认应用前请核对修改前后内容。
      </Paragraph>

      {draftUnsaved && <Alert type="warning" showIcon message="修改要求尚未暂存，刷新可能丢失" style={{ marginBottom: 12 }} />}

      {/* p37-04: 未应用的持久候选——刷新/重启后可恢复 */}
      {candidates.length > 0 && !preview && (
        <Alert
          type="warning" showIcon style={{ marginBottom: 12 }}
          message={`发现 ${candidates.length} 个未应用的候选（已持久保存，刷新不丢失）`}
          description={
            <List
              size="small"
              dataSource={candidates}
              renderItem={record => (
                <List.Item
                  actions={[
                    <Button key="restore" size="small" type="primary"
                      aria-label="恢复候选"
                      onClick={() => restoreCandidate(record)}>恢复</Button>,
                  ]}
                >
                  <List.Item.Meta
                    title={
                      <Space size={4}>
                        <Tag color="gold">READY</Tag>
                        <Text style={{ fontSize: 12 }}>
                          {record.instructions
                            ? (record.instructions.length > 40
                              ? record.instructions.slice(0, 40) + '…'
                              : record.instructions)
                            : '（无修改要求记录）'}
                        </Text>
                      </Space>
                    }
                    description={
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        基于 v{record.baseVersion} · {new Date(record.createdAt).toLocaleString()}
                      </Text>
                    }
                  />
                </List.Item>
              )}
            />
          }
        />
      )}

      <Input.TextArea
        aria-label="修改要求"
        autoFocus
        rows={4}
        value={instructions}
        disabled={generating || applying}
        placeholder="例如：保留既有事实，只增强冲突动机与可验证细节"
        onChange={(event) => {
          setInstructions(event.target.value);
          if (preview) setPreview(null);
          if (error) setError('');
        }}
      />
      {error && <Alert type="error" showIcon message={error} style={{ marginTop: 16 }} />}
      {preview && target && (
        <div style={{ marginTop: 16 }}>
          <Alert type="info" showIcon message={preview.summary} style={{ marginBottom: 12 }} />
          <Row gutter={[12, 12]}>
            <Col xs={24} md={12}>
              <Card size="small" title="修改前">
                <pre
                  data-revision-side="before"
                  style={{ margin: 0, maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
                >{renderSnapshot(target.targetType, preview.before)}</pre>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" title="修改后">
                <pre
                  data-revision-side="after"
                  style={{ margin: 0, maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
                >{renderSnapshot(target.targetType, preview.after)}</pre>
              </Card>
            </Col>
          </Row>
          {stale && <Card size="small" title="最新主稿（旧候选已失效）" style={{ marginTop: 12 }}>
            <pre data-revision-side="latest" style={{ margin: 0, maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap' }}>
              {latest ? renderSnapshot('CHAPTER', latest) : '最新稿读取失败，请重新生成预览。旧候选不会覆盖主稿。'}
            </pre>
          </Card>}
          <Space style={{ marginTop: 10 }}>
            <Text type="secondary">来源指纹</Text>
            <Text code>{preview.sourceVersion.slice(0, 12)}</Text>
          </Space>
        </div>
      )}
    </Modal>
  );
}
