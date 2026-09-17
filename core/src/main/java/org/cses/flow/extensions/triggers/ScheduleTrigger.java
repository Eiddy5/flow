package org.cses.flow.extensions.triggers;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.triggers.AbstractTrigger;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.utils.RequiredUtil;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.time.ZoneId;

/** 使用 SPRING53 六段表达式和显式时区的定时启动条件。 */
@Plugin(
    title = "定时触发",
    description = "根据六段 Cron（秒 分 时 日 月 周）和显式时区独立启动 Flow",
    examples = {
        @Example(
            title = "每天上海时间 09:00 启动",
            code = """
                key: daily-schedule
                triggers:
                  - key: morning
                    type: org.cses.flow.extensions.triggers.ScheduleTrigger
                    cron: "0 0 9 * * *"
                    timezone: Asia/Shanghai
                tasks:
                  - key: hello
                    type: org.cses.flow.extensions.log.Log
                    message: "每日定时流程已启动"
                """,
            full = true
        ),
        @Example(
            title = "两个定时条件分别在 09:00 和 18:00 独立启动",
            code = """
                key: independent-schedules
                triggers:
                  - key: morning
                    type: org.cses.flow.extensions.triggers.ScheduleTrigger
                    cron: "0 0 9 * * *"
                    timezone: Asia/Shanghai
                  - key: evening
                    type: org.cses.flow.extensions.triggers.ScheduleTrigger
                    cron: "0 0 18 * * *"
                    timezone: Asia/Shanghai
                tasks:
                  - key: hello
                    type: org.cses.flow.extensions.log.Log
                    message: "一个定时条件满足，启动一次流程"
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class ScheduleTrigger extends AbstractTrigger implements org.cses.flow.core.domains.triggers.ScheduledTrigger {
    @jakarta.validation.constraints.NotBlank
    String cron;
    @jakarta.validation.constraints.NotBlank
    String timezone;
    /** @return 六段 Cron 原文 */
    public String cron() { return cron; }
    /** @return 显式配置的时区 */
    public String timezone() { return timezone; }
    /**
     * @param after 排他的 UTC 毫秒下界
     * @return 下一次 Cron 时间；无后续日期时为 null
     */
    @Override
    public Long nextAt(long after) {
        var cron = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53)).parse(cron());
        return com.cronutils.model.time.ExecutionTime.forCron(cron)
            .nextExecution(java.time.Instant.ofEpochMilli(after).atZone(ZoneId.of(timezone)))
            .map(value -> value.toInstant().toEpochMilli()).orElse(null);
    }
    /** @return Cron 与时区组成的时间规则比较值 */
    @Override
    public String scheduleKey() { return cron + "@" + timezone; }
    /** 校验公共属性、Cron 语法和时区，非法配置不能发布。 */
    @Override
    public void validate() {
        super.validate();
        new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53))
            .parse(RequiredUtil.required(cron, "ScheduleTrigger.cron")).validate();
        ZoneId.of(RequiredUtil.required(timezone, "ScheduleTrigger.timezone"));
    }
}
