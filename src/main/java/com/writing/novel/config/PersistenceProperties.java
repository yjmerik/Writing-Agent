package com.writing.novel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "persistence")
public class PersistenceProperties {
    private String relational = "sqlite";
    private String vector = "embedded";
    private String embedding = "cloud-api";
    private Sqlite sqlite = new Sqlite();
    private VectorStoreCfg vectorStore = new VectorStoreCfg();
    private EmbeddingCfg embeddingCfg = new EmbeddingCfg();

    public static class Sqlite {
        private String dbPath = "./data/novel.db";
        public String getDbPath() { return dbPath; }
        public void setDbPath(String p) { this.dbPath = p; }
    }
    public static class VectorStoreCfg {
        private String indexPath = "./data/vectors.idx";
        public String getIndexPath() { return indexPath; }
        public void setIndexPath(String p) { this.indexPath = p; }
    }
    public static class EmbeddingCfg {
        private String apiBaseUrl;
        private String apiKey;
        private String model = "text-embedding-v2";
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String u) { this.apiBaseUrl = u; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String k) { this.apiKey = k; }
        public String getModel() { return model; }
        public void setModel(String m) { this.model = m; }
    }

    public String getRelational() { return relational; }
    public void setRelational(String r) { this.relational = r; }
    public String getVector() { return vector; }
    public void setVector(String v) { this.vector = v; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String e) { this.embedding = e; }
    public Sqlite getSqlite() { return sqlite; }
    public void setSqlite(Sqlite s) { this.sqlite = s; }
    public VectorStoreCfg getVectorStore() { return vectorStore; }
    public void setVectorStore(VectorStoreCfg v) { this.vectorStore = v; }
    public EmbeddingCfg getEmbeddingCfg() { return embeddingCfg; }
    public void setEmbeddingCfg(EmbeddingCfg e) { this.embeddingCfg = e; }
}
