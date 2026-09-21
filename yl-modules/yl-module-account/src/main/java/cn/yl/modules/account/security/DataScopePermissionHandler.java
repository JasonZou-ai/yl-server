package cn.yl.modules.account.security;

import cn.yl.api.security.LoginUser;
import cn.yl.modules.account.domain.DataScope;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 数据域权限处理器（四道闸·第三闸：数据域）。
 *
 * <p>按 {@link LoginUser#dataScope()} 对 SQL 追加行级过滤条件：1-本人 → {@code 归属列 = 我}；2-本机构 → {@code org_id IN
 * (我的机构)}；3-全量 / 4-只读全局 → 不加限（4 的写拒绝由 {@code DataScopeReadOnlyGuardInterceptor} + 权限矩阵保证）。
 *
 * <p><b>映射策略（保守）</b>：仅对本类显式登记、且列名已在 {@code 02_schema.sql} 核对过的表追加条件；未登记的表一律 不加限，避免误改 SQL
 * 破坏其它模块。映射随各模块实现逐步补全，权限矩阵（第二闸）与红线断言仍是第一道防线。
 */
public class DataScopePermissionHandler implements MultiDataPermissionHandler {

    /** 机构级数据域（scope=2）适用的表 → 机构列（列名均已核对 schema）。 */
    private static final Map<String, String> ORG_COLUMN =
            Map.ofEntries(
                    Map.entry("org", "id"),
                    Map.entry("org_bed", "org_id"),
                    Map.entry("staff", "org_id"),
                    Map.entry("sys_org_member", "org_id"),
                    Map.entry("elder", "org_id"),
                    Map.entry("eval_task", "org_id"),
                    Map.entry("eval_order", "org_id"),
                    Map.entry("eval_report", "org_id"),
                    Map.entry("care_plan", "org_id"),
                    Map.entry("supervise_report", "org_id"));

    /** 本人级数据域（scope=1）适用的表 → 归属用户列。 */
    private static final Map<String, String> SELF_COLUMN =
            Map.ofEntries(
                    Map.entry("sys_user", "id"),
                    Map.entry("login_log", "user_id"),
                    Map.entry("audit_log", "operator_id"));

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        LoginUser user = currentUser();
        if (user == null) {
            return null;
        }
        int scope = user.dataScope();
        if (scope >= DataScope.ALL.code()) {
            return null;
        }
        String tableName = table.getName() == null ? "" : table.getName().toLowerCase(Locale.ROOT);
        if (scope == DataScope.ORG.code()) {
            String column = ORG_COLUMN.get(tableName);
            return column == null ? null : orgCondition(table, column, user.orgIds());
        }
        String column = SELF_COLUMN.get(tableName);
        return column == null ? null : selfCondition(table, column, user.userId());
    }

    /** {@code table.col IN (orgIds)}；无机构归属时构造恒假条件，避免越权看到任何机构数据。 */
    private Expression orgCondition(Table table, String column, List<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return new Parenthesis(new EqualsTo(new LongValue(1), new LongValue(0)));
        }
        List<Expression> items = new ArrayList<>(orgIds.size());
        for (Long orgId : orgIds) {
            items.add(new LongValue(orgId));
        }
        return new Parenthesis(
                new InExpression(new Column(table, column), new ExpressionList(items)));
    }

    private Expression selfCondition(Table table, String column, long userId) {
        return new Parenthesis(new EqualsTo(new Column(table, column), new LongValue(userId)));
    }

    private LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            return null;
        }
        return loginUser;
    }
}
