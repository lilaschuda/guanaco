package io.github.lilaschuda.guanaco.config;

import io.github.lilaschuda.guanaco.config.exception.GuanacoConfigException;
import io.github.lilaschuda.guanaco.config.exception.UnsupportedConfigFormatException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Loads and parses the guanaco routes configuration file.
 *
 * <p>Supports both YAML and JSON. Format is determined purely by file
 * extension — there is no separate format property to configure or keep in
 * sync with the actual file. Two conventions are supported:
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
 * <p>Both formats are parsed with strict duplicate-key detection enabled —
 * a configuration file with the same key repeated at the same level fails
 * to load immediately, rather than silently keeping whichever value Jackson
 * happens to parse last.
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String DEFAULT_BASE_NAME = "routes";

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