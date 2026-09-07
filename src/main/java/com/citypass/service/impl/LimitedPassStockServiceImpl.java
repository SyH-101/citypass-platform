package com.citypass.service.impl;

import com.citypass.entity.LimitedPassStock;
import com.citypass.mapper.LimitedPassStockMapper;
import com.citypass.service.ILimitedPassStockService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 限量预约通行证表，与通行证是一对一关系 服务实现类
 * </p>
 *
 * @since 2022-01-04
 */
@Service
public class LimitedPassStockServiceImpl extends ServiceImpl<LimitedPassStockMapper, LimitedPassStock> implements ILimitedPassStockService {

}
