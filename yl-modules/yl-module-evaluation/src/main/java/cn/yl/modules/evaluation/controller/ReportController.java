package cn.yl.modules.evaluation.controller;

import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.modules.evaluation.service.ReportPdfService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告文件接口（PRD P02「下载 PDF」）。
 *
 * <p>PDF 由 {@link ReportPdfService} 渲染并落存储，此处按报告编号取回字节流；报告编号做白名单校验，防目录穿越。
 *
 * <p><b>鉴权（CR-M2-006）</b>：本端点原<b>无第二闸</b>，且 {@code ReportPdfService.read()} 零 DB 查询使第三闸（数据域）在文件路径上
 * 完全失效 —— 实测任何已登录用户知道 {@code reportNo} 即可跨机构下载报告全文。修复后：① 第二闸补 {@code report:view}
 * （该码五角色全授予，与「可见范围不靠权限点区分」的既定口径一致）；② {@code read()} 先经 Mapper 反查使数据域行级过滤生效。 两道闸的回归断言见 {@code
 * scripts/smoke-report-review-chain.sh}「跨机构 reportNo 下载必须被拒」。
 */
@RestController
@RequestMapping("/api/v1/evaluation/reports")
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification = "ReportPdfService 是 Spring 容器管理的无状态单例 Bean，构造器注入是标准 DI 用法"))
public class ReportController {

    private final ReportPdfService pdfService;

    /**
     * 下载报告 PDF。
     *
     * <p>权限码 {@code report:view}：老人/评估员/家属/机构管理员/监管全部持有；<b>可见范围由第三闸数据域决定</b> —— 跨机构（或跨亲属绑定）的 {@code
     * reportNo} 在 {@code read()} 的反查处即被过滤为 404。
     */
    @GetMapping("/{reportNo}/pdf")
    @RequiresPermission(PermissionCode.REPORT_VIEW)
    public ResponseEntity<byte[]> download(@PathVariable String reportNo) {
        byte[] bytes = pdfService.read(reportNo);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(reportNo + ".pdf", StandardCharsets.UTF_8)
                        .build());
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }
}
