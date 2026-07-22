/**
 * 抢票领域模型：Reservation 生命周期、预占决策、Outbox 记录与幂等释放结果。
 * 这里不依赖 Spring、Redis、Kafka 或 MySQL，便于先理解业务状态，再阅读 Adapter。
 */
package dev.dahuangggg.ticketrush.domain.rush;
