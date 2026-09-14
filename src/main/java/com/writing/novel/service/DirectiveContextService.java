package com.writing.novel.service;

import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * p37-10：生成章前选取并渲染有效作者要求。
 * select 使用 findApplicableDirectives（enabled+范围+未撤销；ONCE 确认后自然排除）；
 * 渲染稳定排序：kind 优先级（HARD>ONCE>PREFERENCE）→ createdAt → id。
 * HARD 超出预算 → 明确阻塞（禁止截掉 HARD，需作者缩减）；软意见可截断并返回遗漏 ID 供 UI 解释。
 */
@Service
public class DirectiveContextService {

    private final NovelRepository repository;

    public DirectiveContextService(NovelRepository repository) {
        this.repository = repository;
    }

    public record SelectedDirectives(String section, List<String> includedIds,
                                     List<String> omittedSoftIds, List<String> blockedHardIds) {

        public boolean isEmpty() {
            return section == null || section.isBlank();
        }
    }

    /** 预算以字符近似（1 token ≈ 2 char）；budgetChars=0 表示不限。 */
    public SelectedDirectives select(String projectId, int chapterIndex, int budgetChars) {
        List<AuthorDirective> applicable = repository.findApplicableDirectives(projectId, chapterIndex);
        if (applicable.isEmpty()) {
            return new SelectedDirectives(null, List.of(), List.of(), List.of());
        }
        List<AuthorDirective> sorted = applicable.stream()
                .sorted(Comparator
                        .comparing((AuthorDirective d) -> kindPriority(d.getKind()))
                        .thenComparing(AuthorDirective::getCreatedAt)
                        .thenComparing(AuthorDirective::getId))
                .toList();

        List<String> included = new ArrayList<>();
        List<String> omittedSoft = new ArrayList<>();
        List<String> blockedHard = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int used = 0;
        for (AuthorDirective d : sorted) {
            String entry = renderEntry(d);
            int entryCost = entry.length();
            boolean hard = d.getKind() == AuthorDirective.Kind.HARD;
            if (budgetChars > 0 && used + entryCost > budgetChars) {
                if (hard) {
                    blockedHard.add(d.getId()); // 不截 HARD：交由上层阻塞并要求缩减
                } else {
                    omittedSoft.add(d.getId());
                }
                continue;
            }
            body.append(entry);
            used += entryCost;
            included.add(d.getId());
        }
        if (body.isEmpty()) {
            return new SelectedDirectives(null, List.of(), omittedSoft, blockedHard);
        }
        String section = body.toString();
        return new SelectedDirectives(section, List.copyOf(included),
                List.copyOf(omittedSoft), List.copyOf(blockedHard));
    }

    private String renderEntry(AuthorDirective d) {
        String scope = d.getFromChapter() == null ? "全书"
                : "第" + d.getFromChapter() + (d.getToChapter() == null ? "章起" : "-" + d.getToChapter() + "章");
        return "- [" + d.getId() + " rev" + d.getRevision() + " " + d.getKind().name() + " " + scope + "] "
                + d.getContent() + "\n";
    }

    private int kindPriority(AuthorDirective.Kind kind) {
        return switch (kind) {
            case HARD -> 0;
            case ONCE -> 1;
            case PREFERENCE -> 2;
        };
    }
}
