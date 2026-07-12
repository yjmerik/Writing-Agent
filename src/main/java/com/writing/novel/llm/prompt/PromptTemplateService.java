package com.writing.novel.llm.prompt;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板服务。启动时加载 classpath:prompts/*.st 到内存。
 * 支持 {{var}} 变量插值和 {{#if var}}...{{/if}} 条件块。
 * Agent 调 render("chapter-writing", Map.of(...)) 取代硬编码 prompt。
 */
@Service
public class PromptTemplateService {

    private static final String PROMPTS_DIR = "classpath:prompts/*.st";

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");
    private static final Pattern IF_PATTERN = Pattern.compile("\\{\\{#if\\s+(\\w+)\\}\\}(.*?)\\{\\{/if\\}\\}", Pattern.DOTALL);

    private final Map<String, String> templates = new HashMap<>();

    @PostConstruct
    public void loadTemplates() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(PROMPTS_DIR);
        for (Resource r : resources) {
            String filename = r.getFilename();
            if (filename == null) continue;
            String name = filename.replace(".st", "");
            String content = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            templates.put(name, content);
        }
    }

    /**
     * 渲染模板：替换 {{var}} 和 {{#if var}}...{{/if}}。
     * @param templateName 模板名（不含 .st 后缀）
     * @param variables 变量映射，key=变量名，value=替换值（null/空串视为未设置）
     */
    public String render(String templateName, Map<String, ?> variables) {
        String template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Prompt 模板不存在: " + templateName
                    + "，可用: " + templates.keySet());
        }
        // 先处理条件块
        String result = processConditionals(template, variables);
        // 再处理变量插值
        result = processVariables(result, variables);
        return result.trim();
    }

    /** 直接获取模板原文（不渲染） */
    public String getRawTemplate(String templateName) {
        return templates.get(templateName);
    }

    private String processConditionals(String template, Map<String, ?> variables) {
        Matcher m = IF_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String blockContent = m.group(2);
            Object value = variables.get(varName);
            boolean present = value != null && !value.toString().isBlank();
            m.appendReplacement(sb, Matcher.quoteReplacement(present ? blockContent : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String processVariables(String template, Map<String, ?> variables) {
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            Object value = variables.get(varName);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value.toString() : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
