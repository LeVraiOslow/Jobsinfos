package fr.jobsinfo;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Items definis dans les fichiers YAML :
 *   Material: DIAMOND_PICKAXE  |  minecraft:stone  |  nexo:mon_item
 *   Name / Lore (couleurs &c, MiniMessage, glyphs) / Amount / Model_Data / Glow / Hide_Flags
 */
final class Items {

    private final JobsInfoPlugin plugin;
    private final Set<String> warned = new HashSet<>();

    Items(JobsInfoPlugin plugin) {
        this.plugin = plugin;
    }

    /** Item de base (sans nom ni lore) pour une valeur "Material". */
    ItemStack base(String spec) {
        if (spec == null || spec.isBlank()) return new ItemStack(Material.STONE);
        String s = spec.trim();

        if (s.regionMatches(true, 0, "nexo:", 0, 5)) {
            NexoHook nexo = plugin.nexo();
            ItemStack item = nexo == null ? null : nexo.create(s.substring(5));
            if (item != null) return item;
            warnOnce("Item Nexo introuvable (ou Nexo absent) : " + s);
            return new ItemStack(Material.BARRIER);
        }

        Material material = Material.matchMaterial(s);
        if (material == null || material.isAir() || !material.isItem()) {
            warnOnce("Material invalide : " + s);
            return new ItemStack(Material.BARRIER);
        }
        return new ItemStack(material);
    }

    /** Construit un item complet depuis une section (Material + Name + Lore...). */
    ItemStack build(ConfigurationSection sec, Ph ph) {
        if (sec == null) return new ItemStack(Material.BARRIER);
        return apply(base(sec.getString("Material", "STONE")), sec, ph);
    }

    /** Applique Name / Lore / Amount / Model_Data / Glow / Hide_Flags a un item de base. */
    ItemStack apply(ItemStack base, ConfigurationSection sec, Ph ph) {
        ItemStack stack = base.clone();
        if (sec == null) return stack;

        stack.setAmount(Math.max(1, Math.min(64, sec.getInt("Amount", 1))));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        Text text = plugin.text();

        if (sec.contains("Name")) {
            meta.displayName(text.parse(sec.getString("Name"), ph));
        }
        if (sec.contains("Lore")) {
            List<Component> lore = new ArrayList<>();
            for (String line : sec.getStringList("Lore")) {
                // Une ligne reduite a %cle% dont la valeur est vide est supprimee (ex. %chance_line%).
                String t = line.trim();
                if (ph != null && t.length() > 2 && t.startsWith("%") && t.endsWith("%")) {
                    String value = ph.strings.get(t.substring(1, t.length() - 1));
                    if (value != null && value.isEmpty()) continue;
                }
                lore.add(text.parse(line, ph));
            }
            meta.lore(lore);
        }
        int model = sec.getInt("Model_Data", 0);
        if (model > 0) {
            var component = meta.getCustomModelDataComponent();
            component.setFloats(List.of((float) model));
            meta.setCustomModelDataComponent(component);
        }
        if (sec.getBoolean("Glow", false)) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (sec.getBoolean("Hide_Flags", true)) {
            meta.addItemFlags(ItemFlag.values());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void warnOnce(String message) {
        if (warned.add(message)) plugin.getLogger().warning(message);
    }
}
