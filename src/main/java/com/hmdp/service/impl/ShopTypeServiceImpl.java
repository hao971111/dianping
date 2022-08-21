package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    /**
     * 思路同 ShopServiceImpl.queryById()
     */
    public Result queryTypeList() {
        String shopTypeListJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_LIST_KEY);
        if (!StrUtil.isBlank(shopTypeListJSON)){
            List<ShopType> list = JSONUtil.toList(shopTypeListJSON,ShopType.class);
            return Result.ok(list);
        }
        List<ShopType> typeList = query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_LIST_KEY,JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
