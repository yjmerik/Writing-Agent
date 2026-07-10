import { useState, useEffect, useCallback } from 'react';
import { Tree, Spin, Empty, Card, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { api } from '../api/client';
import type { Outline } from '../types';

const { Paragraph } = Typography;

export default function OutlineView({ projectId }: { projectId: string }) {
  const [outline, setOutline] = useState<Outline | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setOutline(await api.getOutline(projectId));
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { load(); }, [load]);

  if (loading) return <Spin />;
  if (!outline) return <Empty description="暂无大纲，请先运行「构思」阶段" />;

  // 构建 Tree 数据
  const treeData: DataNode[] = [
    {
      title: <strong>📖 总纲</strong>,
      key: 'premise',
      children: [
        {
          title: <Paragraph style={{ marginBottom: 0 }}>{outline.premise}</Paragraph>,
          key: 'premise-text',
          selectable: false,
        },
      ],
    },
    ...outline.volumes.map((v) => ({
      title: <strong>📚 卷{v.volumeIndex} · {v.title}</strong>,
      key: v.id,
      children: [
        ...(v.summary ? [{
          title: <span style={{ color: '#888' }}>{v.summary}</span>,
          key: `${v.id}-summary`,
          selectable: false,
        }] : []),
        ...v.synopses.map((s) => ({
          title: (
            <span>
              <strong>第{s.chapterIndex}章 {s.title}</strong>
              <br />
              <span style={{ color: '#666', fontSize: 13 }}>{s.synopsis}</span>
            </span>
          ),
          key: s.id,
          selectable: false,
        })),
      ],
    })),
  ];

  return (
    <Card>
      <Tree
        treeData={treeData}
        defaultExpandAll
        showLine
        blockNode
      />
    </Card>
  );
}
