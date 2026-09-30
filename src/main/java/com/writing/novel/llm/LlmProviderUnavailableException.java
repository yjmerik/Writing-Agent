package com.writing.novel.llm;

/**
 * NCS-156（review 整改）：网关穷尽路由仍无可用 provider 时抛出的类型化异常，
 * 携带 cooldown 瞬态标志（任一 provider 因冷却被跳过或失败进入冷却 = true）。
 * 长任务重投分类改用 instanceof 本异常读标志位，替代对异常消息文本的
 * contains 匹配（措辞一变重投即静默失效——Primitive Obsession 修复）。
 */
public class LlmProviderUnavailableException extends IllegalStateException {

    private final boolean cooldown;

    public LlmProviderUnavailableException(String message, boolean cooldown) {
        super(message);
        this.cooldown = cooldown;
    }

    /** true = 瞬态冷却（延迟后可能恢复），false = 非冷却的配置性不可用 */
    public boolean isCooldown() {
        return cooldown;
    }
}
