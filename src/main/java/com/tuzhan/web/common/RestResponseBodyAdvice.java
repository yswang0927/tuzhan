package com.tuzhan.web.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统一封装 REST-Controller 返回结果
 */
@RestControllerAdvice
public class RestResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    public RestResponseBodyAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (returnType.hasMethodAnnotation(IgnoreRestBody.class)) {
            return false;
        }
        if (returnType.getContainingClass().isAnnotationPresent(IgnoreRestBody.class)) {
            return false;
        }
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {

        if (returnType.getGenericParameterType().equals(String.class)) {
            try {
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return objectMapper.writeValueAsString(ApiResult.success(body));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        if (body instanceof ApiResult apiResult) {
            if (apiResult.isErrorResult()) {
                final int errorCode = apiResult.getCode();
                if (errorCode >= 100 && errorCode <= 999) {
                    response.setStatusCode(HttpStatusCode.valueOf(errorCode));
                }
            }
            return body;
        }

        return ApiResult.success(body);
    }

}
