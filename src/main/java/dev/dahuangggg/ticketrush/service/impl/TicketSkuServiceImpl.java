package dev.dahuangggg.ticketrush.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;
import dev.dahuangggg.ticketrush.entity.TicketSku;
import dev.dahuangggg.ticketrush.exception.TicketSkuNotFoundException;
import dev.dahuangggg.ticketrush.mapper.TicketSkuMapper;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TicketSkuServiceImpl implements TicketSkuService {

    private final TicketSkuMapper ticketSkuMapper;

    public TicketSkuServiceImpl(TicketSkuMapper ticketSkuMapper) {
        this.ticketSkuMapper = ticketSkuMapper;
    }

    @Override
    public List<TicketSkuDTO> listByEvent(Long eventId) {
        List<TicketSku> skus = ticketSkuMapper.selectList(
                new LambdaQueryWrapper<TicketSku>()
                        .eq(TicketSku::getEventId, eventId)
                        .orderByAsc(TicketSku::getPrice)
        );
        return skus.stream().map(this::toDTO).toList();
    }

    @Override
    public TicketSkuDTO getSkuDetail(Long skuId) {
        TicketSku sku = ticketSkuMapper.selectById(skuId);
        if (sku == null) {
            throw new TicketSkuNotFoundException(skuId);
        }
        return toDTO(sku);
    }

    private TicketSkuDTO toDTO(TicketSku sku) {
        return new TicketSkuDTO(
                sku.getId(),
                sku.getEventId(),
                sku.getName(),
                sku.getPrice(),
                sku.getStock(),
                sku.getSaleStartTime(),
                sku.getSaleEndTime(),
                sku.getLimitPerUser(),
                sku.getStatus()
        );
    }
}
