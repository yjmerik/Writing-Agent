package com.writing.novel.service;

import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.List;
import java.util.Map;

/**
 * NCS-009（方案 §09/R03/F04）：来源撤回与支持传播。
 *
 * <p>证据锚定精确稿版本；来源稿被修订后，绑定旧版本的证据失效（stale）。
 * 若某事实的全部证据均失效——它失去支持：转 UNSUPPORTED（停止注入）并留事件，
 * 绝不伪装成仍然成立；若仍有其他有效证据则保留。后续重新收割/补证可恢复 ACTIVE。
 * 循环支持不能自证为真：只有有效外部证据（fact_evidence 非 stale）才维持事实。
 */
public class SupportWithdrawalService {

    private final StoryFactRepository factRepo;

    public SupportWithdrawalService(StoryFactRepository factRepo) {
        this.factRepo = factRepo;
    }

    /**
     * 章节修订（版本推进到 currentVersion）后的支持传播。
     * 幂等：重复调用无新效果（stale 已置位，第二次 affected 为空）。
     */
    public void onChapterRevised(String projectId, int chapterIndex, int currentVersion) {
        List<String> affected = factRepo.markEvidenceStaleForRevisedChapter(projectId, chapterIndex, currentVersion);
        for (String factId : affected) {
            if (factRepo.hasActiveEvidence(factId)) {
                continue; // 多证据：删一留余，事实保留
            }
            factRepo.findById(factId).filter(f -> f.getStatus() == StoryFact.Status.ACTIVE)
                    .ifPresent(fact -> {
                        fact.setStatus(StoryFact.Status.UNSUPPORTED);
                        factRepo.save(fact);
                        EventLog.emit("FACT_UNSUPPORTED", Map.of(
                                "projectId", projectId,
                                "factId", factId,
                                "subject", String.valueOf(fact.getSubject()),
                                "assertion", EventLog.truncate(fact.getAssertion(), 150),
                                "chapterIndex", chapterIndex,
                                "revisedToVersion", currentVersion));
                    });
        }
    }
}
