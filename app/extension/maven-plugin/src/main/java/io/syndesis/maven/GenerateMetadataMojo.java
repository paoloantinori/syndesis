/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.maven;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.atlasmap.core.DefaultAtlasConversionService;
import io.atlasmap.java.inspect.ClassInspectionService;
import io.atlasmap.java.service.AtlasJsonProvider;
import io.atlasmap.java.v2.JavaClass;
import io.syndesis.core.Json;
import io.syndesis.core.Names;
import io.syndesis.extension.converter.ExtensionConverter;
import io.syndesis.model.DataShape;
import io.syndesis.model.action.ActionDescriptor;
import io.syndesis.model.action.ExtensionAction;
import io.syndesis.model.action.ExtensionDescriptor;
import io.syndesis.model.connection.ConfigurationProperty;
import io.syndesis.model.extension.Extension;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;


/**
 * Helper Maven plugin
 *
 * @author pantinor
 */
@Mojo(name = "generate-metadata", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresProject = true, threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class GenerateMetadataMojo extends AbstractMojo {
    public enum InspectionMode {
        RESOURCE,
        SPECIFICATION,
        RESOURCE_AND_SPECIFICATION
    }

    @Component
    private ArtifactFactory artifactFactory;

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;

    @Parameter(readonly = true, defaultValue = "mapper/v1/java-inspections")
    private String inspectionsResourceDir;

    @Parameter(readonly = true, defaultValue = "${project.build.directory}/classes/META-INF/syndesis")
    private File inspectionsOutputDir;

    @Parameter(readonly = true, defaultValue = "${project.build.directory}/classes/META-INF/syndesis/syndesis-extension-definition.json")
    private String metadataDestination;

    @Parameter(readonly = true, defaultValue = "${repositorySystemSession}", required = true)
    private RepositorySystemSession repoSession;

    @Parameter(readonly = true, defaultValue = "${project.remoteProjectRepositories}")
    private List<RemoteRepository> remoteRepos;

    @Parameter(defaultValue = "${project.groupId}:${project.artifactId}")
    private String extensionId;

    @Parameter
    private String version;

    @Parameter(defaultValue = "${project.name}")
    private String name;

    @Parameter(defaultValue = "${project.description}")
    private String description;

    @Parameter
    private String icon;

    @Parameter
    private String tags;

    @Parameter(defaultValue = "RESOURCE_AND_SPECIFICATION")
    private InspectionMode inspectionMode = InspectionMode.RESOURCE_AND_SPECIFICATION;

    /**
     * Partial Extension JSON descriptor to augment
     */
    @Parameter(readonly = true)
    private String source;

    @Parameter(defaultValue = "false")
    private Boolean listAllArtifacts;

    protected Extension.Builder extensionBuilder = new Extension.Builder();
    protected Map<String, ExtensionAction> actions = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException {
        tryImportingPartialJSON();
        processAnnotations();
        overrideConfigFromMavenPlugin();
        includeDependencies();

        Extension extension = extensionBuilder.actions(actions.values()).build();

        saveExtensionMetaData(extension);
    }

    protected void processAnnotations() throws MojoExecutionException {
        String directory = project.getModel().getBuild().getDirectory();
        Path dir = Paths.get(directory, "generated-sources", "annotations");
        if (Files.exists(dir)) {
            getLog().info("Looking in for annotated classes in: " + dir);
            try {
                final Properties p = new Properties();
                final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**.properties");
                final List<Path> paths = Files.find(dir, Integer.MAX_VALUE, (path, attr) -> matcher.matches(path)).sorted().collect(Collectors.toList());

                for (Path path: paths) {
                    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        getLog().info("Loading annotations properties from: " + path);

                        p.clear();
                        p.load(reader);

                        assignProperties(p);
                    } catch (IOException e) {
                        getLog().error("Error reading file " + path);
                    }
                };
            } catch (Exception e) {
                throw new MojoExecutionException("Error checking annotations.", e);
            }
        } else {
            getLog().debug("Path " + dir + " does not exists");
        }
    }

    @SuppressWarnings("PMD.PrematureDeclaration")
    protected void assignProperties(Properties p) throws Exception {

        final String actionId = p.getProperty("id");
        final String actionName = p.getProperty("name");
        final String actionKind = p.getProperty("kind");
        final String actionEntry = p.getProperty("entrypoint");

        if (StringUtils.isEmpty(actionId)) {
            getLog().warn("Unable to define action, reason: 'id' is not set (properties: " + p + ")");
            return;
        }
        if (StringUtils.isEmpty(actionName)) {
            getLog().warn("Unable to define action, reason: 'name' is not set (properties: " + p + ")");
            return;
        }
        if (StringUtils.isEmpty(actionKind)) {
            getLog().warn("Unable to define action, reason: 'kind' is not set (properties: " + p + ")");
            return;
        }
        if (StringUtils.isEmpty(actionEntry)) {
            getLog().warn("Unable to define action, reason: 'entrypoint' is not set (properties: " + p + ")");
            return;
        }

        ExtensionAction.Builder actionBuilder = new ExtensionAction.Builder();
        if (actions.containsKey(actionId)) {
            // Create action from existing action if available in the partial json
            actionBuilder = actionBuilder.createFrom(actions.get(actionId));
        }

        String description = p.getProperty("description");
        if(StringUtils.isNotEmpty(description)){
            actionBuilder.description(description);
        }

        String tags = p.getProperty("tags");
        if(StringUtils.isNotEmpty(tags)){
            for (String tag : tags.trim().split(",")) {
                actionBuilder.addTag(tag);
            }
        }

        List<ActionDescriptor.ActionDescriptorStep> propertyDefinitionSteps = createPropertiesDefinitionSteps(p);

        actions.put(
            actionId,
            actionBuilder.id(actionId)
                .name(actionName)
                .descriptor(
                    new ExtensionDescriptor.Builder()
                        .kind(ExtensionAction.Kind.valueOf(actionKind))
                        .entrypoint(actionEntry)
                        .inputDataShape(buildDataShape(actionId, p.getProperty("inputDataShape")))
                        .outputDataShape(buildDataShape(actionId, p.getProperty("outputDataShape")))
                        .propertyDefinitionSteps(propertyDefinitionSteps)
                        .build())
                .build()
        );
    }

    protected DataShape buildDataShape(String actionId, String dataShape) throws Exception {
        DataShape.Builder builder = new DataShape.Builder();
        if (StringUtils.isNotEmpty(dataShape)) {
            int separator = dataShape.indexOf(':');
            String kind = null;
            String type = null;

            if (separator == -1) {
                kind = dataShape;
            } else {
                kind = dataShape.substring(0, separator);
                type = dataShape.substring(separator + 1);

            }

            if (StringUtils.isNotEmpty(kind)) {
                builder.kind(kind);
            }
            if (StringUtils.isNotEmpty(type)) {
                builder.type(type);
            }

            if (StringUtils.isNotEmpty(kind) && StringUtils.isNotEmpty(type)) {
                generateInspections(actionId, kind, type).ifPresent(builder::specification);
            }
        } else {
            builder.kind("any");
        }

        return builder.build();
    }

    protected List<ActionDescriptor.ActionDescriptorStep> createPropertiesDefinitionSteps(Properties p) {
        List<ActionDescriptor.ActionDescriptorStep> propertyDefinitionSteps = new ArrayList<>();
        int idx = 0;
        while (getPropertyValue(p, idx, "name") != null) {
            String name = getPropertyValue(p, idx, "name");
            String displayName = getPropertyValue(p, idx, "displayName");
            String pDescription = getPropertyValue(p, idx, "description");
            Boolean componentProperty = getBooleanPropertyValue(p, idx, "componentProperty");
            String defaultValue = getPropertyValue(p, idx, "defaultValue");
            Boolean deprecated = getBooleanPropertyValue(p, idx, "deprecated");
            String group = getPropertyValue(p, idx, "group");
            String javaType = getPropertyValue(p, idx, "javaType");
            String kind = getPropertyValue(p, idx, "kind");
            String label = getPropertyValue(p, idx, "label");
            Boolean required = getBooleanPropertyValue(p, idx, "required");
            Boolean secret = getBooleanPropertyValue(p, idx, "secret");
            String type = getPropertyValue(p, idx, "type");

            String pTags = getPropertyValue(p, idx, "tags");
            List<String> propTagList = new ArrayList<>();
            if(StringUtils.isNotEmpty(pTags)){
                for (String tag : pTags.trim().split(",")) {
                    propTagList.add(tag);
                }
            }

            int idy = 0;
            List<ConfigurationProperty.PropertyValue> propertyValues = new ArrayList<>();
            while (getPropertyValue(p, idx, "enums", idy, "value") != null) {
                String enumValue = getPropertyValue(p, idx, "enums", idy, "value");
                String enumLabel = getPropertyValue(p, idx, "enums", idy, "label");
                propertyValues.add(new ConfigurationProperty.PropertyValue.Builder()
                    .value(enumValue)
                    .label(enumLabel)
                    .build());

                idy++;
            }

            propertyDefinitionSteps.add(new ActionDescriptor.ActionDescriptorStep.Builder()
                .name(name)
                .description(pDescription)
                .putProperty(name, new ConfigurationProperty.Builder()
                    .displayName(displayName)
                    .description(pDescription)
                    .componentProperty(componentProperty)
                    .defaultValue(defaultValue)
                    .deprecated(deprecated)
                    .group(group)
                    .javaType(javaType)
                    .kind(kind)
                    .label(label)
                    .required(required)
                    .secret(secret)
                    .type(type)
                    .tags(propTagList)
                    .addAllEnum(propertyValues)
                    .build())
                .build());

            idx++;
        }

        return propertyDefinitionSteps;
    }

    /**
     * Loads a partial metadata json file, if configured at Maven Plugin level.
     * @throws MojoExecutionException
     */
    protected void tryImportingPartialJSON() throws MojoExecutionException {
        File template;

        if(StringUtils.isNotEmpty(source)){
            template = new File(source);
        } else {
            template = new File(metadataDestination);
        }

        if (template.exists()) {
            try {
                JsonNode tree = Json.mapper().readTree(template);
                Extension extension = ExtensionConverter.getDefault().toInternalExtension(tree);
                getLog().info("Loaded base partial metadata configuration file: " + source);

                actions.clear();
                actions.putAll(
                    extension.getActions().stream()
                        .filter(a -> a.getId().isPresent())
                        .collect(Collectors.toMap(a -> a.getId().get(), a -> a))
                );

                extensionBuilder = extensionBuilder.createFrom(extension);
                extensionBuilder.actions(Collections.emptySet());
            } catch (IOException e) {
                throw new MojoExecutionException("Invalid input json: " + source, e );
            }
        }
    }

    protected void overrideConfigFromMavenPlugin() {
        getLog().info("Looking for configuration to override at Maven Plugin configuration level. ");

        if(StringUtils.isBlank(extensionId)) {
            extensionBuilder.extensionId(project.getGroupId() + ":" + project.getArtifactId());
        } else {
            extensionBuilder.extensionId(extensionId);
        }

        if(StringUtils.isBlank(version)){
            version = project.getVersion();
        }
        extensionBuilder.version(version);

        if(StringUtils.isNotEmpty(name)) {
            extensionBuilder.name(name);
        }

        if(StringUtils.isNotEmpty(description)) {
            extensionBuilder.description(description);
        }

        if(StringUtils.isNotEmpty(icon)) {
            extensionBuilder.icon(icon);
        }

        if(StringUtils.isNotEmpty(tags)){
            String[] split = tags.split(",");
            extensionBuilder.tags(Arrays.asList(split));
        }
    }

    protected void includeDependencies() {
        Stream<Artifact> artifacts;

        if (Boolean.TRUE.equals(listAllArtifacts)) {
            artifacts = project.getArtifacts().stream()
                .filter(artifact -> StringUtils.equals(artifact.getScope(), DefaultArtifact.SCOPE_PROVIDED));
        } else {
            artifacts = this.project.getDependencies().stream()
                .filter(dependency -> StringUtils.equals(dependency.getScope(), DefaultArtifact.SCOPE_PROVIDED))
                .map(this::toArtifact);
        }

        artifacts.map(Artifact::getId)
            .sorted()
            .map(io.syndesis.model.Dependency::maven)
            .forEachOrdered(extensionBuilder::addDependency);
    }

    protected void saveExtensionMetaData(Extension jsonObject) throws MojoExecutionException {
        File targetFile = new File(metadataDestination);
        if (!targetFile.getParentFile().exists() &&
            !targetFile.getParentFile().mkdirs()) {
            throw new MojoExecutionException("Cannot create directory " + targetFile.getParentFile());
        }
        try {
            JsonNode tree = ExtensionConverter.getDefault().toPublicExtension(jsonObject);
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(targetFile, tree);
            getLog().info("Created file " + targetFile.getAbsolutePath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write to file: " + metadataDestination, e);
        }
    }

    @Nullable
    protected Boolean getBooleanPropertyValue(Properties props, int prg, String name) {
        String val = getPropertyValue(props, prg, name);
        if (val == null) {
            return null;
        }
        return "true".equalsIgnoreCase(val);
    }

    protected String getPropertyValue(Properties props, int prg, String name) {
        return props.getProperty("property[" + prg + "]." + name);
    }

    protected String getPropertyValue(Properties props, int prg1, String root2, int prg2, String name) {
        return props.getProperty("property[" + prg1 + "]." + root2 + "[" + prg2 + "]." + name);
    }

    // ****************************************
    // Inspections
    // ****************************************

    private Optional<String> generateInspections(String actionId, String kind, String type) throws Exception {
        Optional<String> specification = Optional.empty();

        if (StringUtils.equals("java", kind)) {
            final String name = Names.sanitize(actionId);

            File outputFile = new File(inspectionsOutputDir, String.format("%s/%s/%s.json", inspectionsResourceDir, name, type));
            if (!outputFile.getParentFile().exists()) {
                if (outputFile.getParentFile().mkdirs()) {
                    getLog().debug("Directory " + outputFile.getParentFile() + " created");
                }
            }

            getLog().info("Generating inspection for action: " + actionId + " (" + name + "), and type: " + type);

            final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            final List<String> elements = project.getCompileClasspathElements();
            final URL[] classpath = new URL[elements.size()];

            for (int i = 0; i < elements.size(); i++) {
                classpath[i] = new File(elements.get(i)).toURI().toURL();

                getLog().debug("Add element to classpath: " + classpath[i]);
            }

            try (URLClassLoader loader = new URLClassLoader(classpath, tccl)) {
                ClassInspectionService classInspectionService = new ClassInspectionService();
                classInspectionService.setConversionService(DefaultAtlasConversionService.getInstance());

                final Class<?> clazz = loader.loadClass(type);
                final JavaClass c = classInspectionService.inspectClass(loader, clazz);
                final ObjectMapper mapper = AtlasJsonProvider.createObjectMapper();

                if (inspectionMode == InspectionMode.SPECIFICATION || inspectionMode == InspectionMode.RESOURCE_AND_SPECIFICATION) {
                    specification = Optional.of(mapper.writeValueAsString(c));
                    getLog().info("Specification for type: " + type + " created");
                }

                if (inspectionMode == InspectionMode.RESOURCE || inspectionMode == InspectionMode.RESOURCE_AND_SPECIFICATION) {
                    mapper.writeValue(outputFile, c);
                    getLog().info("Created: " + outputFile);
                }
            }
        }

        return specification;
    }

    // ****************************************
    // Helpers
    // ****************************************

    protected Artifact toArtifact(Dependency dependency) {
        return artifactFactory.createArtifact(
            dependency.getGroupId(),
            dependency.getArtifactId(),
            dependency.getVersion(),
            dependency.getScope(),
            dependency.getType()
        );
    }

}
