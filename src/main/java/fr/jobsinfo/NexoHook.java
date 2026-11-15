package fr.jobsinfo;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Lien optionnel avec Nexo, par reflexion (aucune dependance a la compilation) :
 *  - items custom : "nexo:mon_item"
 *  - le MiniMessage de Nexo, qui comprend <glyph:nom>, <shift:-52>, etc.
 */
public final class NexoHook {

    private final Method itemFromId;
    private final Object itemsInstance;
    private final MiniMessage mini;

    private NexoHook(Method itemFromId, Object itemsInstance, MiniMessage mini) {
        this.itemFromId = itemFromId;
        this.itemsInstance = itemsInstance;
        this.mini = mini;
    }

    /** Retourne le hook, ou null si Nexo est absent. */
    public static NexoHook create(JobsInfoPlugin plugin) {
        Plugin nexo = Bukkit.getPluginManager().getPlugin("Nexo");
        if (nexo == null || !nexo.isEnabled()) return null;
        ClassLoader cl = nexo.getClass().getClassLoader();

        Method method = null;
        Object instance = null;
        try {
            Class<?> items = Class.forName("com.nexomc.nexo.api.NexoItems", true, cl);
            method = items.getMethod("itemFromId", String.class);
            if (!Modifier.isStatic(method.getModifiers())) {
                instance = items.getField("INSTANCE").get(null);
            }
        } catch (Throwable t) {
            method = null;
            plugin.getLogger().warning("Nexo detecte mais l'API des items est inaccessible : " + t);
        }

        MiniMessage mini = findMiniMessage(cl);
        plugin.getLogger().info("Nexo detecte : items custom " + (method != null ? "OK" : "INDISPONIBLES")
                + ", MiniMessage Nexo (<glyph:..>) " + (mini != null ? "OK" : "INDISPONIBLE"));
        if (method == null && mini == null) return null;
        return new NexoHook(method, instance, mini);
    }

    /** MiniMessage de Nexo (null si introuvable) : gere les tags glyph/shift de Nexo. */
    public MiniMessage miniMessage() {
        return mini;
    }

    /** Construit l'item Nexo (id sans le prefixe "nexo:"), ou null s'il n'existe pas. */
    public ItemStack create(String id) {
        if (itemFromId == null) return null;
        try {
            Object builder = itemFromId.invoke(itemsInstance, id);
            if (builder == null) return null;
            Object built = builder.getClass().getMethod("build").invoke(builder);
            return built instanceof ItemStack stack ? stack : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static MiniMessage findMiniMessage(ClassLoader cl) {
        try {
            Class<?> utils = Class.forName("com.nexomc.nexo.utils.AdventureUtils", true, cl);
            Object instance = null;
            try {
                instance = utils.getField("INSTANCE").get(null);
            } catch (Throwable ignored) {
                // pas un objet Kotlin
            }

            // 1) champ statique MINI_MESSAGE
            try {
                Field f = utils.getField("MINI_MESSAGE");
                Object value = f.get(Modifier.isStatic(f.getModifiers()) ? null : instance);
                if (value instanceof MiniMessage mm) return mm;
            } catch (Throwable ignored) {
                // essaie la suite
            }
            // 2) getters (statiques ou sur INSTANCE)
            for (String getter : new String[]{"getMINI_MESSAGE", "getMiniMessage"}) {
                try {
                    Method m = utils.getMethod(getter);
                    Object value = m.invoke(Modifier.isStatic(m.getModifiers()) ? null : instance);
                    if (value instanceof MiniMessage mm) return mm;
                } catch (Throwable ignored) {
                    // essaie la suite
                }
            }
        } catch (Throwable ignored) {
            // classe introuvable
        }
        return null;
    }
}
