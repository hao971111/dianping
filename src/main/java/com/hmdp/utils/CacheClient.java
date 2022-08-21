package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * @param key：存入键
     * @param value：存入的对象
     * @param time：过期时间TTL
     * @param unit：过期时间单位
     * 存入带有过期时间的对象
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * @param key：存入键
     * @param value：存入的对象
     * @param time：逻辑过期时间
     * @param unit：逻辑过期时间单位
     * 存入逻辑时间过期的对象
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //DONE 将value和time封装成RedisData对象
        RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(unit.toSeconds(time)),value);
        //DONE 将redisData存入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     *
     * @param keyPrefix：键的前缀
     * @param id：查询的具体信息，与keyPrefix组成key
     * @param type：实体类的类型
     * @param dbStrategy：数据库查询策略（查询哪个数据库）
     * @param time：TTL时间
     * @param unit：TTL单位
     * @param <R>：查询的实体类
     * @param <ID>：查询的关键字
     * @return
     * 传入空值方法解决缓存穿透
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbStrategy,Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //DONE 1.查询Redis缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            //DONE 2.查到就直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断是否命中的是空值,上一个if判断null,""," \t\n",所以这里还要判断是否是""
        if (json != null){//不等于null，就是""，则不能去查数据库
            //返回一个错误信息
            return  null;
        }
        //DONE 3.缓存中没有需要去查对应数据库
        R r = dbStrategy.apply(id);
        //DONE 4.不存在，返回错误
        if (r == null){
            //当查询的数据为空值时，为了解决缓存穿透的问题，将键和空值传入Redis，并设置较短的过期时间
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //DONE 5.存在，加入Redis缓存
        String s = JSONUtil.toJsonStr(r);
        set(key,s,time, unit);
        return r;
    }

    /**
     * @param keyPrefix：键的前缀
     * @param id：查询的具体信息，与keyPrefix组成key
     * @param type：实体类的类型
     * @param dbStrategy：数据库查询策略（查询哪个数据库）
     * @param time：逻辑过期时间
     * @param unit：逻辑过期时间单位
     * @param <R>：查询的实体类
     * @param <ID>：查询的关键字
     * @return
     * 逻辑过期解决缓存击穿
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public  <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbStrategy,Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //DONE 1.查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)){
            //DONE 2.没有查到就直接返回
            return null;
        }
        //DONE 3.命中，将shopJson反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //DONE 4.取出Shop对象和expireTime
        JSONObject jsonObject = (JSONObject)redisData.getData();//这里是Object类型，所以要先转成JSONObject，再转成Shop类型
        R r = JSONUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //DONE 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //DONE 6.没过期则直接返回
            return r;
        }
        //DONE 7.过期需要重建缓存
        //DONE 7.1获取互斥锁
        String keyLock = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(keyLock);
        //DONE 7.2得到互斥锁，开启新线程写入数据
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                   //DONE 7.3查询数据库
                    R r1 = dbStrategy.apply(id);
                    //DONE 7.4存入带逻辑过期时间的写
                    setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    unLock(keyLock);
                }
            });
        }
        //DONE 7.3未得到锁，直接返回旧数据
        return r;
    }


    //互斥锁得到锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//防止拆箱的时候出现空指针
    }
    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


}
