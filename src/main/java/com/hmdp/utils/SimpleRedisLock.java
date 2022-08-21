package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PERFIX = UUID.randomUUID().toString(true) + "-";//为了生成全局唯一ID
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String id = ID_PERFIX + Thread.currentThread().getId();
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, String.valueOf(id), timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);//避免拆箱出现空指针
    }

    @Override
    public void unLock() {
        //DONE 利用lua脚本，保证原子性    在脚本中编写多条Redis命令，确保多条命令执行时的原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PERFIX + Thread.currentThread().getId());
    }
//    @Override
//    public void unLock() {
//        String id = ID_PERFIX + Thread.currentThread().getId();
//        //DONE 防止锁误删
//        //取出Redis中对应值，与自己的id比较，如果相等可以释放锁
//        String curId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (curId.equals(id)) {
//            //一致才释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
