package fr.jobsinfo;

import fr.jobsinfo.JobsInfoPlugin.Category;
import fr.jobsinfo.JobsInfoPlugin.Entry;
import fr.jobsinfo.JobsInfoPlugin.JobInfo;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Detection du clic droit dans /jobs + les deux menus (actions du job, detail d'une action). */
final class Menus implements Listener {

    /** Holder commun : identifie nos inventaires. */
    abstract static class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static final class CategoryHolder extends Menu {
        final JobInfo job;
        final Map<Integer, Category> bySlot = new HashMap<>();
        int backSlot = -1;

        CategoryHolder(JobInfo job) {
            this.job = job;
        }
    }

    static final class EntriesHolder extends Menu {
        final JobInfo job;
        final Category category;
        final int page;
        final int pages;
        final boolean fromCategories;
        int prevSlot = -1;
        int nextSlot = -1;
        int backSlot = -1;

        EntriesHolder(JobInfo job, Category category, int page, int pages, boolean fromCategories) {
            this.job = job;
            this.category = category;
            this.page = page;
            this.pages = pages;
            this.fromCategories = fromCategories;
        }
    }

    private final JobsInfoPlugin plugin;

    Menus(JobsInfoPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------- detection (/jobs)

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJobsMenuClick(InventoryClickEvent event) {
        YamlConfiguration cfg = plugin.general();
        if (cfg == null) return;
        ClickType trigger = parseClick(cfg.getString("trigger-click", "RIGHT"));
        if (event.getClick() != trigger) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Menu) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != top) return;
        if (top.getSize() != cfg.getInt("browse-menu-size", 36)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        JobInfo job = matchJob(clicked);

        if (cfg.getBoolean("debug", false)) debugClick(player, top, clicked, job);
        if (job == null) return;
        if (!player.hasPermission("jobsinfo.use")) return;

        // On tente d'empecher ExcellentJobs de traiter ce clic...
        event.setCancelled(true);

        // ...et comme il peut quand meme ouvrir son menu des niveaux, on ouvre le notre APRES lui,
        // puis on verifie que le notre est bien celui qui reste affiche.
        long open = Math.max(1, cfg.getLong("open-delay-ticks", 2));
        long verify = Math.max(open + 1, cfg.getLong("verify-delay-ticks", 6));
        Bukkit.getScheduler().runTaskLater(plugin, () -> openJob(player, job), open);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !(player.getOpenInventory().getTopInventory().getHolder() instanceof Menu)) {
                openJob(player, job);
            }
        }, verify);
    }

    private static ClickType parseClick(String name) {
        try {
            return ClickType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return ClickType.RIGHT;
        }
    }

    /** Retrouve le job d'un item du menu : par custom model data, sinon par nom affiche. */
    private JobInfo matchJob(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        Set<Float> floats = new HashSet<>();
        if (meta.hasCustomModelDataComponent()) {
            floats.addAll(meta.getCustomModelDataComponent().getFloats());
        }
        String displayName = meta.hasDisplayName() ? plugin.text().plainText(meta.displayName()) : "";

        for (JobInfo job : plugin.jobs().values()) {
            if (job.modelData() != null && item.getType() == job.iconMaterial() && floats.contains(job.modelData())) {
                return job;
            }
        }
        for (JobInfo job : plugin.jobs().values()) {
            if (!displayName.isEmpty() && displayName.equals(job.plainName())) return job;
        }
        return null;
    }

    private void debugClick(Player player, Inventory top, ItemStack clicked, JobInfo job) {
        ItemMeta meta = clicked.getItemMeta();
        List<Float> floats = meta != null && meta.hasCustomModelDataComponent()
                ? meta.getCustomModelDataComponent().getFloats() : List.of();
        String name = meta != null && meta.hasDisplayName() ? plugin.text().plainText(meta.displayName()) : "-";
        plugin.getLogger().info("[debug] clic par " + player.getName() + " : menu=" + top.getSize()
                + " slots, item=" + clicked.getType() + ", modelData=" + floats + ", nom='" + name
                + "', job reconnu=" + (job == null ? "AUCUN" : job.id()));
    }

    // ------------------------------------------------------------ open menus

    /** Point d'entree : menu des actions, ou directement le detail s'il n'y a qu'une action. */
    void openJob(Player player, JobInfo job) {
        List<Category> cats = plugin.categoriesOf(job.id());
        if (cats.isEmpty()) {
            plugin.send(player, "no-categories", new Ph().str("job", job.plainName()));
            return;
        }
        if (cats.size() == 1 && plugin.general().getBoolean("skip-category-menu-if-single", true)) {
            openEntries(player, job, cats.get(0), 0, false);
            return;
        }
        openCategories(player, job, cats);
    }

    private void openCategories(Player player, JobInfo job, List<Category> cats) {
        YamlConfiguration cfg = plugin.categoryMenu();
        int size = rows(cfg) * 9;
        Ph base = new Ph().str("job", job.plainName());

        CategoryHolder holder = new CategoryHolder(job);
        holder.inv = Bukkit.createInventory(holder, size, plugin.text().parse(cfg.getString("Title", "%job%"), base));
        decorate(holder.inv, cfg.getConfigurationSection("Decoration"), base, size);

        List<Integer> defaults = Slots.parse(cfg.get("Default_Slots"), size);
        int nextDefault = 0;
        for (Category c : cats) {
            int slot = c.slot();
            if (slot < 0 || slot >= size) {
                slot = nextDefault < defaults.size() ? defaults.get(nextDefault++) : -1;
            }
            if (slot < 0) continue;

            ConfigurationSection sec = c.item();
            if (sec == null) {
                sec = new MemoryConfiguration();
                sec.set("Material", "PAPER");
                sec.set("Name", "%category%");
            }
            Ph ph = base.copy()
                    .str("category", c.name())
                    .str("action", c.actionLabel())
                    .str("count", String.valueOf(c.entries().size()));
            holder.inv.setItem(slot, plugin.items().build(sec, ph));
            holder.bySlot.put(slot, c);
        }
        holder.backSlot = button(holder.inv, cfg.getConfigurationSection("Back"), base, size);

        player.openInventory(holder.inv);
        play(player, "open");
    }

    private void openEntries(Player player, JobInfo job, Category cat, int page, boolean fromCategories) {
        YamlConfiguration cfg = plugin.entriesMenu();
        int size = rows(cfg) * 9;

        List<Integer> slots = Slots.parse(cfg.get("Entry_Slots"), size);
        if (slots.isEmpty()) {
            for (int i = 0; i < Math.max(1, size - 9); i++) slots.add(i);
        }
        int perPage = slots.size();
        List<Entry> entries = cat.entries();
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        int pg = Math.max(0, Math.min(page, pages - 1));

        Ph base = new Ph()
                .str("job", job.plainName())
                .str("category", cat.name())
                .str("action", cat.actionLabel())
                .str("page", String.valueOf(pg + 1))
                .str("pages", String.valueOf(pages))
                .str("symbol", plugin.general().getString("currency-symbol", "$"));

        String titleRaw = cat.entriesTitle() != null ? cat.entriesTitle() : cfg.getString("Title", "%category%");
        EntriesHolder holder = new EntriesHolder(job, cat, pg, pages, fromCategories);
        holder.inv = Bukkit.createInventory(holder, size, plugin.text().parse(titleRaw, base));
        decorate(holder.inv, cfg.getConfigurationSection("Decoration"), base, size);

        ConfigurationSection entrySec = cat.entryOverride() != null
                ? cat.entryOverride() : cfg.getConfigurationSection("Entry");
        int from = pg * perPage;
        int to = Math.min(entries.size(), from + perPage);
        for (int i = from; i < to; i++) {
            holder.inv.setItem(slots.get(i - from), entryItem(entries.get(i), entrySec, base));
        }

        if (entries.isEmpty()) {
            button(holder.inv, cfg.getConfigurationSection("Empty"), base, size);
        }
        boolean hide = cfg.getBoolean("Hide_When_Unavailable", true);
        if (pg > 0 || !hide) holder.prevSlot = button(holder.inv, cfg.getConfigurationSection("Previous"), base, size);
        if (pg < pages - 1 || !hide) holder.nextSlot = button(holder.inv, cfg.getConfigurationSection("Next"), base, size);
        holder.backSlot = button(holder.inv, cfg.getConfigurationSection("Back"), base, size);

        player.openInventory(holder.inv);
        play(player, "open");
    }

    private ItemStack entryItem(Entry e, ConfigurationSection entrySec, Ph base) {
        Material icon = Material.PAPER;
        Component name = Component.text(e.key());

        NamespacedKey nk = NamespacedKey.fromString(e.key());
        if (nk != null) {
            EntityType et = e.entity() ? Registry.ENTITY_TYPE.get(nk) : null;
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

        String chanceLine = "";
        if (e.chance() < 100 && entrySec != null) {
            chanceLine = entrySec.getString("Chance_Line", "").replace("%chance%", plugin.format(e.chance()));
        }

        Ph ph = base.copy()
                .comp("name", name)
                .str("xp", plugin.span(e.xpMin(), e.xpMax()))
                .str("xp_min", plugin.format(e.xpMin()))
                .str("xp_max", plugin.format(e.xpMax()))
                .str("money", plugin.span(e.moneyMin(), e.moneyMax()))
                .str("money_min", plugin.format(e.moneyMin()))
                .str("money_max", plugin.format(e.moneyMax()))
                .str("chance", plugin.format(e.chance()))
                .str("chance_line", chanceLine);
        return plugin.items().apply(new ItemStack(icon), entrySec, ph);
    }

    // ---------------------------------------------------------------- helpers

    private static int rows(YamlConfiguration cfg) {
        return Math.max(1, Math.min(6, cfg.getInt("Rows", 3)));
    }

    /** Place un item "bouton" (Slot + Material/Name/Lore...). Retourne son slot, ou -1. */
    private int button(Inventory inv, ConfigurationSection sec, Ph ph, int size) {
        if (sec == null) return -1;
        int slot = sec.getInt("Slot", -1);
        if (slot < 0 || slot >= size) return -1;
        inv.setItem(slot, plugin.items().build(sec, ph));
        return slot;
    }

    private void decorate(Inventory inv, ConfigurationSection sec, Ph ph, int size) {
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection d = sec.getConfigurationSection(key);
            if (d == null) continue;
            ItemStack item = plugin.items().build(d, ph);
            for (int slot : Slots.parse(d.get("Slots"), size)) inv.setItem(slot, item);
        }
    }

    private void play(Player player, String key) {
        String sound = plugin.general().getString("sounds." + key, "");
        if (sound != null && !sound.isBlank()) player.playSound(player.getLocation(), sound, 1f, 1f);
    }

    private void goToJobsMenu(Player player) {
        String command = plugin.general().getString("back-to-jobs-command", "jobs menu");
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            if (command != null && !command.isBlank()) player.performCommand(command);
        });
    }

    // ------------------------------------------------------------ menu clicks

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;
        int slot = event.getSlot();

        if (menu instanceof CategoryHolder ch) {
            if (slot == ch.backSlot) {
                play(player, "click");
                goToJobsMenu(player);
                return;
            }
            Category cat = ch.bySlot.get(slot);
            if (cat != null) {
                play(player, "click");
                JobInfo job = ch.job;
                Bukkit.getScheduler().runTask(plugin, () -> openEntries(player, job, cat, 0, true));
            }
        } else if (menu instanceof EntriesHolder eh) {
            JobInfo job = eh.job;
            Category cat = eh.category;
            if (slot == eh.backSlot) {
                play(player, "click");
                if (eh.fromCategories) {
                    Bukkit.getScheduler().runTask(plugin, () -> openCategories(player, job, plugin.categoriesOf(job.id())));
                } else {
                    goToJobsMenu(player);
                }
            } else if (slot == eh.prevSlot && eh.page > 0) {
                play(player, "page");
                int target = eh.page - 1;
                boolean from = eh.fromCategories;
                Bukkit.getScheduler().runTask(plugin, () -> openEntries(player, job, cat, target, from));
            } else if (slot == eh.nextSlot && eh.page < eh.pages - 1) {
                play(player, "page");
                int target = eh.page + 1;
                boolean from = eh.fromCategories;
                Bukkit.getScheduler().runTask(plugin, () -> openEntries(player, job, cat, target, from));
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
}
