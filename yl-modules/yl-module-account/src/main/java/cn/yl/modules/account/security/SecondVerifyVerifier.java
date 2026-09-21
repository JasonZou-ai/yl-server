package cn.yl.modules.account.security;

/**
 * 二次验证渠道校验端口（SCAN 扫码 / PHONE 短信 / FACE 人脸）。
 *
 * <p>MVP 由 {@link ConfigurableSecondVerifyVerifier} 以配置口令实现，便于联调；生产替换为短信/人脸渠道服务，接口不变。
 */
public interface SecondVerifyVerifier {

    /**
     * 校验二次验证输入。
     *
     * @param userId 待验证用户
     * @param method 验证方式：SCAN/PHONE/FACE
     * @param credential 渠道凭据（短信码、扫码结果、人脸票据等）
     * @return 是否通过
     */
    boolean verify(long userId, String method, String credential);
}
