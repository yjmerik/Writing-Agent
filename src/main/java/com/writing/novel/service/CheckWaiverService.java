package com.writing.novel.service;

import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.persistence.sqlite.CheckResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * NCS-088：未查清检查信封的作者担责豁免（AUTHOR_WAIVED）。
 *
 * <p>版本化批准的语义是"作者已读当前稿并担责放行"——批准成功时对作者尚未拿到
 * 有效结论的检查（最新状态 UNKNOWN/INVALID）自动追加担责豁免行；存量章节
 * （批准先于本能力存在、或作者在审阅中直接豁免）走显式 waiver 入口。
 * 豁免行不可变追加、reason 强制可审计；unknownCount 因此归零，成书门禁
 * 不再因 unknown 阻塞，但校验报告仍将豁免与"已检查"区分展示。
 */
@Service
public class CheckWaiverService {

    private static final Logger log = LoggerFactory.getLogger(CheckWaiverService.class);

    private final CheckResultRepository checkResultRepository;

    public CheckWaiverService(CheckResultRepository checkResultRepository) {
        this.checkResultRepository = checkResultRepository;
    }

    /** 版本化批准成功后的自动豁免：返回豁免的检查器数。 */
    public int waiveForApproval(String projectId, int chapterIndex, String approvalId) {
        List<CheckResultRepository.LatestCheck> unresolved = unresolved(projectId, chapterIndex);
        for (CheckResultRepository.LatestCheck check : unresolved) {
            checkResultRepository.save(projectId, chapterIndex, check.checker(),
                    CheckResultEnvelope.Status.AUTHOR_WAIVED,
                    "approvalId=" + approvalId + " 作者确认已复核并批准（担责放行）");
        }
        if (!unresolved.isEmpty()) {
            log.info("批准自动豁免(项目{}, 章{}): {} 项未查清 → AUTHOR_WAIVED",
                    projectId, chapterIndex, unresolved.size());
        }
        return unresolved.size();
    }

    /** 手动豁免（reason 必填由调用方校验）：返回被豁免的检查器名。 */
    public String waive(String projectId, int chapterIndex, String checker, String reason) {
        CheckResultRepository.LatestCheck latest = checkResultRepository.latestForChapter(projectId, chapterIndex)
                .stream().filter(c -> c.checker().equals(checker)).findFirst().orElse(null);
        if (latest != null && (latest.status() == CheckResultEnvelope.Status.SUCCESS
                || latest.status() == CheckResultEnvelope.Status.VALID_EMPTY)) {
            throw new IllegalStateException("该检查已有有效结论（" + latest.status() + "），无需担责豁免");
        }
        checkResultRepository.save(projectId, chapterIndex, checker,
                CheckResultEnvelope.Status.AUTHOR_WAIVED, "作者担责豁免：" + reason);
        return checker;
    }

    private List<CheckResultRepository.LatestCheck> unresolved(String projectId, int chapterIndex) {
        return checkResultRepository.latestForChapter(projectId, chapterIndex).stream()
                .filter(c -> c.status() == CheckResultEnvelope.Status.UNKNOWN
                        || c.status() == CheckResultEnvelope.Status.INVALID)
                .toList();
    }
}
