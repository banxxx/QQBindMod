package org.slf4j;
import java.util.*;
public final class LoggerFactory {
    public static final List<String> messages=new ArrayList<>();
    public static Logger getLogger(Class<?> type) { return new Logger() {}; }
}
