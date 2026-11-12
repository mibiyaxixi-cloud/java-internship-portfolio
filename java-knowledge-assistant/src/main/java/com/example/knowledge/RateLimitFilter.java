package com.example.knowledge;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimits limits;private final GuardProperties props;private final ObjectMapper json;
    public RateLimitFilter(RateLimits limits,GuardProperties props,ObjectMapper json){this.limits=limits;this.props=props;this.json=json;}
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String key=null,path=request.getRequestURI().substring(request.getContextPath().length());int maximum=0;
        if("POST".equals(request.getMethod())&&path.equals("/api/login")){key="login:"+request.getRemoteAddr();maximum=props.loginPerMinute();}
        else if(!"GET".equals(request.getMethod())) {
            var auth=SecurityContextHolder.getContext().getAuthentication();
            if(auth!=null&&auth.isAuthenticated()){
                if(path.equals("/api/chats")){key="ask:"+auth.getName();maximum=props.questionsPerMinute();}
                else if(path.startsWith("/api/admin/")){key="import:"+auth.getName();maximum=props.importsPerMinute();}
            }
        }
        if(key!=null){var decision=limits.take(key,maximum);if(!decision.allowed()){
            response.setStatus(429);response.setHeader("Retry-After",String.valueOf(decision.retryAfterSeconds()));response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(),Map.of("code","RATE_LIMITED","message","请求过于频繁，请在 "+decision.retryAfterSeconds()+" 秒后重试","retryAfterSeconds",decision.retryAfterSeconds()));return;
        }}
        chain.doFilter(request,response);
    }
}
