package com.writing.novel.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * p32-14（UI 验证发现）：/api/** 响应统一禁止浏览器缓存。
 * 无 Cache-Control 时浏览器启发式缓存会让普通刷新读到旧章节版本/旧字数，
 * 作者保存后误以为未生效。静态资源（SPA 壳与打包产物带指纹）不受影响。
 */
@Component
public class ApiCacheControlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        filterChain.doFilter(request, response);
    }
}
