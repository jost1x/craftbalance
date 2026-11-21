package dev.craftbalance.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Server-side translations for clients that do not install the mod. */
public final class BalanceTranslations {
    private static final Map<String, String> SPANISH = load("es_es");
    private static final Map<String, String> ENGLISH = load("en_us");

    private BalanceTranslations() {}

    public static Text text(ServerPlayerEntity player, String key, Object... arguments) {
        String language = player == null ? "es_es" : player.getClientOptions().language();
        return text(language, key, arguments);
    }

    public static Text text(ServerCommandSource source, String key, Object... arguments) {
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity entity ? entity : null;
        return text(player, key, arguments);
    }

    private static Text text(String language, String key, Object... arguments) {
        Map<String, String> translations = language != null && language.toLowerCase(Locale.ROOT).startsWith("en")
                ? ENGLISH : SPANISH;
        String template = translations.getOrDefault(key, SPANISH.getOrDefault(key, key));
        Object[] values = new Object[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            Object argument = arguments[index];
            values[index] = argument instanceof Text text ? text.getString() : argument;
        }
        return Text.literal(values.length == 0 ? template : String.format(Locale.ROOT, template, values));
    }

    private static Map<String, String> load(String language) {
        String path = "/assets/craftbalance/lang/" + language + ".json";
        try (var stream = BalanceTranslations.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing translation resource " + path);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return json.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> {
                        JsonElement value = entry.getValue();
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                            throw new IllegalStateException("Invalid translation for " + entry.getKey());
                        return value.getAsString();
                    }));
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
