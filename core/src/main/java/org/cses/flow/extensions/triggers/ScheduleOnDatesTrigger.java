package org.cses.flow.extensions.triggers;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.triggers.AbstractTrigger;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.domains.triggers.ScheduledTrigger;
import java.util.List;

/** 在指定的 UTC 毫秒时间点各启动一次。 */
@Plugin(title = "指定日期触发", description = "每个指定 UTC 毫秒时间点独立启动一次，日期耗尽后停止",
    examples = @Example(title = "在两个指定时间启动（请替换为未来 UTC 毫秒时间）", full = true, code = """
        key: scheduled-dates
        triggers:
          - key: appointments
            type: org.cses.flow.extensions.triggers.ScheduleOnDatesTrigger
            dates: [1893456000000, 1893542400000]
        tasks:
          - key: hello
            type: org.cses.flow.extensions.log.Log
            message: "预约时间已到"
        """))
@SuperBuilder
@NoArgsConstructor
public class ScheduleOnDatesTrigger extends AbstractTrigger implements ScheduledTrigger {
    @jakarta.validation.constraints.NotEmpty
    List<Long> dates;
    /** @return 只读时间点集合，单位为 UTC 毫秒 */
    public List<Long> dates() { return List.copyOf(dates); }
    /** 校验至少一个非负且不重复的时间点，允许保存已过去的日期。 */
    @Override
    public void validate() {
        super.validate();
        if (dates == null || dates.isEmpty() || dates.stream().anyMatch(value -> value == null || value < 0)
            || new java.util.HashSet<>(dates).size() != dates.size())
            throw new IllegalArgumentException("dates must contain distinct nonnegative UTC timestamps");
    }
    /**
     * @param after 排他的 UTC 毫秒下界
     * @return 最早的后续时间；全部结束时为 null
     */
    @Override
    public Long nextAt(long after) { return dates.stream().filter(value -> value > after).min(Long::compare).orElse(null); }
    /** @return 排序后的日期规则，调整声明顺序不重建计划 */
    @Override
    public String scheduleKey() { return dates.stream().sorted().toList().toString(); }
}
