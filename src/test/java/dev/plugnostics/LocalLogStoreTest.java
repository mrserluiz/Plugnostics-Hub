package dev.plugnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalLogStoreTest {
    @TempDir Path temp;
    private LogRouter router() { return new LogRouter(Map.of("bettermodel", "BetterModel"), Map.of()); }
    private LocalLogStore store(Path source, long size) throws Exception {
        return new LocalLogStore(temp.resolve("output"), source, router(), s -> s, size, 2);
    }
    @Test void routesMultilineErrorsAndResetsAtNextRecord() {
        LogRouter r = router();
        assertEquals(Set.of("plugins/BetterModel.log", "errors/plugins-errors.log"),
            r.route("[12:00:00] [Server thread/ERROR]: [BetterModel] Failed"));
        assertEquals(Set.of("plugins/BetterModel.log", "errors/plugins-errors.log"), r.route("  at foo.Bar.run(Bar.java:1)"));
        assertEquals(Set.of("plugins/BetterModel.log", "errors/plugins-errors.log"),
            r.route("[12:00:00] [Server thread/ERROR]: \tat foo.Bar.run(Bar.java:1)"));
        assertEquals(Set.of("server.log", "errors/paper-errors.log"),
            r.route("[12:00:01] [Server thread/ERROR]: Watchdog timeout"));
        assertEquals(Set.of("server.log"), r.route("[12:00:02] [Server thread/INFO]: Recovered"));
        assertEquals(Set.of("server.log"), r.route("[12:00:03] [Server thread/INFO]: Someone mentioned BetterModel"));
    }
    @Test void incompleteUtf8IsPreservedAndCheckpointAvoidsRestartDuplicates() throws Exception {
        Path source = temp.resolve("latest.log");
        byte[] data = "[12:00:00] [Server thread/INFO]: [BetterModel] coração\n".getBytes(StandardCharsets.UTF_8);
        Files.write(source, Arrays.copyOf(data, data.length - 2));
        LocalLogStore s = store(source, 100000);
        s.poll(1048576);
        assertFalse(Files.exists(temp.resolve("output/plugins/BetterModel.log")));
        Files.write(source, Arrays.copyOfRange(data, data.length - 2, data.length), StandardOpenOption.APPEND);
        s.poll(1048576);
        String result = Files.readString(temp.resolve("output/plugins/BetterModel.log"));
        assertTrue(result.contains("coração"));
        store(source, 100000).poll(1048576);
        assertEquals(result, Files.readString(temp.resolve("output/plugins/BetterModel.log")));
    }
    @Test void detectsTruncationAndRotatesUsersAsOneFile() throws Exception {
        Path source = temp.resolve("latest.log");
        Files.writeString(source, "[12:00:00] [Server thread/INFO]: initial long record\n");
        LocalLogStore s = store(source, 40);
        s.poll(1048576);
        Files.writeString(source, "new\n");
        s.poll(1048576);
        s.append("users.log", "Alice JOIN"); s.flush();
        s.append("users.log", "Bob CHAT hello this is a longer message"); s.flush();
        assertTrue(Files.readString(temp.resolve("output/users.log.1")).contains("Alice"));
        assertTrue(Files.readString(temp.resolve("output/users.log")).contains("Bob"));
        assertTrue(Files.readString(temp.resolve("output/server.log")).contains("new"));
    }
}
