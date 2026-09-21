package com.kalyan.hookah;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class KalyanHookahPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<Integer, Component> smokeSprites = new ConcurrentHashMap<>();
    private final Map<UUID, SmokeState> smokeStates = new ConcurrentHashMap<>();
    private long lookAwaySmokeDelayMs;
    private int smokeParticlesPerTick;
    private double smokeVelocity;
    private double smokeSpread;
    private int ringLifetimeTicks;
    private double ringSpeed;
    private double ringGravityUp;
    private double ringGravityDown;
    private double ringRotationSpeed;
    private int ringOpacityDecrement;
    private double ringScaleIncrement;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            loadSmokeSprites();
        } catch (IllegalArgumentException exception) {
            getLogger().severe("Configuration error: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("hookah").setExecutor(this);
        getCommand("hookah").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(Component.text("Using: /" + label + " <reload|status>"));
            return true;
        }
        if (args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }
        if (!args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(Component.text("Using: /" + label + " reload|status"));
            return true;
        }
        reloadConfig();
        try {
            loadSmokeSprites();
        } catch (IllegalArgumentException exception) {
            getLogger().severe("Configuration reload failed: " + exception.getMessage());
            sender.sendMessage(Component.text("Configuration reload failed: " + exception.getMessage()));
            return true;
        }
        sender.sendMessage(Component.text("Hookah configuration reloaded."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase();
        return List.of("reload", "status").stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("Hookah status: enabled"));
        sender.sendMessage(Component.text("Display stages: " + smokeSprites.size() + "/4"));
        sender.sendMessage(Component.text("Active smokers: " + smokeStates.size()));
        sender.sendMessage(Component.text("Smoke: particles=" + smokeParticlesPerTick
                + ", velocity=" + smokeVelocity + ", spread=" + smokeSpread));
        sender.sendMessage(Component.text("Ring: lifetime=" + ringLifetimeTicks
                + " ticks, speed=" + ringSpeed + ", scale=" + ringScaleIncrement));
    }

    @Override
    public void onDisable() {
        smokeStates.values().forEach(state -> {
            if (state.trailTask != null) state.trailTask.cancel();
        });
        smokeStates.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.BREWING_STAND
                || !event.getPlayer().isSneaking() || !isEmptyHand(event.getItem())) return;

        Player player = event.getPlayer();
        event.setCancelled(true);
        SmokeState state = smokeStates.computeIfAbsent(player.getUniqueId(), ignored -> new SmokeState());
        if (System.currentTimeMillis() - state.lastSmokeAt > 5000) state.smoking = 15;
        if (state.smoking < 35) state.smoking++;
        else player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 40, 0));
        state.lastSmokeAt = System.currentTimeMillis();
        applyRandomHookahEffect(player, (BrewingStand) event.getClickedBlock().getState());
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_VILLAGER_WORK_CLERIC, 0.16f, 0.1f);
        startSmokeTrail(player, state, event.getClickedBlock());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player player = event.getPlayer();
        SmokeState state = smokeStates.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (state != null && now - state.lastLeftClickAt < 400) return;
        if (!isEmptyHand(player.getInventory().getItemInMainHand())) return;
        Block clickedBlock = player.getTargetBlockExact(5);
        if (clickedBlock != null && clickedBlock.getType() == Material.BREWING_STAND) return;
        if (state == null) {
            return;
        }
        if (state.smoking < 3) {
            return;
        }
        if (System.currentTimeMillis() < state.handLockedUntil) {
            return;
        }
        state.lastLeftClickAt = now;
        state.handLockedUntil = System.currentTimeMillis() + 700;
        state.smoking -= 3;
        Location eye = player.getEyeLocation();
        Location origin = eye.clone().add(eye.getDirection().multiply(0.45)).add(0, -0.35, 0);
        spawnSmokeRing(player, origin, false);
        spawnSmokeRing(player, origin, true);
    }

    private boolean isEmptyHand(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private void applyRandomHookahEffect(Player player, BrewingStand stand) {
        List<PotionEffect> possibleEffects = new ArrayList<>();
        for (ItemStack item : stand.getInventory().getContents()) {
            if (item == null || !(item.getItemMeta() instanceof PotionMeta potionMeta)) continue;
            if (potionMeta.getBasePotionType() != null) {
                potionMeta.getBasePotionType().getPotionEffects().stream()
                        .filter(effect -> !effect.getType().isInstant())
                        .forEach(possibleEffects::add);
            }
            if (!potionMeta.getCustomEffects().isEmpty()) {
                PotionEffect customEffect = potionMeta.getCustomEffects().get(0);
                if (!customEffect.getType().isInstant()) possibleEffects.add(customEffect);
            }
        }
        if (possibleEffects.isEmpty()) return;
        PotionEffect selected = possibleEffects.get(ThreadLocalRandom.current().nextInt(possibleEffects.size()));
        player.addPotionEffect(new PotionEffect(selected.getType(), 60, selected.getAmplifier(),
                selected.isAmbient(), selected.hasParticles(), selected.hasIcon()));
    }

    private void startSmokeTrail(Player player, SmokeState state, Block stand) {
        if (state.trailTask != null) state.trailTask.cancel();
        state.trailTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || state.smoking + 5 <= 0) {
                    cancel();
                    state.trailTask = null;
                    return;
                }
                long now = System.currentTimeMillis();
                if (isLookingAt(player, stand)) {
                    state.lookAwayAt = 0;
                    return;
                }
                if (state.handLockedUntil > now) {
                    return;
                }
                if (state.lookAwayAt == 0) state.lookAwayAt = now + lookAwaySmokeDelayMs;
                if (now < state.lookAwayAt) return;
                Location smoke = player.getEyeLocation().clone().add(0, -0.35, 0)
                        .add(player.getEyeLocation().getDirection().multiply(0.7));
                spawnSmokePuff(player, smoke);
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 0.05f, 0.5f);
                state.smoking--;
                if (state.smoking + 5 <= 0) {
                    cancel();
                    state.trailTask = null;
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void spawnSmokePuff(Player player, Location origin) {
        Vector velocity = player.getEyeLocation().getDirection().normalize();
        velocity.add(new Vector(
            ThreadLocalRandom.current().nextDouble(-smokeSpread, smokeSpread),
            ThreadLocalRandom.current().nextDouble(-smokeSpread, smokeSpread),
            ThreadLocalRandom.current().nextDouble(-smokeSpread, smokeSpread)
        )).normalize().multiply(smokeVelocity);
        for (int particle = 0; particle < smokeParticlesPerTick; particle++) {
            player.getWorld().spawnParticle(Particle.POOF, origin, 0,
                velocity.getX(), velocity.getY(), velocity.getZ(), 0.32);
        }
    }

    private boolean isLookingAt(Player player, Block stand) {
        Block target = player.getTargetBlockExact(5);
        return target != null && target.equals(stand);
    }

    private void spawnSmokeRing(Player player, Location origin, boolean reverse) {
        Location eye = player.getEyeLocation();
        float yaw = eye.getYaw() + (reverse ? 180.0f : 0.0f);
        float pitch = reverse ? -eye.getPitch() : eye.getPitch();
        TextDisplay display = spawnRingDisplay(origin, yaw, pitch);
        new SmokeRingTask(display, player, reverse).runTaskTimer(this, 0L, 2L);
    }

    private TextDisplay spawnRingDisplay(Location origin, float yaw, float pitch) {
        return origin.getWorld().spawn(origin, TextDisplay.class, entity -> {
            entity.text(smokeSprite(2));
            entity.setTextOpacity((byte) 210);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setSeeThrough(true);
            entity.setShadowed(false);
            entity.setViewRange(64.0f);
            entity.setRotation(yaw, pitch);
                entity.setTransformation(new Transformation(new Vector3f(0, -0.2f, 0), new Quaternionf(),
                    new Vector3f(2, 2, 2), new Quaternionf()));
            entity.setInterpolationDuration(1);
        });
    }

    private final class SmokeRingTask extends BukkitRunnable {
        private final TextDisplay display;
        private final Player player;
        private final Vector direction;
        private double step;
        private final double gravity;
        private final double rotationScale;
        private double rotation;
        private int sneeze = 31;
        private int fly;

        private SmokeRingTask(TextDisplay display, Player player, boolean reverse) {
            this.display = display;
            this.player = player;
            this.sneeze = ringLifetimeTicks;
                this.direction = player.getEyeLocation().getDirection().normalize()
                    .multiply(reverse ? -1 : 1);
            this.step = reverse ? -ringSpeed : ringSpeed;
            this.gravity = player.getLocation().getPitch() <= 0 ? ringGravityUp : ringGravityDown;
            this.rotationScale = ringRotationSpeed;
        }

        @Override
        public void run() {
            if (!display.isValid() || !player.isOnline() || sneeze <= 0) {
                display.remove();
                cancel();
                return;
            }
            Location nextLocation = display.getLocation().add(direction.clone().multiply(step))
                    .add(0, gravity, 0);
            display.teleport(nextLocation);
            fly++;
            if (fly == 4) display.text(smokeSprite(3));
            if (fly == 7) display.text(smokeSprite(4));
            if (fly == 10) display.text(smokeSprite(5));
                rotation += rotationScale;
            Transformation transformation = display.getTransformation();
            Vector3f scale = transformation.getScale();
            display.setTransformation(new Transformation(transformation.getTranslation(), transformation.getLeftRotation(),
                    new Vector3f(scale.x + (float) ringScaleIncrement, scale.y + (float) ringScaleIncrement,
                        scale.z + (float) ringScaleIncrement),
                    new Quaternionf().rotateZ((float) rotation)));
            int opacity = Byte.toUnsignedInt(display.getTextOpacity());
                display.setTextOpacity((byte) Math.max(0, opacity - ringOpacityDecrement));
                step += step < 0 ? ringSpeed / ringLifetimeTicks : -ringSpeed / ringLifetimeTicks;
            if (fly > 1 && nextLocation.getBlock().getType().isSolid()) {
                World world = nextLocation.getWorld();
                world.spawnParticle(Particle.POOF, nextLocation, 2, 0.07, 0.07, 0.07, 0.02);
                display.remove();
                cancel();
                return;
            }
            sneeze--;
        }
    }

    private Component smokeSprite(int sprite) {
        return smokeSprites.computeIfAbsent(sprite, key -> {
                int stage = key - 1;
                String configured = getConfig().getString("display.stage-" + stage);
            return miniMessage.deserialize(configured);
        });
    }

    private void loadSmokeSprites() {
        validateConfiguration();
        loadBehaviorSettings();
        Map<Integer, Component> loadedSprites = new HashMap<>();
        for (int sprite = 2; sprite <= 5; sprite++) {
            int stage = sprite - 1;
            loadedSprites.put(sprite, miniMessage.deserialize(
                    getConfig().getString("display.stage-" + stage)));
        }
        smokeSprites.clear();
        smokeSprites.putAll(loadedSprites);
    }

    private void validateConfiguration() {
        for (int stage = 1; stage <= 4; stage++) {
            String path = "display.stage-" + stage;
            String configured = getConfig().getString(path);
            if (configured == null || configured.isBlank()) {
                throw new IllegalArgumentException("Missing required value: " + path);
            }
        }
        requireLong("smoke.look-away-delay-ms");
        requireInt("smoke.particles-per-tick");
        requireDouble("smoke.velocity");
        requireDouble("smoke.spread");
        requireInt("ring.lifetime-ticks");
        requireDouble("ring.speed");
        requireDouble("ring.gravity-up");
        requireDouble("ring.gravity-down");
        requireDouble("ring.rotation-speed");
        requireInt("ring.opacity-decrement");
        requireDouble("ring.scale-increment");
        if (getConfig().getLong("smoke.look-away-delay-ms") < 0) {
            throw new IllegalArgumentException("Value must be non-negative: smoke.look-away-delay-ms");
        }
        if (getConfig().getInt("smoke.particles-per-tick") < 0) {
            throw new IllegalArgumentException("Value must be non-negative: smoke.particles-per-tick");
        }
        if (getConfig().getDouble("smoke.velocity") < 0 || getConfig().getDouble("smoke.spread") < 0) {
            throw new IllegalArgumentException("Values must be non-negative: smoke.velocity, smoke.spread");
        }
        if (getConfig().getInt("ring.lifetime-ticks") <= 0) {
            throw new IllegalArgumentException("Value must be greater than zero: ring.lifetime-ticks");
        }
        if (getConfig().getDouble("ring.speed") < 0) {
            throw new IllegalArgumentException("Value must be non-negative: ring.speed");
        }
    }

    private void loadBehaviorSettings() {
        lookAwaySmokeDelayMs = getConfig().getLong("smoke.look-away-delay-ms");
        smokeParticlesPerTick = getConfig().getInt("smoke.particles-per-tick");
        smokeVelocity = getConfig().getDouble("smoke.velocity");
        smokeSpread = getConfig().getDouble("smoke.spread");
        ringLifetimeTicks = getConfig().getInt("ring.lifetime-ticks");
        ringSpeed = getConfig().getDouble("ring.speed");
        ringGravityUp = getConfig().getDouble("ring.gravity-up");
        ringGravityDown = getConfig().getDouble("ring.gravity-down");
        ringRotationSpeed = getConfig().getDouble("ring.rotation-speed");
        ringOpacityDecrement = getConfig().getInt("ring.opacity-decrement");
        ringScaleIncrement = getConfig().getDouble("ring.scale-increment");
    }

    private void requireLong(String path) {
        if (!getConfig().isInt(path) && !getConfig().isLong(path)) {
            throw new IllegalArgumentException("Missing or invalid integer: " + path);
        }
    }

    private void requireInt(String path) {
        if (!getConfig().isInt(path)) {
            throw new IllegalArgumentException("Missing or invalid integer: " + path);
        }
    }

    private void requireDouble(String path) {
        if (!getConfig().isDouble(path)) {
            throw new IllegalArgumentException("Missing or invalid number: " + path);
        }
    }

    private static final class SmokeState {
        private int smoking;
        private long lastSmokeAt;
        private long lastLeftClickAt;
        private long lookAwayAt;
        private long handLockedUntil;
        private BukkitTask trailTask;
    }
}