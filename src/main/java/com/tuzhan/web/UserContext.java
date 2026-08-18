package com.tuzhan.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class UserContext {

    private UserContext() {}

    /**
     * 获取当前登录用户的 CN（证书中的用户名）
     * @return CN，如果未登录则返回 null
     */
    public static String getCurrentUser() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object cn = session.getAttribute(ClientCertCheckFilter.SESSION_USER_ID);
        return cn != null ? cn.toString() : null;
    }
}