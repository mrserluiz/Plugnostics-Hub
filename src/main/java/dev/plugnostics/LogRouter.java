package dev.plugnostics;

import java.util.*;
import java.util.regex.*;

/** Routes console records without treating a mentioned plugin as a proven cause. */
final class LogRouter {
    private static final Pattern HEADER = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}[^]]*]\\s*\\[([^]]+)]\\s*:?[ ]?(.*)$");
    private static final Pattern TAG = Pattern.compile("^\\[([^]]+)]");
    private static final Pattern ERROR = Pattern.compile("(?i)(?:^|[^a-z])(ERROR|SEVERE|FATAL)(?:$|[^a-z])");
    private final Map<String,String> names;
    private final Map<String,String> packages;
    private String owner;
    private boolean error;

    LogRouter(Map<String,String> names, Map<String,String> packages) {
        this.names = Map.copyOf(names);
        this.packages = Map.copyOf(packages);
    }

    Set<String> route(String line) {
        Matcher header = HEADER.matcher(line);
        if (header.matches()) {
            String message = header.group(2);
            boolean nextError = ERROR.matcher(header.group(1)).find();
            boolean continuation = error && nextError && message.matches("(?:\\s+at\\s+.*|\\s*(?:Caused by:|Suppressed:|\\.\\.\\. \\d+ more).*|[a-zA-Z_$][\\w.$]*(?:Exception|Error)(?::.*)?)");
            if (!continuation) owner = null;
            error = nextError;
            Matcher tag = TAG.matcher(message);
            if (tag.find()) owner = names.get(tag.group(1).toLowerCase(Locale.ROOT));
            // Attribution by the actual emitting logger when the console includes it.
            if (owner == null) {
                for (var entry : packages.entrySet()) {
                    if (header.group(1).contains(entry.getKey())) { owner = entry.getValue(); break; }
                }
            }
        }
        Set<String> destinations = new LinkedHashSet<>();
        destinations.add(owner == null ? "server.log" : "plugins/" + owner + ".log");
        if (error) destinations.add(owner == null ? "errors/paper-errors.log" : "errors/plugins-errors.log");
        return destinations;
    }
}
