package dev.plugnostics;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import java.util.logging.*;

public final class PlugnosticsHub extends JavaPlugin {
    private volatile boolean running;
    private volatile String activeToken = "";
    private Path tokenPath() throws IOException {
        Path root = getDataFolder().toPath().toAbsolutePath().normalize();
        Path path = root.resolve(getConfig().getString("github.token-file", "github-token.txt")).normalize();
        if (!path.startsWith(root) || path.equals(root) || Files.isSymbolicLink(path))
            throw new IOException("Caminho do token invalido");
        return path;
    }
    private void prepareTokenFile() {
        try {
            Path path = tokenPath();
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
                try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                catch (UnsupportedOperationException ignored) {}
                getLogger().info("Preencha github-token.txt com o token GitHub (uma linha).");
            }
            readToken();
        } catch (IOException ex) { getLogger().warning("Nao foi possivel preparar o arquivo de token."); }
    }
    private String readToken() {
        String token = "";
        try {
            Path path = tokenPath();
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(path) > 4096) throw new IOException("Arquivo muito grande");
                token = Files.readString(path, StandardCharsets.UTF_8).strip();
            }
            if (token.isBlank()) {
                String env = System.getenv(getConfig().getString("github.token-env", "PLUGNOSTICS_GITHUB_TOKEN"));
                token = env == null ? "" : env.strip();
            }
            if (token.chars().anyMatch(Character::isWhitespace)) token = "";
        } catch (IOException | IllegalArgumentException ex) {
            getLogger().warning("Falha ao ler credencial GitHub; confira github.token-file.");
        }
        activeToken = token;
        return token;
    }
    private long cursor;
    private final List<String> pending = new ArrayList<>();
    private final Object lock = new Object();
    private Path logPath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private static final Pattern SECRET = Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?|(?:token|password|passwd|secret|api[_-]?key|access[_-]?key)\\s*[:=]\\s*)[^\\s,;]+" );
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    private static final Pattern PLUGIN = Pattern.compile("\\[([^]\\r\\n]{1,64})\\]");

    @Override public void onEnable() {
        saveDefaultConfig();
        prepareTokenFile();
        running = true;
        logPath = Paths.get("logs", "latest.log");
        try { if (Files.exists(logPath)) cursor = Files.size(logPath); } catch(IOException ignored) {}
        int minutes = Math.max(5, getConfig().getInt("reports.interval-minutes", 30));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::collectAndReport, 20L*60, minutes*60L*20);
        getLogger().info("Plugnostics-Hub 0.1.1 ativo; GitHub: " + getConfig().getBoolean("github.enabled", false));
    }
    @Override public void onDisable() { running = false; }
    private void collectAndReport() {
        if (!running) return;
        try {
            readToken();
            readIncrement();
            List<String> lines;
            synchronized(lock) { lines = new ArrayList<>(pending); pending.clear(); }
            String report = createReport(lines);
            Path out = getDataFolder().toPath().resolve("reports");
            Files.createDirectories(out);
            String name = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now()) + ".json";
            Path file = out.resolve(name);
            Files.writeString(file, report, StandardCharsets.UTF_8);
            if (getConfig().getBoolean("github.enabled", false)) {
                if (!publish(name, report)) getLogger().warning("Falha no envio; relatorio mantido localmente: " + file);
            }
        } catch(Exception ex) { getLogger().warning("Diagnostico falhou: " + ex.getClass().getSimpleName() + ": " + ex.getMessage()); }
    }
    private void readIncrement() throws IOException {
        if (!Files.isRegularFile(logPath)) return;
        long size = Files.size(logPath);
        if (size < cursor) cursor = 0;
        long maxBytes = Math.max(65536, getConfig().getInt("collection.max-bytes-per-cycle", 1048576));
        if (size - cursor > maxBytes) { cursor = size - maxBytes; getLogger().warning("Janela de log excedida; inicio truncado para limitar uso de memoria."); }
        try (RandomAccessFile f = new RandomAccessFile(logPath.toFile(), "r")) {
            f.seek(cursor);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            while ((n = f.read(buffer)) != -1) bytes.write(buffer,0,n);
            cursor = f.getFilePointer();
            String chunk = bytes.toString(StandardCharsets.UTF_8);
            synchronized(lock) {
                for (String line : chunk.split("\\R")) {
                    if (line.isBlank()) continue;
                    if (pending.size() >= 2500) pending.remove(0);
                    pending.add(sanitize(line));
                }
            }
        }
    }
    private String sanitize(String line) {
        line = ANSI.matcher(line).replaceAll("");
        if (!activeToken.isBlank()) line = line.replace(activeToken, "[REDACTED]");
        line = line.replaceAll("(?:github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]+)", "[REDACTED]");
        line = SECRET.matcher(line).replaceAll("$1[REDACTED]");
        if (getConfig().getBoolean("privacy.redact-player-ips", true)) line = IPV4.matcher(line).replaceAll("[IP]");
        if (getConfig().getBoolean("privacy.redact-uuids", true)) line = UUID.matcher(line).replaceAll("[UUID]");
        return line.length() > 2000 ? line.substring(0,2000) + " [TRUNCATED]" : line;
    }
    private String createReport(List<String> lines) {
        Map<String,List<String>> byPlugin = new TreeMap<>();
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("exception") || lower.contains("severe")) errors.add(line);
            else if (lower.contains("warn")) warnings.add(line);
            Matcher m = PLUGIN.matcher(line);
            String category = "Unknown";
            while (m.find()) {
                String match = m.group(1);
                for (Plugin plugin : Bukkit.getServer().getPluginManager().getPlugins()) {
                    if (plugin.getName().equalsIgnoreCase(match)) { category = plugin.getName(); break; }
                }
                if (!category.equals("Unknown")) break;
            }
            byPlugin.computeIfAbsent(category, k -> new ArrayList<>()).add(line);
        }
        StringBuilder b = new StringBuilder("{");
        field(b,"schema","plugnostics-0.1"); b.append(','); field(b,"createdUtc",Instant.now().toString()); b.append(',');
        field(b,"serverVersion",Bukkit.getVersion()); b.append(',');
        b.append("\"onlinePlayers\":").append(Bukkit.getServer().getOnlinePlayers().size()).append(',');
        b.append("\"plugins\":["); boolean first=true;
        for (Plugin p : Bukkit.getServer().getPluginManager().getPlugins()) {
            if(!first)b.append(','); first=false;
            b.append('{'); field(b,"name",p.getName()); b.append(',');field(b,"version",p.getDescription().getVersion());b.append('}');
        }
        b.append("],"); array(b,"errors",errors);b.append(',');array(b,"warnings",warnings);b.append(',');
        b.append("\"pluginLogs\":{"); first=true;
        for (var e : byPlugin.entrySet()) {if(!first)b.append(',');first=false;b.append('"').append(esc(e.getKey())).append("\":"); stringArray(b,e.getValue());}
        b.append("}}"); return b.toString();
    }
    private boolean publish(String name,String report) {
        String repo = getConfig().getString("github.repository", "");
        String branch = getConfig().getString("github.branch", "main");
        String prefix = getConfig().getString("github.path", "reports").replaceAll("^/+|/+$", "");
        String token = readToken();
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || token == null || token.isBlank()) {
            getLogger().warning("GitHub nao configurado: repository ou token ausente; preencha github-token.txt ou a variavel de ambiente.");return false;
        }
        try {
            String path = prefix.isEmpty()? name : prefix + "/" + name;
            String encoded = Arrays.stream(path.split("/")).map(s -> java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)).reduce((a,b)->a+"/"+b).orElse(name);
            String body = "{\"message\":\"Plugnostics diagnostic report\",\"branch\":\""+esc(branch)+"\",\"content\":\""+Base64.getEncoder().encodeToString(report.getBytes(StandardCharsets.UTF_8))+"\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/"+repo+"/contents/"+encoded))
                .timeout(java.time.Duration.ofSeconds(25)).header("Authorization","Bearer "+token)
                .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req,HttpResponse.BodyHandlers.ofString());
            if (res.statusCode()==201) { getLogger().info("GitHub: autenticacao e envio confirmados."); return true; }
            getLogger().warning("GitHub HTTP " + res.statusCode());return false;
        } catch(Exception ex) {getLogger().warning("GitHub: "+ex.getClass().getSimpleName());return false;}
    }
    private static void field(StringBuilder b,String k,String v){b.append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"');}
    private static void array(StringBuilder b,String k,List<String> v){b.append('"').append(k).append("\":");stringArray(b,v);}
    private static void stringArray(StringBuilder b,List<String> v){b.append('[');boolean first=true;for(String s:v){if(!first)b.append(',');first=false;b.append('"').append(esc(s)).append('"');}b.append(']');}
    private static String esc(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
}
