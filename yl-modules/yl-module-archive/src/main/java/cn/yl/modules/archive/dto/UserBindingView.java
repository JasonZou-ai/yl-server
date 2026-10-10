package cn.yl.modules.archive.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** elder↔user 绑定出参（D-4）。 */
@Data
public class UserBindingView {

    private Long elderId;

    private Long userId;

    /** 绑定时间（= {@code elder.updated_at}）。 */
    private LocalDateTime boundAt;
}
