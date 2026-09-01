package com.writing.novel.spi;

/** p22-01: 契约在内存适配器上的实例化 */
class InMemoryStoryCanonContractTest extends StoryCanonContract {
    @Override StoryCanon create() { return new InMemoryStoryCanon(); }
}
