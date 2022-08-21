package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIDWork {
    private static final int COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 通过32位的时间戳+Redis自增长的后缀拼接成一个新的64位的id
     * @param keyPrefix
     * @return
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;//记录初始时间
    public long nextId(String keyPrefix){
        //DONE 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();//记录当前的时间
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);//得到当前时间的秒数
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;//当前时间的秒数减去初始时间，得到时间戳
        //DONE 2.获得自增长键
        //DONE 2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + date);//最后利用年：月：日的形式将每一天的自增长键放到一起
        //DONE 3.将时间戳和自增长键的值拼接到一起
        return timeStamp << COUNT_BITS | count;
    }

    /**
     * 生成指定日期的时间戳
     * @param args
     */
    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);//将LocalDateTime转换成自1970-01-01T00：00：00Z以来的秒数。
        System.out.println(second);
    }
}
