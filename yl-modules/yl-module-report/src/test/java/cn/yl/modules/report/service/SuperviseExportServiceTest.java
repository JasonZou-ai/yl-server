package cn.yl.modules.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.security.SensitiveFieldCodec;
import cn.yl.modules.report.domain.entity.ExportTask;
import cn.yl.modules.report.dto.ExportCreateRequest;
import cn.yl.modules.report.dto.ExportSourceRow;
import cn.yl.modules.report.dto.ExportTaskView;
import cn.yl.modules.report.mapper.ExportSourceMapper;
import cn.yl.modules.report.mapper.ExportTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/** B9 导出编排服务测试：格式/类型越界、超限、落库（org/键名）、无机构上下文、产物 BOM 魔数。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SuperviseExportServiceTest {

    private static final int MAX_SYNC_ROWS = 5000;

    @Mock private ExportSourceMapper sourceMapper;
    @Mock private ExportTaskMapper exportTaskMapper;
    @Mock private ObjectProvider<SensitiveFieldCodec> codecProvider;

    @TempDir Path tempDir;

    private SuperviseExportService service;

    @BeforeEach
    void setUp() {
        when(codecProvider.getIfAvailable()).thenReturn(null);
        service =
                new SuperviseExportService(
                        sourceMapper,
                        exportTaskMapper,
                        new ExportCsvWriter(),
                        new ObjectMapper(),
                        codecProvider,
                        MAX_SYNC_ROWS,
                        tempDir.toString(),
                        7);
    }

    @Test
    void rejectsNonCsvFormat() {
        ExportCreateRequest req = request("EVAL_LIST", "XLSX");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, operator(1L)));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void rejectsNonEvalListType() {
        ExportCreateRequest req = request("STAT", "CSV");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, operator(1L)));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void rejectsWhenRowCountExceedsLimit() {
        when(sourceMapper.selectEvalList(MAX_SYNC_ROWS + 1)).thenReturn(rows(MAX_SYNC_ROWS + 1));

        BizException ex =
                assertThrows(
                        BizException.class,
                        () -> service.create(request("EVAL_LIST", "CSV"), operator(1L)));

        assertEquals(ErrorCode.EXPORT_TASK_TOO_LARGE.getCode(), ex.getCode());
    }

    @Test
    void rejectsWhenOperatorHasNoOrg() {
        BizException ex =
                assertThrows(
                        BizException.class,
                        () -> service.create(request("EVAL_LIST", "CSV"), operator(null)));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void successInsertsTaskWithOrgAndKeyAndWritesBomFile() throws Exception {
        when(sourceMapper.selectEvalList(MAX_SYNC_ROWS + 1)).thenReturn(rows(2));

        ExportTaskView view = service.create(request("EVAL_LIST", "CSV"), operator(1L));

        ArgumentCaptor<ExportTask> captor = ArgumentCaptor.forClass(ExportTask.class);
        verify(exportTaskMapper).insert(captor.capture());
        ExportTask task = captor.getValue();
        assertEquals("EVAL_LIST", task.getExportType());
        assertEquals("CSV", task.getFormat());
        assertEquals(1L, task.getOrgId());
        assertEquals(101L, task.getOperatorId());
        assertEquals(2, task.getStatus());
        assertEquals(2, task.getRowCount());
        assertEquals("export/" + task.getTaskNo() + ".csv", task.getFileCosKey());
        assertTrue(task.getTaskNo().startsWith("EX"));
        assertTrue(task.getExpireAt().isAfter(task.getStartedAt()));

        byte[] file =
                Files.readAllBytes(tempDir.resolve("export").resolve(view.getTaskNo() + ".csv"));
        assertEquals((byte) 0xEF, file[0]);
        assertEquals((byte) 0xBB, file[1]);
        assertEquals((byte) 0xBF, file[2]);
    }

    private ExportCreateRequest request(String type, String format) {
        ExportCreateRequest req = new ExportCreateRequest();
        req.setExportType(type);
        req.setFormat(format);
        return req;
    }

    private LoginUser operator(Long orgId) {
        return new LoginUser(
                101L, orgId, List.of("ORG_ADMIN"), List.of("data:export"), List.of(1L), 2);
    }

    private List<ExportSourceRow> rows(int count) {
        List<ExportSourceRow> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ExportSourceRow row = new ExportSourceRow();
            row.setOrderNo("EO-" + i);
            row.setGradeCode("GRADE_MILD");
            row.setElderNameEnc("cipher-name");
            row.setIdCardMask("5301**********1234");
            row.setPhoneEnc("cipher-phone");
            row.setStatus("PUBLISHED");
            row.setOrgId(1L);
            list.add(row);
        }
        return list;
    }
}
