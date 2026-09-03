package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.spi.NovelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 人工大纲结构替换边界：已写前缀不可变，未写结构按完整 payload 替换。
 */
@Component
public class OutlineStructureService {

    private final NovelRepository repository;
    private final CanonPatchService canonPatch;
    private final WritingTaskExecutor writingTaskExecutor;

    @Autowired
    public OutlineStructureService(NovelRepository repository, CanonPatchService canonPatch,
                                   WritingTaskExecutor writingTaskExecutor) {
        this.repository = repository;
        this.canonPatch = canonPatch;
        this.writingTaskExecutor = writingTaskExecutor;
    }

    /** Unit-test compatibility for callers that do not exercise project work ownership. */
    public OutlineStructureService(NovelRepository repository, CanonPatchService canonPatch) {
        this(repository, canonPatch, null);
    }

    public Outline replaceUnwritten(String projectId, Outline incoming) {
        String workId = "outline-edit-" + UUID.randomUUID();
        if (writingTaskExecutor != null) writingTaskExecutor.beginWork(projectId, workId);
        try {
            return replaceWhileOwningProject(projectId, incoming);
        } finally {
            if (writingTaskExecutor != null) writingTaskExecutor.endWork(projectId, workId);
        }
    }

    private Outline replaceWhileOwningProject(String projectId, Outline incoming) {
        if (incoming == null) throw new IllegalArgumentException("大纲内容不能为空");
        requireProject(projectId, incoming.getProjectId(), "大纲");

        Outline existing = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("大纲不存在: " + projectId));
        Project project = repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        int writtenBoundary = project.getCurrentChapterIndex();

        List<Volume> incomingVolumes = incoming.getVolumes();
        if (incomingVolumes == null) throw new IllegalArgumentException("卷结构不能为空");
        validateVolumeStructure(projectId, incomingVolumes);

        List<Integer> indices = incomingVolumes.stream()
                .flatMap(volume -> volume.getSynopses().stream())
                .map(ChapterSynopsis::getChapterIndex)
                .toList();
        validateChapterIndices(indices);
        if (indices.isEmpty()) throw new IllegalArgumentException("大纲至少需要一章");

        Map<Integer, LocatedSynopsis> existingWritten = writtenByIndex(existing, writtenBoundary);
        Map<Integer, LocatedSynopsis> incomingByIndex = locateByIndex(incomingVolumes);
        validateWrittenPrefix(existingWritten, incomingByIndex, writtenBoundary);

        String premise = incoming.getPremise() == null || incoming.getPremise().isBlank()
                ? existing.getPremise()
                : incoming.getPremise();
        Outline merged = detachedCopy(projectId, premise, incomingVolumes, existingWritten, writtenBoundary);
        return (Outline) canonPatch.replaceOutline(projectId, merged, writtenBoundary).entity();
    }

    private void validateVolumeStructure(String projectId, List<Volume> volumes) {
        Set<Integer> volumeIndices = new HashSet<>();
        int previous = 0;
        for (Volume volume : volumes) {
            if (volume == null) throw new IllegalArgumentException("卷不能为空");
            requireProject(projectId, volume.getProjectId(), "卷");
            if (volume.getVolumeIndex() <= 0) throw new IllegalArgumentException("卷号必须为正数");
            if (!volumeIndices.add(volume.getVolumeIndex())) {
                throw new IllegalArgumentException("卷号必须唯一");
            }
            if (volume.getVolumeIndex() <= previous) {
                throw new IllegalArgumentException("卷号必须严格递增");
            }
            previous = volume.getVolumeIndex();
            if (volume.getSynopses() == null) throw new IllegalArgumentException("章节列表不能为空");
            for (ChapterSynopsis synopsis : volume.getSynopses()) {
                if (synopsis == null) throw new IllegalArgumentException("章节不能为空");
                requireProject(projectId, synopsis.getProjectId(), "章节");
            }
        }
    }

    private void validateChapterIndices(List<Integer> indices) {
        Set<Integer> unique = new HashSet<>();
        int previous = 0;
        for (int index : indices) {
            if (index <= 0) throw new IllegalArgumentException("章号必须为正数");
            if (!unique.add(index)) throw new IllegalArgumentException("章号必须唯一");
            if (index <= previous) throw new IllegalArgumentException("章号必须严格递增");
            previous = index;
        }
    }

    private Map<Integer, LocatedSynopsis> writtenByIndex(Outline outline, int boundary) {
        Map<Integer, LocatedSynopsis> result = new HashMap<>();
        for (Volume volume : outline.getVolumes()) {
            for (ChapterSynopsis synopsis : volume.getSynopses()) {
                if (synopsis.getChapterIndex() > boundary) continue;
                LocatedSynopsis duplicate = result.put(synopsis.getChapterIndex(),
                        new LocatedSynopsis(volume, synopsis));
                if (duplicate != null) throw new IllegalArgumentException("现有大纲章号重复");
            }
        }
        return result;
    }

    private Map<Integer, LocatedSynopsis> locateByIndex(List<Volume> volumes) {
        Map<Integer, LocatedSynopsis> result = new HashMap<>();
        for (Volume volume : volumes) {
            for (ChapterSynopsis synopsis : volume.getSynopses()) {
                result.put(synopsis.getChapterIndex(), new LocatedSynopsis(volume, synopsis));
            }
        }
        return result;
    }

    private void validateWrittenPrefix(Map<Integer, LocatedSynopsis> existingWritten,
                                       Map<Integer, LocatedSynopsis> incomingByIndex,
                                       int boundary) {
        for (Map.Entry<Integer, LocatedSynopsis> entry : existingWritten.entrySet()) {
            LocatedSynopsis before = entry.getValue();
            LocatedSynopsis after = incomingByIndex.get(entry.getKey());
            if (after == null) throw new IllegalArgumentException("已写章节不可删除或改号");
            if (before.volume().getVolumeIndex() != after.volume().getVolumeIndex()
                    || !Objects.equals(before.volume().getTitle(), after.volume().getTitle())
                    || !Objects.equals(before.volume().getSummary(), after.volume().getSummary())
                    || !Objects.equals(before.synopsis().getTitle(), after.synopsis().getTitle())
                    || !Objects.equals(before.synopsis().getSynopsis(), after.synopsis().getSynopsis())) {
                throw new IllegalArgumentException("已写章节及所属卷不可修改");
            }
        }
        for (Map.Entry<Integer, LocatedSynopsis> entry : incomingByIndex.entrySet()) {
            if (entry.getKey() <= boundary && !existingWritten.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("已写范围内不可新增或替换章节");
            }
        }
    }

    private Outline detachedCopy(String projectId, String premise, List<Volume> incomingVolumes,
                                 Map<Integer, LocatedSynopsis> existingWritten, int boundary) {
        Outline result = new Outline(projectId, premise);
        for (Volume sourceVolume : incomingVolumes) {
            Volume trustedWrittenVolume = sourceVolume.getSynopses().stream()
                    .filter(synopsis -> synopsis.getChapterIndex() <= boundary)
                    .map(synopsis -> existingWritten.get(synopsis.getChapterIndex()).volume())
                    .findFirst().orElse(null);
            Volume volume = trustedWrittenVolume != null
                    ? trustedWrittenVolume.copyPreservingIdentity()
                    : new Volume(projectId, sourceVolume.getVolumeIndex(),
                    sourceVolume.getTitle(), sourceVolume.getSummary());
            for (ChapterSynopsis sourceSynopsis : sourceVolume.getSynopses()) {
                ChapterSynopsis source = sourceSynopsis.getChapterIndex() <= boundary
                        ? existingWritten.get(sourceSynopsis.getChapterIndex()).synopsis()
                        : sourceSynopsis;
                volume.addSynopsis(sourceSynopsis.getChapterIndex() <= boundary
                        ? source.copyPreservingIdentity()
                        : copySynopsis(projectId, source));
            }
            result.addVolume(volume);
        }
        return result;
    }

    private ChapterSynopsis copySynopsis(String projectId, ChapterSynopsis source) {
        ChapterSynopsis copy = new ChapterSynopsis(projectId, source.getChapterIndex(),
                source.getTitle(), source.getSynopsis());
        copy.setWritingNotes(source.getWritingNotes());
        copy.setBeats(source.getBeats());
        copy.setClimax(source.getClimax());
        copy.setCast(source.getCast());
        copy.setPlotPurpose(source.getPlotPurpose());
        copy.setTensionLevel(source.getTensionLevel());
        copy.setVolumeArcPosition(source.getVolumeArcPosition());
        copy.setForeshadowingsToPlant(source.getForeshadowingsToPlant());
        copy.setForeshadowingsToResolve(source.getForeshadowingsToResolve());
        return copy;
    }

    private void requireProject(String expected, String actual, String target) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(target + "不属于该项目");
        }
    }

    private record LocatedSynopsis(Volume volume, ChapterSynopsis synopsis) {}
}
