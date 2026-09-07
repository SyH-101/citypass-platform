package com.citypass.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citypass.dto.Result;
import com.citypass.entity.LimitedPassStock;
import com.citypass.entity.ActivityPass;
import com.citypass.mapper.ActivityPassMapper;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.service.ILimitedPassStockService;
import com.citypass.service.IActivityPassService;
import com.citypass.service.IVenueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class ActivityPassServiceImpl extends ServiceImpl<ActivityPassMapper, ActivityPass> implements IActivityPassService {

    @Resource
    private ILimitedPassStockService limitedPassStockService;
    @Resource
    private ReliableTaskRepository reliableTaskRepository;
    @Resource
    private IVenueService venueService;

    @Override
    public Result queryActivityPassOfVenue(Long venueId) {
        // 查询通行证信息
        List<ActivityPass> passes = getBaseMapper().queryActivityPassOfVenue(venueId);
        // 返回结果
        return Result.ok(passes);
    }

    @Override
    @Transactional
    public void addLimitedPassStock(ActivityPass pass) {
        validateLimitedPass(pass);
        pass.setId(null).setType(1).setStatus(1);
        // 保存通行证
        if (!save(pass)) {
            throw new IllegalStateException("通行证保存失败");
        }
        // 保存限量预约信息
        LimitedPassStock limitedPassStock = new LimitedPassStock();
        limitedPassStock.setActivityPassId(pass.getId());
        limitedPassStock.setStock(pass.getStock());
        // 初始库存与当前库存一致：对账库存重算的基准（initial_stock 不可变）
        limitedPassStock.setInitialStock(pass.getStock());
        limitedPassStock.setBeginTime(pass.getBeginTime());
        limitedPassStock.setEndTime(pass.getEndTime());
        if (!limitedPassStockService.save(limitedPassStock)) {
            throw new IllegalStateException("限量通行证库存保存失败");
        }
        // 与 DB 同事务写可靠任务；提交后由任务执行器幂等初始化 Redis，失败会持续重试。
        reliableTaskRepository.enqueue(
                ReliableTaskRepository.INIT_RESERVATION_STOCK,
                "init-reservation-stock:" + pass.getId(),
                JSONUtil.toJsonStr(limitedPassStock));
    }

    @Override
    public void addActivityPass(ActivityPass pass) {
        validateBase(pass);
        pass.setId(null).setType(0).setStatus(1);
        if (!save(pass)) {
            throw new IllegalStateException("通行证保存失败");
        }
    }

    private void validateLimitedPass(ActivityPass pass) {
        validateBase(pass);
        LocalDateTime now = LocalDateTime.now();
        if (pass.getStock() == null || pass.getStock() <= 0) {
            throw new IllegalArgumentException("限量通行证库存必须大于 0");
        }
        if (pass.getBeginTime() == null || pass.getEndTime() == null
                || !pass.getEndTime().isAfter(pass.getBeginTime())
                || !pass.getEndTime().isAfter(now)) {
            throw new IllegalArgumentException("预约时间窗无效");
        }
    }

    private void validateBase(ActivityPass pass) {
        if (pass == null || pass.getVenueId() == null || pass.getTitle() == null
                || pass.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("场馆和通行证标题不能为空");
        }
        if (venueService.getById(pass.getVenueId()) == null) {
            throw new IllegalArgumentException("场馆不存在");
        }
        if (pass.getPayValue() == null || pass.getPayValue() < 0
                || pass.getActualValue() == null || pass.getActualValue() < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
    }
}
