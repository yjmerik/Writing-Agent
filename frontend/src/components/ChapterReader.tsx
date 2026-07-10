import { useState, useEffect, useCallback } from 'react';
import { Card, Spin, Empty, Button, Space, Typography, List, Tag } from 'antd';
import { LeftOutlined, RightOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import type { Chapter } from '../types';

const { Title, Paragraph } = Typography;

export default function ChapterReader({ projectId }: { projectId: string }) {
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [loading, setLoading] = useState(true);
  const [currentIdx, setCurrentIdx] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.getChapters(projectId);
      setChapters(list || []);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { load(); }, [load]);

  if (loading) return <Spin />;
  if (chapters.length === 0) return <Empty description="暂无章节，请先运行写作" />;

  const chapter = chapters[currentIdx];

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <List
          size="small"
          style={{ width: 250, background: '#fff' }}
          header={<strong>章节目录（{chapters.length}）</strong>}
          dataSource={chapters}
          renderItem={(c, idx) => (
            <List.Item
              onClick={() => setCurrentIdx(idx)}
              style={{ cursor: 'pointer', background: idx === currentIdx ? '#e6f4ff' : undefined }}
            >
              <span>第{c.chapterIndex}章 {c.title}</span>
              <Tag>{c.wordCount}字</Tag>
            </List.Item>
          )}
        />
      </Space>

      {chapter && (
        <Card>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <Title level={3} style={{ margin: 0 }}>
              第{chapter.chapterIndex}章 {chapter.title}
            </Title>
            <Space>
              <Button
                icon={<LeftOutlined />}
                disabled={currentIdx === 0}
                onClick={() => setCurrentIdx(currentIdx - 1)}
              >
                上一章
              </Button>
              <Button
                disabled={currentIdx === chapters.length - 1}
                onClick={() => setCurrentIdx(currentIdx + 1)}
              >
                下一章
                <RightOutlined />
              </Button>
            </Space>
          </div>

          <div style={{ textAlign: 'right', marginBottom: 12, color: '#999', fontSize: 13 }}>
            {chapter.wordCount} 字
          </div>

          {/* 正文：按段落分割显示 */}
          <div style={{ lineHeight: 2, fontSize: 16 }}>
            {chapter.content.split('\n').filter(Boolean).map((para, i) => (
              <Paragraph key={i} style={{ textIndent: '2em', marginBottom: '1em' }}>
                {para.trim()}
              </Paragraph>
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}
