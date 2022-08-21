package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);

    @Insert("insert into tb_voucher ( shop_id, title, sub_title, rules, pay_value, actual_value, type) values " +
            "( #{shopId}, #{title}, #{subTitle}, #{rules}, #{payValue},#{actualValue},#{type} )")
    @Options(useGeneratedKeys = true,keyProperty = "id")
    int insert(Voucher voucher);
}
