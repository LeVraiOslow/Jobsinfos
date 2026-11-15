package fr.jobsinfo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Clic droit sur un job dans le menu /jobs d'ExcellentJobs -> menu d'infos du job
 * (blocs / mobs / poissons qui rapportent de l'XP et de l'argent).
 * Les donnees sont lues dans les fichiers jobs/*.yml d'ExcellentJobs.
 */
public final class JobsInfoPlugin extends JavaPlugin implements Listener, org.bukkit.command.TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PER_PAGE = 45;
    private static final Set<String> ENTITY_TYPES = Set.of("killing", "shearing", "breeding", "milking", "taming");

    record Entry(String type, String key, double xpMin, double xpMax,
                 double moneyMin, double moneyMax, double chance) {}

    record Job(String id, String plainName, Material iconMaterial, Float modelData, List<Entry> entries) {}

    /** Holder qui identifie nos menus. */
    static final class InfoMenu implements InventoryHolder {
        final Job job;
        final int page;
        Inventory inventory;

        InfoMenu(Job job, int page) {
            this.job = job;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final DecimalFormat number = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.FRANCE));

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("jobsinfo");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        loadJobs();
    }

    // ---------------------------------------------------------------- loading

    private void loadJobs() {
        jobs.clear();
        String custom = getConfig().getString("excellentjobs-folder", "");
        File base = (custom == null || custom.isBlank())
                ? new File(getDataFolder().getParentFile(), "ExcellentJobs")
                : new File(custom);
        File[] files = new File(base, "jobs").listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            getLogger().warning("Dossier introuvable : " + new File(base, "jobs").getPath());
            return;
        }
        boolean swap = getConfig().getBoolean("swap-xp-and-money", false);

        for (File f : files) {
            try {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                String id = f.getName().substring(0, f.getName().length() - 4);
                String rawName = yml.getString("Name", id);
                String plain = PlainTextComponentSerializer.plainText().serialize(MM.deserialize(rawName));

                Material icon = Material.matchMaterial(yml.getString("Icon.Material", "PAPER"));
                Float model = yml.contains("Icon.Model.Data") ? (float) yml.getInt("Icon.Model.Data") : null;

                List<Entry> entries = new ArrayList<>();
                ConfigurationSection objectives = yml.getConfigurationSection("Objectives");
                if (objectives != null) {
                    for (String objId : objectives.getKeys(false)) {
                        ConfigurationSection obj = objectives.getConfigurationSection(objId);
                        if (obj == null) continue;
                        String type = obj.getString("Type", "default").toLowerCase(Locale.ROOT);
                        ConfigurationSection tables = obj.getConfigurationSection("SourceTable");
                        if (tables == null) continue;
                        List<Entry> group = new ArrayList<>();
                        for (String table : tables.getKeys(false)) {
                            ConfigurationSection t = tables.getConfigurationSection(table);
                            if (t == null) continue; // ex. SpawnerMobBonus (valeur simple)
                            for (String key : t.getKeys(false)) {
                                Entry e = parse(type, key, t.getString(key), swap);
                                if (e != null) group.add(e);
                            }
                        }
                        group.sort(Comparator.comparingDouble((Entry e) -> e.moneyMax()).reversed());
                        entries.addAll(group);
                    }
                }
                jobs.put(id, new Job(id, plain, icon == null ? Material.PAPER : icon, model, entries));
            } catch (Exception ex) {
                getLogger().warning("Job ignore (" + f.getName() + ") : " + ex);
            }
        }
        getLogger().info(jobs.size() + " job(s) charge(s) depuis ExcellentJobs.");
    }

    /** Format : "xpMin;xpMax moneyMin;moneyMax chance". */
    private Entry parse(String type, String key, String value, boolean swap) {
        if (value == null) return null;
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 2) return null;
        try {
            double[] a = range(parts[0]);
            double[] b = range(parts[1]);
            double chance = parts.length >= 3 ? Double.parseDouble(parts[2]) : 100.0;
            if (swap) {
                double[] tmp = a;
                a = b;
                b = tmp;
            }
            return new Entry(type, key, a[0], a[1], b[0], b[1], chance);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double[] range(String s) {
        String[] p = s.split(";");
        double min = Double.parseDouble(p[0]);
        double max = p.length > 1 ? Double.parseDouble(p[1]) : min;
        return new double[]{min, max};
    }

    // ------------------------------------------------------------- detection

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJobsMenuClick(InventoryClickEvent event) {
        if (event.getClick() != ClickType.RIGHT) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof InfoMenu) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != top) return;
        if (top.getSize() != getConfig().getInt("browse-menu-size", 36)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        Job job = matchJob(clicked);
        if (job == null) return;

        // On ouvre au tick suivant pour ne pas toucher aux inventaires pendant le clic.
        Bukkit.getScheduler().runTask(this, () -> open(player, job, 0));
    }

    /** Retrouve le job d'un item du menu : par custom model data, sinon par nom affiche. */
    private Job matchJob(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        Set<Float> floats = new HashSet<>();
        if (meta.hasCustomModelDataComponent()) {
            floats.addAll(meta.getCustomModelDataComponent().getFloats());
        }
        String displayName = meta.hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(meta.displayName())
                : "";

        for (Job job : jobs.values()) {
            if (job.modelData() != null && item.getType() == job.iconMaterial()
                    && floats.contains(job.modelData())) {
                return job;
            }
        }
        for (Job job : jobs.values()) {
            if (!displayName.isEmpty() && displayName.equals(job.plainName())) return job;
        }
        return null;
    }

    // ------------------------------------------------------------------ menu

    private void open(Player player, Job job, int page) {
        int pages = Math.max(1, (int) Math.ceil(job.entries().size() / (double) PER_PAGE));
        int p = Math.max(0, Math.min(page, pages - 1));

        InfoMenu holder = new InfoMenu(job, p);
        Component title = text(getConfig().getString("title", "Infos"), Map.of("%job%", job.plainName()));
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inv;

        int from = p * PER_PAGE;
        int to = Math.min(job.entries().size(), from + PER_PAGE);
        for (int i = from; i < to; i++) {
            inv.setItem(i - from, entryItem(job.entries().get(i)));
        }

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);

        Map<String, String> ph = Map.of("%page%", String.valueOf(p + 1), "%pages%", String.valueOf(pages));
        if (p > 0) inv.setItem(45, named(Material.ARROW, text(btn("previous"), ph), List.of()));
        if (p < pages - 1) inv.setItem(53, named(Material.ARROW, text(btn("next"), ph), List.of()));
        inv.setItem(49, named(Material.BARRIER, text(btn("back"), ph), List.of(text(btn("page"), ph))));

        player.openInventory(inv);
    }

    private String btn(String name) {
        return getConfig().getString("buttons." + name, name);
    }

    private ItemStack entryItem(Entry e) {
        boolean entity = ENTITY_TYPES.contains(e.type());
        Material icon = Material.PAPER;
        Component name = Component.text(e.key());

        NamespacedKey nk = NamespacedKey.fromString(e.key());
        if (nk != null) {
            EntityType et = entity ? Registry.ENTITY_TYPE.get(nk) : null;
            Material mat = Material.matchMaterial(e.key());
            if (et != null) {
                name = Component.translatable(et.translationKey());
                Material egg = Material.matchMaterial(nk.getKey() + "_spawn_egg");
                if (egg != null) icon = egg;
            } else if (mat != null) {
                name = Component.translatable(mat.translationKey());
                if (mat.isItem() && !mat.isAir()) icon = mat;
            } else {
                EntityType any = Registry.ENTITY_TYPE.get(nk);
                if (any != null) name = Component.translatable(any.translationKey());
            }
        }

        String category = getConfig().getString("categories." + e.type(),
                getConfig().getString("categories.default", "Objectif"));
        String chanceLine = e.chance() < 100
                ? getConfig().getString("entry.chance-line", "").replace("%chance%", number.format(e.chance()))
                : "";

        Map<String, String> ph = Map.of(
                "%category%", category,
                "%xp%", span(e.xpMin(), e.xpMax()),
                "%money%", span(e.moneyMin(), e.moneyMax()),
                "%symbol%", getConfig().getString("currency-symbol", "$"),
                "%chance%", chanceLine);

        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("entry.lore")) {
            if (line.contains("%chance%") && chanceLine.isEmpty()) continue;
            lore.add(text(line, ph));
        }

        // Le nom est un composant traduisible : on l'injecte apres coup dans le format MiniMessage.
        final Component finalName = name;
        Component display = text(getConfig().getString("entry.name", "%name%"), Map.of("%name%", "\u0000"))
                .replaceText(b -> b.matchLiteral("\u0000").replacement(finalName));
        return named(icon, display, lore);
    }

    private String span(double min, double max) {
        return min == max ? number.format(min) : number.format(min) + " - " + number.format(max);
    }

    // ------------------------------------------------------------- menu clicks

    @EventHandler
    public void onInfoClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof InfoMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        int slot = event.getSlot();
        if (slot == 45 && menu.page > 0) {
            Bukkit.getScheduler().runTask(this, () -> open(player, menu.job, menu.page - 1));
        } else if (slot == 53 && event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ARROW) {
            Bukkit.getScheduler().runTask(this, () -> open(player, menu.job, menu.page + 1));
        } else if (slot == 49) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.closeInventory();
                player.performCommand("jobs menu");
            });
        }
    }

    @EventHandler
    public void onInfoDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof InfoMenu) event.setCancelled(true);
    }

    // --------------------------------------------------------------- helpers

    private static Component text(String miniMessage, Map<String, String> placeholders) {
        String s = miniMessage;
        for (var e : placeholders.entrySet()) s = s.replace(e.getKey(), e.getValue());
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
        });
        return stack;
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("jobsinfo.reload")) {
            reloadConfig();
            loadJobs();
            sender.sendMessage(Component.text("JobsInfo recharge (" + jobs.size() + " jobs)."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 && sender.hasPermission("jobsinfo.reload") ? List.of("reload") : List.of();
    }
}
