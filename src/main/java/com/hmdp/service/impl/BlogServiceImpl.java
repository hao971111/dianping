package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    /**
     * 跟据Blog的id查询对应blog，以及blog关联的用户信息
     */
    public Result searchBlog(Long id) {
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("博客不存在");
        }
        queryBlogUser(blog);
        //DONE 查询是否被该用户点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //DONE 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //DONE 为空说明没有用户登录，直接返回
            return;
        }
        Long userId = user.getId();
        //DONE 判断当前用户是否点过赞
        String key = BLOG_LIKED_KEY +blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.query().eq("id", blog.getUserId()).one();
        blog.setUserId(user.getId());
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }

    @Override
    public Result searchHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 完成点赞功能，一个用户只能点一次赞，再点就是取消赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //DONE 获取登录用户
        Long userId = UserHolder.getUser().getId();
        //DONE 判断当前用户是否点过赞
        String key = BLOG_LIKED_KEY +id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null){
            //DONE 增加赞
            boolean isUpdate = update().setSql("liked = liked + 1").eq("id", id).update();
            //DONE 加入Redis
            if (isUpdate) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }
        else {
            //DONE 减少赞
            boolean isUpdate = update().setSql("liked = liked - 1").eq("id", id).update();
            //DONE 移除Redis
            if (isUpdate) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    /**
     * 返回点赞列表
     */
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY +id;
        //DONE 查询前5个点赞的用户
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //DONE 如果range为空则返回
        if (range == null || range.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //DONE 解析出用户id
        List<Long> list = range.stream().map(Long::valueOf).collect(Collectors.toList());
        //DONE  根据id查询用户
        List<UserDTO> users = new LinkedList<>();
        for (User user :userService.listByIdsOrderById(list)) {
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            users.add(userDTO);
        }
        //DONE
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        if(blog.getTitle() == null){
            return Result.fail("请加入标题");
        }
        if(blog.getShopId() == null){
            return Result.fail("请加入关联店铺");
        }
        // 保存探店博文
        boolean save = save(blog);
        if (!save){
            return Result.fail("博文保存失败");
        }
        //DONE 保存成功
        //DONE 推送到粉丝
        //DONE  1.查询所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id",blog.getUserId()).list();
        //DONE  2. 推送到每一个粉丝
        for (Follow fan : follows) {
            String key = "feed:"+fan.getUserId();
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //DONE 获得当前用户
        Long userId = UserHolder.getUser().getId();
        //DONE 查询被推送的Blog
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()){
            //DONE 查询到的结果为空直接返回
            return Result.ok();
        }
        //DONE 解析数据：blogId minTime（时间戳） offset
        List<Long> users = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取id
            users.add(Long.valueOf(typedTuple.getValue()));
            // 获取分数（时间戳）
            long time = typedTuple.getScore().longValue();
            if (time == minTime){
                os++;
            }
            else {
                minTime = time;
                os = 1;
            }
        }
        //DONE 查询对应的用户，并且排序
        List<Blog> blogs = listByIdsOrderById(users);
        for (Blog blog : blogs) {
            //添加关联用户
            queryBlogUser(blog);
            //添加当前点赞情况
            isBlogLiked(blog);
        }
        //DONE 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private List<Blog> listByIdsOrderById(List<Long> users) {
        String strList = StrUtil.join(",",users);
        List<Blog> blogs = query().in("id", users).last("order by field (id," + strList + ")").list();
        return blogs;
    }
}
