package cn.yl.common.sms;

import java.util.Optional;

/**
 * 图形验证码答案存储端口（P2-8-A，设计 §4.4）。
 *
 * <p>键：{@code yl:sms:captcha:{captchaId}} → 答案，TTL 120s。<b>GETDEL 一次性</b>消费（无论对错即失效）。
 */
public interface SmsCaptchaStore {

    /**
     * 保存图形验证码答案。
     *
     * @param captchaId 图形验证码标识
     * @param answer 正确答案
     * @param ttlSeconds 有效期（秒）
     */
    void save(String captchaId, String answer, long ttlSeconds);

    /**
     * 原子取出并删除答案（{@code GETDEL}）。
     *
     * @return 答案；键不存在/已消费返回 {@link Optional#empty()}
     */
    Optional<String> consume(String captchaId);
}
