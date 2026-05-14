package dev.dahuangggg.ticketrush.service.impl;

import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.StockInitService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class StockInitServiceImpl implements StockInitService {

    static final String STOCK_KEY = "ticket:stock:";

    private final StringRedisTemplate redisTemplate;
    private final TicketSkuMapper ticketSkuMapper;

    public StockInitServiceImpl(StringRedisTemplate redisTemplate,
                                TicketSkuMapper ticketSkuMapper) {
        this.redisTemplate = redisTemplate;
        this.ticketSkuMapper = ticketSkuMapper;
    }

    @Override
    public boolean initStock(Long skuId) {
        TicketSku sku = ticketSkuMapper.selectById(skuId);
        if (sku == null) {
            throw new TicketSkuNotFoundException(skuId);
        }
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(STOCK_KEY + skuId, String.valueOf(sku.getStock()));
        return Boolean.TRUE.equals(set);
    }

    @Override
    public Integer getAvailableStock(Long skuId) {
        String value = redisTemplate.opsForValue().get(STOCK_KEY + skuId);
        if (value == null) {
            return null;
        }
        return Integer.parseInt(value);
    }
}
