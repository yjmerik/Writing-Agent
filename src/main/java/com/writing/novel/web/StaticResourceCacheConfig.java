package com.writing.novel.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * NCS-177: 静态资源缓存策略——升级换 jar 后浏览器必须拿到新 SPA 壳。
 *
 * <ul>
 *   <li>index.html（SPA 壳）：由 {@link ApiCacheControlFilter} 设 no-store——
 *       壳引用的 bundle 文件名带内容指纹，壳不刷新则新功能不可见</li>
 *   <li>/assets/**：文件名含内容 hash，可 immutable 长缓存（一年）</li>
 * </ul>
 */
@Configuration
public class StaticResourceCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());
    }
}
