package com.tuzhan.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // 尽量靠前执行
public class ClientCertCheckFilter extends OncePerRequestFilter {

    /** Session 中存放当前用户 CN 的 key */
    public static final String SESSION_USER_ID = "CURRENT_USER_ID";

    /** Spring Boot 3 的证书属性名 */
    private static final String CERT_ATTR = "jakarta.servlet.request.X509Certificate";

    /** 匹配 CN 的正则 */
    private static final Pattern CN_PATTERN = Pattern.compile("CN=([^,]+)", Pattern.CASE_INSENSITIVE);

    // 需要放行的资源请求
    private static final String[] IGNORE_PATHS = {
            "/assets/",
            "/static/",
            "/images/",
            "/favicon.ico",
            "/common/"
    };

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${server.ssl.client-auth:NONE}")
    private String sslClientAuthMode;

    private static boolean isPublicPath(String path) {
        for (String ignored : IGNORE_PATHS) {
            if (path.startsWith(ignored)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 1. 放行提示页和静态资源（按需调整）
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 已登录过了
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_USER_ID) != null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!sslEnabled || "NONE".equals(sslClientAuthMode)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 获取客户端证书
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute(CERT_ATTR);
        if (certs == null || certs.length == 0) {
            // 没有证书 → 跳转提示页
            response.sendRedirect("/common/cert-required");
            return;
        }

        // 3. 提取 CN
        String cn = extractCn(certs[0]);
        if (cn == null || cn.isBlank()) {
            response.sendRedirect("/common/cert-required");
            return;
        }

        // 4. 存入 Session
        session = request.getSession(true);
        session.setAttribute(SESSION_USER_ID, cn.trim());
        System.out.println(">>>当前登录用户：" + cn);

        // 5. 继续后续处理
        filterChain.doFilter(request, response);
    }

    /**
     * 从证书 SubjectDN 中提取 CN
     */
    private String extractCn(X509Certificate cert) {
        String subjectDN = cert.getSubjectX500Principal().getName();
        Matcher matcher = CN_PATTERN.matcher(subjectDN);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}