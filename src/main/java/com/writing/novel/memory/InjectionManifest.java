package com.writing.novel.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-096: 上下文注入清单——ContextAssembler 组装 WritingContext 的同一路径中
 * 同步产出的"实际注入了什么"结构化记录，按 (projectId, chapterIndex, chapterVersion)
 * 持久化。禁止事后重建：清单条目在选取点（真典事实/向量 chunk/知识条目/伏笔/角色）
 * 现场记录，预算裁剪后按最终注入内容结算 section token。
 *
 * <p>消费方：
 * <ul>
 *   <li>NCS-097 检查器封闭式核对（核对已注入约束是否被正文遵守）</li>
 *   <li>NCS-114 召回使用反馈回路（manifest × FactHarvester 交叉 = 注入命中率）</li>
 *   <li>NCS-137 / NCS-057 质量评测度量底座</li>
 * </ul>
 *
 * <p>确定性要求：payload 不含时间戳等易变字段——同输入同版本重复组装清单稳定。
 */
public class InjectionManifest {

    /** 条目来源类型；SECTION 用于 section 级汇总（无离散 refId） */
    public enum Kind { FACT, CHUNK, KNOWLEDGE, FORESHADOW, CHARACTER, ENTITY, STYLE_ASSET }

    public static final class Entry {
        public final String section;
        public final Kind kind;
        public final String refId;
        public final String label;
        public final int estTokens;

        public Entry(String section, Kind kind, String refId, String label, int estTokens) {
            this.section = section;
            this.kind = kind;
            this.refId = refId;
            this.label = label;
            this.estTokens = estTokens;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Entry that)) return false;
            return estTokens == that.estTokens
                    && java.util.Objects.equals(section, that.section)
                    && kind == that.kind
                    && java.util.Objects.equals(refId, that.refId)
                    && java.util.Objects.equals(label, that.label);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(section, kind, refId, label, estTokens);
        }
    }

    private final String projectId;
    private final int chapterIndex;
    /** 本次组装针对的稿件版本（现有章版本+1；新章=1） */
    private final int chapterVersion;
    private final int windowBudget;
    private final List<Entry> entries = new ArrayList<>();
    /** 预算裁剪后各 section 实际注入 token（终态口径） */
    private final Map<String, Integer> sectionTokens = new LinkedHashMap<>();
    /** 预算裁剪中内容被削减的 section（置空或截断） */
    private final List<String> trimmedSections = new ArrayList<>();

    public InjectionManifest(String projectId, int chapterIndex, int chapterVersion, int windowBudget) {
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.chapterVersion = chapterVersion;
        this.windowBudget = windowBudget;
    }

    public void addEntry(String section, Kind kind, String refId, String label, int estTokens) {
        entries.add(new Entry(section, kind, refId, label, estTokens));
    }

    public void setSectionTokens(Map<String, Integer> tokens) {
        sectionTokens.clear();
        sectionTokens.putAll(tokens);
    }

    public void addTrimmedSection(String section) {
        if (!trimmedSections.contains(section)) trimmedSections.add(section);
    }

    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public int getChapterVersion() { return chapterVersion; }
    public int getWindowBudget() { return windowBudget; }
    public List<Entry> getEntries() { return List.copyOf(entries); }
    public Map<String, Integer> getSectionTokens() { return java.util.Collections.unmodifiableMap(sectionTokens); }
    public List<String> getTrimmedSections() { return List.copyOf(trimmedSections); }

    public List<Entry> entriesOfKind(Kind kind) {
        return entries.stream().filter(e -> e.kind == kind).toList();
    }

    /** 各 section 实际注入 token 合计 */
    public int totalTokens() {
        return sectionTokens.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 持久化出口——memory 层不依赖 persistence（实现方：InjectionManifestRepository） */
    public interface Store {
        void save(InjectionManifest manifest);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("projectId", projectId);
            root.put("chapterIndex", chapterIndex);
            root.put("chapterVersion", chapterVersion);
            root.put("windowBudget", windowBudget);
            List<Map<String, Object>> es = new ArrayList<>();
            for (Entry e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("section", e.section);
                m.put("kind", e.kind.name());
                m.put("refId", e.refId);
                m.put("label", e.label);
                m.put("estTokens", e.estTokens);
                es.add(m);
            }
            root.put("entries", es);
            root.put("sectionTokens", sectionTokens);
            root.put("trimmedSections", trimmedSections);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("injection manifest serialize failed", e);
        }
    }

    public static InjectionManifest fromJson(String json) {
        try {
            Map<String, Object> root = MAPPER.readValue(json, Map.class);
            InjectionManifest m = new InjectionManifest(
                    (String) root.get("projectId"),
                    ((Number) root.get("chapterIndex")).intValue(),
                    ((Number) root.get("chapterVersion")).intValue(),
                    ((Number) root.get("windowBudget")).intValue());
            for (Object o : (List<?>) root.getOrDefault("entries", List.of())) {
                Map<?, ?> e = (Map<?, ?>) o;
                m.entries.add(new Entry(
                        (String) e.get("section"),
                        Kind.valueOf((String) e.get("kind")),
                        (String) e.get("refId"),
                        (String) e.get("label"),
                        ((Number) e.get("estTokens")).intValue()));
            }
            ((Map<?, ?>) root.getOrDefault("sectionTokens", Map.of()))
                    .forEach((k, v) -> m.sectionTokens.put((String) k, ((Number) v).intValue()));
            ((List<?>) root.getOrDefault("trimmedSections", List.of()))
                    .forEach(s -> m.trimmedSections.add((String) s));
            return m;
        } catch (Exception e) {
            throw new IllegalStateException("injection manifest parse failed", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InjectionManifest that)) return false;
        return chapterIndex == that.chapterIndex && chapterVersion == that.chapterVersion
                && java.util.Objects.equals(projectId, that.projectId)
                && entries.equals(that.entries)
                && sectionTokens.equals(that.sectionTokens)
                && trimmedSections.equals(that.trimmedSections);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(projectId, chapterIndex, chapterVersion, entries, sectionTokens, trimmedSections);
    }
}
