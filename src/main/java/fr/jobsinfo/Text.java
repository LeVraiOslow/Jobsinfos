package fr.jobsinfo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforme un texte de config en Component. Accepte :
 *  - les codes legacy : &c, &l, &r, &#RRGGBB
 *  - MiniMessage : <red>, <gradient:..>, <glyph:nom>, <shift:-52> (si Nexo est present)
 *  - les placeholders %cle% (voir Ph)
 */
final class Text {

    private static final Pattern HEX = Pattern.compile("[&\u00a7]#([0-9a-fA-F]{6})");
    private static final Pattern CODE = Pattern.compile("[&\u00a7]([0-9a-fk-orA-FK-OR])");

    private final JobsInfoPlugin plugin;
    private final MiniMessage plain = MiniMessage.miniMessage();
    private final Map<String, String> glyphs = new HashMap<>();
    private MiniMessage nexo;

    /** <glyph:nom> defini a la main dans config.yml (secours si le MiniMessage de Nexo est indisponible). */
    private final TagResolver glyphResolver = TagResolver.resolver("glyph", (args, ctx) -> {
        Tag.Argument arg = args.peek();
        if (arg == null) return null;
        String ch = glyphs.get(arg.value().toLowerCase(Locale.ROOT));
        if (ch == null) return null;
        args.pop();
        return Tag.selfClosingInserting(Component.text(ch));
    });

    Text(JobsInfoPlugin plugin) {
        this.plugin = plugin;
    }

    void reload(ConfigurationSection general, NexoHook hook) {
        nexo = hook == null ? null : hook.miniMessage();
        glyphs.clear();
        ConfigurationSection sec = general.getConfigurationSection("glyphs");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                glyphs.put(k.toLowerCase(Locale.ROOT), sec.getString(k, ""));
            }
        }
    }

    Component parse(String input, Ph ph) {
        String s = input == null ? "" : input;
        if (ph != null) {
            for (Map.Entry<String, String> e : ph.strings.entrySet()) {
                s = s.replace("%" + e.getKey() + "%", e.getValue());
            }
        }
        s = legacyToMini(s);

        List<TagResolver> resolvers = new ArrayList<>();
        resolvers.add(glyphResolver);
        if (ph != null) {
            for (Map.Entry<String, Component> e : ph.comps.entrySet()) {
                String tag = "ji_" + e.getKey();
                s = s.replace("%" + e.getKey() + "%", "<" + tag + ">");
                resolvers.add(Placeholder.component(tag, e.getValue()));
            }
        }
        TagResolver all = TagResolver.resolver(resolvers);

        Component result;
        try {
            result = (nexo != null ? nexo : plain).deserialize(s, all);
        } catch (Exception ex) {
            try {
                result = plain.deserialize(s, all);
            } catch (Exception ex2) {
                plugin.getLogger().warning("Texte invalide : " + input + " (" + ex2.getMessage() + ")");
                result = Component.text(input == null ? "" : input);
            }
        }
        return result.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    String plainText(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** &c -> <red> (avec reset comme en legacy), &l -> <bold>, &#RRGGBB -> <#RRGGBB>... */
    static String legacyToMini(String s) {
        if (s.indexOf('&') < 0 && s.indexOf('\u00a7') < 0) return s;
        s = HEX.matcher(s).replaceAll("<#$1>");

        Matcher m = CODE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tag = switch (Character.toLowerCase(m.group(1).charAt(0))) {
                case '0' -> "<reset><black>";
                case '1' -> "<reset><dark_blue>";
                case '2' -> "<reset><dark_green>";
                case '3' -> "<reset><dark_aqua>";
                case '4' -> "<reset><dark_red>";
                case '5' -> "<reset><dark_purple>";
                case '6' -> "<reset><gold>";
                case '7' -> "<reset><gray>";
                case '8' -> "<reset><dark_gray>";
                case '9' -> "<reset><blue>";
                case 'a' -> "<reset><green>";
                case 'b' -> "<reset><aqua>";
                case 'c' -> "<reset><red>";
                case 'd' -> "<reset><light_purple>";
                case 'e' -> "<reset><yellow>";
                case 'f' -> "<reset><white>";
                case 'k' -> "<obfuscated>";
                case 'l' -> "<bold>";
                case 'm' -> "<strikethrough>";
                case 'n' -> "<underlined>";
                case 'o' -> "<italic>";
                default -> "<reset>";
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(tag));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
