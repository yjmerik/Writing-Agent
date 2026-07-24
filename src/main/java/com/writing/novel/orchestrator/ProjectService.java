package com.writing.novel.orchestrator;

import com.writing.novel.domain.*;
import com.writing.novel.service.PresetRegistry;
import com.writing.novel.spi.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 项目管理服务。创建/查询/模式切换。
 */
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final NovelRepository repository;
    private final PresetRegistry presetRegistry;

    public ProjectService(NovelRepository repository) {
        this(repository, null);
    }

    public ProjectService(NovelRepository repository, PresetRegistry presetRegistry) {
        this.repository = repository;
        this.presetRegistry = presetRegistry;
    }

    public Project create(String title, String concept, String tags) {
        return create(title, concept, tags, ProjectMode.SOLO, null);
    }

    public Project create(String title, String concept, String tags, ProjectMode mode) {
        return create(title, concept, tags, mode, null);
    }

    public Project create(String title, String concept, String tags, ProjectMode mode, String presetName) {
        Project p = new Project(title, concept, tags);
        p.setMode(mode);
        repository.saveProject(p);

        // 应用预设：立即初始化 WritingPreferences + StyleProfile 种子 + GenreHint
        if (presetName != null && !presetName.isBlank() && presetRegistry != null) {
            presetRegistry.getPreset(presetName).ifPresent(preset -> {
                // 初始化 WritingPreferences
                var prefs = preset.applyToPreferences(p.getId());
                repository.saveWritingPreferences(prefs);

                // 初始化 StyleProfile 种子
                if (preset.getStyleTone() != null || preset.getStyleVoice() != null) {
                    var profile = new StyleProfile(p.getId());
                    preset.applyToStyleProfile(profile);
                    repository.saveStyleProfile(profile);
                }

                // 设置题材提示
                if (preset.getGenreHint() != null) {
                    p.setGenre(preset.getGenreHint());
                    repository.saveProject(p);
                }

                log.info("项目 {} 应用预设: {} (genre={}, dialogue={})",
                        p.getId(), presetName,
                        preset.getGenreHint(),
                        prefs.getDialogueDensity());
            });
        }

        return p;
    }

    public Optional<Project> find(String id) {
        return repository.findProject(id);
    }

    public List<Project> findAll() {
        return repository.findAllProjects();
    }

    public void switchMode(String projectId, ProjectMode mode) {
        Project p = repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        p.setMode(mode);
        repository.saveProject(p);
    }

    public void delete(String id) {
        repository.deleteProject(id);
    }
}
