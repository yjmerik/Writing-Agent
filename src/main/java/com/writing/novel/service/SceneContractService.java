package com.writing.novel.service;

import com.writing.novel.agent.SceneContractAgent;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.spi.NovelRepository;

/**
 * NCS-018: 场景合同服务——按需生成并落库到章梗概；读取时解析为结构化 Map。
 */
public class SceneContractService {

    public record ContractResult(int chapterIndex, boolean generated, String contractJson) {}

    private final NovelRepository repository;
    private final SceneContractAgent agent;

    public SceneContractService(NovelRepository repository, SceneContractAgent agent) {
        this.repository = repository;
        this.agent = agent;
    }

    public ContractResult generateForChapter(String projectId, int chapterIndex) {
        Outline outline = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目尚无大纲"));
        ChapterSynopsis synopsis = outline.getVolumes().stream()
                .flatMap(v -> v.getSynopses().stream())
                .filter(s -> s.getChapterIndex() == chapterIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("章节梗概不存在: " + chapterIndex));
        String contract = agent.generate(synopsis.getTitle(), synopsis.getSynopsis(),
                synopsis.getWritingNotes());
        if (contract == null) {
            return new ContractResult(chapterIndex, false, null);
        }
        synopsis.setSceneContract(contract);
        repository.saveOutline(outline);
        return new ContractResult(chapterIndex, true, contract);
    }

    /** 作者编辑通道：直接写入合同 JSON（空串=清除） */
    public void saveContract(String projectId, int chapterIndex, String contractJson) {
        Outline outline = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目尚无大纲"));
        ChapterSynopsis synopsis = outline.getVolumes().stream()
                .flatMap(v -> v.getSynopses().stream())
                .filter(s -> s.getChapterIndex() == chapterIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("章节梗概不存在: " + chapterIndex));
        String trimmed = contractJson == null ? "" : contractJson.trim();
        if (!trimmed.isEmpty()) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
                if (!node.isObject()) throw new IllegalArgumentException("合同必须是 JSON 对象");
            } catch (Exception e) {
                throw new IllegalArgumentException("合同不是有效 JSON: " + e.getMessage());
            }
        }
        synopsis.setSceneContract(trimmed.isEmpty() ? null : trimmed);
        repository.saveOutline(outline);
    }

    /** 读取合同（结构化解析；无合同返回 null） */
    public java.util.Map<String, Object> contractOf(String projectId, int chapterIndex) {
        return repository.findOutlineByProject(projectId)
                .flatMap(o -> o.getVolumes().stream()
                        .flatMap(v -> v.getSynopses().stream())
                        .filter(s -> s.getChapterIndex() == chapterIndex)
                        .findFirst())
                .map(s -> {
                    if (s.getSceneContract() == null) return null;
                    try {
                        return new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(s.getSceneContract(),
                                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }
}
