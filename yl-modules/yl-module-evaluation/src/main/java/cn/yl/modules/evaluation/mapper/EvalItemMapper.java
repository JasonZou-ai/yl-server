package cn.yl.modules.evaluation.mapper;

import cn.yl.modules.evaluation.domain.entity.EvalItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 指标题库 Mapper（eval_item）。用于提交前校验必答项完整性（ER-06）。 */
@Mapper
public interface EvalItemMapper extends BaseMapper<EvalItem> {}
