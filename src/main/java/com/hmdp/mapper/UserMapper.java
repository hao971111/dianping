package com.hmdp.mapper;

import com.hmdp.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Repository
public interface UserMapper extends BaseMapper<User> {
    @Select("select * from tb_user where phone = #{phone}")
    User selectByPhone(String phone);

    @Select("select * from tb_user where id in #{str} order by field(id,#{str})")
    List<User> listByIdsOrderById(String str);

}
