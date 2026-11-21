package com.hackathon.alerting.common.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link TeamScopeInterceptor} for the REST API. */
@Configuration(proxyBeanMethods = false)
public class TeamScopeConfig implements WebMvcConfigurer {

    private final TeamScopeInterceptor interceptor;

    public TeamScopeConfig(TeamScopeInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
