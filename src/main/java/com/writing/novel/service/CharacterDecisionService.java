package com.writing.novel.service;

import com.writing.novel.agent.CharacterDecisionExtractor;
import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.CharacterDecision;
import com.writing.novel.persistence.sqlite.CharacterDecisionRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.List;
import java.util.Map;

/**
 * NCS-012: 决策记录服务——按章提取、幂等落库、信封记录。
 * 提取为可选能力（按需端点/偏好开关的管线阶段），坏输出 UNKNOWN 不伪装为无决策。
 */
public class CharacterDecisionService {

    private final CharacterDecisionRepository repository;
    private final CharacterDecisionExtractor extractor;

    public CharacterDecisionService(CharacterDecisionRepository repository,
                                    CharacterDecisionExtractor extractor) {
        this.repository = repository;
        this.extractor = extractor;
    }

    public record ExtractResult(int extracted, CheckResultEnvelope.Status status) {}

    /** 为某章提取决策：同章同稿同人物已存在则跳过（幂等）；执行状态经检查信封记录。 */
    public ExtractResult extractForChapter(String projectId, Chapter chapter) {
        var extraction = extractor.extract(projectId, chapter);
        repository.recordExtraction(projectId, chapter.getChapterIndex(), extraction.status(), extraction.reason());
        if (extraction.status() == CheckResultEnvelope.Status.UNKNOWN
                || extraction.status() == CheckResultEnvelope.Status.INVALID) {
            EventLog.emit("CHARACTER_DECISION_EXTRACTION_FAILED", Map.of(
                    "projectId", projectId,
                    "chapterIndex", chapter.getChapterIndex(),
                    "status", extraction.status().name(),
                    "reason", EventLog.truncate(extraction.reason(), 150)));
            return new ExtractResult(0, extraction.status());
        }
        int saved = 0;
        for (CharacterDecision decision : extraction.decisions()) {
            if (repository.existsForVersion(projectId, decision.getChapterIndex(),
                    decision.getChapterVersion(), decision.getCharacterName())) {
                continue; // 同章同稿同人物幂等
            }
            repository.save(decision);
            saved++;
        }
        return new ExtractResult(saved, extraction.status());
    }

    public List<CharacterDecision> decisions(String projectId) {
        return repository.findByProject(projectId);
    }

    public List<CharacterDecision> decisionsOf(String projectId, String characterName) {
        return repository.findByCharacter(projectId, characterName);
    }
}
