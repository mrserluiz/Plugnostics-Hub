package dev.plugnostics;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class PlugnosticsHub extends JavaPlugin implements Listener {
    private final ArrayBlockingQueue<String> users = new ArrayBlockingQueue<>(10000);
    private final AtomicLong dropped = new AtomicLong();
    private ScheduledExecutorService worker;
    private LocalLogStore store;
    private volatile boolean accepting;
    private boolean chat, commands, hideIps, hideUuids;
    private long lastWarning;
    private static final Pattern SECRET = Pattern.compile("(?i)((?:password|passwd|token|secret|api[_-]?key|authorization)\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+");
    private static final Pattern TOKEN = Pattern.compile("(?:github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]+)");
    private static final Pattern IP = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    @Override public void onEnable() {
        saveDefaultConfig();
        // Migration: even existing configurations cannot trigger uploads in this build.
        getConfig().set("github.enabled", false);
        saveConfig();
        chat = getConfig().getBoolean("players.chat", true);
        commands = getConfig().getBoolean("players.commands", true);
        hideIps = getConfig().getBoolean("privacy.redact-player-ips", true);
        hideUuids = getConfig().getBoolean("privacy.redact-uuids", true);
        Map<String,String> names = new HashMap<>(), packages = new HashMap<>();
        for (Plugin plugin : getServer().getPluginManager().getPlugins()) {
            String file = plugin.getName().replaceAll("[^A-Za-z0-9_.-]", "_");
            names.put(plugin.getName().toLowerCase(Locale.ROOT), file);
            names.put(plugin.getLogger().getName().toLowerCase(Locale.ROOT), file);
            packages.put(plugin.getClass().getPackageName(), file);
        }
        try {
            store = new LocalLogStore(getDataFolder().toPath().resolve("logs"), Paths.get("logs/latest.log"),
                new LogRouter(names, packages), this::sanitize,
                Math.max(1, getConfig().getInt("local.max-file-mb", 10)) * 1024L * 1024L,
                Math.max(1, Math.min(100, getConfig().getInt("local.archives", 5))));
        } catch (Exception e) {
            getLogger().severe("Nao foi possivel abrir logs locais: " + e.getClass().getSimpleName());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        accepting = true;
        getServer().getPluginManager().registerEvents(this, this);
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Plugnostics-local-logs"); thread.setDaemon(true); return thread;
        });
        worker.scheduleWithFixedDelay(this::drain, 0, 1, TimeUnit.SECONDS);
        getLogger().info("Plugnostics-Hub 0.2.1: logs locais ativos; envio externo desativado.");
    }

    private void drain() {
        try {
            String line;
            while ((line = users.poll()) != null) store.append("users.log", line);
            long lost = dropped.getAndSet(0);
            if (lost > 0) store.append("users.log", stamp() + " [OVERFLOW] " + lost + " eventos descartados.");
            store.poll(1048576);
        } catch (Exception e) {
            // Rate limit to avoid recursively flooding the source log on disk errors.
            if (System.currentTimeMillis() - lastWarning > 60000) {
                lastWarning = System.currentTimeMillis();
                getLogger().warning("Falha na gravacao local: " + e.getClass().getSimpleName());
            }
        }
    }

    @Override public void onDisable() {
        accepting = false;
        if (worker != null) {
            worker.execute(this::drain);
            worker.shutdown();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); worker.shutdownNow(); }
        }
    }

    private static String stamp() { return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()); }
    private void user(String kind, String name, String text) {
        if (!accepting) return;
        String line = stamp() + " [" + kind + "] " + name + (text.isBlank() ? "" : " | " + text);
        if (!users.offer(line.replace("\r", "\\r").replace("\n", "\\n"))) dropped.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void login(AsyncPlayerPreLoginEvent e) {
        user("LOGIN_ATTEMPT", e.getName(), e.getLoginResult().name());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void loginResult(PlayerLoginEvent e) {
        user("LOGIN_RESULT", e.getPlayer().getName(), e.getResult().name());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) { user("JOIN", e.getPlayer().getName(), ""); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) { user("QUIT", e.getPlayer().getName(), ""); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void chat(AsyncChatEvent e) {
        if (chat) user(e.isCancelled() ? "CHAT_CANCELLED" : "CHAT", e.getPlayer().getName(),
            PlainTextComponentSerializer.plainText().serialize(e.message()));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void command(PlayerCommandPreprocessEvent e) {
        if (!commands) return;
        // All arguments are excluded, including /login, /register and private messages.
        String label = e.getMessage().split("\\s+", 2)[0];
        user(e.isCancelled() ? "COMMAND_CANCELLED" : "COMMAND", e.getPlayer().getName(), label + " [argumentos omitidos]");
    }

    private String sanitize(String text) {
        text = text.replaceAll("\\x1B\\[[;\\d]*m", "").replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        // The console source can contain full player commands; remove every argument there too.
        text = text.replaceAll("(?i)(issued server command: /\\S+).*", "$1 [argumentos omitidos]");
        text = TOKEN.matcher(text).replaceAll("[REDACTED]");
        text = SECRET.matcher(text).replaceAll("$1[REDACTED]");
        if (hideIps) text = IP.matcher(text).replaceAll("[IP]");
        if (hideUuids) text = UUID.matcher(text).replaceAll("[UUID]");
        return text;
    }
}
