package fr.jobsinfo;

import net.kyori.adventure.text.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Placeholders %cle% : texte (strings) ou composant (comps, ex. nom traduit d'un bloc). */
final class Ph {

    final Map<String, String> strings = new LinkedHashMap<>();
    final Map<String, Component> comps = new LinkedHashMap<>();

    Ph str(String key, String value) {
        strings.put(key, value == null ? "" : value);
        return this;
    }

    Ph comp(String key, Component value) {
        comps.put(key, value);
        return this;
    }

    Ph copy() {
        Ph p = new Ph();
        p.strings.putAll(strings);
        p.comps.putAll(comps);
        return p;
    }
}
