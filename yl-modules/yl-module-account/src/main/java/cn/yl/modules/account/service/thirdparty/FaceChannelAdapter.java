package cn.yl.modules.account.service.thirdparty;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.domain.thirdparty.LoginChannelAdapter;
import cn.yl.modules.account.dto.LoginRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 人脸渠道适配器（FACE，B2 / rpW1xZ）——**未接入占位**。
 *
 * <p>人脸识别涉及生物识别信息，按《个人信息保护法》属敏感个人信息，需单独同意 + 影响评估；M1《敏感个人信息单独同意设计》 尚未覆盖人脸场景，故本渠道**本期不接入**：{@link
 * #configured()} 恒为 false，注册表会明确拒绝， 不做「形同虚设的认证」。
 *
 * <p>接入前置条件（三项齐备才可打开）：① DPO 出具人脸场景单独同意的同意书版本；② 完成 PIA 增补； ③ 选定具备资质的人脸服务商并确定本地化与留存策略。
 */
@Component
public class FaceChannelAdapter implements LoginChannelAdapter {

    @Override
    public Set<LoginChannel> channels() {
        return Set.of(LoginChannel.FACE);
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public String resolveUsername(LoginRequest request) {
        throw new BizException(ErrorCode.LOGIN_FAILED, "人脸渠道未接入：生物识别信息需先完成单独同意与影响评估");
    }
}
