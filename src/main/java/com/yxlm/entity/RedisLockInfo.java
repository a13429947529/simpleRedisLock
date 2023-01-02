package com.yxlm.entity;

import lombok.Data;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义锁对象，方便拿参数
 */
@Data
public class RedisLockInfo {

    /**
     * 锁的持有线程
     */
    private Thread lockThread;

    /**
     * 续命次数
     */
    private AtomicInteger lifeCount;


    /**
     * 锁的key
     */
    private String lockKey;

    /**
     * 锁的超时时间
     */
    private Long lockExpire;


    public RedisLockInfo(Thread lockThread, AtomicInteger lifeCount,String lockKey,Long lockExpire) {
        this.lockThread = lockThread;
        this.lifeCount = lifeCount;
        this.lockKey = lockKey;
        this.lockExpire = lockExpire;
    }
}
