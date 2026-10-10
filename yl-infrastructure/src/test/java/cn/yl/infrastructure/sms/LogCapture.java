package cn.yl.infrastructure.sms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * 日志断言工具（测试专用）。
 *
 * <p>短信通道的两条合规红线是「**不得把明文手机号或验证码写进日志**」——这类断言必须**直接读日志输出**才有区分力 （QA 突变 M13 实证：只断言配置值、不断言行为，等于没有门禁）。
 */
final class LogCapture {

    private LogCapture() {}

    /** 采集 {@code type} 的 INFO 级日志消息后返回（{@code action} 执行期间）。 */
    static List<String> messagesOf(Class<?> type, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
