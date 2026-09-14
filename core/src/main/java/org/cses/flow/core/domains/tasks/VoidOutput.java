package org.cses.flow.core.domains.tasks;

/** 不携带任何属性的空任务输出。 */
public class VoidOutput implements Output {

    /** 构造无属性的空输出，调用方通过 from() 创建。 */
    private VoidOutput() {
    }

    /**
     * 创建空输出，由执行边界采用默认成功状态。
     * @return 新建的无属性空输出
     */
    public static VoidOutput from() {
        return new VoidOutput();
    }
}
