package com.writing.novel.service;

import com.writing.novel.domain.AuthorIntent;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.TokenBudget;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Compiles the persisted author contract without promoting prose into execution permissions. */
@Service
public class AuthorIntentContextService {
    private final NovelRepository repository;
    private final PromptTemplateService prompts;

    public AuthorIntentContextService(NovelRepository repository, PromptTemplateService prompts) {
        this.repository = repository;
        this.prompts = prompts;
    }

    /** NCS-034: 当前契约修订号（空契约=-1）——规划/修订/核查请求审计所用修订 */
    public int currentRevision(String projectId) {
        return repository.findAuthorIntent(projectId)
                .filter(i -> !i.isEmpty())
                .map(AuthorIntent::getRevision)
                .orElse(-1);
    }

    /** Empty contracts preserve existing requests. Nonempty contracts are never silently truncated. */
    public String render(String projectId, int maxTokens) {
        if (maxTokens < 0) throw new IllegalArgumentException("创作约定预算不能为负数");
        var intent = repository.findAuthorIntent(projectId).orElse(null);
        if (intent == null || intent.isEmpty()) return "";
        String section = prompts.render("author-intent", Map.of(
                "revision", intent.getRevision(),
                "premise", intent.getPremise(),
                "genreExpectation", intent.getGenreExpectation(),
                "themes", intent.getThemes(),
                "pov", intent.getPov(),
                "languageSamples", intent.getLanguageSamples(),
                "lengthRange", intent.getLengthRange(),
                "autonomy", intent.getAutonomy(),
                "unwantedPatterns", intent.getUnwantedPatterns()));
        if (TokenBudget.estimateTokens(section) > maxTokens) {
            throw new IllegalStateException("创作约定第" + intent.getRevision()
                    + "版超出上下文预算，请缩减约定或选择更大窗口；约定未被截断");
        }
        return section;
    }
}
