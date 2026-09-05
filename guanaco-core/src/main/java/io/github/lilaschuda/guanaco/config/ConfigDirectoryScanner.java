package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lilaschuda.guanaco.config.exception.GuanacoConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Discovers and reads the set of configuration files in a scanned classpath
 * directory, resolving each logical file's format precedence and enforcing
 * a single-format-per-directory discipline by default, before handing the
 * resulting parsed trees to {@link ConfigTreeMerger}.
 *
 * <p>Uses Spring's {@code classpath*:} resolution (via
 * {@link PathMatchingResourcePatternResolver}) rather than a single-root
 * {@code classpath:} lookup, so a directory of the same name present on
 * more than one classpath location (e.g. contributed by more than one jar)
 * is merged rather than one location being silently, unpredictably chosen
 * over another with no error at all. Two files with the same logical name
 * AND the same format resolving from two different physical locations is
 * a hard failure regardless -- see {@link #indexByLogicalName}.
 *
 * <p>Per logical file name (filename with its extension stripped), the same
 * JSON-over-YAML-over-YML precedence {@link ConfigLoader}'s single-file
 * resolution already uses applies again here, independently for each name.
 * Whether that's allowed to happen across an entire mixed-format directory
 * at all is a separate, coarser check: by default, a directory containing
 * both any {@code .json} file and any {@code .yaml}/{@code .yml} file fails
 * to load at all -- {@code -Dguanaco.config.allowMixedFormats=true} opts
 * into permitting it (e.g. for an in-progress migration between formats,
 * or a mix of hand-authored and generated files).
 */
class ConfigDirectoryScanner {

    private static final Logger log = LoggerFactory.getLogger(ConfigDirectoryScanner.class);

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /**
     * @param yamlMapper a YAML-configured mapper, already set up with the same
     *                   strict-duplicate-key detection and module registration
     *                   {@link ConfigLoader} applies to single-file loading
     * @param jsonMapper the JSON-configured counterpart
     */
    ConfigDirectoryScanner(ObjectMapper yamlMapper, ObjectMapper jsonMapper) {
        this.yamlMapper = yamlMapper;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Scans {@code classpathDirectory} for configuration files, resolving
     * each logical file's format and reading it into a tree.
     *
     * @param classpathDirectory the classpath directory to scan, e.g.
     *                           {@code "routes"} (no leading or trailing slash)
     * @param allowMixedFormats whether a directory containing both JSON and
     *                          YAML/YML files is permitted at all
     * @return the parsed tree for each winning file, and a matching
     *         human-readable name for each, both in a stable,
     *         deterministic (alphabetical by logical name) order --
     *         ready to pass directly to {@link ConfigTreeMerger#merge}
     * @throws GuanacoConfigException if no files are found, if the directory
     *         mixes formats without {@code allowMixedFormats}, if the same
     *         logical name resolves to more than one file in the same
     *         format, or if any file fails to parse
     */
    ScanResult scan(String classpathDirectory, boolean allowMixedFormats) {
        List<Resource> jsonFiles = findResources(classpathDirectory, "*.json");
        List<Resource> yamlFiles = findResources(classpathDirectory, "*.yaml");
        List<Resource> ymlFiles = findResources(classpathDirectory, "*.yml");

        if (jsonFiles.isEmpty() && yamlFiles.isEmpty() && ymlFiles.isEmpty()) {
            throw new GuanacoConfigException(
                    "No configuration files found under classpath*:" + classpathDirectory + "/ "
                    + "(looked for *.json, *.yaml, *.yml).");
        }

        boolean hasJson = !jsonFiles.isEmpty();
        boolean hasYamlOrYml = !yamlFiles.isEmpty() || !ymlFiles.isEmpty();
        if (hasJson && hasYamlOrYml && !allowMixedFormats) {
            throw new GuanacoConfigException(
                    "Mixed JSON and YAML configuration files found under classpath*:" + classpathDirectory + "/. "
                    + "Guanaco requires a single format per scanned directory by default -- set "
                    + "-Dguanaco.config.allowMixedFormats=true to permit mixing, e.g. for an in-progress "
                    + "migration between formats.");
        }

        Map<String, Resource> byLogicalNameJson = indexByLogicalName(jsonFiles);
        Map<String, Resource> byLogicalNameYaml = indexByLogicalName(yamlFiles);
        Map<String, Resource> byLogicalNameYml = indexByLogicalName(ymlFiles);

        // TreeSet: a stable, deterministic (alphabetical) iteration order.
        // Purely cosmetic -- ConfigTreeMerger's own result is provably
        // independent of the order its inputs arrive in -- but a
        // deterministic scan order still makes log output and any error
        // that DOES occur easier to reproduce and reason about.
        Set<String> allLogicalNames = new TreeSet<>();
        allLogicalNames.addAll(byLogicalNameJson.keySet());
        allLogicalNames.addAll(byLogicalNameYaml.keySet());
        allLogicalNames.addAll(byLogicalNameYml.keySet());

        List<ObjectNode> trees = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (String logicalName : allLogicalNames) {
            Resource winner;
            ObjectMapper mapperForWinner;

            if (byLogicalNameJson.containsKey(logicalName)) {
                winner = byLogicalNameJson.get(logicalName);
                mapperForWinner = jsonMapper;
                if (byLogicalNameYaml.containsKey(logicalName) || byLogicalNameYml.containsKey(logicalName)) {
                    log.warn("Both a JSON and a YAML configuration file were found for '{}' under "
                            + "classpath*:{}/ -- the JSON file takes precedence and the YAML file is ignored.",
                            logicalName, classpathDirectory);
                }
            } else if (byLogicalNameYaml.containsKey(logicalName)) {
                winner = byLogicalNameYaml.get(logicalName);
                mapperForWinner = yamlMapper;
            } else {
                winner = byLogicalNameYml.get(logicalName);
                mapperForWinner = yamlMapper; // the YAML mapper handles both .yaml and .yml
            }

            trees.add(readTree(winner, mapperForWinner));
            names.add(describeResource(winner));
        }

        return new ScanResult(trees, names);
    }

    private List<Resource> findResources(String classpathDirectory, String pattern) {
        try {
            Resource[] found = resolver.getResources("classpath*:" + classpathDirectory + "/" + pattern);
            return new ArrayList<>(Arrays.asList(found));
        } catch (IOException e) {
            throw new GuanacoConfigException(
                    "Failed to scan classpath*:" + classpathDirectory + "/ for " + pattern + " files: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Groups resources of one format by logical name (filename with its
     * extension stripped). Two resources sharing both a logical name and a
     * format -- e.g. the same directory name contributed by two different
     * jars, each with their own {@code orders.yaml} -- is always a hard
     * failure: there is no principled way to pick one over the other, and
     * unlike the JSON-vs-YAML case, this isn't a difference Guanaco has any
     * existing precedence rule for.
     */
    Map<String, Resource> indexByLogicalName(List<Resource> resources) {
        Map<String, Resource> byLogicalName = new LinkedHashMap<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue; // defensive only -- shouldn't happen for a file-pattern match
            }
            String logicalName = stripExtension(filename);
            Resource existing = byLogicalName.put(logicalName, resource);
            if (existing != null) {
                throw new GuanacoConfigException(
                        "'" + logicalName + "' resolved to more than one file with the same format: "
                        + describeResource(existing) + " and " + describeResource(resource) + ". "
                        + "Guanaco cannot determine which one should apply.");
            }
        }
        return byLogicalName;
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private ObjectNode readTree(Resource resource, ObjectMapper mapper) {
        try (InputStream stream = resource.getInputStream()) {
            JsonNode node = mapper.readTree(stream);
            if (!(node instanceof ObjectNode)) {
                throw new GuanacoConfigException(
                        describeResource(resource) + " does not contain a JSON/YAML object at its root.");
            }
            return (ObjectNode) node;
        } catch (IOException e) {
            throw new GuanacoConfigException(
                    "Failed to read " + describeResource(resource) + ": " + e.getMessage(), e);
        }
    }

    private String describeResource(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getFilename();
        }
    }

    /** The parsed tree for each winning file, paired with a matching human-readable name for error messages. */
    record ScanResult(List<ObjectNode> trees, List<String> names) { }
}