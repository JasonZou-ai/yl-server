package cn.yl.modules.account.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 保留字列名映射回归（CR-M2-003）。
 *
 * <p>MySQL 8 有 262 个保留字（{@code information_schema.KEYWORDS.RESERVED=1}）。若列名恰为保留字而实体映射未加反引号，
 * MyBatis-Plus 生成的 SQL 会在<b>运行时</b>报语法错误：编译过、单测过（Mapper 被 Mock 掉即绕过），只在真实库上表现为 "写入静默失败"。{@code
 * audit_log.sensitive} 就曾因此让<b>审计留痕长期 0 行</b>。
 *
 * <p>发现同类问题的扫描口径（全库一次性排查，勿凭记忆）：
 *
 * <pre>
 * SELECT c.table_name, c.column_name
 *   FROM information_schema.columns c
 *   JOIN information_schema.KEYWORDS k ON UPPER(c.column_name) = k.WORD AND k.RESERVED = 1
 *  WHERE c.table_schema = 'yl_evaluation';
 * </pre>
 *
 * <p>本用例把「已确认的保留字列」逐个固定住：新增命中列时须同时补 {@code @TableField("`col`")} 与下方清单。
 */
class ReservedWordColumnMappingTest {

    /** 全库扫描命中清单（2026-09-23 实测仅此一列；MySQL 8.0.46）。 */
    private static final List<String> RESERVED_WORD_COLUMNS = List.of("sensitive");

    @Test
    @DisplayName("保留字列必须反引号映射：audit_log.sensitive（否则审计写入整体失败）")
    void reservedWordColumnsAreBackquoted() throws Exception {
        for (String column : RESERVED_WORD_COLUMNS) {
            Field field = AuditLog.class.getDeclaredField(column);
            TableField mapping = field.getAnnotation(TableField.class);

            assertThat(mapping).as("列 %s 为 MySQL 保留字，必须有 @TableField 显式映射", column).isNotNull();
            assertThat(mapping.value())
                    .as("列 %s 必须反引号包裹，否则真实库上 INSERT 语法错误", column)
                    .isEqualTo("`" + column + "`");
        }
    }
}
