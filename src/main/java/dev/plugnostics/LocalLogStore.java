package dev.plugnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.UnaryOperator;

/** One worker owns this store, its cursor and its file handles. */
final class LocalLogStore {
    private final Path root, source, checkpoint;
    private final long maxSize;
    private final int archives;
    private final LogRouter router;
    private final UnaryOperator<String> sanitize;
    private long cursor;
    private String identity = "";
    private final Map<String,StringBuilder> batch = new LinkedHashMap<>();

    LocalLogStore(Path root, Path source, LogRouter router, UnaryOperator<String> sanitize,
                  long maxSize, int archives) throws IOException {
        this.root = root;
        this.source = source;
        this.checkpoint = root.resolve("cursor.properties");
        this.router = router;
        this.sanitize = sanitize;
        this.maxSize = maxSize;
        this.archives = archives;
        Files.createDirectories(root.resolve("plugins"));
        Files.createDirectories(root.resolve("errors"));
        if (Files.isRegularFile(checkpoint)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(checkpoint)) { p.load(r); }
            identity = p.getProperty("identity", "");
            try { cursor = Long.parseLong(p.getProperty("cursor", "0")); }
            catch (NumberFormatException ignored) { cursor = 0; }
        }
    }

    void append(String destination, String line) {
        batch.computeIfAbsent(destination, k -> new StringBuilder()).append(sanitize.apply(line)).append('\n');
    }

    void poll(int limit) throws IOException {
        if (!Files.isRegularFile(source)) { flush(); return; }
        BasicFileAttributes a = Files.readAttributes(source, BasicFileAttributes.class);
        String currentIdentity = a.creationTime() + ":" + a.fileKey();
        if (!identity.equals(currentIdentity) || a.size() < cursor) {
            cursor = 0;
            identity = currentIdentity;
        }
        long nextCursor = cursor;
        try (RandomAccessFile input = new RandomAccessFile(source.toFile(), "r")) {
            input.seek(cursor);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b, read = 0;
            while (read < limit && (b = input.read()) != -1) {
                read++;
                if (b == '\n') {
                    String text = line.toString(StandardCharsets.UTF_8).replaceFirst("\\r$", "");
                    for (String destination : router.route(text)) append(destination, text);
                    line.reset();
                    nextCursor = input.getFilePointer();
                } else {
                    line.write(b);
                    // A pathological line cannot permanently stall the reader.
                    if (line.size() >= 65536) {
                        String text = line.toString(StandardCharsets.UTF_8) + " [LONG LINE FRAGMENT]";
                        for (String destination : router.route(text)) append(destination, text);
                        line.reset();
                        nextCursor = input.getFilePointer();
                    }
                }
            }
        }
        flush();
        cursor = nextCursor; // Incomplete UTF-8 lines are reread on the next poll.
        Properties p = new Properties();
        p.setProperty("identity", identity);
        p.setProperty("cursor", Long.toString(cursor));
        Path tmp = checkpoint.resolveSibling("cursor.tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) { p.store(w, "Plugnostics local log cursor"); }
        Files.move(tmp, checkpoint, StandardCopyOption.REPLACE_EXISTING);
    }

    void flush() throws IOException {
        Iterator<Map.Entry<String,StringBuilder>> entries = batch.entrySet().iterator();
        while (entries.hasNext()) {
            var e = entries.next();
            Path file = root.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            byte[] data = e.getValue().toString().getBytes(StandardCharsets.UTF_8);
            if (Files.exists(file) && Files.size(file) > 0 && Files.size(file) + data.length > maxSize) {
                Files.deleteIfExists(Path.of(file + "." + archives));
                for (int i = archives - 1; i >= 1; i--) {
                    Path old = Path.of(file + "." + i);
                    if (Files.exists(old)) Files.move(old, Path.of(file + "." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(file, Path.of(file + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            entries.remove();
        }
    }
}
