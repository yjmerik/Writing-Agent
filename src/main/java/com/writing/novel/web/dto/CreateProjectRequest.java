package com.writing.novel.web.dto;

public record CreateProjectRequest(String title, String concept, String tags, String mode, String preset,
                                   Integer targetChapters) {
    public CreateProjectRequest(String title, String concept, String tags, String mode, String preset) {
        this(title, concept, tags, mode, preset, null);
    }
}
