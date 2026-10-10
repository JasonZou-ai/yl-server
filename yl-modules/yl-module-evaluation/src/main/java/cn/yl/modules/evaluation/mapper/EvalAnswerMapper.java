package cn.yl.modules.evaluation.mapper;

import cn.yl.modules.evaluation.domain.entity.EvalAnswer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 评估作答明细 Mapper（eval_answer）。按 order_id 取全部作答用于提交时评分。 */
@Mapper
public interface EvalAnswerMapper extends BaseMapper<EvalAnswer> {}
