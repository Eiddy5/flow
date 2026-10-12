package org.cses.flow;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.SAXParserFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackXmlCompatibilityTest {
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String LOGBACK_RESOURCE = "logback.xml";

    /** 验证运行类路径的 SAX 提供者支持安全特性，且实际 Logback XML 加载无错误。 */
    @Test
    void configuresLogbackWithAParserThatSupportsRequiredSecurityFeatures() {
        assertDoesNotThrow(() -> SAXParserFactory.newInstance().setFeature(EXTERNAL_GENERAL_ENTITIES, false));
        LoggerContext context = new LoggerContext();
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            assertDoesNotThrow(() -> configurator.doConfigure(getClass().getClassLoader().getResource(LOGBACK_RESOURCE)));
            assertTrue(context.getStatusManager().getCopyOfStatusList().stream()
                .noneMatch(status -> status.getLevel() == Status.ERROR));
        } finally {
            context.stop();
        }
    }
}
