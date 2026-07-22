INSERT INTO tb_event (
    id, title, artist, city, venue, event_time, cover_url, description,
    status, is_hot, deleted, create_time, update_time
) VALUES (
    @bench_event_id, 'Benchmark Event', 'Benchmark Artist', 'Benchmark City',
    'Benchmark Venue', NOW() + INTERVAL 30 DAY, '', 'Synthetic benchmark fixture',
    1, 0, 0, NOW(), NOW()
);

INSERT INTO tb_ticket_sku (
    id, event_id, name, price, stock, sale_start_time, sale_end_time,
    limit_per_user, status, stock_initialized, deleted, create_time, update_time
) VALUES (
    @bench_sku_id, @bench_event_id, 'Benchmark SKU', 10000, @bench_stock,
    NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 1 DAY,
    1, 1, 1, 0, NOW(), NOW()
);
