package cn.yl.modules.evaluation.mapper;

import cn.yl.modules.evaluation.domain.entity.GbRuleVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 国标规则版本 Mapper（gb_rule_version）。用于绑定判定所用规则版本（事后复算锚点）。 */
@Mapper
public interface GbRuleVersionMapper extends BaseMapper<GbRuleVersion> {}
