package com.writing.novel.persistence;

import com.writing.novel.domain.Outline;
import com.writing.novel.domain.PlanChange;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanCommitContractTest {
    @Test void unsupportedAtomicCommitCannotFallBackToPartialWrites() {
        var operation = assertDoesNotThrow(() -> NovelRepository.class.getMethod("commitPlanEdition",
                String.class, String.class, String.class, WritingPreferences.class, Outline.class, List.class, String.class));
        assertEquals(PlanChange.class, operation.getReturnType());
        var unsupported = Proxy.newProxyInstance(NovelRepository.class.getClassLoader(), new Class<?>[]{NovelRepository.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    throw new AssertionError("Atomic commit must not fall back to a partial operation: " + method.getName());
                });
        var failure = assertThrows(InvocationTargetException.class, () -> operation.invoke(unsupported,
                "project", "preview", "source", new WritingPreferences("project"), new Outline("project", "outline"), List.of(), "request"));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
