package org.cses.flow.core.services.executions;

import com.sun.net.httpserver.HttpServer;
import org.cses.flow.core.utils.TimeUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 记录实际外部调用并阻塞 A 响应的独立业务样本；不替换流程或消息处理。 */
public class SubmittedBusiness implements AutoCloseable {
    private static final String EXECUTION_HEADER = "X-Execution-Id";
    private static final String TASK_RUN_HEADER = "X-TaskRun-Id";
    private HttpServer server;
    private ExecutorService executor;
    private CountDownLatch released = new CountDownLatch(1);
    private List<Call> calls = new CopyOnWriteArrayList<>();

    /**
     * 启动本机独立 HTTP 服务，A 请求在显式释放后才返回，其他步骤立即返回输入文本。
     * @throws Exception 本机临时端口无法绑定时抛出
     */
    public SubmittedBusiness() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestURI().getPath().substring(1);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(Call.from(key, body, exchange.getRequestHeaders().getFirst(EXECUTION_HEADER),
                exchange.getRequestHeaders().getFirst(TASK_RUN_HEADER), TimeUtil.now()));
            try {
                if (key.equals("a")) released.await();
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    /**
     * 返回当前样本的步骤端点。
     * @param key 非空步骤业务标识
     * @return 只指向本机服务的 HTTP 地址
     */
    public String endpoint(String key) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + key;
    }

    /**
     * 查询某步骤的实际外部请求事实。
     * @param key 非空步骤业务标识
     * @return 不可变请求快照，未调用时为空
     */
    public List<Call> calls(String key) {
        return calls.stream().filter(call -> call.key().equals(key)).toList();
    }

    /** 放行 A 的外部响应，后续调用也不再阻塞。 */
    public void release() {
        released.countDown();
    }

    /** 释放所有响应并关闭本样本拥有的监听端口和线程。 */
    @Override
    public void close() {
        release();
        server.stop(0);
        executor.shutdownNow();
    }

    /** 可与公开运行身份对应的物理请求事实。 */
    public record Call(String key, String body, String executionId, String taskRunId, long at) {
        /**
         * 记录一个已经到达外部样本的请求。
         * @param key 请求路径中的步骤业务标识
         * @param body 实际请求正文
         * @param executionId 请求携带的原运行编号
         * @param taskRunId 请求携带的原步骤编号
         * @param at 请求到达的 UTC 毫秒时间
         * @return 不可变请求事实
         */
        static Call from(String key, String body, String executionId, String taskRunId, long at) {
            return new Call(key, body, executionId, taskRunId, at);
        }
    }
}
