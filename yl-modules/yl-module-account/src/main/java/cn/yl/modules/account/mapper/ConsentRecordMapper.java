package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.ConsentRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 告知同意留痕 Mapper（consent_record，ER-08）。 */
@Mapper
public interface ConsentRecordMapper extends BaseMapper<ConsentRecord> {}
