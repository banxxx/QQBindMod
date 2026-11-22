package org.slf4j;
/** Test-only no-I/O logger surface. Not a claim of real SLF4J integration. */
public interface Logger {
    default void info(String message,Object... values) { LoggerFactory.messages.add(message + java.util.Arrays.toString(values)); }
    default void warn(String message,Object... values) { LoggerFactory.messages.add(message + java.util.Arrays.toString(values)); }
    default void error(String message,Object... values) {
        for (Object value : values) if (value instanceof Throwable) throw new AssertionError("Config must not log raw causes");
        LoggerFactory.messages.add(message + java.util.Arrays.toString(values));
    }
}
