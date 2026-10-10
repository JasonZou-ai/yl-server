package cn.yl.modules.archive.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 老人档案（elder）。
 *
 * <p>敏感字段一律以密文列（{@code *_enc}）/ 摘要列（{@code *_hash}）/ 脱敏列（{@code id_card_mask}）落库， 禁止明文（PRD
 * §9；{@code SensitiveFieldCodec} 负责加解密与摘要）。
 *
 * <p>主键采用 {@link IdType#ASSIGN_ID}（雪花，ADR-0003：业务表禁用 AUTO_INCREMENT）。 {@code created_at/updated_at}
 * 依赖数据库默认值（同 {@code EvalTask} 范式，实体不填充）。
 *
 * <p><b>{@code userId}</b> 为 D-2 裁决二·方案 A 新增的「本人登录账号锚列」（对应 {@code elder.user_id}， 可空）；它使数据域
 * scope=1（ELDER 本人）在 SQL 层可实现为 {@code elder.user_id = <我>}。
 *
 * <p>{@code deleted} 为普通列（非生成列），<b>查询须显式 {@code deleted = 0}</b> （{@code
 * DataScopePermissionHandler} 不改写该条件）。
 */
@Data
@TableName("elder")
public class Elder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对外档案编号（不含隐私，唯一）。 */
    private String elderNo;

    /** 姓名（密文）。 */
    private String nameEnc;

    /** 姓名 HMAC 摘要（辅助精确检索）。 */
    private String nameHash;

    /** 身份证号（密文，禁止明文）。 */
    private String idCardEnc;

    /** 身份证号 HMAC-SHA256 摘要（精确去重/检索）。 */
    private String idCardHash;

    /** 身份证号脱敏展示串（如 5301**********1234）。 */
    private String idCardMask;

    /** 0-女 1-男 2-未知。 */
    private Integer gender;

    private LocalDate birthDate;

    /** 年龄（冗余，便于统计）。 */
    private Integer age;

    /** 手机号（密文）。 */
    private String phoneEnc;

    /** 手机号 HMAC 摘要。 */
    private String phoneHash;

    /** 住址（密文）。 */
    private String addressEnc;

    /** 健康史（密文，D-2 不写）。 */
    private String healthHistoryEnc;

    /** 用药记录（密文，D-2 不写）。 */
    private String medicationEnc;

    /** 所属机构。 */
    private Long orgId;

    /** 本人登录账号（sys_user.id，应用层引用；D-2 裁决二新增锚列，可空）。 */
    private Long userId;

    /** 照片对象键。 */
    private String photoCosKey;

    /** 0-迁出/死亡 1-在册 2-归档。 */
    private Integer status;

    /** 软删除标记（0-未删除 1-已删除），查询须显式过滤。 */
    private Integer deleted;

    private Long createdBy;

    private LocalDateTime createdAt;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
