package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.mongo.UserLog;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIDWork;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
@RunWith(SpringRunner.class)
class HmDianPingApplicationTests {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIDWork redisIDWork;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Test
    void testString() {
        // 写入一条String数据
        stringRedisTemplate.opsForValue().set("verify:phone:13600527634", "124143");
        // 获取string数据
        Object name = stringRedisTemplate.opsForValue().get("name");
        System.out.println("name = " + name);
    }

    @Test
    void testSaveShop(){
        shopService.saveShop2Redis(1l,10l);
    }

    @Test
    void testRedisIdWord() throws InterruptedException {
        ExecutorService service = Executors.newFixedThreadPool(500);
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                long l = redisIDWork.nextId("order:");
                System.out.println(l);
                countDownLatch.countDown();
            }
        };
        for (int i = 0; i < 500; i++) {
            service.submit(task);
        }
        countDownLatch.await();
    }
    @Test
    public void deleteVoucherOrder(){
        QueryWrapper<VoucherOrder> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id",1l);
        int delete = voucherOrderMapper.delete(queryWrapper);
        System.out.println(delete);
    }
    @Test
    public void getTime(){
        System.out.println(System.currentTimeMillis());
    }


    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    public void testSave(){
        UserLog userLog = new UserLog();
        userLog.setNewer(true);
        userLog.setSex(1);
        userLog.setUserId(1000L);
        userLog.setLastLoginTime(System.currentTimeMillis());
        userLog.setRegisterTime(System.currentTimeMillis());
        mongoTemplate.save(userLog);

        Query query = Query.query(Criteria.where("userId").is(1000));
        query.limit(1);
        UserLog log = mongoTemplate.findOne(query, UserLog.class);
        System.out.println(log);
    }

}
