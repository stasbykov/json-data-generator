package io.github.stasbykov.jsondata;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationLogTest {
    @Test void logsActualSeedClockAndBuildOnlyOnGenerationIncludingFailures() {
        Logger logger = (Logger) LoggerFactory.getLogger(JsonDataBuilder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            var b = JsonData.builder("operation.json").clock(Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"),ZoneId.of("UTC")));
            long seed = b.getSeed(); b.build(); b.buildTree(); b.build();
            assertEquals(1,appender.list.size());
            assertEquals(JsonDataBuilder.class.getName(), appender.list.getFirst().getLoggerName());
            String message=appender.list.getFirst().getFormattedMessage();
            assertTrue(message.contains("seed="+seed)); assertTrue(message.contains("clockInstant=2026-10-26T10:00:00Z"));
            assertTrue(message.contains("clockZone=UTC")); assertTrue(message.contains("build=1"));
            assertEquals("INFO",appender.list.getFirst().getLevel().toString());
            b.set("/new",1).build(); assertEquals(2,appender.list.size());
            assertTrue(appender.list.getLast().getFormattedMessage().contains("build=2"));
            var missing=JsonData.builder("does-not-exist.json").seed(123);
            assertThrows(JsonDataException.class,missing::build); assertThrows(JsonDataException.class,missing::build);
            assertEquals(4,appender.list.size());
            assertTrue(appender.list.getLast().getFormattedMessage().contains("seed=123"));
            assertTrue(appender.list.getLast().getFormattedMessage().contains("build=2"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
