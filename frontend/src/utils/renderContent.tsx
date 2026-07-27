import React from 'react';

/**
 * 简易 markdown 渲染器——将小说正文中的 markdown 标记渲染为 HTML。
 * 支持：# 标题、## 小节、--- 分隔线、**加粗**、普通段落缩进。
 */
export function renderContent(content: string): React.ReactNode[] {
  if (!content) return [];
  return content.split('\n').filter(Boolean).map((line, i) => {
    const trimmed = line.trim();
    if (!trimmed) return <br key={i} />;

    // # 标题
    if (trimmed.startsWith('# ') && !trimmed.startsWith('## ')) {
      return (
        <h3 key={i} style={{ textAlign: 'center', fontSize: '1.3em', fontWeight: 700, margin: '24px 0 12px', color: '#1a1a2e' }}>
          {renderInline(trimmed.slice(2))}
        </h3>
      );
    }

    // ## 小节标题
    if (trimmed.startsWith('## ')) {
      return (
        <h4 key={i} style={{ fontSize: '1.1em', fontWeight: 600, margin: '20px 0 8px', color: '#333' }}>
          {renderInline(trimmed.slice(3))}
        </h4>
      );
    }

    // --- 分隔线
    if (trimmed === '---' || trimmed === '***') {
      return <hr key={i} style={{ margin: '16px 0', border: 'none', borderTop: '1px dashed #ddd' }} />;
    }

    // 普通段落
    return (
      <p key={i} style={{ textIndent: '2em', marginBottom: '0.4em', lineHeight: 2 }}>
        {renderInline(trimmed)}
      </p>
    );
  });
}

/** 渲染行内 markdown（**加粗**） */
function renderInline(text: string): React.ReactNode[] {
  const parts: React.ReactNode[] = [];
  const regex = /\*\*(.+?)\*\*/g;
  let lastIdx = 0;
  let match: RegExpExecArray | null;
  let key = 0;

  while ((match = regex.exec(text)) !== null) {
    if (match.index > lastIdx) {
      parts.push(text.slice(lastIdx, match.index));
    }
    parts.push(<strong key={key++}>{match[1]}</strong>);
    lastIdx = regex.lastIndex;
  }
  if (lastIdx < text.length) {
    parts.push(text.slice(lastIdx));
  }

  return parts.length > 0 ? parts : [text];
}
