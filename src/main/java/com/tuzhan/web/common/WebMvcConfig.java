package com.tuzhan.web.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuzhan.util.JsonObjectMapper;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_FORMAT = "yyyy-MM-dd";

    @Value("${spring.time-zone:Asia/Shanghai}")
    private String timeZone = "Asia/Shanghai";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 如果前端页面是 SPA 单页路由应用方式，则可以针对性的配置前端url地址映射
        // 也可以使用 FrontendRoutePageRegistrar 实现
        // 示例：
        /*String[] frontendRoutes = {"/user/**", "..."};
        for ( String route : frontendRoutes) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }*/
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // setOrder(-1) 设置静态资源优先级高于 RequestMapping 的优先级
        // 这样当静态资源请求 /static/** 和 RequestMapping(/**) 冲突时，优先使用静态资源处理
        /*registry.addResourceHandler("/static/**", "/assets/**")
                .addResourceLocations("classpath:/static/", "classpath:/assets/");*/
    }

    /**
     * 自定义增强 ObjectMapper
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return new JsonObjectMapper(this.timeZone);
    }

}
