import { useEffect, useRef, useState } from 'react';
import { Card, Input, Modal, Tag, Space, Typography, Spin, Button, Tooltip, message } from 'antd';
import { AlertOutlined, EditOutlined, ForkOutlined, QuestionCircleOutlined, ReadOutlined, SafetyOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { ReviewClassification, ReviewCard } from '../types';

const { Text, Paragraph } = Typography;

/**
 * NCS-023（方案 §13/UX09）：三类信息分层审阅——必须处理 / 尚未查清 / 文学建议。
 * 文字+图标区分（不只靠颜色）；问题卡含章稿版本与过期标注；
 * 不提供无证据的"标记已解决"（复核后关闭走人工复核流）。
 * NCS-088：未查清项增加「担责豁免」出口（AUTHOR_WAIVED，reason 必填记入台账）。
 */
export default function ReviewClassificationView({ projectId }: { projectId: string }) {
  const [data, setData] = useState<ReviewClassification | null>(null);
  const [loading, setLoading] = useState(true);
  // NCS-080: 未查清项「重新检查」——正在重检的章号（同一时间只跑一个同步补查）
  const [rechecking, setRechecking] = useState<number | null>(null);
  // NCS-088: 担责豁免表单
  const [waiverTarget, setWaiverTarget] = useState<{ chapterIndex: number; checker: string } | null>(null);
  const [waiverReason, setWaiverReason] = useState('');
  const [waiverBusy, setWaiverBusy] = useState(false);
  const navigate = useNavigate();
  const requestRef = useRef(0);

  const load = (pid: string) => {
    const requestId = ++requestRef.current;
    void api.getReviewClassification(pid).then(d => {
      if (requestRef.current === requestId) setData(d);
    }).catch(() => {}).finally(() => {
      if (requestRef.current === requestId) setLoading(false);
    });
  };

  const submitWaiver = async () => {
    if (!waiverTarget) return;
    if (!waiverReason.trim()) { message.warning('豁免必须说明理由（记入台账，作者可查）'); return; }
    setWaiverBusy(true);
    try {
      const ctx = await api.getReviewContext(projectId, waiverTarget.chapterIndex);
      await api.checkWaiver(projectId, waiverTarget.chapterIndex, ctx.identity, waiverTarget.checker, waiverReason.trim());
      message.success(`第 ${waiverTarget.chapterIndex} 章 ${waiverTarget.checker} 已记入作者担责豁免`);
      setWaiverTarget(null);
      setWaiverReason('');
      load(projectId);
    } catch (e) {
      message.error(`担责豁免失败: ${(e as Error).message}（若提示身份过期，请先打开该章刷新审阅）`);
    } finally {
      setWaiverBusy(false);
    }
  };

  useEffect(() => {
    load(projectId);
  }, [projectId]);

  if (loading) return <Card size="small"><Spin><div style={{ height: 60 }} /></Spin></Card>;

  const toReview = (chapterIndex: number) => {
    navigate(`/project/${projectId}/chapter/${chapterIndex}`);
  };

  // NCS-080（听雪刀 P1-1）：未查清检查点的 UI 出路——经当前稿身份触发 recheck，
  // 完成后刷新分类（检查结果信封落库会覆盖 UNKNOWN 状态）。长调用期间按钮置 loading；
  // 占坑可经协作栏「取消该操作」释放（NCS-074）。
  // NCS-087: 按检查器路由重检——continuity 缺省；consistency-guard 显式传 checkers
  const recheckUnknown = async (chapterIndex: number, checker?: string) => {
    setRechecking(chapterIndex);
    try {
      const ctx = await api.getReviewContext(projectId, chapterIndex);
      const checkers = checker === 'consistency-guard' ? ['consistency-guard'] : undefined;
      await api.recheckChapter(projectId, chapterIndex, ctx.identity, checkers);
      message.success(`第 ${chapterIndex} 章 ${checker ?? 'continuity'} 重检完成，正在刷新分类…`);
    } catch (e) {
      message.error(`重新检查失败: ${(e as Error).message}（若提示身份过期，请先打开该章刷新审阅）`);
    } finally {
      setRechecking(null);
      load(projectId);
    }
  };

  const column = (title: string, icon: React.ReactNode, color: string, cards: ReviewCard[],
                  hint: string) => (
    <div style={{ flex: 1, minWidth: 260, background: '#fafaf8', borderRadius: 6, padding: 10 }}>
      <Space size={6} style={{ marginBottom: 8 }}>
        {icon}
        <Text strong>{title}</Text>
        <Tag color={color} style={{ fontSize: 11 }}>{cards.length}</Tag>
      </Space>
      <Paragraph type="secondary" style={{ fontSize: 11, marginBottom: 8 }}>{hint}</Paragraph>
      {cards.length === 0 ? (
        <Text type="secondary" style={{ fontSize: 12 }}>（空——没有此类条目时如实显示为空，不显示"0 个问题"的完成假象）</Text>
      ) : cards.map((c, i) => (
        <div key={i} style={{ background: '#fff', border: '1px solid #eee', borderRadius: 4,
          padding: '6px 8px', marginBottom: 6, fontSize: 12 }}>
          <Paragraph style={{ marginBottom: 4, fontSize: 12 }}>{c.description}</Paragraph>
          <Space size={6} wrap>
            <Tag style={{ fontSize: 10 }}>第 {c.chapterIndex} 章</Tag>
            {c.chapterVersion != null && <Tag style={{ fontSize: 10 }}>v{c.chapterVersion} 稿</Tag>}
            {c.stale && <Tooltip title="该问题针对旧稿——正文已推进，复核前结论可能过期">
              <Tag color="default" style={{ fontSize: 10 }}>过期</Tag></Tooltip>}
            {c.severity && <Tag color={c.severity === 'HIGH' ? 'red' : 'orange'} style={{ fontSize: 10 }}>
              {c.severity === 'HIGH' ? '严重' : '中等'}</Tag>}
          </Space>
          <div style={{ marginTop: 6 }}>
            <Space size={6} wrap>
              {c.kind === 'UNKNOWN_CHECK' ? (
                <>
                  {c.checker !== 'correction-verification' && (
                    <Tooltip title={c.reason ?? ''}>
                      <Button size="small" icon={<QuestionCircleOutlined />}
                        loading={rechecking === c.chapterIndex}
                        disabled={rechecking != null}
                        onClick={() => void recheckUnknown(c.chapterIndex, c.checker)}>
                        {rechecking === c.chapterIndex ? '重检中（约 1-2 分钟）…' : '重新检查'}
                      </Button>
                    </Tooltip>
                  )}
                  <Tooltip title={c.checker === 'correction-verification'
                    ? '复验依赖当时的原问题清单，不可重跑——请作者复核后担责豁免'
                    : '作者已复核并愿意担责放行（AUTHOR_WAIVED，理由记入台账）'}>
                    <Button size="small" icon={<SafetyOutlined />}
                      disabled={rechecking != null}
                      onClick={() => { setWaiverTarget({ chapterIndex: c.chapterIndex, checker: c.checker ?? '' }); setWaiverReason(''); }}>
                      担责豁免
                    </Button>
                  </Tooltip>
                </>
              ) : (
                <>
                  <Button size="small" icon={<EditOutlined />} onClick={() => toReview(c.chapterIndex)}>我来编辑</Button>
                  <Tooltip title="到修订工作区登记修订请求">
                    <Button size="small" icon={<ForkOutlined />}
                      onClick={() => navigate(`/project/${projectId}?tab=revisions`)}>生成修订建议</Button>
                  </Tooltip>
                  <Tooltip title="有依据的创作例外（记入处置台账，附理由）——跳转后预开裁决表单">
                    <Button size="small" icon={<SafetyOutlined />}
                      onClick={() => navigate(`/project/${projectId}/chapter/${c.chapterIndex}${c.issueId ? `?adjudicate=${c.issueId}` : ''}`)}>作为创作例外保留</Button>
                  </Tooltip>
                </>
              )}
            </Space>
          </div>
        </div>
      ))}
    </div>
  );

  return (
    <Card size="small" title="🗂 三类信息分层审阅" style={{ marginBottom: 12 }}
      extra={<Text type="secondary" style={{ fontSize: 11 }}>必须处理 ≠ 文学建议；未查清 ≠ 无问题</Text>}>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {column('必须处理', <AlertOutlined style={{ color: '#cf1322' }} />, 'red', data?.mustFix ?? [],
          '有证据的严重冲突或硬约束违背——阻断续写/成书')}
        {column('尚未查清', <QuestionCircleOutlined style={{ color: '#fa8c16' }} />, 'orange', data?.unknown ?? [],
          '检查未完成或不可判定——不能当作无问题，需重新检查或补证据')}
        {column('文学建议', <ReadOutlined style={{ color: '#389e0d' }} />, 'green', data?.suggestions ?? [],
          '节奏/语言方面的可讨论意见——可拒绝，不影响完成度')}
      </div>
      <Modal title={`担责豁免 · 第 ${waiverTarget?.chapterIndex ?? ''} 章 ${waiverTarget?.checker ?? ''}`}
        open={waiverTarget != null}
        onOk={() => void submitWaiver()}
        onCancel={() => setWaiverTarget(null)}
        okText="记入台账（AUTHOR_WAIVED）"
        cancelText="取消"
        confirmLoading={waiverBusy}
        okButtonProps={{ disabled: !waiverReason.trim() }}>
        <Paragraph type="secondary" style={{ fontSize: 12 }}>
          作者确认已复核该检查关注点并愿意担责放行；豁免不可撤销、理由记入台账，
          成书门禁与校验报告将不再因该未查清项阻塞（会标注「作者担责豁免」以区分「已检查」）。
        </Paragraph>
        <Input.TextArea
          value={waiverReason}
          onChange={e => setWaiverReason(e.target.value)}
          placeholder="豁免必须说明理由（记入处置台账，作者可查）"
          rows={3} />
      </Modal>
    </Card>
  );
}
