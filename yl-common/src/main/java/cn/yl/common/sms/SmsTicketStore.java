package cn.yl.common.sms;

import java.util.Optional;

/**
 * 短信核身 ticket 存储端口（P2-8-A，设计 §4.4/§4.5）。
 *
 * <p>键：{@code yl:sms:ticket:{ticket}} → {@code {phoneHash}:{scene}:{purpose}}，TTL 300s。 <b>GETDEL
 * 一次性</b>消费——同一 ticket 二次兑换必失败，天然防重放。
 */
public interface SmsTicketStore {

    /**
     * 保存一次性 ticket。
     *
     * @param ticket ticket 串
     * @param phoneHash 手机号摘要
     * @param scene 端侧场景
     * @param purpose 用途
     * @param ttlSeconds 有效期（秒）
     */
    void save(String ticket, String phoneHash, String scene, SmsPurpose purpose, long ttlSeconds);

    /**
     * 原子取出并删除 ticket（{@code GETDEL}）。
     *
     * @return ticket 载荷；键不存在/已消费返回 {@link Optional#empty()}
     */
    Optional<SmsTicket> consume(String ticket);
}
