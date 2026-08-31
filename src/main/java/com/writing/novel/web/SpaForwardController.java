package com.writing.novel.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * p23-04: SPA 深链接转发——/project/** 等前端路由直接访问时返回 index.html，
 * 由 React Router 接管；否则返回 500/404（书签与分享链接不可用）。
 * API（/api/**）与静态资源（带扩展名）不受影响。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/project/**", "/error"})
    public ModelAndView forwardToIndex() {
        return new ModelAndView("forward:/index.html");
    }
}
