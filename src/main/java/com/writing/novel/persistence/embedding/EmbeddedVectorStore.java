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

    public EmbeddedVectorStore(String indexPath) {
        this.indexPath = indexPath;
        load();
    }

    @Override
    public synchronized void upsert(String id, String projectId, String text, float[] vector) {
        index.put(id, new Entry(id, projectId, text, vector.clone()));
        persist();
    }

    @Override
    public synchronized void delete(String id) {
        index.remove(id);
        persist();
    }

    @Override
    public List<VectorSearchResult> search(float[] queryVector, String projectId, int topK) {
        List<VectorSearchResult> results = new ArrayList<>();
        for (Entry e : index.values()) {
            if (!e.projectId.equals(projectId)) continue;
            double score = cosineSimilarity(queryVector, e.vector);
            results.add(new VectorSearchResult(e.id, score));
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.size() <= topK ? results : results.subList(0, topK);
    }

    @Override
    public String getText(String id) {
        Entry e = index.get(id);
        return e == null ? null : e.text;
    }

    /** 余弦相似度 */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // --- 文件持久化 ---
    @SuppressWarnings("unchecked")
    private void load() {
        File f = new File(indexPath);
        if (!f.exists()) return;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            List<Entry> list = (List<Entry>) ois.readObject();
            for (Entry e : list) index.put(e.id, e);
        } catch (Exception e) {
            throw new RuntimeException("向量索引加载失败: " + indexPath, e);
        }
    }

    private void persist() {
        File f = new File(indexPath);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
            oos.writeObject(new ArrayList<>(index.values()));
        } catch (IOException e) {
            throw new RuntimeException("向量索引持久化失败", e);
        }
    }
}
