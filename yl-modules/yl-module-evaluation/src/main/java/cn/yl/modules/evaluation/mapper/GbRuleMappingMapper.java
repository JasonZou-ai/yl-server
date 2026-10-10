package cn.yl.modules.evaluation.mapper;

import cn.yl.modules.evaluation.domain.entity.GbRuleMapping;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 国标条款→规则条目映射 Mapper（gb_rule_mapping，字典类表）。 */
@Mapper
public interface GbRuleMappingMapper extends BaseMapper<GbRuleMapping> {}
