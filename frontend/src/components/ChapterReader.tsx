import { useState, useEffect, useCallback } from 'react';
import { Card, Spin, Empty, Button, Space, Typography, List, Tag, message } from 'antd';
import { LeftOutlined, RightOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import { renderContent } from '../utils/renderContent';
import type { Chapter } from '../types';

const { Title } = Typography;

/**
 * p21-05: 章节阅读——初始加载只请求一次元数据（不含正文）；
 * 选中章节时才请求该章正文（本地缓存，翻章不重复请求）。
 * p21-07: 目录行键盘可激活（Tab + Enter/Space）。
 */
export default function ChapterReader({ projectId }: { projectId: string }) {
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [bodies, setBodies] = useState<Record<number, Chapter>>({});
  const [loading, setLoading] = useState(true);
  const [bodyLoading, setBodyLoading] = useState(false);
  const [currentIdx, setCurrentIdx] = useState(0);

  // 元数据只加载一次（不含正文）
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await api.getChapterPage(projectId, 0, 1000);
      setChapters(page.chapters || []);
      setBodies({}); // 目录刷新后正文缓存失效
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { load(); }, [load]);

  // 选中章节变化 → 仅请求该章正文
  const currentMeta = chapters[currentIdx];
  useEffect(() => {
    if (!currentMeta) return;
    if (bodies[currentMeta.chapterIndex]) return;
    let cancelled = false;
    setBodyLoading(true);
    api.getChapter(projectId, currentMeta.chapterIndex)
      .then((full) => {
        if (cancelled || !full) return;
        setBodies((prev) => ({ ...prev, [currentMeta.chapterIndex]: full }));
      })
      .catch((e) => message.error(`加载正文失败: ${(e as Error).message}`))
      .finally(() => { if (!cancelled) setBodyLoading(false); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, currentMeta?.chapterIndex]);

  if (loading) return <Spin />;
  if (chapters.length === 0) return <Empty description="暂无章节，请先运行写作" />;

  const chapter = bodies[currentMeta.chapterIndex] ?? currentMeta;

  const go = (idx: number) => {
    if (idx >= 0 && idx < chapters.length) setCurrentIdx(idx);
  };

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button size="small" icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        <List
          size="small"
          style={{ width: 250, background: '#fff' }}
          header={<strong>章节目录（{chapters.length}）</strong>}
          dataSource={chapters}
          renderItem={(c, idx) => (
            <List.Item
              role="button"
              tabIndex={0}
              aria-label={`第${c.chapterIndex}章 ${c.title}`}
              onClick={() => setCurrentIdx(idx)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  setCurrentIdx(idx);
                }
              }}
              style={{ cursor: 'pointer', background: idx === currentIdx ? '#e6f4ff' : undefined }}
            >
              <span>第{c.chapterIndex}章 {c.title}</span>
              <Tag>{c.wordCount}字</Tag>
            </List.Item>
          )}
        />
      </Space>

      {currentMeta && (
        <Card>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <Title level={3} style={{ margin: 0 }}>
              第{currentMeta.chapterIndex}章 {currentMeta.title}
            </Title>
            <Space>
              <Button
                icon={<LeftOutlined />}
                disabled={currentIdx === 0}
                onClick={() => go(currentIdx - 1)}
                aria-label="上一章"
              >
                上一章
              </Button>
              <Button
                disabled={currentIdx === chapters.length - 1}
                onClick={() => go(currentIdx + 1)}
                aria-label="下一章"
              >
                下一章
                <RightOutlined />
              </Button>
            </Space>
          </div>

          <div style={{ textAlign: 'right', marginBottom: 12, color: '#999', fontSize: 13 }}>
            {currentMeta.wordCount} 字
          </div>

          {bodyLoading && !bodies[currentMeta.chapterIndex] ? (
            <Spin style={{ display: 'block', margin: '40px auto' }} />
          ) : (
            <div style={{ lineHeight: 2, fontSize: 16 }}>
              {renderContent(chapter.content)}
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
