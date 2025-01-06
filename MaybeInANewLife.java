package com.avery.maybeinanewlife;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.CommandSender;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.time.Duration;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
public class MaybeInANewLife extends JavaPlugin implements Listener {
    private Map<String, Integer> warnings;
    private FileConfiguration config;
    private boolean enableWarnings;
    private int maxWarnings;
    private boolean enableEffects;
    private boolean enableLogging;
    private File logFile;
    private HttpClient httpClient;
    private Cache<String, Boolean> contentCache;
    private final Map<String, Long> lastCheckTime = new ConcurrentHashMap<>();
    private final long CHECK_INTERVAL = 100; // milliseconds between API calls
    @Override
    public void onEnable() {
        warnings = new HashMap<>();
        setupConfig();
        setupHttpClient();
        setupCache();
        getServer().getPluginManager().registerEvents(this, this);
        setupCommands();
        displayStartupMessage();
        startAutoSave();
    }
    private void setupHttpClient() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
    private void setupCache() {
        contentCache = CacheBuilder.newBuilder()
                .maximumSize(config.getInt("cache.max-size", 1000))
                .expireAfterWrite(java.time.Duration.ofSeconds(config.getInt("cache.expire-after-write", 3600)))
                .build();
    }
    private void setupConfig() {
        saveDefaultConfig();
        config = getConfig();
        enableWarnings = config.getBoolean("settings.enable-warnings", true);
        maxWarnings = config.getInt("settings.max-warnings", 3);
        enableEffects = config.getBoolean("effects.enabled", true);
        enableLogging = config.getBoolean("logging.enabled", true);
        if (enableLogging) {
            setupLogging();
        }
    }
    private void setupLogging() {
        logFile = new File(getDataFolder(), "violations.log");
        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create log file!");
            }
        }
    }
    private void setupCommands() {
        Objects.requireNonNull(getCommand("minl")).setExecutor((sender,
command, label, args) -> {
            if (sender.hasPermission("maybeinanewlife.admin")) {
                if (args.length > 0) {
                    switch (args[0].toLowerCase()) {
                        case "reload":
                            reloadConfiguration();
                            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded!");
                            break;
                        case "stats":
                            showStats(sender);
                            break;
                    }
                }
            }
            return true;
        });
    }
    private CompletableFuture<Boolean> checkContentAsync(String text) {
        // Check cache first
        Boolean cachedResult = contentCache.getIfPresent(text);
        if (cachedResult != null) {
            return CompletableFuture.completedFuture(cachedResult);
        }
        // Rate limiting check
        String normalizedText = text.toLowerCase();
        long currentTime = System.currentTimeMillis();
        Long lastCheck = lastCheckTime.get(normalizedText);
        if (lastCheck != null && currentTime - lastCheck < CHECK_INTERVAL) {
            return CompletableFuture.completedFuture(false);
        }
        lastCheckTime.put(normalizedText, currentTime);
        // Make API request
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = config.getString("api.purgomalum.endpoint",
                        "https://www.purgomalum.com/service/containsprofanity") +
                        "?text=" + java.net.URLEncoder.encode(text, "UTF-8");
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofMillis(config.getInt("api.purgomalum.timeout", 5000)))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    boolean result = Boolean.parseBoolean(response.body());
                    contentCache.put(text, result);
                    return result;
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error checking content", e);
            }
            return false;
        });
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        String message = event.getMessage();
        // Check message content
        checkContentAsync(message).thenAccept(inappropriate -> {
            if (inappropriate) {
                event.setCancelled(true);
                handleViolation(player, message);
            }
        });
    }
    private void handleViolation(Player player, String message) {
        // Log violation
        if (enableLogging) {
            logViolation(player, message);
        }
        // Handle warnings and consequences
        new BukkitRunnable() {
            @Override
            public void run() {
                if (enableWarnings) {
                    int currentWarnings = warnings.getOrDefault(player.getName(), 0) + 1;
                    warnings.put(player.getName(), currentWarnings);
                    if (currentWarnings >= maxWarnings) {
                        executeConsequences(player);
                    } else {
                        sendWarning(player, currentWarnings);
                    }
                } else {
                    executeConsequences(player);
                }
            }
        }.runTask(this);
    }
    private void executeConsequences(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Kill player
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "kill
" + player.getName());
                // Visual and sound effects if enabled
                if (enableEffects) {
                    Location loc = player.getLocation();
                    // Lightning effects
                    if (config.getBoolean("effects.lightning.enabled", true)) {
                        int strikes = config.getInt("effects.lightning.strike-count", 3);
                        for (int i = 0; i < strikes; i++) {
                            player.getWorld().strikeLightningEffect(loc);
                        }
                    }
                    // Particle effects
                    if (config.getBoolean("effects.particles.enabled", true)) {
                        player.getWorld().spawnParticle(
                                Particle.EXPLOSION_LARGE,
                                loc,
                                config.getInt("effects.particles.count", 1)
                        );
                    }
                    // Sound effects
                    if (config.getBoolean("effects.sounds.enabled", true)) {
                        player.getWorld().playSound(
                                loc,
                                Sound.valueOf(config.getString("effects.sounds.sound", "ENTITY_GENERIC_EXPLODE")),
                                (float) config.getDouble("effects.sounds.volume", 1.0),
                                (float) config.getDouble("effects.sounds.pitch", 1.0)
                        );
                    }
                }
                // Broadcast
                String removalMessage = config.getString("messages.removal")
                        .replace("%player%", player.getName());
                String learnMessage = config.getString("messages.learn");
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', removalMessage));
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', learnMessage));
            }
        }.runTask(this);
    }
    private void sendWarning(Player player, int currentWarnings) {
        String warning = config.getString("messages.warning")
                .replace("%prefix%", config.getString("messages.prefix"))
                .replace("%current%", String.valueOf(currentWarnings))
                .replace("%max%", String.valueOf(maxWarnings));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', warning));
    }
    private void logViolation(Player player, String message) {
        if (!enableLogging) return;
        try (FileWriter fw = new FileWriter(logFile, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            String logFormat = config.getString("logging.format")
                    .replace("%date%", new Date().toString())
                    .replace("%type%", "VIOLATION")
                    .replace("%player%", player.getName())
                    .replace("%message%", message);
            out.println(logFormat);
        } catch (IOException e) {
            getLogger().severe("Could not log violation: " + e.getMessage());
        }
    }
    private void startAutoSave() {
        new BukkitRunnable() {
            @Override
            public void run() {
                saveWarnings();
            }
        }.runTaskTimerAsynchronously(this, 6000L, 6000L);
    }
    private void displayStartupMessage() {
        String[] startupMessage = {
                "********************",
                "",
                "Maybe In a New Life...",
                "A life, where no one is bad,",
                "Where people can be who they want,",
                "Where there is peace,",
                "And where people can be happy for each other.",
                "",
                "--- Loading \"Maybe In a New Life\"",
                "--- Version 1.2",
                "--- Made by Avery (@cgcristi on discord)",
                "--- Remember to be good..",
                "",
                "********************"
        };
        for (String line : startupMessage) {
            getServer().getConsoleSender().sendMessage(ChatColor.GREEN + line);
        }
    }
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (enableWarnings && warnings.containsKey(player.getName())) {
            int playerWarnings = warnings.get(player.getName());
            if (playerWarnings > 0) {
                String warning = ChatColor.YELLOW + "» You have " + playerWarnings +
                        "/" + maxWarnings + " warnings for inappropriate behavior.";
                player.sendMessage(warning);
            }
        }
    }
    @Override
    public void onDisable() {
        saveWarnings();
        getServer().getConsoleSender().sendMessage(ChatColor.RED + "Maybe In a New Life plugin disabled.");
    }
    private void saveWarnings() {
        File warningsFile = new File(getDataFolder(), "warnings.yml");
        YamlConfiguration warningsConfig = new YamlConfiguration();
        warnings.forEach((player, count) -> warningsConfig.set(player, count));
        try {
            warningsConfig.save(warningsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save warnings: " + e.getMessage());
        }
    }
    private void reloadConfiguration() {
        reloadConfig();
        config = getConfig();
        setupCache();
    }
    private void showStats(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "Plugin Statistics:");
        sender.sendMessage(ChatColor.YELLOW + "Players with warnings: "
+ warnings.size());
        sender.sendMessage(ChatColor.YELLOW + "Cache size: " + contentCache.size());
    }
}
