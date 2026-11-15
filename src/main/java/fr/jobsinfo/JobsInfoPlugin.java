package fr.jobsinfo;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.stream.Stream;

/**
 * Clic droit sur un job dans /jobs (ExcellentJobs) -> menu des actions du job -> detail de l'action.
 *
 * Dossiers de config :
 *   config/      configuration generale
 *   messages/    messages
 *   menus/       apparence des menus (titres, slots, boutons, decoration)
 *   categories/  une categorie (action) par fichier : Miner, Cuire, ...
 */
public final class JobsInfoPlugin extends JavaPlugin implements TabExecutor {

    /** Une ligne de gain : item/bloc/mob + XP + argent + chance. */
    public record Entry(String key, boolean entity, double xpMin, double xpMax,
                        double moneyMin, double moneyMax, double chance) {}

    public record Objective(String id, String type, List<Entry> entries) {}

    public record JobInfo(String id, String plainName, Material iconMaterial, Float modelData,
                          Map<String, Objective> objectives) {}

    public record Category(String id, String jobId, int order, int slot, String name, String actionLabel,
                           String entriesTitle, ConfigurationSection item, ConfigurationSection entryOverride,
                           List<Entry> entries) {}

    private static final Set<String> ENTITY_TYPES = Set.of("killing", "shearing", "breeding", "milking", "taming");

    private static final List<String> DEFAULT_FILES = List.of(
            "config/config.yml", "messages/messages.yml",
            "menus/category_menu.yml", "menus/entries_menu.yml");

    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "miner_mine.yml", "miner_smelt.yml", "hunter_kill.yml", "lumberjack_chop.yml",
            "farmer_harvest.yml", "farmer_breed.yml", "farmer_shear.yml", "farmer_milk.yml",
            "fisherman_fish.yml");

    private YamlConfiguration general;
    private YamlConfiguration messages;
    private YamlConfiguration categoryMenu;
    private YamlConfiguration entriesMenu;

    private final Map<String, JobInfo> jobs = new LinkedHashMap<>();
    private final List<Category> categories = new ArrayList<>();

    private Text text;
    private Items items;
    private Menus menus;
    private NexoHook nexo;
    private DecimalFormat number = new DecimalFormat("0.##");

    @Override
    public void onEnable() {
        text = new Text(this);
        items = new Items(this);
        menus = new Menus(this);
        getServer().getPluginManager().registerEvents(menus, this);

        var cmd = getCommand("jobsinfo");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        reloadAll();
    }

    // ---------------------------------------------------------------- accessors

    YamlConfiguration general() { return general; }
    YamlConfiguration categoryMenu() { return categoryMenu; }
    YamlConfiguration entriesMenu() { return entriesMenu; }
    Text text() { return text; }
    Items items() { return items; }
    NexoHook nexo() { return nexo; }
    Map<String, JobInfo> jobs() { return jobs; }

    List<Category> categoriesOf(String jobId) {
        List<Category> out = new ArrayList<>();
        for (Category c : categories) {
            if (c.jobId().equalsIgnoreCase(jobId)) out.add(c);
        }
        return out;
    }

    String format(double value) {
        return number.format(value);
    }

    String span(double min, double max) {
        return min == max ? format(min) : format(min) + " - " + format(max);
    }

    void send(CommandSender to, String key, Ph ph) {
        String raw = messages.getString(key, key);
        if (raw == null || raw.isEmpty()) return;
        Ph p = ph == null ? new Ph() : ph;
        p.str("prefix", messages.getString("prefix", ""));
        to.sendMessage(text.parse(raw, p));
    }

    // ------------------------------------------------------------------ loading

    void reloadAll() {
        for (String res : DEFAULT_FILES) {
            if (!new File(getDataFolder(), res).exists()) saveResource(res, false);
        }
        if (!new File(getDataFolder(), "categories").exists()) {
            for (String file : DEFAULT_CATEGORIES) saveResource("categories/" + file, false);
        }

        general = load("config/config.yml");
        messages = load("messages/messages.yml");
        categoryMenu = load("menus/category_menu.yml");
        entriesMenu = load("menus/entries_menu.yml");

        nexo = NexoHook.create(this);
        text.reload(general, nexo);

        Locale locale = Locale.forLanguageTag(general.getString("number-locale", "fr"));
        try {
            number = new DecimalFormat(general.getString("number-format", "0.##"),
                    DecimalFormatSymbols.getInstance(locale));
        } catch (IllegalArgumentException e) {
            getLogger().warning("number-format invalide, utilisation de 0.##");
            number = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale));
        }

        loadJobs();
        loadCategories();
        getLogger().info(jobs.size() + " job(s) et " + categories.size() + " categorie(s) charge(s).");
    }

    private YamlConfiguration load(String path) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), path));
    }

    /** Lit les jobs d'ExcellentJobs (nom, icone, objectifs). */
    private void loadJobs() {
        jobs.clear();
        String custom = general.getString("excellentjobs-folder", "");
        File base = (custom == null || custom.isBlank())
                ? new File(getDataFolder().getParentFile(), "ExcellentJobs")
                : new File(custom);
        File[] files = new File(base, "jobs").listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            getLogger().warning("Dossier introuvable : " + new File(base, "jobs").getPath());
            return;
        }
        boolean swap = general.getBoolean("swap-xp-and-money", false);

        for (File f : files) {
            try {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
                String plain = text.plainText(text.parse(yml.getString("Name", id), null));

                Material icon = Material.matchMaterial(yml.getString("Icon.Material", "PAPER"));
                Float model = yml.contains("Icon.Model.Data") ? (float) yml.getInt("Icon.Model.Data") : null;

                Map<String, Objective> objectives = new LinkedHashMap<>();
                ConfigurationSection objSec = yml.getConfigurationSection("Objectives");
                if (objSec != null) {
                    for (String objId : objSec.getKeys(false)) {
                        ConfigurationSection obj = objSec.getConfigurationSection(objId);
                        if (obj == null) continue;
                        String type = obj.getString("Type", "default").toLowerCase(Locale.ROOT);
                        boolean entity = ENTITY_TYPES.contains(type);
                        List<Entry> list = new ArrayList<>();
                        ConfigurationSection tables = obj.getConfigurationSection("SourceTable");
                        if (tables != null) {
                            for (String table : tables.getKeys(false)) {
                                ConfigurationSection t = tables.getConfigurationSection(table);
                                if (t == null) continue; // ex. SpawnerMobBonus (valeur simple)
                                for (String key : t.getKeys(false)) {
                                    Entry e = parseEntry(key, t.getString(key), entity, swap);
                                    if (e != null) list.add(e);
                                }
                            }
                        }
                        objectives.put(objId, new Objective(objId, type, list));
                    }
                }
                jobs.put(id, new JobInfo(id, plain, icon == null ? Material.PAPER : icon, model, objectives));
            } catch (Exception ex) {
                getLogger().warning("Job ignore (" + f.getName() + ") : " + ex);
            }
        }
    }

    /** Lit tous les fichiers de categories/ (sous-dossiers acceptes). */
    private void loadCategories() {
        categories.clear();
        File dir = new File(getDataFolder(), "categories");
        if (!dir.isDirectory()) return;

        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            files = stream.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            getLogger().warning("Lecture de categories/ impossible : " + e);
            return;
        }
        for (Path path : files) {
            try {
                Category c = readCategory(path.toFile());
                if (c != null) categories.add(c);
            } catch (Exception ex) {
                getLogger().warning("Categorie ignoree (" + path.getFileName() + ") : " + ex);
            }
        }
        categories.sort(Comparator.comparingInt(Category::order).thenComparing(Category::id));
    }

    private Category readCategory(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        String id = file.getName().replaceFirst("\\.yml$", "");

        String jobId = y.getString("Job");
        if (jobId == null || jobId.isBlank()) {
            getLogger().warning(file.getName() + " : cle 'Job' manquante.");
            return null;
        }
        JobInfo job = jobs.get(jobId.toLowerCase(Locale.ROOT));
        if (job == null) {
            getLogger().warning(file.getName() + " : job inconnu '" + jobId + "' (absent d'ExcellentJobs/jobs).");
            return null;
        }

        Set<String> objectiveIds = lower(y.getStringList("Objectives"));
        Set<String> types = lower(y.getStringList("Types"));
        List<Entry> list = new ArrayList<>();
        for (Objective o : job.objectives().values()) {
            if (objectiveIds.contains(o.id().toLowerCase(Locale.ROOT)) || types.contains(o.type())) {
                list.addAll(o.entries());
            }
        }

        boolean swap = general.getBoolean("swap-xp-and-money", false);
        boolean customEntity = "entity".equalsIgnoreCase(y.getString("Custom_Key_Type", "material"));
        ConfigurationSection custom = y.getConfigurationSection("Custom_Entries");
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                Entry e = parseEntry(key, custom.getString(key), customEntity, swap);
                if (e != null) list.add(e);
            }
        }
        sort(list, y.getString("Sort", "money_desc"));

        return new Category(id, job.id(), y.getInt("Order", 100), y.getInt("Slot", -1),
                y.getString("Name", id), y.getString("Action_Label", ""), y.getString("Entries_Title"),
                y.getConfigurationSection("Item"), y.getConfigurationSection("Entry"), list);
    }

    private static Set<String> lower(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    private static void sort(List<Entry> list, String mode) {
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "money_desc" -> list.sort(Comparator.comparingDouble(Entry::moneyMax).reversed());
            case "money_asc" -> list.sort(Comparator.comparingDouble(Entry::moneyMax));
            case "xp_desc" -> list.sort(Comparator.comparingDouble(Entry::xpMax).reversed());
            case "xp_asc" -> list.sort(Comparator.comparingDouble(Entry::xpMax));
            case "name" -> list.sort(Comparator.comparing(Entry::key));
            default -> { /* none : ordre du fichier */ }
        }
    }

    /** Format : "xpMin;xpMax moneyMin;moneyMax chance" (comme dans les fichiers d'ExcellentJobs). */
    private Entry parseEntry(String key, String value, boolean entity, boolean swap) {
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
            return new Entry(key, entity, a[0], a[1], b[0], b[1], chance);
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

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("jobsinfo.reload")) {
                send(sender, "no-permission", null);
                return true;
            }
            reloadAll();
            send(sender, "reloaded", new Ph()
                    .str("jobs", String.valueOf(jobs.size()))
                    .str("categories", String.valueOf(categories.size())));
            return true;
        }
        send(sender, "usage", null);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 && sender.hasPermission("jobsinfo.reload") ? List.of("reload") : List.of();
    }
}
