package com.writing.novel.util;

/** NCS-042: 简单三元组（整组事务的章节/阶段集/来源版本载体） */
public record Triple<A, B, C>(A first, B second, C third) {
    public static <A, B, C> Triple<A, B, C> of(A a, B b, C c) {
        return new Triple<>(a, b, c);
    }
}
