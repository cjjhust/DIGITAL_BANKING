package com.jinjing.banking.common.annotation;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class TheBestOneAspect {

    /**
     * 切入点：只要方法上标注了 @TheBestOne
     */
    @Before("@annotation(theBestOne)")
    public void beforeMethod(JoinPoint joinPoint, TheBestOne theBestOne) {
        String methodName = joinPoint.getSignature().getName();
        String annotationValue = theBestOne.value();
        
        log.info(">>> [TheBestOne 拦截] 正在执行核心方法: {}", methodName);
        log.info(">>> 注解参数值: {}", annotationValue);
    }
}
