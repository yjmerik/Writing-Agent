package com.writing.novel.service;

import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.domain.AuthorIntent;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AuthorIntentContextTest {
    @TempDir Path temp;
    private NovelRepository repo;
    private AuthorIntentRepository author;
    private String projectId;

    @BeforeEach void setup() {
        var cp = new ConnectionProvider(temp.resolve("intent.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        author = new AuthorIntentRepository(cp);
        var p = new Project("书", "旧构思", "悬疑"); repo.saveProject(p); projectId = p.getId();
    }

    private String render(int budget) {
        return new AuthorIntentContextService(repo, TestPromptHelper.create()).render(projectId, budget);
    }

    @Test void emptyContractAddsNoPromptAndDoesNotInventDefaults() {
        assertEquals("", render(0));
        author.append(projectId, new AuthorIntent());
        assertEquals("", render(0));
    }
    @Test void everyFieldAndRevisionReachRenderedContextWithoutInterpretingAuthorTemplateSyntax() {
        var i = new AuthorIntent();
        i.setPremise("寻找失踪的账房"); i.setGenreExpectation("成年读者的悬疑");
        i.setThemes("信任的代价"); i.setPov("第三人称限制视角");
        i.setLanguageSamples("他写下{{name}}，没有抬头。$1\\"); i.setLengthRange("60至90章");
        i.setAutonomy("命运变更必须问我"); i.setUnwantedPatterns("不靠巧合解围");
        author.append(projectId, i);
        String text = render(16000);
        for (String v : new String[]{"寻找失踪的账房", "成年读者的悬疑", "信任的代价", "第三人称限制视角",
                "他写下{{name}}，没有抬头。$1\\", "60至90章", "命运变更必须问我", "不靠巧合解围", "第1版"}) {
            assertTrue(text.contains(v), "约定被遗漏或变形: " + v);
        }
    }
    @Test void newerContractReplacesOldContractWithoutMixingProjects() {
        var i = new AuthorIntent(); i.setPov("第一人称旧约定"); author.append(projectId, i);
        assertTrue(render(16000).contains("第一人称旧约定"));
        var j = new AuthorIntent(); j.setPov("第三人称新约定"); author.append(projectId, j);
        var unrelated = new AuthorIntent(); unrelated.setPov("其他书的秘密视角"); author.append("other", unrelated);
        String text = render(16000);
        assertTrue(text.contains("第三人称新约定")); assertTrue(text.contains("第2版"));
        assertFalse(text.contains("第一人称旧约定")); assertFalse(text.contains("其他书的秘密视角"));
    }
    @Test void oversizedContractBlocksInsteadOfDroppingFieldsOrTail() {
        var i = new AuthorIntent(); i.setPov("不得泄露身份"); i.setLanguageSamples("完整原文".repeat(100));
        author.append(projectId, i);
        assertThrows(IllegalStateException.class, () -> render(8));
        assertThrows(IllegalStateException.class, () -> render(0));
        assertTrue(render(16000).contains(i.getLanguageSamples()));
    }
}
