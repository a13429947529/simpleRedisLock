package com.yxlm.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解形式
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface YxlmLock {

    /**
     * 锁的key
      * @return
     */
    String lockKey();

    /**
     * 锁的超时时间
     * @return
     */
    long lockExpire() default 30;

    /**
     * 锁的等待时间
     * @return
     */
    long waitTime() default 3;

    /**
     * 时间单位
     * @return
     */
    TimeUnit timeUtil() default TimeUnit.SECONDS;
}
