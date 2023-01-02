package com.yxlm.aspect;

import com.yxlm.annotation.YxlmLock;
import com.yxlm.entity.RedisLockInfo;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Aspect
@Component
@Slf4j
public class YxlmLockAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用于重入锁判断，这里需注意内存溢出问题
     */
    private Map<Thread, RedisLockInfo> localCacheLock = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();



    public YxlmLockAspect(){
        //开启额外线程检测续约机制
        this.scheduledExecutorService.scheduleAtFixedRate(new LifeExtensionThread(), 0, 5, TimeUnit.SECONDS);
    }



    @Pointcut(value = "@annotation(com.yxlm.annotation.YxlmLock)")
    public void logPointCut() {

    }


    /**
     * 前置通知
     * @param jp
     */
    @Before(value = "logPointCut()")
    public void beforeAspect(JoinPoint jp) throws Exception {
        YxlmLock annotation = getAnnotation(jp);
        //获取锁
        if (annotation != null){
            boolean tryLock = tryLock(annotation.lockKey(), annotation.timeUtil(), annotation.lockExpire(), annotation.waitTime());
            if (!tryLock){
                throw new Exception("获取锁失败");
            }
        }
    }

    /**
     * 后置通知
     * @param jp
     * @throws Exception
     */
    @After(value = "logPointCut()")
    public void afterAspect(JoinPoint jp) throws Exception{
        YxlmLock annotation = getAnnotation(jp);
        if (annotation != null){
            unLock(annotation.lockKey());
        }
    }



    /**
     * 解锁key，记得一定要移除本地缓存，不然会造成内存溢出
     * @param lockKey
     */
    private void unLock(String lockKey){
        stringRedisTemplate.delete(lockKey);
        RedisLockInfo redisLockInfo = getTheadByLockKey(lockKey);
        if (redisLockInfo != null && localCacheLock.containsKey(redisLockInfo.getLockThread())){
            //主动释放内存，避免内存溢出
            localCacheLock.remove(redisLockInfo.getLockThread());
            log.info(">>>>>>>>>>>>>>>释放锁成功,key为：{}",lockKey);
        }
    }


    /**
     * 通过lockKey获取Map对应的对象
     * @param lockKey
     * @return
     */
    private RedisLockInfo getTheadByLockKey(String lockKey){
        List<RedisLockInfo> result = localCacheLock.values().stream().filter(s -> s != null && lockKey.equals(s.getLockKey())).collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(result)){
            return result.get(0);
        }
        return null;
    }



    /**
     * 获取锁
     * 1.可重入锁要先判断，如果是同一把锁直接返回
     * 2.获取锁成功是要缓存到本地，方便后续做续命时做处理
     * 3.没有获取到锁时进行短暂自旋，注意CPU飙高问题
     * @param lockKey 锁的key
     * @param timeUnit 时间单位
     * @param lockExpire  超时时间
     * @param waitTime  锁的等待时间
     * @return
     */
    private boolean tryLock(String lockKey, TimeUnit timeUnit, Long lockExpire, Long waitTime){
        //重入锁判断
        Thread currentThread = Thread.currentThread();
        if (localCacheLock.get(currentThread) != null){
            return true;
        }
        //短暂自旋,注意CPU标高问题
        Long startTime = System.currentTimeMillis();
        for (;;) {
            Boolean check = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "2", lockExpire, timeUnit);
            if (check){
                log.info(">>>>>>>>>>>获取锁成功,key为:{}",lockKey);
                localCacheLock.put(currentThread,new RedisLockInfo(currentThread,new AtomicInteger(0),lockKey,lockExpire));
                return true;
            }
            //下面开始自旋
            Long endTime = System.currentTimeMillis();
            if (endTime - startTime > waitTime){
                log.info(">>>>>>>>>>>重试次数大于3次，key为:{}",lockKey);
                return false;
            }
            try {
                // 此方法处理CPU飙高问题
                Thread.sleep(10);
            } catch (Exception e) {

            }
        }
    }

    /**
     * 获取注解
     * @param jp
     * @return
     * @throws Exception
     */
    private YxlmLock getAnnotation(JoinPoint jp) throws Exception{
        MethodSignature signature = (MethodSignature) jp.getSignature();
        Class[] parameterTypes = signature.getParameterTypes();
        String  name = signature.getName();
        Object target = jp.getTarget();
        //用target.getClass()获得的是一个类对象的实例，不同于Class.forName();因此可以获取指定的注解信息
        Method method = target.getClass().getMethod(name, parameterTypes);
        YxlmLock annotation = method.getAnnotation(YxlmLock.class);
        if (annotation != null){
            return annotation;
        }
        return null;
    }

    /**
     * 当我们的获取锁的jvm业务执行时间>过期key的超时时间 应该实现续命：
     * 当key过期的时候：走事件回调到客户端。---时间在延长
     * 延长过期key 应该是提前的。通过定时任务提前延长过期key
     * 算法实现：
     * 开启一个定时任务，每隔一段时间检测获取到锁的线程，延长该过期key的时间
     */
    class LifeExtensionThread implements Runnable {

        @Override
        public void run() {
            localCacheLock.forEach((k, lockInfo) -> {
                try {
                    // 需要控制续命多次，如果获取到锁的jvm 续命多次还是没有将业务逻辑执行完毕的情况下处理： 主动释放锁 事务会回滚
                    int count =  lockInfo.getLifeCount().intValue();
                    String lockKey = lockInfo.getLockKey();
                    Thread currentThread = lockInfo.getLockThread();
                    if (count > 3) {
                        log.info(">>>>>>续约检测释放锁，key为:{}",lockKey);
                        // 1.事务回滚
                        // 2.释放锁 dele
                        unLock(lockKey);
                        // 3.将该线程主动停止
                        lockInfo.getLockThread().interrupt();
                        return;
                    }
                    count++;
                    //提前实现续命 延长过期key的时间
                    stringRedisTemplate.expire(lockKey, lockInfo.getLockExpire(), TimeUnit.SECONDS);
                    localCacheLock.put(currentThread,new RedisLockInfo(currentThread,new AtomicInteger(count),lockKey,lockInfo.getLockExpire()));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

}
