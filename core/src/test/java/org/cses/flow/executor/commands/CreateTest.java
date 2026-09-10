package org.cses.flow.executor.commands;

import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.pulsar.JacksonSchema;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CreateTest {

    /** 初始化实际 PAAS Schema 所需的 JSON 绑定组件。 */
    @BeforeAll
    static void initializeJsonMapper() {
        PulsarTestEnvironment.initializeJson();
    }

    /** 通过 PAAS Schema 往返创建命令，验证具体类型、流程版本与输入保持不变。 */
    @Test
    void restoresTheConcreteCreateCommandThroughTheExecutorContract() {
        Create command = Create.from(
            "company-1",
            "execution-1",
            "actor-1",
            "flow-key-1",
            7,
            Map.of("amount", 1200.5)
        );

        JacksonSchema<ExecutionCommand> schema = new JacksonSchema<>(ExecutionCommand.class);
        byte[] encoded = schema.encode(command);
        ExecutionCommand restored = schema.decode(encoded);

        Create create = assertInstanceOf(Create.class, restored);
        assertEquals(ExecutionCommand.Type.CREATE, create.getType());
        assertEquals("company-1", create.getCompanyId());
        assertEquals(
            "execution-1",
            create.getExecutionId()
        );
        assertEquals("actor-1", create.getActorId());
        assertEquals("flow-key-1", create.getFlowKey());
        assertEquals(7L, create.getFlowVersion());
        assertEquals(Map.of("amount", 1200.5), create.getInputs());
    }
}
