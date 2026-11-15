package fr.jobsinfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Lit des slots depuis la config : 11, "0-8,18-26", ou une liste YAML. */
final class Slots {

    private Slots() {}

    static List<Integer> parse(Object raw, int size) {
        List<Integer> out = new ArrayList<>();
        collect(raw, size, out);
        return out;
    }

    private static void collect(Object raw, int size, List<Integer> out) {
        if (raw == null) return;
        if (raw instanceof Collection<?> collection) {
            for (Object o : collection) collect(o, size, out);
            return;
        }
        for (String part : String.valueOf(raw).split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                int dash = p.indexOf('-', 1);
                if (dash > 0) {
                    int a = Integer.parseInt(p.substring(0, dash).trim());
                    int b = Integer.parseInt(p.substring(dash + 1).trim());
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) add(i, size, out);
                } else {
                    add(Integer.parseInt(p), size, out);
                }
            } catch (NumberFormatException ignored) {
                // slot invalide : ignore
            }
        }
    }

    private static void add(int slot, int size, List<Integer> out) {
        if (slot >= 0 && slot < size && !out.contains(slot)) out.add(slot);
    }
}
