package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.AuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 审计日志 Mapper（audit_log）。仅追加，不做逻辑删除。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {}
