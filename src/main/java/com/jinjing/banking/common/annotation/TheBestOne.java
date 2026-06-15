package com.jinjing.banking.common.annotation;

import org.springframework.stereotype.Component;
import java.lang.annotation.*;

/**
 * 自定义注解：TheBestOne
 * @Target: 表示该注解可以用在类(TYPE)或方法(METHOD)上
 * @Retention: 运行时保留，这样反射才能拿得到
 * @Component: 核心点！让 Spring 能够扫描到它，并把标注的类注册为 Bean
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface TheBestOne {
    String value() default "Default Best One";
}
