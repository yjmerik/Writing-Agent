package com.writing.novel.service;

import com.writing.novel.domain.AuthorIntent;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class IntentRuntimeReadTest {
    @TempDir Path temp;

    @Test
    void runtimeReadsLatestPersistedContractWithoutCrossProjectOrStaleCache() throws Exception {
        var cp = new ConnectionProvider(temp.resolve("intent.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        NovelRepository runtime = new SqliteNovelRepository(cp);
        var project = new Project("甲", "", "");
        var other = new Project("乙", "", "");
        runtime.saveProject(project);
        runtime.saveProject(other);
        assertEquals(Optional.empty(), runtime.findAuthorIntent(project.getId()));
        var author = new AuthorIntentRepository(cp);
        var first = new AuthorIntent();
        first.setPov("第一人称");
        author.append(project.getId(), first);
        AuthorIntent v1 = runtime.findAuthorIntent(project.getId()).orElseThrow();
        assertEquals("第一人称", v1.getPov());
        var second = new AuthorIntent();
        second.setPov("第三人称限制视角");
        second.setLanguageSamples("风停了，他仍等在门外。");
        author.append(project.getId(), second);
        for (NovelRepository reader : java.util.List.of(runtime, new SqliteNovelRepository(cp))) {
            AuthorIntent current = reader.findAuthorIntent(project.getId()).orElseThrow();
            assertEquals(2, current.getRevision());
            assertEquals("第三人称限制视角", current.getPov());
            assertEquals("风停了，他仍等在门外。", current.getLanguageSamples());
            assertEquals(Optional.empty(), reader.findAuthorIntent(other.getId()));
        }
        assertEquals("第一人称", author.history(project.getId()).get(0).getPov());
    }
}
