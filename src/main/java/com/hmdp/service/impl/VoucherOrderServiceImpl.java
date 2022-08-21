package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWork;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

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
@Transactional //涉及到两张表的操作要加上事务
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIDWork redisIDWork;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct//在类加载完毕的时候就执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @SneakyThrows
        @Override
        public void run() {
            while (true) {
                try {
                    //DONE 获取消息队列中的信息
                    List<MapRecord<String, Object, Object>> mapRecords = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    //DONE 判断订单是否为空
                    if (mapRecords == null || mapRecords.isEmpty()) {
                        //如果为空，说明没有消息，继续下一次循环
                        continue;
                    }
                    //DONE 解析消息
                    MapRecord<String, Object, Object> record = mapRecords.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    //DONE 创建订单
                    createVoucherOrder(voucherOrder);
                    //DONE 确认消息 xack s1 g1 id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() throws InterruptedException {
            while (true) {
                try {
                    //DONE 获取消息队列中的信息
                    List<MapRecord<String, Object, Object>> mapRecords = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    //DONE 判断订单是否为空
                    if (mapRecords == null || mapRecords.isEmpty()) {
                        //没有异常消息，直接结束
                        break;
                    }
                    //DONE 解析消息
                    MapRecord<String, Object, Object> record = mapRecords.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);
                    //DONE 创建订单
                    createVoucherOrder(voucherOrder);
                    //DONE 确认消息 xack s1 g1 id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    //出现异常之后再进入循环就可以
                    log.error("订单异常", e);
                    Thread.sleep(100);
                }
            }
        }
    }

    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        long orderId = redisIDWork.nextId("order");
        Long userId = user.getId();
        System.out.println(userId);
        //DONE 执行Lua脚本，判断是否可以下单
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        //DONE 判断结果是否为零
        int r = result.intValue();
        if (r != 0) {
            //DONE 不为零，返回错误信息
            return Result.fail(r == 1 ? "库存不足" : "禁止重复下单");
        }
        //DONE 返回订单id
        return Result.ok(orderId);
    }
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024);
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    createVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("订单异常", e);
//                }
//            }
//        }
//    }


//    @PostConstruct//在类加载完毕的时候就执行
//    private void init(){
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }

    //    public Result seckillVoucher(Long voucherId) {
//        UserDTO user = UserHolder.getUser();
//        Long userId = user.getId();
//        System.out.println(userId);
//        //DONE 执行Lua脚本，判断是否可以下单
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//        //DONE 判断结果是否为零
//        int r = result.intValue();
//        if (r != 0){
//            //DONE 不为零，返回错误信息
//            return Result.fail(r == 1 ? "库存不足" : "禁止重复下单");
//        }
//        //DONE 结果为零，有购买资格
//        long orderId = redisIDWork.nextId("order");
//        //DONE 保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        orderTasks.add(voucherOrder);
//        return Result.ok(orderId);
//    }
    @Resource
    private RedissonClient redissonClient;

    void createVoucherOrder(VoucherOrder voucherOrder) {
        //DONE 库存充足，实现一人一单
//        Long userId = 1L; //测试用
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();
        //DONE 1.获取锁
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
//        SimpleRedisLock redisLock = new SimpleRedisLock("order:"+1l,stringRedisTemplate);//测试用
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            //DONE 1.1获取锁失败，直接返回
            log.error("不允许重复下单");
            return;
        }
        //DONE 2获取锁成功
        try {
            //DONE 2.1查询订单
            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                //DONE 2.2下过单直接返回
                log.error("不允许重复下单");
                return;
            }
            //DONE 3. 乐观锁扣除库存（减少库存之前判断是否大于零）
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                //DONE 3.2库存不足返回
                log.error("库存不足");
                return;
            }

            save(voucherOrder);
        } finally {
            redisLock.unlock();
        }

    }

//    @Override
//    /**
//     * 乐观锁购买优惠券
//     */
//    public Result seckillVoucher(Long voucherId) {
//        // DONE 1.提交优惠券id
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //DONE 2.查询优惠券信息
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        Integer stock = voucher.getStock();
//        //DONE 3.判断时间是否合法
//        if (beginTime.isAfter(LocalDateTime.now())) {
//            return Result.fail("抢购尚未开始");
//        }
//        if (LocalDateTime.now().isAfter(endTime)) {
//            return Result.fail("抢购已结束");
//        }
//        //DONE 4.判断库存是否充足
//        if (stock < 1){
//            //DONE 不充足直接返回
//            return Result.fail("存货不足");
//        }
//        //DONE 5.库存充足
//       //DONE 6.创建订单并且返回
//        return createVoucherOrder(voucherId);
//    }
//

//    @Transactional
//    Result createVoucherOrder(Long voucherId){
//        //DONE 库存充足，实现一人一单
////        Long userId = 1L; //测试用
//        Long userId = UserHolder.getUser().getId();
//        //DONE 1.获取锁
//        RLock redisLock = redissonClient.getLock("lock:order:"+userId);
////        SimpleRedisLock redisLock = new SimpleRedisLock("order:"+1l,stringRedisTemplate);//测试用
//        boolean isLock = redisLock.tryLock();
//        if (!isLock){
//            //DONE 1.1获取锁失败，直接返回
//            return Result.fail("不可以重复下单");
//        }
//        //DONE 2获取锁成功
//        try {
//            //DONE 2.1查询订单
//            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            if (count > 0) {
//                //DONE 2.2下过单直接返回
//                return Result.fail("已经下过单");
//            }
//            //DONE 3. 乐观锁扣除库存（减少库存之前判断是否大于零）
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1")
//                    .eq("voucher_id", voucherId).gt("stock", 0)
//                    .update();
//            if (!success) {
//                //DONE 3.2库存不足返回
//                return Result.fail("库存不足");
//            }
//            //DONE 4.生成新的VoucherOrder
//            VoucherOrder voucherOrder = new VoucherOrder();
//            voucherOrder.setId(redisIDWork.nextId(SECKILL_STOCK_KEY));
//            voucherOrder.setUserId(userId);
////            voucherOrder.setUserId(1l);
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//            return Result.ok(voucherOrder.getId());
//        } finally {
//            //DONE 5.释放锁
//            redisLock.unlock();
//        }
//
//    }


//    @Resource
//    private StringRedisTemplate stringRedisTemplate;
//    @Transactional
//    Result createVoucherOrder(Long voucherId){
//        //DONE 库存充足，实现一人一单
////        Long userId = 1L; //测试用
//        Long userId = UserHolder.getUser().getId();
//        //DONE 1.获取锁
//        SimpleRedisLock redisLock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
////        SimpleRedisLock redisLock = new SimpleRedisLock("order:"+1l,stringRedisTemplate);//测试用
//        boolean isLock = redisLock.tryLock(1200);
//        if (!isLock){
//            //DONE 1.1获取锁失败，直接返回
//            return Result.fail("不可以重复下单");
//        }
//        //DONE 2获取锁成功
//        try {
//            //DONE 2.1查询订单
//            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            if (count > 0) {
//                //DONE 2.2下过单直接返回
//                return Result.fail("已经下过单");
//            }
//            //DONE 3. 乐观锁扣除库存（减少库存之前判断是否大于零）
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1")
//                    .eq("voucher_id", voucherId).gt("stock", 0)
//                    .update();
//            if (!success) {
//                //DONE 3.2库存不足返回
//                return Result.fail("库存不足");
//            }
//            //DONE 4.生成新的VoucherOrder
//            VoucherOrder voucherOrder = new VoucherOrder();
//            voucherOrder.setId(redisIDWork.nextId(SECKILL_STOCK_KEY));
//            voucherOrder.setUserId(userId);
////            voucherOrder.setUserId(1l);
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//            return Result.ok(voucherOrder.getId());
//        } finally {
//            //DONE 5.释放锁
//             redisLock.unLock();
//        }
//
//    }


//    @Transactional
//    Result createVoucherOrder(Long voucherId){
//        //DONE 1.库存充足
//        //DONE 2.一人一单
//        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) {
//            //DONE 2.1查询订单
//            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            //DONE 2.2判断是否下过单
//            if (count > 0) {
//                return Result.fail("已经下过单");
//            }
//            //DONE 3. 乐观锁扣除库存（减少库存之前判断是否大于零）
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1")
//                    .eq("voucher_id", voucherId).gt("stock", 0)
//                    .update();
//            if (!success) {
//                //DONE 3.2库存不足返回
//                return Result.fail("库存不足");
//            }
//            //DONE 4.生成新的VoucherOrder
//            VoucherOrder voucherOrder = new VoucherOrder();
//            voucherOrder.setId(redisIDWork.nextId(SECKILL_STOCK_KEY));
//            voucherOrder.setUserId(userId);
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//            return Result.ok(voucherOrder.getId());
//        }
//    }
}
