import { useEffect, useRef, useState } from 'react';
import { Card, Button, Table, Tag, Space, Typography, Empty, Spin, Modal, message, Alert, List } from 'antd';
import { BookOutlined, CheckOutlined, ExportOutlined, FileAddOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { BookEditionSummary, BookEditionDetail, EditionValidation } from '../types';

const { Text } = Typography;

/**
 * NCS-027（方案 §16/UX11/U6）：成书版本闭环 UI。
 * 创建整书记录（快照+manifestHash）→ 详情（章覆盖/批准状态）→ 确认定稿（哈希绑定）
 * → 导出固定版本；与「导出工作稿」明确区分——导出对象始终明确。
 */
export default function EditionsPanel({ projectId }: { projectId: string }) {
  const [editions, setEditions] = useState<BookEditionSummary[]>([]);
  const [detail, setDetail] = useState<BookEditionDetail | null>(null);
  const [validation, setValidation] = useState<EditionValidation | null>(null);
  const [reportOpen, setReportOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [approveTarget, setApproveTarget] = useState<BookEditionSummary | null>(null);
  const [messageApi, messageContextHolder] = message.useMessage();
  const requestRef = useRef(0);

  const load = () => {
    const requestId = ++requestRef.current;
    void api.listBookEditions(projectId).then(es => {
      if (requestRef.current === requestId) setEditions(es ?? []);
    }).catch(() => {}).finally(() => {
      if (requestRef.current === requestId) setLoading(false);
    });
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [projectId]);

  const handleCreate = async () => {
    setBusy(true);
    try {
      const created = await api.createBookEdition(projectId);
      messageApi.success(`整书记录已创建（${created.chapterCount} 章，已批准 ${created.approvedChapters}）`);
      load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '创建失败（需至少一章）');
    } finally { setBusy(false); }
  };

  const openDetail = async (editionId: string) => {
    setBusy(true);
    try {
      setDetail(await api.getBookEditionDetail(projectId, editionId));
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '读取详情失败');
    } finally { setBusy(false); }
  };

  const showValidation = async (record: BookEditionSummary, run: boolean, approve = false) => {
    setBusy(true);
    try {
      const report = await (run ? api.validateBookEdition(projectId, record.editionId)
        : api.getEditionValidation(projectId, record.editionId));
      setValidation(report);
      if (approve) setApproveTarget(record);
      else setReportOpen(true);
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '读取校验报告失败');
    } finally { setBusy(false); }
  };

  const handleApprove = async () => {
    if (!approveTarget) return;
    setBusy(true);
    try {
      await api.approveBookEdition(projectId, approveTarget.editionId, approveTarget.manifestHash);
      messageApi.success('定稿确认已绑定该整书记录（manifestHash 校验通过）');
      setApproveTarget(null);
      load();
    } catch (e) {
      messageApi.error(e instanceof Error ? e.message : '确认失败（检查批准覆盖）');
    } finally { setBusy(false); }
  };

  return (
    <Card title="📚 成书版本（整书记录）" size="small" extra={
      <Button size="small" type="primary" icon={<FileAddOutlined />} loading={busy}
        onClick={handleCreate}>创建整书记录</Button>
    }>
      {messageContextHolder}
      {loading ? <Spin><div style={{ height: 60 }} /></Spin>
        : editions.length === 0 ? (
          <Empty description="尚无整书记录——创建后可审阅覆盖、确认定稿、导出固定版本" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Table size="small" pagination={false} dataSource={editions} rowKey="editionId"
            columns={[
              { title: '记录', dataIndex: 'editionId', render: (v: string) => <Text code>{v.slice(0, 8)}</Text> },
              { title: '状态', dataIndex: 'state', render: (v: string) => (
                  <Tag color={v === 'APPROVED' ? 'green' : 'blue'}
                    icon={v === 'APPROVED' ? <CheckOutlined /> : <BookOutlined />}>
                    {v === 'APPROVED' ? '已定稿' : '待确认'}
                  </Tag>) },
              { title: '指纹', dataIndex: 'manifestHash', render: (v: string) => <Text code style={{ fontSize: 11 }}>{v.slice(0, 12)}…</Text> },
              { title: '创建时间', dataIndex: 'createdAt', render: (v: string) => v.slice(0, 19).replace('T', ' ') },
              { title: '操作', render: (_: unknown, record: BookEditionSummary) => (
                  <Space size={6}>
                    <Button size="small" disabled={busy} onClick={() => openDetail(record.editionId)}>详情</Button>
                    <Button size="small" disabled={busy} onClick={() => showValidation(record, true)}>校验此版本</Button>
                    <Button size="small" disabled={busy} onClick={() => showValidation(record, false)}>校验报告</Button>
                    {record.state !== 'APPROVED' && (
                      <Button size="small" type="primary" disabled={busy} onClick={() => showValidation(record, false, true)}>确认定稿</Button>
                    )}
                    <Button size="small" icon={<ExportOutlined />}
                      href={api.exportBookEditionUrl(projectId, record.editionId)}
                      download={`book-edition-${record.editionId}.md`}>导出此版本</Button>
                  </Space>) },
            ]} />
        )}
      <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 8 }}>
        整书记录是不可变快照（物化逐章内容副本）；确认定稿绑定精确 manifestHash；
        导出始终来自选定版本——与上方「导出工作稿（当前稿，未定稿）」不同。
      </Text>

      <Modal open={!!detail} title={detail ? `整书记录 ${detail.editionId.slice(0, 8)}` : ''}
        onCancel={() => setDetail(null)} footer={null} width={720}>
        {detail && (
          <Table size="small" pagination={false} dataSource={detail.chapters} rowKey="chapterIndex"
            columns={[
              { title: '章', dataIndex: 'chapterIndex', width: 60 },
              { title: '标题', dataIndex: 'title' },
              { title: '稿', dataIndex: 'version', width: 60, render: (v: number) => `v${v}` },
              { title: '字数', dataIndex: 'wordCount', width: 80 },
              { title: '作者批准', dataIndex: 'approvalId', width: 100, render: (v: string | null) => (
                  v ? <Tag color="green" style={{ fontSize: 10 }}>已批准</Tag>
                    : <Tag color="orange" style={{ fontSize: 10 }}>未批准</Tag>) },
            ]} />
        )}
      </Modal>

      <Modal open={reportOpen} title="这份整书记录的校验报告" onCancel={() => setReportOpen(false)} footer={null} width={760}>
        {validation && <Space direction="vertical" style={{ width: '100%' }}>
          <Alert showIcon type={validation.status === 'PASS' && !validation.stale ? 'success' : 'warning'}
            message={validation.stale ? '检查证据已变化，请重新校验此版本' : validation.status === 'PASS'
              ? '此版本校验通过，等待作者确认定稿' : validation.status === 'NOT_RUN' ? '此版本尚未校验' : '此版本仍有待处理项'} />
          {validation.workingCopyChanged && <Alert type="info" showIcon message="当前工作稿已更新；此报告只属于所选整书记录，不适用于新稿。" />}
          <Text>整书记录 {validation.editionId.slice(0, 8)} · 指纹 {validation.manifestHash.slice(0, 12)}</Text>
          <Text type="secondary">{validation.scope}</Text>
          <Table size="small" pagination={false} rowKey="chapterIndex" dataSource={validation.coverage ?? []}
            columns={[
              { title: '章', dataIndex: 'chapterIndex' },
              { title: '稿', dataIndex: 'version', render: (v: number) => `第 ${v} 稿` },
              { title: '作者决定', dataIndex: 'authorApproved', render: (v: boolean) => v ? '已批准' : '待批准' },
              { title: '检查证据', dataIndex: 'checks', render: (cs: NonNullable<EditionValidation['coverage']>[number]['checks']) =>
                cs.length ? cs.map(c => `${c.checker}：${!c.bound ? '旧检查未绑定正文'
                  : c.status === 'AUTHOR_WAIVED' ? '作者担责豁免'
                  : ['SUCCESS', 'VALID_EMPTY'].includes(c.status) ? '已检查' : '尚未查清'}`).join('；') : '未检查' },
            ]} />
          {([
            ['必须处理', validation.mustFix], ['尚未查清', validation.unknown],
            ['刻意保留的例外 / 误报决定', validation.exceptions], ['故事承诺与处置', validation.promises],
            ['文学建议', validation.suggestions],
          ] as const).map(([label, items]) => <div key={label} style={{ width: '100%' }}>
            <Text strong>{label}（{items?.length ?? 0}）</Text>
            {!!items?.length && <List size="small" dataSource={items} renderItem={item => <List.Item>
              <Space direction="vertical" size={0}>
                <Text>第 {item.chapterIndex} 章：{item.description}</Text>
                {item.reason && <Text type="secondary">{item.reason}</Text>}
                {item.state && <Text type="secondary">{item.state === 'RESOLVED' ? '已兑现' : item.state === 'OPEN_END' ? '作者决定开放结局' : '未处置'}</Text>}
              </Space>
            </List.Item>} />}
          </div>)}
        </Space>}
      </Modal>

      <Modal open={!!approveTarget} title="确认这份定稿" onCancel={() => setApproveTarget(null)}
        onOk={handleApprove} okText={`确认定稿 ${approveTarget?.manifestHash.slice(0, 8) ?? ''}`}
        cancelText="再看看" okButtonProps={{ loading: busy, disabled: validation?.status !== 'PASS' || validation.stale }}>
        {validation?.workingCopyChanged && <Alert type="warning" showIcon message="当前工作稿已更新，你确认的是这份历史整书记录。" />}
        {(validation?.status !== 'PASS' || validation.stale) && <Alert type="warning" showIcon
          message="请先校验此版本并处理阻断项；证据过期时需重新校验。" />}
        <Text>将把整书记录 <Text code>{approveTarget?.editionId.slice(0, 8)}</Text> 确认为定稿。</Text>
        <ul style={{ marginTop: 8, paddingLeft: 18, fontSize: 12 }}>
          <li>绑定精确 manifestHash：<Text code style={{ fontSize: 11 }}>{approveTarget?.manifestHash.slice(0, 16)}…</Text></li>
          <li>要求所选章稿已获作者批准、所选版本的有效校验报告通过，无未决必查项</li>
          <li>确认后该版本不可变；工作稿继续修改不影响已确认的定稿</li>
        </ul>
      </Modal>
    </Card>
  );
}
