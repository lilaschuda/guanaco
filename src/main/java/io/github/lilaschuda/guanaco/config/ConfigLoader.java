package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Loads and parses the guanaco routes.yaml configuration file.
 * Looks for the file on the classpath by default.
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String DEFAULT_CONFIG_PATH = "routes.yaml";

    private final ObjectMapper mapper;

    public ConfigLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory());
        this.mapper.findAndRegisterModules();
    }

    /**
     * Load from the default classpath location: routes.yaml
     */
    public GuanacoConfig load() {
        return load(DEFAULT_CONFIG_PATH);
    }

    /**
     * Load from a specific classpath resource path.
     */
    public GuanacoConfig load(String classpathResource) {
        log.info("Loading guanaco config from classpath:{}", classpathResource);
        InputStream stream = getClass().getClassLoader().getResourceAsStream(classpathResource);
        if (stream == null) {
            throw new GuanacoConfigException(
                "Could not find routes config at classpath:" + classpathResource +
                ". Make sure routes.yaml is on the classpath."
            );
        }
        try {
            GuanacoConfig config = mapper.readValue(stream, GuanacoConfig.class);
            log.info("Loaded {} route(s), validation mode: {}",
                config.getRoutes() == null ? 0 : config.getRoutes().size(),
                config.getFramework().getValidation());
            return config;
        } catch (Exception e) {
            throw new GuanacoConfigException("Failed to parse routes config: " + e.getMessage(), e);
        }
    }
}
