/**
 * Copyright (C) 2016 Red Hat, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.rest.v1beta1.util;

import io.fabric8.openshift.client.NamespacedOpenShiftClient;
import io.syndesis.model.ListResult;
import io.syndesis.model.integration.Integration;
import io.syndesis.openshift.OpenShiftConfigurationProperties;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.syndesis.rest.v1.handler.integration.IntegrationHandler;

@Service
public class SupportUtil {
    public static final Logger LOG = LoggerFactory.getLogger(SupportUtil.class);

    private final NamespacedOpenShiftClient client;
    private final OpenShiftConfigurationProperties config;
    private final IntegrationHandler integrationHandler;

    public SupportUtil(NamespacedOpenShiftClient client, OpenShiftConfigurationProperties config, IntegrationHandler integrationHandler) {
        this.client = client;
        this.config = config;
        this.integrationHandler = integrationHandler;
    }

    public File createSupportZipFile(Map<String, Boolean> configurationMap, UriInfo uriInfo) {
        File zipFile = null;
        try{
            zipFile = File.createTempFile("syndesis.", ".zip");
        } catch (IOException e) {
            LOG.error("Error creating Support zip file", e);
            throw new WebApplicationException(500);
        }

        try ( ZipOutputStream os = new ZipOutputStream(new FileOutputStream(zipFile));) {
            configurationMap.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).forEach(integrationName -> {
                        switch(integrationName){
                            case "platformLogs":
                                Stream.of("syndesis-atlasmap","syndesis-db", "syndesis-oauthproxy", "syndesis-rest", "syndesis-ui", "syndesis-verifier").forEach(componentName -> {
                                    getComponentLogs(componentName).ifPresent((String fileContent) -> {
                                        try {
                                            addEntryToZip(componentName, fileContent, os);
                                        } catch (IOException e) {
                                            LOG.error("Error preparing logs for: " + componentName, e);
                                        }
                                    });
                                });
                                break;
                            default:
                                getIntegrationLogs(integrationName).ifPresent((String fileContent) -> {
                                    try {
                                        addEntryToZip(integrationName, fileContent, os);
                                    } catch (IOException e) {
                                        LOG.error("Error preparing logs for: " + integrationName, e);
                                    }

                                    ListResult<Integration> list = integrationHandler.list(uriInfo);
                                    list.getItems().stream().filter(integration -> integrationName.equalsIgnoreCase(integration.getName().replace(' ', '-'))).forEach(
                                            integration -> {
                                                integration.getId().ifPresent(id -> {
                                                    try {
                                                        addSourceEntryToZip(integrationName, id, os);
                                                    } catch (IOException e) {
                                                        e.printStackTrace();
                                                    }
                                                });
                                            }
                                    );
                                });

                        }
                    }

            );
            LOG.info("Created Support file: {}", zipFile);
        } catch (IOException e) {
            LOG.error("Error producing Support zip file", e);
            throw new WebApplicationException(500);
        }

        return zipFile;
    }

    protected void addSourceEntryToZip(String integrationName, String integrationId, ZipOutputStream os) throws IOException {
        StreamingOutput export = integrationHandler.export(integrationId);
        ZipEntry ze = new ZipEntry(integrationName + ".src.zip");
        os.putNextEntry(ze);

        File file = File.createTempFile(integrationName, ".src.zip");
        export.write(FileUtils.openOutputStream(file));
        FileUtils.copyFile(file, os);
        os.closeEntry();
    }

    protected void addEntryToZip(String integrationName, String fileContent, ZipOutputStream os) throws IOException {
        ZipEntry ze = new ZipEntry(integrationName + ".log");
        os.putNextEntry(ze);
        File file = File.createTempFile(integrationName, ".log");
        FileUtils.writeStringToFile( file, fileContent, Charset.defaultCharset() );
        FileUtils.copyFile(file, os);
        os.closeEntry();
    }


    public Map<String,Map<String,Object>> supportFormConfig() {
        Map<String,Map<String,Object>> config = new LinkedHashMap<>();

        Map<String,Object> platformLogsMap = new LinkedHashMap<>();
        platformLogsMap.put("displayName", "Platform Logs");
        platformLogsMap.put("type", "boolean");
        platformLogsMap.put("description", "Include logs of platform services");
        platformLogsMap.put("default", false);
        config.put("platformLogs", platformLogsMap);

        Map<String,Object> integrationLogsMap = new LinkedHashMap<>();
        integrationLogsMap.put("displayName", "Integrations Logs");
        integrationLogsMap.put("type", "boolean");
        integrationLogsMap.put("description", "Include logs of integrations");
        integrationLogsMap.put("default", true);
        config.put("integrationLogs", integrationLogsMap);

        for (String pod : getIntegrationPods()) {
            Map<String,Object> podMap = new LinkedHashMap<>();
            podMap.put("displayName", pod);
            podMap.put("type", "boolean");
            podMap.put("description", "Include logs of integration " + pod);
            podMap.put("default", true);

            config.put(pod, podMap);
        }

        LOG.trace("SupportFormConfiguration: {}", config);

        return config;
    }

    public Collection<String> getIntegrationPods() {
        Collection<String> collect = client.pods().list().getItems().stream()
                .filter(pod -> pod.getMetadata().getLabels().containsKey("integration"))
                .map(pod -> pod.getMetadata().getLabels().get("integration"))
                .collect(Collectors.toList());
        return collect;
    }

    public Optional<String> getLogs(String label, String integrationName) {
       Optional<String> result =  client.pods().list().getItems().stream()
                .filter(p -> integrationName.equals(p.getMetadata().getLabels().get(label))).findAny().
                        flatMap(p -> Optional.of(client.pods().inNamespace(config.getNamespace()).withName(p.getMetadata().getName()).getLog()));
        return result;
    }

    public Optional<String> getIntegrationLogs(String integrationName){
        return getLogs("integration", integrationName);
    }


    public Optional<String> getComponentLogs(String componentName){
        return getLogs("component", componentName);
    }


}