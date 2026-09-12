package com.citypass.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citypass.dto.Result;
import com.citypass.dto.ActivitySearchMetadataRequest;
import com.citypass.entity.LimitedPassStock;
import com.citypass.entity.ActivityPass;
import com.citypass.mapper.ActivityPassMapper;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.service.ILimitedPassStockService;
import com.citypass.service.IActivityPassService;
import com.citypass.service.IVenueService;
import com.citypass.search.ActivitySearchOutboxService;
import com.citypass.search.ActivitySearchSourceRepository;
import com.citypass.search.SearchRebuildStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

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
    @Resource
    private ActivitySearchOutboxService activitySearchOutboxService;
    @Resource
    private ActivitySearchSourceRepository activitySearchSourceRepository;
    @Resource
    private SearchRebuildStateRepository searchRebuildStateRepository;

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
        searchRebuildStateRepository.assertWritesAllowed();
        validateLimitedPass(pass);
        pass.setId(null).setType(1).setStatus(1).setSearchVersion(1L);
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
        activitySearchOutboxService.enqueue(pass.getId(), pass.getSearchVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addActivityPass(ActivityPass pass) {
        searchRebuildStateRepository.assertWritesAllowed();
        validateBase(pass);
        pass.setId(null).setType(0).setStatus(1).setSearchVersion(1L);
        if (!save(pass)) {
            throw new IllegalStateException("通行证保存失败");
        }
        activitySearchOutboxService.enqueue(pass.getId(), pass.getSearchVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateSearchMetadata(Long id, ActivitySearchMetadataRequest request) {
        if (id == null) throw new IllegalArgumentException("活动 ID 不能为空");
        validateSearchMetadata(request, true);
        searchRebuildStateRepository.assertWritesAllowed();
        if (activitySearchSourceRepository.updateMetadata(id, request) != 1) {
            return Result.fail("活动不存在");
        }
        Long version = activitySearchSourceRepository.findVersion(id);
        activitySearchOutboxService.enqueue(id, version);
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateStatus(Long id, Integer status) {
        if (id == null || status == null || status != 1 && status != 2) {
            throw new IllegalArgumentException("活动状态只支持 1（上架）或 2（下架）");
        }
        searchRebuildStateRepository.assertWritesAllowed();
        int changed = activitySearchSourceRepository.updateStatus(id, status);
        Long version = activitySearchSourceRepository.findVersion(id);
        if (version == null) return Result.fail("活动不存在");
        if (changed == 1) activitySearchOutboxService.enqueue(id, version);
        return Result.ok();
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
        boolean anySearchMetadata = pass.getEventStartTime() != null || pass.getEventEndTime() != null
                || notBlank(pass.getActivityCategory()) || notBlank(pass.getDescription()) || notBlank(pass.getTags());
        if (anySearchMetadata) validateSearchMetadata(toSearchRequest(pass), true);
        if (notBlank(pass.getActivityCategory())) {
            pass.setActivityCategory(pass.getActivityCategory().trim().toUpperCase(Locale.ROOT));
        }
    }

    private ActivitySearchMetadataRequest toSearchRequest(ActivityPass pass) {
        ActivitySearchMetadataRequest request = new ActivitySearchMetadataRequest();
        request.setTitle(pass.getTitle());
        request.setSubTitle(pass.getSubTitle());
        request.setDescription(pass.getDescription());
        request.setActivityCategory(pass.getActivityCategory());
        request.setTags(pass.getTags());
        request.setEventStartTime(pass.getEventStartTime());
        request.setEventEndTime(pass.getEventEndTime());
        return request;
    }

    private void validateSearchMetadata(ActivitySearchMetadataRequest request, boolean requireComplete) {
        if (request == null || !notBlank(request.getTitle()) || request.getTitle().trim().length() > 255) {
            throw new IllegalArgumentException("活动标题不能为空且不能超过 255 个字符");
        }
        if (!notBlank(request.getActivityCategory()) || request.getActivityCategory().trim().length() > 32) {
            throw new IllegalArgumentException("活动分类不能为空且不能超过 32 个字符");
        }
        if (request.getDescription() != null && request.getDescription().length() > 2000
                || request.getTags() != null && request.getTags().length() > 255
                || request.getSubTitle() != null && request.getSubTitle().length() > 255) {
            throw new IllegalArgumentException("活动搜索文本字段超过长度限制");
        }
        if (request.getEventStartTime() == null || request.getEventEndTime() == null
                || !request.getEventEndTime().isAfter(request.getEventStartTime())) {
            throw new IllegalArgumentException("活动实际举办时间无效");
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
