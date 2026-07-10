package com.writing.novel.orchestrator;

import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Optional;

/**
 * 项目管理服务。创建/查询/模式切换。
 */
public class ProjectService {

    private final NovelRepository repository;

    public ProjectService(NovelRepository repository) {
        this.repository = repository;
    }

    public Project create(String title, String concept, String tags) {
        return create(title, concept, tags, ProjectMode.SOLO);
    }

    public Project create(String title, String concept, String tags, ProjectMode mode) {
        Project p = new Project(title, concept, tags);
        p.setMode(mode);
        repository.saveProject(p);
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
