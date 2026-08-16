package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Volume;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class OutlineReplanner {

    public void replaceUnwritten(Outline outline, int currentChapter, List<Volume> replacements) {
        if (outline == null) return;
        List<Volume> kept = new ArrayList<>();
        for (Volume volume : new ArrayList<>(outline.getVolumes())) {
            List<ChapterSynopsis> written = volume.getSynopses().stream()
                    .filter(s -> s.getChapterIndex() <= currentChapter)
                    .toList();
            if (written.isEmpty()) {
                continue;
            }
            if (written.size() != volume.getSynopses().size()) {
                volume.getSynopses().clear();
                written.forEach(volume::addSynopsis);
            }
            kept.add(volume);
        }
        outline.getVolumes().clear();
        kept.forEach(outline::addVolume);
        if (replacements != null) {
            replacements.forEach(outline::addVolume);
        }
    }
}
