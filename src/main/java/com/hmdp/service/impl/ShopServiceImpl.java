package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    /**
     * 先查询Redis的缓存，如果存在直接返回，如果不存在则查询数据库之后再把数据放入Redis后返回
     */
    public Result queryById(Long id) {
        //DONE 缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, aShop -> getById(id),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //DONE 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        //DONE 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,aShop -> getById(id),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }


    //保存对象和逻辑过期时间
    public void saveShop2Redis(Long id,Long expireSeconds){
        //DONE 1.在数据库中查询Shop
        Shop shop = getById(id);
        //DONE 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //DONE 3.存储到Redis中
        String redisJSON = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,redisJSON);
    }
    //互斥锁解决缓存击穿
    private Shop queryWithMutex(Long id){
        //DONE 1.查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)){
            //DONE 2.查到就直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断是否命中的是空值,上一个if判断null,""," \t\n",所以这里还要判断是否是""
        if (shopJson != null){//不等于null，就是""，则不能去查数据库
            //DONE 2.1 是""返回一个错误信息
            return  null;
        }
        //DONE 3.缓存中没有需要去查数据库,后面的查询需要用到互斥锁
        //DONE 3.1获取互斥锁
        String keyLock = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isGet = tryLock(keyLock);
            //DONE 3.2判断是否获取成功
            if (!isGet){
                //DONE 4.3失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //DONE 4.4 成功，根据id查询数据库
            shop = getById(id);
//            Thread.sleep(10000);
            //DONE 5.不存在，返回错误
            if (shop == null){
                //当查询的数据为空值时，为了解决缓存穿透的问题，将键和空值传入Redis，并设置较短的过期时间
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //DONE 6.存在，加入Redis缓存
            String s = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,s,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //DONE 7.释放互斥锁
            unLock(keyLock);
        }
        return shop;
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


    @Override
    @Transactional
    /**
     * 当数据库更新的时候同时删除缓存
     * 更新策略：先更新数据库再删除缓存
     */
    public Result updateShop(Shop shop) {
        //DONE 1.判断要更新的shop在数据库中是否存在
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //DONE 2.更新数据库
        updateById(shop);
        //DONE 3.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
