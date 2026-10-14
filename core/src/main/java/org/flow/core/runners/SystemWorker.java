package org.flow.core.runners;

import org.flow.core.server.Service;

/** 系统任务执行角色；与普通 Worker 分开装配，供原生引擎统一管理生命周期。 */
public interface SystemWorker extends Service {
    /** 启动系统任务消费和专用线程池；使用实现自身的默认并发配置。 */
    void start();
}
