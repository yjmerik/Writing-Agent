import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Input, Modal, Row, Space, Typography, message } from 'antd';
import { ApiError, api } from '../api/client';
import type {
  AgentRevisionApplyResult,
  AgentRevisionPreview,
  AgentRevisionTarget,
} from '../api/client';

const { Paragraph, Text } = Typography;

interface Props {
  open: boolean;
  target: AgentRevisionTarget | null;
  onApplied: (result: AgentRevisionApplyResult) => void | Promise<void>;
  onCancel: () => void;
}

const formatSnapshot = (value: Record<string, unknown>) => JSON.stringify(value, null, 2);

export default function AgentRevisionDialog({ open, target, onApplied, onCancel }: Props) {
  const [instructions, setInstructions] = useState('');
  const [preview, setPreview] = useState<AgentRevisionPreview | null>(null);
  const [generating, setGenerating] = useState(false);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState('');
  const requestSequence = useRef(0);

  const targetKey = target
    ? `${target.projectId}:${target.targetType}:${target.targetId}:${target.chapterIndex ?? ''}:${target.passageIndex ?? ''}`
    : '';

  useEffect(() => {
    requestSequence.current += 1;
    setInstructions('');
    setPreview(null);
    setGenerating(false);
    setApplying(false);
    setError('');
  }, [open, targetKey]);

  const cancel = () => {
    if (applying) return;
    requestSequence.current += 1;
    setPreview(null);
    setError('');
    onCancel();
  };

  const generatePreview = async () => {
    if (!target || !instructions.trim()) return;
    const requestId = ++requestSequence.current;
    setGenerating(true);
    setPreview(null);
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

  const applyPreview = async () => {
    if (!target || !preview) return;
    setApplying(true);
    setError('');
    let result: AgentRevisionApplyResult;
    try {
      result = await api.applyAgentRevision(target.projectId, preview.previewId);
    } catch (cause) {
      const stale = cause instanceof ApiError && cause.status === 409;
      setError(stale
        ? `当前内容已变化，请关闭后刷新并重新生成预览：${cause.message}`
        : `应用失败：${(cause as Error).message}`);
      setApplying(false);
      return;
    }

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
          <Button key="apply" type="primary" loading={applying} onClick={() => void applyPreview()}>
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
        只处理当前选中的对象。预览与取消不会保存；确认应用前，请核对修改前后内容。
      </Paragraph>
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
      {preview && (
        <div style={{ marginTop: 16 }}>
          <Alert type="info" showIcon message={preview.summary} style={{ marginBottom: 12 }} />
          <Row gutter={[12, 12]}>
            <Col xs={24} md={12}>
              <Card size="small" title="修改前">
                <pre
                  data-revision-side="before"
                  style={{ margin: 0, maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
                >{formatSnapshot(preview.before)}</pre>
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card size="small" title="修改后">
                <pre
                  data-revision-side="after"
                  style={{ margin: 0, maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
                >{formatSnapshot(preview.after)}</pre>
              </Card>
            </Col>
          </Row>
          <Space style={{ marginTop: 10 }}>
            <Text type="secondary">来源指纹</Text>
            <Text code>{preview.sourceVersion.slice(0, 12)}</Text>
          </Space>
        </div>
      )}
    </Modal>
  );
}
