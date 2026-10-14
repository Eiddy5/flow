package org.flow.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.flow.queue.jdbc.client.JdbcQueueClient;
import io.micronaut.context.ApplicationContext;

/** 用于强杀验证的独立 JVM 消费者；仅存在于测试制品。 */
public class QueueCrashProbe {
    /**
     * 领取一条消息后保留事务，等待父进程强杀；不正常返回消费回调。
     * @param args 父进程提供的领取凭证路径
     * @throws Exception Context 或消费初始化失败时使子进程非零退出
     */
    public static void main(String[] args) throws Exception {
        try (var context = ApplicationContext.run(new HashMap<String, Object>(new FlowJdbcTest().getProperties()), "test")) {
            context.getBean(JdbcQueueClient.class).subscribeDispatch("crash", List.of(), bytes -> {
                try {
                    Files.writeString(Path.of(args[0]), new String(bytes, StandardCharsets.UTF_8));
                    new CountDownLatch(1).await();
                    throw new IllegalStateException("强杀探针不应正常 ACK");
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}
