/**
 * Redis Adapters：缓存、抢票线性化点、Reservation Journal 与幂等释放。
 * 业务调用方只依赖 service 包中的 Interface，不直接拼接 Redis key 或解释 Lua 返回码。
 */
package dev.dahuangggg.ticketrush.infrastructure.redis;
