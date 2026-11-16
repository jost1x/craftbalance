package dev.craftbalance.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("craftbalance");
    private static final double MIN_CONSUMPTION_FACTOR = 1.0e-18;
    private static volatile BalanceConfig current = BalanceConfig.defaults();

    private ConfigManager() {}

    public static BalanceConfig current() { return current; }

    public static synchronized BalanceConfig loadOrCreate(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("config.yml");
        if (Files.notExists(file)) {
            try (var defaults = ConfigManager.class.getResourceAsStream("/craftbalance-default.yml")) {
                if (defaults == null) throw new IOException("No se encontró craftbalance-default.yml dentro del JAR.");
                Files.copy(defaults, file);
            }
        }
        return reload(file);
    }

    /** Parses and validates into a temporary value before replacing the active config. */
    public static synchronized BalanceConfig reload(Path file) throws IOException {
        return reload(file, null);
    }

    public static synchronized BalanceConfig reload(Path file, String fixedDatabaseFile) throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(loaderOptions));
        Object raw = yaml.load(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> overrides = map(raw, "documento raíz");
        Map<String, Object> defaults = loadDefaults(yaml);
        List<String> defaultedPaths = new java.util.ArrayList<>();
        collectMissingOptions(defaults, overrides, "", defaultedPaths);
        Map<String, Object> root = mergeDefaults(defaults, overrides);
        Map<String, Object> time = child(root, "time");
        Map<String, Object> reset = child(time, "daily-reset");
        Map<String, Object> multiplayer = child(root, "multiplayer");
        Map<String, Object> consumptionNode = child(multiplayer, "consumption");
        Map<String, Object> recovery = child(root, "recovery");
        Map<String, Object> afk = child(root, "afk");
        Map<String, Object> display = child(root, "display");
        Map<String, Object> database = child(root, "database");

        rejectUnknownKeys(root, "", Set.of("time", "multiplayer", "recovery", "afk", "display", "database"));
        rejectUnknownKeys(time, "time", Set.of("enabled", "daily-minutes", "max-real-minutes", "daily-reset"));
        rejectUnknownKeys(reset, "time.daily-reset", Set.of("hour", "timezone"));
        rejectUnknownKeys(multiplayer, "multiplayer", Set.of("enabled", "consumption"));
        rejectUnknownKeys(consumptionNode, "multiplayer.consumption", Set.of("1", "2", "3", "4", "5+"));
        rejectUnknownKeys(recovery, "recovery", Set.of("enabled", "only-after-normal-exhaustion",
                "seconds-per-friend-per-minute", "max-seconds-per-minute", "initial-wait-minutes",
                "max-daily-minutes", "max-bank-minutes", "minimum-rejoin-minutes", "recovered-consumption"));
        rejectUnknownKeys(afk, "afk", Set.of("enabled", "inactivity-minutes", "counts-as-active-for-others",
                "consume-own-time-while-afk"));
        rejectUnknownKeys(display, "display", Set.of("action-bar", "login-summary", "warnings-minutes"));
        rejectUnknownKeys(database, "database", Set.of("type", "file"));

        Map<Integer, Double> factors = new LinkedHashMap<>();
        for (int count = 1; count <= 4; count++) factors.put(count, positiveDouble(consumptionNode.get(Integer.toString(count)), "multiplayer.consumption." + count));
        double fivePlus = positiveDouble(consumptionNode.get("5+"), "multiplayer.consumption.5+");
        int dailyMinutes = positiveInt(time.get("daily-minutes"), "time.daily-minutes");
        int maxRealMinutes = positiveInt(time.get("max-real-minutes"), "time.max-real-minutes");
        int recoveryMax = nonNegativeInt(recovery.get("max-daily-minutes"), "recovery.max-daily-minutes");
        int bankMax = nonNegativeInt(recovery.get("max-bank-minutes"), "recovery.max-bank-minutes");
        int initialWait = nonNegativeInt(recovery.get("initial-wait-minutes"), "recovery.initial-wait-minutes");
        int perFriend = nonNegativeInt(recovery.get("seconds-per-friend-per-minute"), "recovery.seconds-per-friend-per-minute");
        int perMinuteCap = nonNegativeInt(recovery.get("max-seconds-per-minute"), "recovery.max-seconds-per-minute");
        int rejoin = nonNegativeInt(recovery.get("minimum-rejoin-minutes"), "recovery.minimum-rejoin-minutes");
        boolean recoveryEnabled = bool(recovery.get("enabled"), "recovery.enabled");
        if (rejoin > bankMax)
            throw new IllegalArgumentException("recovery.minimum-rejoin-minutes no puede superar recovery.max-bank-minutes; el umbral sería inalcanzable.");
        if (recoveryEnabled && rejoin > recoveryMax)
            throw new IllegalArgumentException("recovery.minimum-rejoin-minutes no puede superar recovery.max-daily-minutes mientras la recuperación esté activada.");
        if (recoveryEnabled && (recoveryMax == 0 || bankMax == 0 || perFriend == 0 || perMinuteCap == 0))
            throw new IllegalArgumentException("Con recovery.enabled, los topes diario y de banco y las dos tasas de recuperación deben ser mayores que cero; usa recovery.enabled: false para desactivar la función.");
        double recoveredConsumption = positiveDouble(recovery.get("recovered-consumption"), "recovery.recovered-consumption");
        int afkMinutes = positiveInt(afk.get("inactivity-minutes"), "afk.inactivity-minutes");
        String resetHour = string(reset.get("hour"), "time.daily-reset.hour");
        LocalTime.parse(resetHour);
        ZoneId timezone;
        try { timezone = ZoneId.of(string(reset.get("timezone"), "time.daily-reset.timezone")); }
        catch (DateTimeException exception) { throw new IllegalArgumentException("Zona horaria inválida.", exception); }
        Object warningsValue = display.get("warnings-minutes");
        if (!(warningsValue instanceof List<?> warningValues)) throw new IllegalArgumentException("display.warnings-minutes debe ser una lista.");
        int[] warnings = warningValues.stream().mapToInt(value -> positiveInt(value, "display.warnings-minutes")).toArray();
        String dbFile = string(database.get("file"), "database.file");
        String dbType = string(database.get("type"), "database.type");
        if (!dbType.equalsIgnoreCase("sqlite")) throw new IllegalArgumentException("database.type debe ser sqlite.");
        Path normalizedDatabaseFile = normalizeDatabaseFile(file, dbFile);

        BalanceConfig parsed = new BalanceConfig(bool(time.get("enabled"), "time.enabled"), dailyMinutes,
                maxRealMinutes, resetHour, timezone, bool(multiplayer.get("enabled"), "multiplayer.enabled"), Map.copyOf(factors), fivePlus,
                recoveryEnabled,
                bool(recovery.get("only-after-normal-exhaustion"), "recovery.only-after-normal-exhaustion"),
                perFriend, perMinuteCap,
                initialWait, recoveryMax, bankMax, rejoin, recoveredConsumption,
                bool(afk.get("enabled"), "afk.enabled"), afkMinutes,
                bool(afk.get("counts-as-active-for-others"), "afk.counts-as-active-for-others"),
                bool(afk.get("consume-own-time-while-afk"), "afk.consume-own-time-while-afk"),
                bool(display.get("action-bar"), "display.action-bar"),
                bool(display.get("login-summary"), "display.login-summary"), warnings, normalizedDatabaseFile.toString());
        if (fixedDatabaseFile != null && !Path.of(fixedDatabaseFile).normalize().equals(normalizedDatabaseFile))
            throw new IllegalArgumentException("database.file requiere reiniciar el servidor; se conserva la configuración activa.");
        if (!defaultedPaths.isEmpty())
            LOGGER.warn("El archivo {} no define estas opciones; se usan los valores predeterminados de esta versión (el YAML no se reescribe): {}",
                    file, String.join(", ", defaultedPaths));
        warnExtremeValues(parsed, file);
        current = parsed;
        return parsed;
    }

    private static void warnExtremeValues(BalanceConfig config, Path file) {
        if (config.dailyMinutes() > 1_440)
            LOGGER.warn("{}: time.daily-minutes={} supera 24 horas; el saldo que exceda el límite real diario puede quedar sin uso.",
                    file, config.dailyMinutes());
        if (config.maxRealMinutes() > 1_440)
            LOGGER.warn("{}: time.max-real-minutes={} puede superar la duración habitual de un día local.",
                    file, config.maxRealMinutes());
        if (config.multiplayerEnabled()) {
            config.consumption().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (entry.getValue() < 0.01 || entry.getValue() > 10.0)
                    LOGGER.warn("{}: multiplayer.consumption.{}={} es extremo; revisa si el multiplicador es intencional.",
                            file, entry.getKey(), entry.getValue());
            });
            if (config.fivePlusConsumption() < 0.01 || config.fivePlusConsumption() > 10.0)
                LOGGER.warn("{}: multiplayer.consumption.5+={} es extremo; revisa si el multiplicador es intencional.",
                        file, config.fivePlusConsumption());
        }
        if (config.recoveredConsumption() < 0.01 || config.recoveredConsumption() > 10.0)
            LOGGER.warn("{}: recovery.recovered-consumption={} es extremo; revisa si el factor es intencional.",
                    file, config.recoveredConsumption());
        if (config.recoveryEnabled() && config.maxRecoverySecondsPerMinute() > 60)
            LOGGER.warn("{}: recovery.max-seconds-per-minute={} puede generar más de un minuto de saldo por minuto elegible.",
                    file, config.maxRecoverySecondsPerMinute());
    }

    private static Map<String, Object> loadDefaults(Yaml yaml) throws IOException {
        try (var defaults = ConfigManager.class.getResourceAsStream("/craftbalance-default.yml")) {
            if (defaults == null) throw new IOException("No se encontró craftbalance-default.yml dentro del JAR.");
            String content = new String(defaults.readAllBytes(), StandardCharsets.UTF_8);
            return map(yaml.load(content), "configuración predeterminada");
        }
    }

    /** Fills omitted options from this mod version while retaining explicit values and unknown keys. */
    private static Map<String, Object> mergeDefaults(Map<String, Object> defaults,
                                                     Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>();
        defaults.forEach((key, defaultValue) -> {
            if (!overrides.containsKey(key)) {
                merged.put(key, defaultValue);
                return;
            }
            Object override = overrides.get(key);
            if (defaultValue instanceof Map<?, ?> && override instanceof Map<?, ?>) {
                merged.put(key, mergeDefaults(map(defaultValue, key), map(override, key)));
            } else {
                merged.put(key, override);
            }
        });
        overrides.forEach(merged::putIfAbsent);
        return merged;
    }

    private static void collectMissingOptions(Map<String, Object> defaults,
                                              Map<String, Object> overrides,
                                              String prefix, List<String> missing) {
        defaults.forEach((key, defaultValue) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (!overrides.containsKey(key)) {
                missing.add(path);
                return;
            }
            Object override = overrides.get(key);
            if (defaultValue instanceof Map<?, ?> && override instanceof Map<?, ?>)
                collectMissingOptions(map(defaultValue, path), map(override, path), path, missing);
        });
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " debe ser un mapa YAML.");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private static Map<String, Object> child(Map<String, Object> parent, String key) { return map(parent.get(key), key); }
    private static void rejectUnknownKeys(Map<String, Object> values, String path, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                String fullPath = path.isEmpty() ? key : path + "." + key;
                throw new IllegalArgumentException("Clave de configuración desconocida: " + fullPath + ".");
            }
        }
    }
    private static Path normalizeDatabaseFile(Path configFile, String value) {
        Path relative = Path.of(value);
        Path configDirectory = configFile.toAbsolutePath().normalize().getParent();
        if (relative.isAbsolute() || configDirectory == null)
            throw new IllegalArgumentException("database.file debe estar dentro de config/craftbalance.");
        Path normalized = relative.normalize();
        Path resolved = configDirectory.resolve(normalized).normalize();
        if (normalized.toString().isBlank() || normalized.equals(Path.of("."))
                || !resolved.startsWith(configDirectory) || resolved.equals(configDirectory))
            throw new IllegalArgumentException("database.file debe ser un archivo dentro de config/craftbalance.");
        return normalized;
    }
    private static boolean bool(Object value, String key) { if (value instanceof Boolean b) return b; throw new IllegalArgumentException(key + " debe ser booleano."); }
    private static String string(Object value, String key) { if (value instanceof String s && !s.isBlank()) return s; throw new IllegalArgumentException(key + " debe ser texto no vacío."); }
    private static int positiveInt(Object value, String key) { int result = integer(value, key); if (result <= 0) throw new IllegalArgumentException(key + " debe ser mayor que cero."); return result; }
    private static int nonNegativeInt(Object value, String key) { int result = integer(value, key); if (result < 0) throw new IllegalArgumentException(key + " no puede ser negativo."); return result; }
    private static int integer(Object value, String key) { if (value instanceof Number number && number.doubleValue() == number.intValue()) return number.intValue(); throw new IllegalArgumentException(key + " debe ser entero."); }
    private static double positiveDouble(Object value, String key) {
        if (value instanceof Number number && number.doubleValue() >= MIN_CONSUMPTION_FACTOR
                && Double.isFinite(number.doubleValue())) return number.doubleValue();
        throw new IllegalArgumentException(key + " debe ser un número finito mayor o igual que 1e-18 para conservar la precisión del cálculo.");
    }
}
