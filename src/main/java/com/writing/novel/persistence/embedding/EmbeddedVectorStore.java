package com.writing.novel.persistence.embedding;

import com.writing.novel.spi.VectorSearchResult;
import com.writing.novel.spi.VectorStore;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 嵌入式本地向量库实现。
 * 内存索引 + 文件持久化，暴力余弦相似度检索。
 * MVP 足够；云端部署换 Qdrant/Milvus 实现。
 */
public class EmbeddedVectorStore implements VectorStore, Serializable {

    private static final long serialVersionUID = 1L;

    /** 单条记录 */
    static class Entry implements Serializable {
        private static final long serialVersionUID = 1L;
        final String id;
        final String projectId;
        final String text;
        final float[] vector;
        Entry(String id, String projectId, String text, float[] vector) {
            this.id = id; this.projectId = projectId; this.text = text; this.vector = vector;
        }
    }

    private final String indexPath;
    private final Map<String, Entry> index = new ConcurrentHashMap<>();
    /** p12-K3: dirty 标记——批量持久化，不再每次 upsert 都写文件 */
    private volatile boolean dirty = false;
    /** 累计 upsert/delete 次数，达到阈值自动 flush */
    private int pendingChanges = 0;
    private static final int FLUSH_THRESHOLD = 20;

    /** 记录在案的向量维度（0=空索引） */
    private int dimension = 0;

    public EmbeddedVectorStore(String indexPath) {
        this.indexPath = indexPath;
        load();
    }

    /**
     * NCS-134: embedding 模型变更防护——索引文件维度与新 provider 维度不匹配时
     * 旧向量余弦恒 0（静默失联）。构造后调用 {@link #validateDimension(int)}：
     * 不匹配则清空索引并发 VECTOR_INDEX_REBUILT 事件（调用方重灌）。
     */
    public boolean validateDimension(int providerDimension) {
        if (providerDimension <= 0) return true;
        if (dimension == 0) {
            dimension = providerDimension;
            return true;
        }
        if (dimension != providerDimension) {
            int old = dimension;
            int dropped = index.size();
            index.clear();
            pendingUpserts.clear();
            pendingDeletes.clear();
            dimension = providerDimension;
            dirty = true;
            persistFull();
            com.writing.novel.telemetry.EventLog.emit("VECTOR_INDEX_REBUILT", java.util.Map.of(
                    "indexPath", indexPath,
                    "oldDimension", old,
                    "newDimension", providerDimension,
                    "droppedEntries", dropped));
            return false;
        }
        return true;
    }

    @Override
    public synchronized void upsert(String id, String projectId, String text, float[] vector) {
        if (vector != null && vector.length > 0) dimension = vector.length;
        index.put(id, new Entry(id, projectId, text, vector.clone()));
        pendingUpserts.add(id);
        pendingDeletes.remove(id);
        markDirty();
    }

    @Override
    public synchronized void delete(String id) {
        index.remove(id);
        pendingDeletes.add(id);
        pendingUpserts.remove(id);
        markDirty();
    }

    /** p10-w1-03: 按前缀批量删除（章节重写清旧 chunk），返回删除条数 */
    @Override
    public synchronized int deleteByPrefix(String idPrefix) {
        int removed = 0;
        var it = index.keySet().iterator();
        while (it.hasNext()) {
            String id = it.next();
            if (id.startsWith(idPrefix)) {
                it.remove();
                pendingDeletes.add(id);
                pendingUpserts.remove(id);
                removed++;
            }
        }
        if (removed > 0) markDirty();
        return removed;
    }

    /** p12-K3: 标记脏 + 达阈值自动 flush */
    private void markDirty() {
        dirty = true;
        pendingChanges++;
        if (pendingChanges >= FLUSH_THRESHOLD) {
            flush();
        }
    }

    /** p12-K3: 手动触发持久化（每章写完后调用） */
    public synchronized void flush() {
        if (!dirty) return;
        persist();
        dirty = false;
        pendingChanges = 0;
    }

    @Override
    public List<VectorSearchResult> search(float[] queryVector, String projectId, int topK) {
        List<VectorSearchResult> results = new ArrayList<>();
        for (Entry e : index.values()) {
            if (!e.projectId.equals(projectId)) continue;
            double score = cosineSimilarity(queryVector, e.vector);
            // score 阈值过滤：cosine < 0 视为负相关，丢弃避免噪声注入
            if (score >= 0.0) {
                results.add(new VectorSearchResult(e.id, score));
            }
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.size() <= topK ? results : results.subList(0, topK);
    }

    @Override
    public String getText(String id) {
        Entry e = index.get(id);
        return e == null ? null : e.text;
    }

    /**
     * 余弦相似度。
     * 注意：乘积必须在 double 下计算——float 乘积对大分量会丢精度，
     * 可能令 cos>1（数学上不可能）并污染 top-K 排序。
     */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * NCS-134: 按项目派生索引路径——<dir>/<base>-<projectId>.idx，
     * 多项目互不拖累（旧全局单文件 O(全表)扫描跨项目条目 + 全量重写）。
     * 现有部署显式传旧路径不受影响（本方法仅在派生场景使用）。
     */
    public static String perProjectPath(String basePath, String projectId) {
        if (basePath == null || basePath.isBlank()) return basePath;
        int slash = Math.max(basePath.lastIndexOf('/'), basePath.lastIndexOf(File.separatorChar));
        String dir = slash >= 0 ? basePath.substring(0, slash) : ".";
        String base = slash >= 0 ? basePath.substring(slash + 1) : basePath;
        return dir + File.separator + base + "-" + projectId + ".idx";
    }

    // --- 文件持久化 ---
    @SuppressWarnings("unchecked")
    private void load() {
        File f = new File(indexPath);
        // 不存在或空文件视为无索引，避免反序列化空文件抛 EOFException
        if (!f.exists() || f.length() == 0) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new FileInputStream(f))) {
            while (in.available() > 0) {
                int len = (int) in.readLong();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                try (ObjectInputStream ois = new ObjectInputStream(
                        new java.io.ByteArrayInputStream(bytes))) {
                    List<Object> segment = (List<Object>) ois.readObject();
                    for (Object item : segment) {
                        if (item instanceof Entry e) {
                            index.put(e.id, e);
                            if (e.vector != null && e.vector.length > 0) dimension = e.vector.length;
                        } else if (item instanceof String deletedId) {
                            index.remove(deletedId);
                        }
                    }
                }
            }
        } catch (java.io.EOFException eof) {
            // 尾部截断段（进程中途崩溃）——丢弃该段，前序段仍可用
        } catch (Exception e) {
            throw new RuntimeException("向量索引加载失败: " + indexPath, e);
        }
    }

    /**
     * NCS-134: 增量持久化——文件格式为【长度前缀分段】：每段 = long 长度 + 该段序列化字节
     * （段内容为 List，元素 Entry=upsert / String=删除标记）。追加即增量，load 依序合并，
     * 后写覆盖先写。全量重写仅在维度重建时发生（validateDimension）。
     */
    /** NCS-134: 自上次落盘以来的真增量（upsert ids / delete ids） */
    private final java.util.Set<String> pendingUpserts = java.util.Collections.synchronizedSet(
            new java.util.LinkedHashSet<>());
    private final java.util.Set<String> pendingDeletes = java.util.Collections.synchronizedSet(
            new java.util.LinkedHashSet<>());

    private void persist() {
        File f = new File(indexPath);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        boolean firstWrite = !f.exists() || f.length() == 0;
        List<Object> segment = new ArrayList<>();
        if (firstWrite) {
            segment.addAll(index.values());          // 首写全量快照
            pendingDeletes.clear();                  // 空文件无旧条目可删
        } else {
            for (String id : pendingUpserts) {
                Entry e = index.get(id);
                if (e != null) segment.add(e);       // 仍存在才写
            }
            for (String id : pendingDeletes) segment.add(id);   // String = 删除标记
        }
        try (java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(buffer)) {
            oos.writeObject(segment);
            oos.flush();
            byte[] bytes = buffer.toByteArray();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.FileOutputStream(f, true))) {
                out.writeLong(bytes.length);
                out.write(bytes);
            }
            pendingUpserts.clear();
            pendingDeletes.clear();
        } catch (IOException e) {
            throw new RuntimeException("向量索引持久化失败", e);
        }
    }

    /** 全量重写（维度重建后调用——清文件重落当前索引） */
    private void persistFull() {
        new File(indexPath).delete();
        persist();
    }
}
