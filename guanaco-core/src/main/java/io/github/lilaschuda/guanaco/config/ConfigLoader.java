package io.github.lilaschuda.guanaco.config;

import io.github.lilaschuda.guanaco.config.exception.GuanacoConfigException;
import io.github.lilaschuda.guanaco.config.exception.UnsupportedConfigFormatException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Loads and parses the guanaco routes configuration file.
 *
 * <p>Supports both YAML and JSON. Format is determined purely by file
 * extension — there is no separate format property to configure or keep in
 * sync with the actual file. Two single-file conventions are supported:
 *
 * <ul>
 *   <li>{@link #load()} — looks for {@code routes.json}, then
 *       {@code routes.yaml}, then {@code routes.yml}, in that order, on the
 *       classpath. If a JSON file is present, it always wins, on the
 *       assumption that a JSON file's presence is a deliberate choice.</li>
 *   <li>{@link #load(String)} — loads an explicit classpath resource,
 *       with format inferred from its extension.</li>
 * </ul>
 *
 * <p>A third, multi-file convention scans a whole classpath directory
 * instead of a single named resource -- see {@link #loadFromDirectory()},
 * {@link #loadFromDirectory(String)}, {@link ConfigDirectoryScanner}, and
 * {@link ConfigTreeMerger} for the discovery, per-file precedence, and
 * merge semantics.
 *
 * <p>All formats are parsed with strict duplicate-key detection enabled —
 * a configuration file with the same key repeated at the same level fails
 * to load immediately, rather than silently keeping whichever value Jackson
 * happens to parse last.
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String DEFAULT_BASE_NAME = "routes";

    /** Default classpath directory scanned by {@link #loadFromDirectory()}, when no override is set. */
    private static final String DEFAULT_ROUTES_DIR = "routes";

    /** System property overriding the scanned directory for multi-file configuration loading. */
    public static final String ROUTES_DIR_PROPERTY = "guanaco.routes.dir";

    /** System property opting into permitting a scanned directory to mix JSON and YAML/YML files. */
    public static final String ALLOW_MIXED_FORMATS_PROPERTY = "guanaco.config.allowMixedFormats";

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    /** Creates a loader with strict-duplicate-key-detecting YAML and JSON mappers. */
    public ConfigLoader() {
        this.yamlMapper = newStrictMapper(new YAMLFactory());
        this.jsonMapper = newStrictMapper(new JsonFactory());
    }

    private static ObjectMapper newStrictMapper(com.fasterxml.jackson.core.JsonFactory factory) {
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.findAndRegisterModules();
        // Applies uniformly to YAML and JSON — both go through Jackson's
        // streaming parser API, so one feature flag covers both formats.
        mapper.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        return mapper;
    }

    /**
     * Load from the default classpath location, auto-detecting format by
     * trying {@code routes.json}, {@code routes.yaml}, then {@code routes.yml}
     * in that order. JSON takes precedence if present.
     *
     * @return the loaded configuration
     */
    public GuanacoConfig load() {
        return loadDefault(DEFAULT_BASE_NAME);
    }

    /**
     * Package-private so tests can point default-resolution at an isolated
     * classpath subdirectory, rather than the shared root resources used by
     * other tests' routes.yaml fixture.
     *
     * @param baseName the base configuration file name without extension
     * @return the loaded configuration
     */
    GuanacoConfig loadDefault(String baseName) {
        String jsonPath = baseName + ".json";
        String yamlPath = baseName + ".yaml";
        String ymlPath  = baseName + ".yml";

        boolean hasJson = classpathResourceExists(jsonPath);
        boolean hasYaml = classpathResourceExists(yamlPath);
        boolean hasYml  = classpathResourceExists(ymlPath);

        if (hasJson) {
            if (hasYaml || hasYml) {
                log.warn("Both a JSON and a YAML configuration file were found for '{}' — " +
                        "the JSON file takes precedence and the YAML file is ignored.", baseName);
            }
            return load(jsonPath);
        }

        if (hasYaml) {
            return load(yamlPath);
        }

        if (hasYml) {
            return load(ymlPath);
        }

        throw new GuanacoConfigException(
                "Could not find a configuration file. Looked for classpath:" + jsonPath +
                ", classpath:" + yamlPath + ", and classpath:" + ymlPath + ".");
    }
    
    /**
     * Checks whether a single-file configuration exists at the default
     * classpath location ({@code routes.json}, {@code routes.yaml}, or
     * {@code routes.yml}), without loading it -- used by
     * {@link io.github.lilaschuda.guanaco.context.GuanacoContext} to decide
     * whether to use {@link #load()} or fall back to
     * {@link #loadFromDirectory()}.
     *
     * @return {@code true} if any of the three default single-file conventions exists
     */
    public boolean singleFileConfigExists() {
        return classpathResourceExists(DEFAULT_BASE_NAME + ".json")
                || classpathResourceExists(DEFAULT_BASE_NAME + ".yaml")
                || classpathResourceExists(DEFAULT_BASE_NAME + ".yml");
    }
    
    /**
     * Load from a specific classpath resource path. Format is inferred from
     * the resource's file extension.
     *
     * @param classpathResource the classpath-relative path to the configuration file
     * @return the loaded configuration
     */
    public GuanacoConfig load(String classpathResource) {
        ObjectMapper mapper = resolveMapper(classpathResource);

        log.info("Loading guanaco config from classpath:{}", classpathResource);
        InputStream stream = getClass().getClassLoader().getResourceAsStream(classpathResource);
        if (stream == null) {
            throw new GuanacoConfigException(
                    "Could not find routes config at classpath:" + classpathResource);
        }

        try {
            GuanacoConfig config = mapper.readValue(stream, GuanacoConfig.class);
            log.info("Loaded {} route(s), validation mode: {}",
                    config.getRoutes() == null ? 0 : config.getRoutes().size(),
                    config.getFramework().getValidation());
            return config;
        } catch (Exception e) {
            throw new GuanacoConfigException(
                    "Failed to parse routes config at classpath:" + classpathResource + ": " + e.getMessage(), e);
        }
    }

    /**
     * Loads configuration by scanning a classpath directory of multiple
     * files and merging them into one combined configuration -- see
     * {@link ConfigTreeMerger} for the merge semantics and
     * {@link ConfigDirectoryScanner} for file discovery.
     *
     * <p>The scanned directory defaults to {@value #DEFAULT_ROUTES_DIR},
     * overridable via the {@value #ROUTES_DIR_PROPERTY} system property --
     * e.g. to point at a different directory per environment (a local YAML
     * directory for development, a generated JSON directory for
     * deployment). {@value #ALLOW_MIXED_FORMATS_PROPERTY} controls whether
     * that directory may mix JSON and YAML/YML files at all; unset or
     * {@code false}, the default, means it may not.
     *
     * @return the loaded, merged configuration
     */
    public GuanacoConfig loadFromDirectory() {
        String directory = System.getProperty(ROUTES_DIR_PROPERTY, DEFAULT_ROUTES_DIR);
        return loadFromDirectory(directory, Boolean.getBoolean(ALLOW_MIXED_FORMATS_PROPERTY));
    }

    /**
     * Loads configuration by scanning a specific classpath directory,
     * bypassing the {@value #ROUTES_DIR_PROPERTY} system property.
     * {@value #ALLOW_MIXED_FORMATS_PROPERTY} still applies.
     *
     * @param classpathDirectory the classpath directory to scan, e.g. {@code "routes"}
     * @return the loaded, merged configuration
     */
    public GuanacoConfig loadFromDirectory(String classpathDirectory) {
        return loadFromDirectory(classpathDirectory, Boolean.getBoolean(ALLOW_MIXED_FORMATS_PROPERTY));
    }

    /**
     * Package-private so tests can point directory-based resolution at an
     * isolated classpath subdirectory with an explicit mixed-formats
     * setting, bypassing both system properties -- the same reasoning
     * {@link #loadDefault} already applies to the single-file case.
     *
     * @param classpathDirectory the classpath directory to scan, e.g. {@code "routes"}
     * @param allowMixedFormats whether the directory may mix JSON and YAML/YML files
     * @return the loaded, merged configuration
     */
    GuanacoConfig loadFromDirectory(String classpathDirectory, boolean allowMixedFormats) {
        ConfigDirectoryScanner scanner = new ConfigDirectoryScanner(yamlMapper, jsonMapper);
        ConfigDirectoryScanner.ScanResult scanResult = scanner.scan(classpathDirectory, allowMixedFormats);

        ObjectNode merged = ConfigTreeMerger.merge(scanResult.trees(), scanResult.names());

        try {
            // treeToValue is format-agnostic -- the tree's own content
            // already reflects whichever format(s) each source file was
            // originally parsed from during the scan, and either mapper
            // instance binds a tree to a POJO identically regardless of
            // which JsonFactory it was constructed with.
            GuanacoConfig config = yamlMapper.treeToValue(merged, GuanacoConfig.class);
            log.info("Loaded {} route(s) from {} file(s) under classpath*:{}/, validation mode: {}",
                    config.getRoutes() == null ? 0 : config.getRoutes().size(),
                    scanResult.trees().size(), classpathDirectory, config.getFramework().getValidation());
            return config;
        } catch (Exception e) {
            throw new GuanacoConfigException(
                    "Failed to bind merged configuration from classpath*:" + classpathDirectory + "/ to "
                    + "GuanacoConfig: " + e.getMessage(), e);
        }
    }

    private ObjectMapper resolveMapper(String classpathResource) {
        String lower = classpathResource.toLowerCase();
        if (lower.endsWith(".json")) {
            return jsonMapper;
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return yamlMapper;
        }
        throw new UnsupportedConfigFormatException(
                "Unrecognized configuration file extension for '" + classpathResource +
                "'. Supported extensions are .json, .yaml, and .yml.");
    }

    private boolean classpathResourceExists(String classpathResource) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            return stream != null;
        } catch (Exception e) {
            return false;
        }
    }
}