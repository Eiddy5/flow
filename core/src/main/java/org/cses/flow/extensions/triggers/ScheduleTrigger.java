package org.cses.flow.extensions.triggers;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.triggers.AbstractTrigger;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.utils.RequiredUtil;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import java.time.ZoneId;

/** 使用 SPRING53 六段表达式和显式时区的定时启动条件。 */
@Plugin(title = "定时触发", description = "根据 Cron 和时区独立启动 Flow")
@SuperBuilder
@NoArgsConstructor
public class ScheduleTrigger extends AbstractTrigger {
    @jakarta.validation.constraints.NotBlank
    String cron;
    @jakarta.validation.constraints.NotBlank
    String timezone;
    /** @return 六段 Cron 原文 */
    public String cron() { return cron; }
    /** @return 显式配置的时区 */
    public String timezone() { return timezone; }
    /** 校验公共属性、Cron 语法和时区，非法配置不能发布。 */
    @Override
    public void validate() {
        super.validate();
        new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53))
            .parse(RequiredUtil.required(cron, "ScheduleTrigger.cron")).validate();
        ZoneId.of(RequiredUtil.required(timezone, "ScheduleTrigger.timezone"));
    }
}
