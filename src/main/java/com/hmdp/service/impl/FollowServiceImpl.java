package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followId, Boolean isFollow) {
        //DONE 获得当前用户id
        Long id = UserHolder.getUser().getId();
        String key = "follow:"+ id;
        if (isFollow){
            //DONE 当前用户关注followId
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followId);
            boolean save = save(follow);
            if (save){
                stringRedisTemplate.opsForSet().add(key,followId.toString());
            }
            return Result.ok("已关注");
        }
        else {
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", id).eq("follow_user_id", followId));
            if (remove){
                stringRedisTemplate.opsForSet().remove(key,followId.toString());
            }
            return Result.ok("取消关注");
        }

    }

    @Override
    public Result isFollow(Long followUserId) {
        //DONE 获取用户
        Long id = UserHolder.getUser().getId();
        //DONE  查询是否关注
        Integer count = query().eq("user_id", id).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result commonFollow(Long followUserId) {
        //DONE 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //DONE 获得userId和followUserId的交集
        String key1 = "follow:" + userId;
        String key2 = "follow:" + followUserId;
        Set<String> commonList = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (commonList == null || commonList.isEmpty()){
            return Result.ok();
        }
        List<Long> list = commonList.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOs = userService.listByIds(list).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOs);
    }
}
