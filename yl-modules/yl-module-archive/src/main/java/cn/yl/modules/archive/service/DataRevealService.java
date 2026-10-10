package cn.yl.modules.archive.service;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.domain.entity.Elder;
import cn.yl.modules.archive.dto.DataRevealRequest;
import cn.yl.modules.archive.dto.DataRevealResponse;
import cn.yl.modules.archive.mapper.ElderMapper;
import cn.yl.modules.archive.support.SensitiveFieldSupport;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 敏感字段明文查看服务（{@code POST /api/v1/security/reveal}）。
 *
 * <p><b>安全边界</b>：
 *
 * <ul>
 *   <li><b>第四闸</b>：{@code data:reveal} 属敏感权限点，{@code PermissionAspect} 自动校验一次性 二次验证凭据（{@code
 *       X-Second-Verify-Token}）；本服务不重复实现二次验证。
 *   <li><b>第三闸</b>：明文查询<b>经 {@link ElderMapper}</b> 发起，故数据域过滤自动生效——越界行查不到， <b>不得绕过 Mapper 直读</b>。
 *   <li><b>字段白名单</b>：仅允许 {@code name/idCardNo/phone/address}，其余字段名一律拒绝（10001）， 避免把未登记的密文列当作可导出面。
 *   <li><b>越界与不存在同码</b>：查不到统一 {@link ErrorCode#NOT_FOUND}（10004），不泄露存在性。
 * </ul>
 *
 * <p>{@code bizType} 本期仅实现 {@code ELDER}；{@code USER} 属账号域，收到即拒绝（10001「暂不支持」）， 登记为残留（不加新权限码、不动账号域）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRevealService {

    private static final String BIZ_TYPE_ELDER = "ELDER";

    /** 明文查看字段白名单（与契约 {@code x-sensitive-fields} 对齐）。 */
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("name", "idCardNo", "phone", "address");

    private static final int DELETED_NO = 0;

    private final ElderMapper mapper;

    private final SensitiveFieldSupport support;

    /**
     * 解密并返回指定敏感字段的明文。
     *
     * @param req 查看请求（{@code bizType/bizId/fields}）
     * @param operatorId 登录态操作者 id（留痕由 {@code @Audit} 切面完成；此处仅记录不可逆的追溯上下文）
     * @return 字段名 → 明文映射
     */
    public DataRevealResponse reveal(DataRevealRequest req, long operatorId) {
        String bizType =
                req.getBizType() == null ? "" : req.getBizType().trim().toUpperCase(Locale.ROOT);
        if (!BIZ_TYPE_ELDER.equals(bizType)) {
            throw new BizException(
                    ErrorCode.PARAM_INVALID, "bizType 暂不支持：" + req.getBizType() + "（本期仅 ELDER）");
        }
        for (String field : req.getFields()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "不支持的字段：" + field);
            }
        }

        QueryWrapper<Elder> wrapper = new QueryWrapper<>();
        wrapper.eq("id", req.getBizId()).eq("deleted", DELETED_NO);
        // 经 Mapper 查询 → 第三闸（数据域）自动介入；越界与不存在同为 0 行 → 10004
        Elder elder = mapper.selectOne(wrapper);
        if (elder == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        for (String field : req.getFields()) {
            fields.put(field, plainOf(field, elder));
        }
        // 仅记录不可逆上下文（操作者/业务对象/字段名），不打印任何明文
        log.info(
                "敏感字段明文查看 bizType={} bizId={} operatorId={} fields={}",
                BIZ_TYPE_ELDER,
                req.getBizId(),
                operatorId,
                fields.keySet());
        return new DataRevealResponse(BIZ_TYPE_ELDER, req.getBizId(), fields);
    }

    /** 字段名 → 明文（白名单已在调用前校验；未知字段返回 null）。 */
    private String plainOf(String field, Elder elder) {
        return switch (field) {
            case "name" -> support.decrypt(elder.getNameEnc());
            case "idCardNo" -> support.decrypt(elder.getIdCardEnc());
            case "phone" -> support.decrypt(elder.getPhoneEnc());
            case "address" -> support.decrypt(elder.getAddressEnc());
            default -> null;
        };
    }
}
