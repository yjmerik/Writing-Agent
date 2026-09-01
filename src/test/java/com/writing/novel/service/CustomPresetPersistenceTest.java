package com.writing.novel.service;

import com.writing.novel.domain.GenreProfile;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p17-05: 自定义预设持久化。
 * 原状：PresetRegistry.registerPreset 只存内存（重启即丢）。
 * 修复：custom_presets 表落 SQLite，注册时写库、启动时加载。
 */
class CustomPresetPersistenceTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("presets.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
    }

    @Test
    void 自定义预设注册后重启_新Registry实例可见() {
        // 第一次启动：注册自定义预设
        PresetRegistry first = new PresetRegistry(repo);
        first.createPresetFromConfig("my-wuxia", "自义武侠风",
                Map.of("dialogueDensity", 0.5), "WUXIA",
                "苍劲", "第三人称白描", "禁现代词");

        // 模拟重启：同一数据库上新建 Registry（不共享内存）
        PresetRegistry second = new PresetRegistry(repo);
        var restored = second.getPreset("my-wuxia");
        assertTrue(restored.isPresent(), "自定义预设重启后应可从持久层恢复");
        assertEquals(GenreProfile.WUXIA, restored.get().getGenreHint());
        assertEquals("苍劲", restored.get().getStyleTone());
        assertEquals(0.5, ((Number) restored.get().getPreferences().get("dialogueDensity")).doubleValue(), 1e-9);
        assertFalse(restored.get().isBuiltin());

        // 内置预设不受影响
        assertTrue(second.getPreset("wuxia").isPresent());
        assertTrue(second.getPreset("wuxia").get().isBuiltin());
    }

    @Test
    void 更新自定义预设为upsert不报错() {
        PresetRegistry registry = new PresetRegistry(repo);
        registry.createPresetFromConfig("dup", "v1", Map.of(), null, null, null, null);
        registry.createPresetFromConfig("dup", "v2", Map.of("dialogueDensity", 0.4),
                null, null, null, null);
        assertEquals("v2", new PresetRegistry(repo).getPreset("dup").orElseThrow().getDescription());
    }
}
