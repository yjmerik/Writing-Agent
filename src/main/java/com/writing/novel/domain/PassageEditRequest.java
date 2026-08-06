package com.writing.novel.domain;

/**
 * p11-G1: 段落编辑请求 DTO。
 * 支持三种模式：REWRITE（重写）/ EXPAND（扩充）/ INSERT（在其后插入新段落）。
 */
public class PassageEditRequest {
    private int paragraphIndex;     // 段落号（0-based，按 \n 分割）
    private String selectedText;    // 选中段落文本（用于定位验证）
    private String instruction;     // 编辑指令
    private String mode;            // REWRITE / EXPAND / INSERT

    public int getParagraphIndex() { return paragraphIndex; }
    public void setParagraphIndex(int p) { this.paragraphIndex = p; }
    public String getSelectedText() { return selectedText; }
    public void setSelectedText(String s) { this.selectedText = s; }
    public String getInstruction() { return instruction; }
    public void setInstruction(String i) { this.instruction = i; }
    public String getMode() { return mode; }
    public void setMode(String m) { this.mode = m; }
}
