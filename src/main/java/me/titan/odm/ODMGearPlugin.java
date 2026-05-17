package me.titan.odm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ODMGearPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final NamespacedKey gearKey = new NamespacedKey(this, "odm_gear");
    private final NamespacedKey bladeKey = new NamespacedKey(this, "odm_blade");
    private final NamespacedKey fluidKey = new NamespacedKey(this, "titan_fluid");
    private final Map<UUID, HookProcess> activeHooks = new ConcurrentHashMap<>();
    private final Set<UUID> fallImmune = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Long> titanAbilitiesCooldown = new HashMap<>();
    private final Set<UUID> titans = new HashSet<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("odm").setExecutor(this);
        getCommand("odm").setTabCompleter(this);

        // Задача для бега по стенам Титанов
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID uuid : titans) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;
                
                // Проверка бега в стену
                Block front = p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.8)).getBlock();
                if (front.getType().isSolid()) {
                    Vector vel = p.getVelocity();
                    vel.setY(0.4); // Подъем по стене
                    p.setVelocity(vel);
                }
            }
        }, 0, 1);

        getLogger().info("УПМ и Система Титанов активирована!");
    }

    @Override
    public void onDisable() {
        activeHooks.values().forEach(HookProcess::cancel);
        activeHooks.clear();
        for (UUID uuid : titans) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) revertTitan(p);
        }
    }

    // --- КОМАНДА ВЫДАЧИ ---
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!player.hasPermission("odm.admin")) return true;

        if (args.length > 0 && args[0].equalsIgnoreCase("serum")) {
            player.getInventory().addItem(createSerum());
            player.sendMessage(Component.text("Вы получили сыворотку Атакующего Титана!", NamedTextColor.RED));
            return true;
        }

        player.getInventory().addItem(createGear());
        player.getInventory().addItem(createBlade());
        player.sendMessage(Component.text("Вы получили снаряжение УПМ!", NamedTextColor.GREEN));
        return true;
    }

    // --- СОЗДАНИЕ ПРЕДМЕТОВ ---
    private ItemStack createGear() {
        ItemStack item = new ItemStack(Material.NETHERITE_CHESTPLATE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Основа УПМ 2.0", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.setCustomModelData(910);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(gearKey, PersistentDataType.BYTE, (byte) 1);
        meta.setFireResistant(true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSerum() {
        ItemStack item = new ItemStack(Material.COAL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Спинномозговая жидкость атакующего титана", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
        meta.setCustomModelData(913);
        meta.getPersistentDataContainer().set(fluidKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBlade() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Клинок УПМ", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        // Для ресурспака: 912 - модель клинка
        meta.setCustomModelData(912);
        meta.setUnbreakable(true);
        meta.setFireResistant(true);
        meta.getPersistentDataContainer().set(bladeKey, PersistentDataType.BYTE, (byte) 1);

        // Урон 12.0
        AttributeModifier modifier = new AttributeModifier(new NamespacedKey(this, "blade_damage"), 12.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, modifier);

        item.setItemMeta(meta);
        return item;
    }

    // --- ДИНАМИЧЕСКАЯ СМЕНА МОДЕЛИ ---
    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        // Проверяем предмет в новом слоте через тик для точности
        Bukkit.getScheduler().runTask(this, () -> updateGearModel(event.getPlayer()));
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(this, () -> updateGearModel(player));
        }
    }

    private void updateGearModel(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        if (chest != null && isGear(chest)) {
            ItemMeta meta = chest.getItemMeta();
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            
            int targetCmd = isBlade(mainHand) ? 911 : 910;
            
            if (meta.hasCustomModelData() && meta.getCustomModelData() == targetCmd) return;
            
            meta.setCustomModelData(targetCmd);
            chest.setItemMeta(meta);
        }
    }

    // --- ЛОГИКА АКТИВАЦИИ ---
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        UUID uuid = player.getUniqueId();

        // Использование сыворотки
        if (isFluid(item) && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            if (titans.contains(uuid)) {
                revertTitan(player);
            } else {
                transformTitan(player);
            }
            return;
        }

        // Способность Титана (захват)
        if (titans.contains(uuid) && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            useTitanAbility(player);
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        // Запрет УПМ для титанов
        if (titans.contains(uuid)) return;

        if (!isWearingGear(player) || !isBlade(item)) return;

        event.setCancelled(true);

        // Отмена текущего полета
        if (activeHooks.containsKey(uuid)) {
            activeHooks.get(uuid).cancel();
            activeHooks.remove(uuid);
            return;
        }

        // Запуск крюка
        HookProcess process = new HookProcess(player);
        activeHooks.put(uuid, process);
        process.launch();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        HookProcess p = activeHooks.remove(event.getPlayer().getUniqueId());
        if (p != null) p.cancel();
    }

    private boolean isBlade(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(bladeKey, PersistentDataType.BYTE);
    }

    private boolean isWearingGear(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        return chest != null && chest.hasItemMeta() && chest.getItemMeta().getPersistentDataContainer().has(gearKey, PersistentDataType.BYTE);
    }

    private void transformTitan(Player player) {
        UUID uuid = player.getUniqueId();
        titans.add(uuid);

        // Эффекты превращения
        player.getWorld().strikeLightningEffect(player.getLocation());
        player.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, player.getLocation(), 1);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);

        // Атрибуты
        double health = player.getAttribute(Attribute.MAX_HEALTH).getBaseValue();
        double damage = player.getAttribute(Attribute.ATTACK_DAMAGE).getBaseValue();
        double speed = player.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue();

        player.getAttribute(Attribute.SCALE).setBaseValue(7.0);
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health * 2);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(damage * 2);
        player.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(speed * 2);

        // Имя
        player.customName(Component.text("Атакующий Титан", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true));
        player.setCustomNameVisible(true);

        player.sendMessage(Component.text("Вы превратились в Атакующего Титана!", NamedTextColor.GREEN));
        player.setAllowFlight(true); // Для двойного прыжка
    }

    private void revertTitan(Player player) {
        UUID uuid = player.getUniqueId();
        titans.remove(uuid);

        player.getAttribute(Attribute.SCALE).setBaseValue(1.0);
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(20.0);
        player.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(1.0);
        player.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.1);

        player.customName(null);
        player.setCustomNameVisible(false);
        player.setAllowFlight(false);
        player.setFlying(false);

        player.sendMessage(Component.text("Вы вернулись в человеческую форму.", NamedTextColor.YELLOW));
    }

    private void useTitanAbility(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (titanAbilitiesCooldown.containsKey(uuid) && titanAbilitiesCooldown.get(uuid) > now) {
            long remaining = (titanAbilitiesCooldown.get(uuid) - now) / 1000;
            player.sendMessage(Component.text("Способность будет доступна через " + remaining + " сек.", NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("ИСПОЛЬЗОВАН ЗАХВАТ!", NamedTextColor.RED, TextDecoration.BOLD));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.0f, 0.5f);

        for (Entity e : player.getNearbyEntities(10, 10, 10)) {
            if (e instanceof LivingEntity le && !e.equals(player)) {
                // Притягивание
                Vector toTitan = player.getLocation().toVector().subtract(le.getLocation().toVector()).normalize().multiply(1.5);
                le.setVelocity(toTitan);

                // Урон (3 сердца = 6 хп)
                le.damage(6.0, player);

                // Обездвиживание
                le.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 60, 10, false, false));
                le.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST, 60, 200, false, false));
            }
        }

        titanAbilitiesCooldown.put(uuid, now + 25000);
    }

    @EventHandler
    public void onTitanJump(org.bukkit.event.player.PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        if (titans.contains(p.getUniqueId())) {
            event.setCancelled(true);
            p.setFlying(false);
            // Прыжок на 7 блоков вверх
            Vector v = p.getLocation().getDirection().multiply(0.5).setY(1.3);
            p.setVelocity(v);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0f, 0.5f);
        }
    }

    // --- ЗАЩИТА ПРЕДМЕТОВ И ИГРОКА ---
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        // Защита игрока от урона при падении во время и после полета
        if (event.getEntity() instanceof Player player) {
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                if (fallImmune.contains(player.getUniqueId()) || activeHooks.containsKey(player.getUniqueId())) {
                    event.setCancelled(true);
                }
            }
        }

        if (event.getEntityType() == EntityType.ITEM) {
            Item itemEntity = (Item) event.getEntity();
            ItemStack stack = itemEntity.getItemStack();
            if (isBlade(stack) || isGear(stack)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (isBlade(stack) || isGear(stack)) {
            event.setCancelled(true);
        }
    }

    private boolean isGear(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(gearKey, PersistentDataType.BYTE);
    }

    private boolean isFluid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(fluidKey, PersistentDataType.BYTE);
    }

    // --- КЛАСС ПРОЦЕССА КРЮКА ---
    private class HookProcess {
        private final Player player;
        private Location hookLoc;
        private Vector direction;
        private boolean hooked = false;
        private final List<ItemDisplay> chains = new ArrayList<>();
        private int taskLaunch = -1;
        private int taskPull = -1;
        private double distanceTraveled = 0;

        public HookProcess(Player player) {
            this.player = player;
            this.hookLoc = player.getEyeLocation();
            this.direction = player.getEyeLocation().getDirection().normalize();
        }

        private void launch() {
            taskLaunch = Bukkit.getScheduler().scheduleSyncRepeatingTask(ODMGearPlugin.this, () -> {
                // Проверка условий жизни
                if (!player.isOnline() || !isWearingGear(player) || !isBlade(player.getInventory().getItemInMainHand())) {
                    cancel();
                    return;
                }

                // Скорость полета крюка увеличена в 4 раза: 32 блока/сек = 1.6 за тик
                double speed = 1.6;
                // Используем больше итераций для сверхзвукового полета, чтобы не пролетать сквозь стены
                int iterations = 8;
                double step = speed / iterations;

                for (int j = 0; j < iterations; j++) {
                    Location nextLoc = hookLoc.clone().add(direction.clone().multiply(step));
                    
                    // Проверка препятствий (Raycast)
                    Block block = nextLoc.getBlock();
                    if (block.getType().isSolid()) {
                        hookLoc = nextLoc;
                        startPull();
                        return;
                    }

                    hookLoc = nextLoc;
                    distanceTraveled += step;
                    
                    // Коллизия с сущностями
                    for (Entity e : hookLoc.getWorld().getNearbyEntities(hookLoc, 0.4, 0.4, 0.4)) {
                        if (e instanceof LivingEntity le && !e.equals(player)) {
                            le.damage(10, player);
                            startPull();
                            return;
                        }
                    }

                    if (distanceTraveled >= 70) {
                        cancel();
                        return;
                    }
                }
                renderChains();
            }, 0, 1);
        }

        private void startPull() {
            hooked = true;
            if (taskLaunch != -1) Bukkit.getScheduler().cancelTask(taskLaunch);
            
            taskPull = Bukkit.getScheduler().scheduleSyncRepeatingTask(ODMGearPlugin.this, () -> {
                if (!player.isOnline() || !isWearingGear(player) || !isBlade(player.getInventory().getItemInMainHand())) {
                    cancel();
                    return;
                }

                Vector toHook = hookLoc.toVector().subtract(player.getLocation().toVector());
                double dist = toHook.length();

                if (dist < 1.5) {
                    cancel();
                    return;
                }
                
                // Скорость увеличена в 4 раза: 14 блоков/сек.
                // Velocity ~1.8-2.0 дает агрессивную тягу.
                Vector vel = toHook.normalize().multiply(1.8);

                // Логика бега по стенам (УПМ 2.0)
                Block frontBlock = player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.8)).getBlock();
                if (frontBlock.getType().isSolid()) {
                    // Если перед игроком стена, позволяем "бежать" вверх или удерживаться
                    Vector pDir = player.getLocation().getDirection();
                    // Если игрок смотрит вверх, добавляем вертикальную силу
                    if (pDir.getY() > 0.3) {
                        vel.setY(0.4);
                    } else if (pDir.getY() < -0.3) {
                        vel.setY(-0.4);
                    } else {
                        // Удержание на стене
                        vel.setY(0.08); // Легкая компенсация гравитации
                    }
                }

                player.setVelocity(vel);
                
                player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 1, 0.1, 0.1, 0.1, 0.01);
                renderChains();
            }, 0, 1);
        }

        private void renderChains() {
            clearChains();
            Location start = player.getEyeLocation().subtract(0, 0.3, 0); // Чуть ниже глаз (от пояса)
            Location end = hookLoc;
            double dist = start.distance(end);
            Vector dir = end.toVector().subtract(start.toVector()).normalize();

            // Расчет углов для поворота модели
            double yaw = Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            double pitch = Math.toDegrees(Math.asin(dir.getY()));

            // Создаем цепи через каждые 0.5 блока для плотности
            for (double d = 0; d < dist; d += 0.5) {
                Location loc = start.clone().add(dir.clone().multiply(d));
                ItemDisplay display = loc.getWorld().spawn(loc, ItemDisplay.class, ent -> {
                    ent.setItemStack(new ItemStack(Material.CHAIN));
                    ent.setGravity(false);
                    ent.setPersistent(false);
                    ent.setBrightness(new Display.Brightness(15, 15));
                    
                    // Поворот в сторону движения
                    ent.setRotation((float) yaw, (float) pitch);
                    
                    Transformation trans = ent.getTransformation();
                    trans.getScale().set(0.4f, 0.4f, 0.5f); // Скейлинг под трос
                    ent.setTransformation(trans);
                });
                chains.add(display);
            }
        }

        private void clearChains() {
            chains.forEach(Entity::remove);
            chains.clear();
        }

        public void cancel() {
            if (taskLaunch != -1) Bukkit.getScheduler().cancelTask(taskLaunch);
            if (taskPull != -1) Bukkit.getScheduler().cancelTask(taskPull);
            clearChains();
            UUID uuid = player.getUniqueId();
            activeHooks.remove(uuid);
            
            // Защита от урона при падении на 3 секунды после завершения полета
            fallImmune.add(uuid);
            Bukkit.getScheduler().runTaskLater(ODMGearPlugin.this, () -> fallImmune.remove(uuid), 60L);
        }
    }
}
