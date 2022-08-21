package com.hmdp.config;

import io.lettuce.core.RedisClient;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient  redissonClient(){
        //DONE 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.56.103:6379").setPassword("yanghao");

        //DONE 创建RedissonClient对象
        return Redisson.create(config);
    }
}
