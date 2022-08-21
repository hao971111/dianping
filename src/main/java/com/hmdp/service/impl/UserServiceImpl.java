package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private UserMapper userMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private QueryWrapper<User> queryWrapper;

    @Override
    /**
     * 向指定的手机号（phone）发送随机生成的验证码。
     * 1.校验手机号是否合法
     * 2.合法的话产生随机六位的数字
     * 3.验证码存入session
     */
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("非法手机号");
        }
        //2.合法的话产生随机六位的数字，并发送
        String code = RandomUtil.randomNumbers(6);
//        //3.验证码存入session
//        session.setAttribute("code",s);
        //3.将验证码存到Redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);
        log.debug("已发送验证码:{}",code);

        return Result.ok();
    }

    @Override
    /**
     *根据手机号和验证码判断用户是否存在，存在则直接登录，不存在则创建用户并登录
     *
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("非法手机号");
        }
        //2.校验验证码,从Redis中获取验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        if (!loginForm.getCode().equals(code)){
            return Result.fail("验证码错误");
        }
        //3.根据手机号码查询用户
        User user = userMapper.selectByPhone(phone);//查询单个User
        System.out.println(user);
        //3.1如果为user为空，则创建新用户,保存到数据库，并且返回
        if (user == null){
           user = createUserWithPhone(phone);
        }
        //4.将用户加入Redis               这里用BeanUtil自动把User转成UserDTO
        //4.1随机生成token
        String token = UUID.randomUUID().toString(true);
        //4.2将User转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        HashMap<String, Object> map = beanToMap(userDTO);
        //4.3存储
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,map);
        //4.4设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
        //在拦截器中更新token的有效期
    }

    @Override
    public List<User> listByIdsOrderById(List<Long> list) {
        //转换成字符串
        String strList = StrUtil.join(",",list);
        List<User> users = query().in("id", list).last("order by field (id," + strList + ")").list();
        return users;
    }

    /**
     * 因为userDTO的id是Long类型的，而转换后的Map的key只支持String类型，所以要特殊转换
     * @param userDTO
     */
    private HashMap<String,Object> beanToMap(UserDTO userDTO) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("id",String.valueOf(userDTO.getId()));
        map.put("nickName",userDTO.getNickName());
        map.put("icon",userDTO.getIcon());
        return map;
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
//        save(user) 这种方式事务没有自动提交
         userMapper.insert(user);//保存到数据库
        return user;
    }
}
