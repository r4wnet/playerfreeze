package de.rawnet.playerfreeze;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

public final class PlayerFreezePlugin extends JavaPlugin implements Listener {
    private static final String PERMISSION = "playerfreeze.use";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    @SuppressWarnings("deprecation")
    private static final PotionEffectType BLINDNESS_TYPE = PotionEffectType.BLINDNESS;
    private static final PotionEffect BLINDNESS = new PotionEffect(
            BLINDNESS_TYPE,
            Integer.MAX_VALUE,
            0,
            false,
            false,
            true
    );
    private static final Title.Times FROZEN_TITLE_TIMES = Title.Times.times(
            Duration.ZERO,
            Duration.ofHours(24),
            Duration.ZERO
    );

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, FlightState> flightStates = new HashMap<>();
    private PluginSettings settings;
    private PluginMessages messages;

    @Override
    public void onEnable() {
        saveDefaultJson("config.json");
        saveDefaultJson("messages.json");
        settings = loadJson("config.json", PluginSettings.class, PluginSettings.defaults());
        messages = loadJson("messages.json", PluginMessages.class, PluginMessages.defaults());
        loadFrozenPlayers();

        FreezeCommand freezeCommand = new FreezeCommand(true);
        FreezeCommand unfreezeCommand = new FreezeCommand(false);
        registerCommand("freeze", freezeCommand);
        registerCommand("unfreeze", unfreezeCommand);
        registerAdmitCommand();

        Bukkit.getPluginManager().registerEvents(this, this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isFrozen(player)) {
                applyFreezeEffects(player);
            }
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isFrozen(player)) {
                clearFreezeEffects(player);
            }
        }
        saveFrozenPlayers();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isFrozen(player) || event.getTo() == null || !hasChangedPosition(event.getFrom(), event.getTo())) {
            return;
        }

        Location lockedLocation = event.getFrom().clone();
        lockedLocation.setYaw(event.getTo().getYaw());
        lockedLocation.setPitch(event.getTo().getPitch());
        event.setTo(lockedLocation);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isFrozen(player)) {
            applyFreezeEffects(player);
            send(player, messages.stillFrozen);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemPickup(PlayerPickupItemEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player damager = getPlayerDamager(event);
        if (damager != null && isFrozen(damager)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!isFrozen(player)) {
            return;
        }

        runConfiguredCommands(settings.logoutCommands, player);
        unfreezeAfterBan(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        handlePossibleBanCommand(event.getMessage());
    }

    @EventHandler(ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        handlePossibleBanCommand(event.getCommand());
    }

    private void registerCommand(String name, FreezeCommand executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing in plugin.yml.");
            return;
        }

        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerAdmitCommand() {
        PluginCommand command = getCommand("admit");
        if (command == null) {
            getLogger().severe("Command 'admit' is missing in plugin.yml.");
            return;
        }

        command.setExecutor(new AdmitCommand());
    }

    private void freeze(Player target, CommandSender sender) {
        if (!frozenPlayers.add(target.getUniqueId())) {
            send(sender, format(messages.alreadyFrozen, target.getName(), null));
            return;
        }

        applyFreezeEffects(target);
        saveFrozenPlayers();

        send(sender, format(messages.frozenSender, target.getName(), null));
        send(target, messages.frozenTarget);
    }

    private void unfreeze(Player target, CommandSender sender) {
        if (!frozenPlayers.remove(target.getUniqueId())) {
            send(sender, format(messages.notFrozen, target.getName(), null));
            return;
        }

        clearFreezeEffects(target);
        saveFrozenPlayers();

        send(sender, format(messages.unfrozenSender, target.getName(), null));
        send(target, messages.unfrozenTarget);
    }

    private void admit(Player player) {
        if (!isFrozen(player)) {
            send(player, messages.admitOnlyFrozen);
            return;
        }

        runConfiguredCommands(settings.admitCommands, player);
        unfreezeAfterBan(player);
    }

    private void handlePossibleBanCommand(String commandLine) {
        String[] parts = commandLine.strip().split("\\s+");
        if (parts.length < 2 || !isBanCommand(parts[0])) {
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> {
            Player target = Bukkit.getPlayerExact(parts[1]);
            if (target != null) {
                unfreezeAfterBan(target);
            }
        });
    }

    private boolean isBanCommand(String commandName) {
        String normalized = commandName.startsWith("/") ? commandName.substring(1) : commandName;
        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }

        return normalized.equalsIgnoreCase("ban");
    }

    private Player getPlayerDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }

        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }

        return null;
    }

    private void unfreezeAfterBan(Player player) {
        if (!frozenPlayers.remove(player.getUniqueId())) {
            return;
        }

        clearFreezeEffects(player);
        saveFrozenPlayers();
    }

    private void runConfiguredCommands(List<String> commands, Player player) {
        for (String command : commands) {
            String formatted = format(command, player.getName(), null).strip();
            if (formatted.startsWith("/")) {
                formatted = formatted.substring(1);
            }

            if (!formatted.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
            }
        }
    }

    private void applyFreezeEffects(Player player) {
        storeFlightState(player);
        player.setAllowFlight(true);
        if (!player.isOnGround()) {
            player.setFlying(true);
        }

        if (settings.blindnessEnabled) {
            player.addPotionEffect(BLINDNESS);
        } else {
            player.removePotionEffect(BLINDNESS_TYPE);
        }

        player.setVelocity(player.getVelocity().zero());
        if (!settings.titleEnabled) {
            player.clearTitle();
            return;
        }

        player.showTitle(Title.title(
                LEGACY.deserialize(messages.freezeTitle),
                LEGACY.deserialize(messages.freezeSubtitle),
                FROZEN_TITLE_TIMES
        ));
    }

    private void clearFreezeEffects(Player player) {
        player.removePotionEffect(BLINDNESS_TYPE);
        player.clearTitle();
        restoreFlightState(player);
    }

    private void storeFlightState(Player player) {
        flightStates.putIfAbsent(player.getUniqueId(), new FlightState(player.getAllowFlight(), player.isFlying()));
    }

    private void restoreFlightState(Player player) {
        FlightState state = flightStates.remove(player.getUniqueId());
        if (state == null) {
            boolean shouldAllowFlight = player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
            player.setFlying(false);
            player.setAllowFlight(shouldAllowFlight);
            return;
        }

        if (!state.allowFlight) {
            player.setFlying(false);
        } else {
            player.setAllowFlight(true);
            player.setFlying(state.flying);
        }
        player.setAllowFlight(state.allowFlight);
    }

    private boolean isFrozen(Player player) {
        return frozenPlayers.contains(player.getUniqueId());
    }

    private boolean hasChangedPosition(Location from, Location to) {
        return from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ();
    }

    private void loadFrozenPlayers() {
        frozenPlayers.clear();

        FrozenPlayersData data = loadJson("frozen-players.json", FrozenPlayersData.class, new FrozenPlayersData());
        for (String value : data.frozenPlayers) {
            try {
                frozenPlayers.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid UUID in frozen-players.json: " + value);
            }
        }
    }

    private void saveFrozenPlayers() {
        FrozenPlayersData data = new FrozenPlayersData();
        data.frozenPlayers = frozenPlayers.stream()
                .map(UUID::toString)
                .sorted()
                .toList();

        saveJson("frozen-players.json", data);
    }

    private void send(CommandSender receiver, String message) {
        String prefix = messages.prefix == null ? "" : messages.prefix;
        String formatted = prefix.isBlank() ? message : prefix + " " + message;
        receiver.sendMessage(LEGACY.deserialize(formatted));
    }

    private String format(String text, String playerName, String commandLabel) {
        String formatted = text == null ? "" : text;
        if (playerName != null) {
            formatted = formatted
                    .replace("[User]", playerName)
                    .replace("[user]", playerName)
                    .replace("[Player]", playerName)
                    .replace("[player]", playerName)
                    .replace("{user}", playerName)
                    .replace("{player}", playerName)
                    .replace("%user%", playerName)
                    .replace("%player%", playerName);
        }
        if (commandLabel != null) {
            formatted = formatted
                    .replace("[Command]", commandLabel)
                    .replace("[command]", commandLabel)
                    .replace("{command}", commandLabel)
                    .replace("%command%", commandLabel);
        }
        return formatted;
    }

    private void saveDefaultJson(String fileName) {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data folder.");
            return;
        }

        Path target = getDataFolder().toPath().resolve(fileName);
        if (Files.exists(target)) {
            return;
        }

        saveResource(fileName, false);
    }

    private <T> T loadJson(String fileName, Class<T> type, T fallback) {
        Path path = getDataFolder().toPath().resolve(fileName);
        if (!Files.exists(path)) {
            saveJson(fileName, fallback);
            return fallback;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T loaded = GSON.fromJson(reader, type);
            return loaded == null ? fallback : loaded;
        } catch (IOException | JsonSyntaxException exception) {
            getLogger().warning("Could not load " + fileName + ": " + exception.getMessage());
            return fallback;
        }
    }

    private void saveJson(String fileName, Object value) {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create plugin data folder.");
            return;
        }

        Path path = getDataFolder().toPath().resolve(fileName);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        } catch (IOException exception) {
            getLogger().warning("Could not save " + fileName + ": " + exception.getMessage());
        }
    }

    private final class FreezeCommand implements CommandExecutor, TabCompleter {
        private final boolean freeze;

        private FreezeCommand(boolean freeze) {
            this.freeze = freeze;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission(PERMISSION)) {
                send(sender, messages.noPermission);
                return true;
            }

            if (args.length != 1) {
                send(sender, format(messages.usagePlayer, null, label));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                send(sender, format(messages.playerNotFound, args[0], null));
                return true;
            }

            if (freeze) {
                PlayerFreezePlugin.this.freeze(target, sender);
            } else {
                PlayerFreezePlugin.this.unfreeze(target, sender);
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!sender.hasPermission(PERMISSION) || args.length != 1) {
                return List.of();
            }

            String prefix = args[0].toLowerCase();
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(prefix)) {
                    names.add(player.getName());
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
    }

    private final class AdmitCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                send(sender, messages.onlyPlayers);
                return true;
            }

            if (args.length != 0) {
                send(sender, format(messages.usageAdmit, null, label));
                return true;
            }

            admit(player);
            return true;
        }
    }

    private static final class PluginSettings {
        private boolean blindnessEnabled = true;
        private boolean titleEnabled = true;
        private List<String> admitCommands = List.of("ban [User] 14d Cheating - Admitted");
        private List<String> logoutCommands = List.of("ban [User] 30d Logged out whilst frozen");

        private static PluginSettings defaults() {
            return new PluginSettings();
        }
    }

    private static final class PluginMessages {
        private String prefix = "";
        private String noPermission = "&cYou do not have permission to use this command.";
        private String usagePlayer = "&cUsage: /[Command] <player>";
        private String usageAdmit = "&cUsage: /[Command]";
        private String playerNotFound = "&cPlayer '&f[User]&c' was not found.";
        private String alreadyFrozen = "&e[User] is already frozen.";
        private String frozenSender = "&a[User] has been frozen.";
        private String frozenTarget = "&#ff0000&lYou have been frozen.";
        private String notFrozen = "&e[User] is not frozen.";
        private String unfrozenSender = "&a[User] has been unfrozen.";
        private String unfrozenTarget = "&aYou have been unfrozen.";
        private String stillFrozen = "&cYou are still frozen.";
        private String admitOnlyFrozen = "&cYou can only use this command while frozen.";
        private String onlyPlayers = "&cOnly players can use this command.";
        private String freezeTitle = "&#ff0000&lYou are frozen!";
        private String freezeSubtitle = "&fLogging out will result in a ban";

        private static PluginMessages defaults() {
            return new PluginMessages();
        }
    }

    private static final class FrozenPlayersData {
        private List<String> frozenPlayers = List.of();
    }

    private record FlightState(boolean allowFlight, boolean flying) {
    }
}
