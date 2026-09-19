package com.writing.novel.config;

import com.writing.novel.persistence.embedding.CloudApiEmbeddingProvider;
import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.embedding.StubEmbeddingProvider;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PersistenceConfig {

    // --- SQLite ---
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public ConnectionProvider sqliteConnectionProvider(PersistenceProperties props) {
        return new ConnectionProvider(props.getSqlite().getDbPath());
    }

    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public SqliteSchemaInitializer sqliteSchemaInitializer(ConnectionProvider cp) {
        return new SqliteSchemaInitializer(cp);
    }

    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public NovelRepository sqliteNovelRepository(ConnectionProvider cp,
                                                  SqliteSchemaInitializer schemaInit) {
        schemaInit.initialize();
        return new SqliteNovelRepository(cp);
    }

    // NCS-001: 项目级写入门禁（epoch 租约）——持久化与门禁服务
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public com.writing.novel.persistence.sqlite.WriteLeaseRepository writeLeaseRepository(ConnectionProvider cp) {
        return new com.writing.novel.persistence.sqlite.WriteLeaseRepository(cp);
    }

    @Bean
    public com.writing.novel.service.WriteLeaseService writeLeaseService(
            com.writing.novel.persistence.sqlite.WriteLeaseRepository writeLeaseRepository) {
        return new com.writing.novel.service.WriteLeaseService(writeLeaseRepository);
    }

    // NCS-003: 创作约定（AuthorIntent）版本化持久化
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public com.writing.novel.persistence.sqlite.AuthorIntentRepository authorIntentRepository(ConnectionProvider cp) {
        return new com.writing.novel.persistence.sqlite.AuthorIntentRepository(cp);
    }

    // NCS-009: 来源撤回与支持传播
    @Bean
    public com.writing.novel.service.SupportWithdrawalService supportWithdrawalService(
            com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository) {
        return new com.writing.novel.service.SupportWithdrawalService(storyFactRepository);
    }

    // NCS-012: 人物关键决策
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public com.writing.novel.persistence.sqlite.CharacterDecisionRepository characterDecisionRepository(ConnectionProvider cp) {
        return new com.writing.novel.persistence.sqlite.CharacterDecisionRepository(cp);
    }

    @Bean
    public com.writing.novel.agent.CharacterDecisionExtractor characterDecisionExtractor(
            com.writing.novel.llm.LlmGateway llmGateway,
            com.writing.novel.llm.prompt.PromptTemplateService promptTemplateService) {
        return new com.writing.novel.agent.CharacterDecisionExtractor(llmGateway, promptTemplateService);
    }

    @Bean
    public com.writing.novel.service.CharacterDecisionService characterDecisionService(
            com.writing.novel.persistence.sqlite.CharacterDecisionRepository characterDecisionRepository,
            com.writing.novel.agent.CharacterDecisionExtractor characterDecisionExtractor) {
        return new com.writing.novel.service.CharacterDecisionService(characterDecisionRepository, characterDecisionExtractor);
    }

    // NCS-010: 人物时点读取
    @Bean
    public com.writing.novel.service.CharacterAsOfService characterAsOfService(
            NovelRepository repository,
            com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository,
            com.writing.novel.persistence.FactEvidenceRepository factEvidenceRepository) {
        return new com.writing.novel.service.CharacterAsOfService(repository,
                new com.writing.novel.persistence.sqlite.SqliteStoryCanon(storyFactRepository, repository),
                factEvidenceRepository);
    }

    // NCS-007: 检查结果信封持久化
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public com.writing.novel.persistence.sqlite.CheckResultRepository checkResultRepository(ConnectionProvider cp) {
        return new com.writing.novel.persistence.sqlite.CheckResultRepository(cp);
    }

    // NCS-005: 统一工作台读取模型（NCS-007 起接入 unknownChecks）
    @Bean
    public com.writing.novel.service.WorkspaceViewService workspaceViewService(
            NovelRepository repository,
            com.writing.novel.persistence.sqlite.CheckResultRepository checkResultRepository) {
        return new com.writing.novel.service.WorkspaceViewService(repository, checkResultRepository);
    }

    // --- Embedded VectorStore ---
    @Bean
    @ConditionalOnProperty(name = "persistence.vector", havingValue = "embedded", matchIfMissing = true)
    public VectorStore embeddedVectorStore(PersistenceProperties props) {
        return new EmbeddedVectorStore(props.getVectorStore().getIndexPath());
    }

    // --- Embedding: 云 API（OpenAI 兼容 /v1/embeddings） ---
    @Bean
    @ConditionalOnProperty(name = "persistence.embedding", havingValue = "cloud-api", matchIfMissing = true)
    public EmbeddingProvider cloudApiEmbeddingProvider(PersistenceProperties props) {
        return new CloudApiEmbeddingProvider(
                props.getEmbeddingCfg().getApiBaseUrl(),
                props.getEmbeddingCfg().getApiKey(),
                props.getEmbeddingCfg().getModel()
        );
    }

    // --- Embedding: Stub（确定性占位，用于测试与离线开发） ---
    @Bean
    @ConditionalOnProperty(name = "persistence.embedding", havingValue = "stub", matchIfMissing = false)
    public EmbeddingProvider stubEmbeddingProvider() {
        return new StubEmbeddingProvider();
    }
}
