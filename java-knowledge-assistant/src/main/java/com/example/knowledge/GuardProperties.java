package com.example.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("guard")
public record GuardProperties(int loginPerMinute,int questionsPerMinute,int importsPerMinute,
        int maxConcurrentQuestions,int maxConcurrentImports,int qdrantTimeoutSeconds,int aiDeadlineSeconds) {
    public GuardProperties {
        if(loginPerMinute<1||loginPerMinute>1000||questionsPerMinute<1||questionsPerMinute>1000||importsPerMinute<1||importsPerMinute>1000||
            maxConcurrentQuestions<1||maxConcurrentQuestions>16||maxConcurrentImports<1||maxConcurrentImports>4||qdrantTimeoutSeconds<1||qdrantTimeoutSeconds>60||aiDeadlineSeconds<1||aiDeadlineSeconds>180)
            throw new IllegalArgumentException("guard限流/超时配置无效");
    }
}
