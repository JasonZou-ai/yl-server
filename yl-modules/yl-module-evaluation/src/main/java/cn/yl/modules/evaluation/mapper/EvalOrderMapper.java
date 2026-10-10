package cn.yl.modules.evaluation.mapper;

import cn.yl.modules.evaluation.domain.archive.ExpiredOrderArchiver;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 评估单 Mapper（eval_order）。
 *
 * <p>归档动作（{@link ExpiredOrderArchiver}）走<b>手写 SQL</b>：归档是「批量状态流转」而非按主键的单行更新， MyBatis-Plus 的 {@code
 * updateById} 无法表达，且批量场景下乐观锁无意义（不参与并发编辑竞争）。手写 SQL 同时绕开全局 逻辑删除改写，语义显式可读。{@code row_version}
 * 仍显式自增，保证按版本号读取的端侧不会看到"改过但版本没动"。
 */
@Mapper
public interface EvalOrderMapper extends BaseMapper<EvalOrder>, ExpiredOrderArchiver {

    /**
     * 归档已过保留期的已发布评估单：{@code PUBLISHED → ARCHIVED}。
     *
     * <p>{@code retain_until} <b>不清空</b>：它是留存事实，封存后仍需可查；幂等由 {@code status='PUBLISHED'} 条件保证
     * （归档后即离开命中集合）。命中列有 {@code idx_retain(retain_until)} 索引支撑。
     */
    @Override
    @Update(
            "UPDATE eval_order SET status = 'ARCHIVED', row_version = row_version + 1, updated_at = NOW()"
                    + " WHERE retain_until IS NOT NULL AND retain_until < #{cutoff} AND status = 'PUBLISHED'"
                    + " ORDER BY retain_until LIMIT #{limit}")
    int archiveExpired(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 统计已过保留期但尚未归档的评估单数。 */
    @Override
    @Select(
            "SELECT COUNT(*) FROM eval_order WHERE retain_until IS NOT NULL AND retain_until < #{cutoff}"
                    + " AND status = 'PUBLISHED'")
    long countExpired(@Param("cutoff") LocalDateTime cutoff);
}
