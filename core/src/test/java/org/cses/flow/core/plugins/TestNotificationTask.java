package org.cses.flow.core.plugins;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;

/**
 * Test plugin proving that one concrete Task owns its custom definition field.
 */
@Plugin(
    title = "通知",
    description = "向指定频道发送一条通知",
    examples = {
        @Example(
            title = "发送流程告警",
            code = """
                key: notification-flow
                tasks:
                  - key: notify-alerts
                    type: org.cses.flow.core.plugins.TestNotificationTask
                    channel: flow-alerts
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class TestNotificationTask
    extends Task implements RunnableTask<TestNotificationTask.NotificationOutput> {

    @NotBlank
    @Schema(
        title = "Channel",
        description = "Destination channel name.",
        example = "flow-alerts"
    )
    private String channel;

    public String channel() {
        return channel;
    }

    /**
     * 返回配置频道作为后续步骤可读取的通知结果。
     * @param context 运行上下文；本样本不读取它
     * @return 包含频道值的新输出
     */
    @Override
    public NotificationOutput run(RunContext context) {
        return NotificationOutput.from(channel);
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return channel;
    }

    /** 保存此测试任务的具体业务结果。 */
    public record NotificationOutput(String channel) implements org.cses.flow.core.domains.tasks.Output {
        /**
         * 创建本次运行的业务输出。
         * @param channel 本次运行的只读结果值
         * @return 包含该值的新输出
         */
        public static NotificationOutput from(String channel) {
            return new NotificationOutput(channel);
        }
    }
}
