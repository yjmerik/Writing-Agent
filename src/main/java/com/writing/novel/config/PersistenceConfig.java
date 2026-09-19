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

    // NCS-013: 统一修订请求
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public com.writing.novel.persistence.sqlite.RevisionRequestRepository revisionRequestRepository(ConnectionProvider cp) {
        return new com.writing.novel.persistence.sqlite.RevisionRequestRepository(cp);
    }

    @Bean
    public com.writing.novel.service.RevisionRequestService revisionRequestService(
            com.writing.novel.persistence.sqlite.RevisionRequestRepository revisionRequestRepository) {
        return new com.writing.novel.service.RevisionRequestService(revisionRequestRepository);
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

    // NCS-014: 文本变更影响分析（确定性）
    @Bean
    public com.writing.novel.service.ImpactAnalysisService impactAnalysisService(
            NovelRepository repository,
            com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository) {
        return new com.writing.novel.service.ImpactAnalysisService(repository,
                new com.writing.novel.persistence.sqlite.SqliteStoryCanon(storyFactRepository, repository),
                storyFactRepository);
    }

    // NCS-015/016: 修订修复生成 + 依赖组整组接受
    @Bean
    public com.writing.novel.service.GroupAcceptanceService groupAcceptanceService(
            NovelRepository repository,
            com.writing.novel.service.RevisionRequestService revisionRequestService,
            com.writing.novel.service.SupportWithdrawalService supportWithdrawalService) {
        return new com.writing.novel.service.GroupAcceptanceService(repository, revisionRequestService,
                new com.writing.novel.service.ChapterRevisionService(repository, null, null,
                        supportWithdrawalService, revisionRequestService));
    }

    @Bean
    public com.writing.novel.service.RevisionRepairService revisionRepairService(
            NovelRepository repository,
            com.writing.novel.service.ImpactAnalysisService impactAnalysisService,
            com.writing.novel.service.AgentRevisionService agentRevisionService,
            com.writing.novel.service.RevisionRequestService revisionRequestService) {
        return new com.writing.novel.service.RevisionRepairService(
                impactAnalysisService, agentRevisionService, revisionRequestService, repository);
    }

    // NCS-022: 审美档案
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public com.writing.novel.persistence.sqlite.AestheticProfileRepository aestheticProfileRepository(ConnectionProvider cp) {
        return new com.writing.novel.persistence.sqlite.AestheticProfileRepository(cp);
    }

    @Bean
    public com.writing.novel.service.AestheticProfileService aestheticProfileService(
            com.writing.novel.persistence.sqlite.AestheticProfileRepository aestheticProfileRepository) {
        return new com.writing.novel.service.AestheticProfileService(aestheticProfileRepository);
    }

    // NCS-021: 按正文更新计划
    @Bean
    public com.writing.novel.service.ReplanFromTextService replanFromTextService(
            com.writing.novel.orchestrator.NovelOrchestrator orchestrator,
            com.writing.novel.service.RevisionRequestService revisionRequestService,
            com.writing.novel.service.VolumeReadbackService volumeReadbackService) {
        return new com.writing.novel.service.ReplanFromTextService(orchestrator,
                revisionRequestService, volumeReadbackService);
    }

    // NCS-020: 卷末回读
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public com.writing.novel.persistence.sqlite.VolumeReadbackRepository volumeReadbackRepository(ConnectionProvider cp) {
        return new com.writing.novel.persistence.sqlite.VolumeReadbackRepository(cp);
    }

    @Bean
    public com.writing.novel.service.VolumeReadbackService volumeReadbackService(
            NovelRepository repository,
            com.writing.novel.llm.LlmGateway llmGateway,
            com.writing.novel.llm.prompt.PromptTemplateService promptTemplateService,
            com.writing.novel.persistence.sqlite.PlanDeviationRepository planDeviationRepository,
            com.writing.novel.persistence.sqlite.VolumeReadbackRepository volumeReadbackRepository) {
        return new com.writing.novel.service.VolumeReadbackService(repository, llmGateway,
                promptTemplateService, planDeviationRepository, volumeReadbackRepository);
    }

    // NCS-019: 计划偏离
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public com.writing.novel.persistence.sqlite.PlanDeviationRepository planDeviationRepository(ConnectionProvider cp) {
        return new com.writing.novel.persistence.sqlite.PlanDeviationRepository(cp);
    }

    @Bean
    public com.writing.novel.service.PlanDeviationService planDeviationService(
            NovelRepository repository,
            com.writing.novel.service.RevisionRequestService revisionRequestService,
            com.writing.novel.llm.LlmGateway llmGateway,
            com.writing.novel.llm.prompt.PromptTemplateService promptTemplateService,
            com.writing.novel.persistence.sqlite.PlanDeviationRepository planDeviationRepository) {
        return new com.writing.novel.service.PlanDeviationService(repository, revisionRequestService,
                llmGateway, promptTemplateService, planDeviationRepository);
    }

    // NCS-018: 场景合同
    @Bean
    public com.writing.novel.agent.SceneContractAgent sceneContractAgent(
            com.writing.novel.llm.LlmGateway llmGateway,
            com.writing.novel.llm.prompt.PromptTemplateService promptTemplateService) {
        return new com.writing.novel.agent.SceneContractAgent(llmGateway, promptTemplateService);
    }

    @Bean
    public com.writing.novel.service.SceneContractService sceneContractService(
            NovelRepository repository,
            com.writing.novel.agent.SceneContractAgent sceneContractAgent) {
        return new com.writing.novel.service.SceneContractService(repository, sceneContractAgent);
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
