/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Copyright 2019 Adobe Systems Incorporated
 ~
 ~ Licensed under the Apache License, Version 2.0 (the "License");
 ~ you may not use this file except in compliance with the License.
 ~ You may obtain a copy of the License at
 ~
 ~     http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing, software
 ~ distributed under the License is distributed on an "AS IS" BASIS,
 ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ~ See the License for the specific language governing permissions and
 ~ limitations under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package com.adobe.cq.commerce.demandware.replication.transport;

import com.adobe.cq.commerce.demandware.DemandwareClient;
import com.adobe.cq.commerce.demandware.DemandwareClientProvider;
import com.adobe.cq.commerce.demandware.DemandwareCommerceConstants;
import com.adobe.cq.commerce.demandware.replication.TransportHandlerPlugin;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationLog;
import com.github.sardine.Sardine;
import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.SardineImpl;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.*;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * <code>TransportHandlerPlugin</code> to send static files to WebDAV.
 */
@Component
@Service(value = TransportHandlerPlugin.class)
@Properties({
        @Property(name = TransportHandlerPlugin.PN_TASK, value = "Demandware WebDAV Transport Plugin", propertyPrivate = true),
        @Property(name = Constants.SERVICE_RANKING, intValue = 30)
})
public class WebDAVTransportPlugin extends AbstractTransportHandlerPlugin {
    private static final Logger LOG = LoggerFactory.getLogger(WebDAVTransportPlugin.class);

    @Reference
    private DemandwareClientProvider clientProvider;

    @Override
    protected DemandwareClientProvider getClientProvider() {
        return clientProvider;
    }

    @Override
    String getApiType() {
        return DemandwareCommerceConstants.TYPE_WEBDAV;
    }

    @Override
    String getContentType() {
        return "static-asset";
    }

    @Override
    public boolean deliver(JSONObject delivery, AgentConfig config, ReplicationLog log, ReplicationAction action)
        throws ReplicationException {

        // construct the WebDAV request
        String path = null;
        final String endpoint = getClientProvider().getClientForSpecificInstance(config).getWebDavEndpoint();
        final StringBuilder transportUriBuilder = new StringBuilder();
        transportUriBuilder.append(DemandwareClient.DEFAULT_SCHEMA);
        transportUriBuilder.append(endpoint);
        try {
            transportUriBuilder.append(
                constructEndpointURL(delivery.getString(DemandwareCommerceConstants.ATTR_WEBDAV_SHARE), delivery));
            path = delivery.getString(DemandwareCommerceConstants.ATTR_PATH);
            path = StringUtils.substringBeforeLast(path, "/") + "/" + URLEncoder.encode(StringUtils
                .substringAfterLast(path, "/"), "UTF-8").replaceAll("\\+", "%20");
        } catch (JSONException e) {
            LOG.error("Can not create endpoint URI", e);
            throw new ReplicationException("Can not create endpoint URI", e);
        } catch (UnsupportedEncodingException e) {
            LOG.error("Can not encode URI", e);
            throw new ReplicationException("Can not encode URI", e);
        }
        log.info("Deliver %s to %s (%s)", path, transportUriBuilder.toString(), action.getType().getName());

        final HttpClientBuilder httpClientBuilder = getHttpClientBuilder(config, log);
        if (action.getType() == ReplicationActionType.ACTIVATE) {
            // get asset / file to be delivered
            byte[] data;
            String contentType;
            try {
                JSONObject assetData = delivery.getJSONObject(DemandwareCommerceConstants.ATTR_PAYLOAD);
                if (assetData.has(DemandwareCommerceConstants.ATTR_BASE64)) {
                    data = Base64.decodeBase64(assetData.getString(DemandwareCommerceConstants.ATTR_DATA));
                } else {
                    data = assetData.getString(DemandwareCommerceConstants.ATTR_DATA).getBytes();
                }
                contentType = assetData.getString(DemandwareCommerceConstants.ATTR_MIMETYPE);
            } catch (JSONException e) {
                LOG.error("Can not create asset data", e);
                throw new ReplicationException("Can not create asset data", e);
            }

            // send asset to WebDAV share
            if (data != null && StringUtils.isNotEmpty(contentType)) {

                deliverWebDAV(httpClientBuilder, transportUriBuilder.toString(), path, data, contentType, log);
                return true;
            } else {
                log.warn("No asset data to send !?");
                return false;
            }
        } else {
            deleteWevDAV(httpClientBuilder, transportUriBuilder.toString(), action.getPath(), log);
            return true;
        }
    }

    /**
     * Upload asset data to webdav share.
     *
     * @param httpClientBuilder the HTTP client builder to be used
     * @param transportUri      the endpoint including protocol and hostname
     * @param path              the path to the resource
     * @param data              the data to be uploaded
     * @param contentType       the content type
     * @param log               the replication log
     * @throws ReplicationException if an error occurs
     */
    private void deliverWebDAV(HttpClientBuilder httpClientBuilder, String transportUri, String path, byte[] data,
                               String contentType, ReplicationLog log)
        throws ReplicationException {
        Sardine sardine = new SardineImpl(httpClientBuilder);
        try {
            // establish the folders first
            getOrCreateFolders(sardine, transportUri, path, log);

            // sent put request
            log.debug("Upload %s ...", path);
            sardine.put(transportUri + path, data, contentType);
            log.debug("Upload done.");
        } catch (IOException e) {
            throw new ReplicationException(e);
        } finally {
            try {
                sardine.shutdown();
            } catch (IOException e) {
                log.warn("Error shut down WebDAV client %s", e.getMessage());
            }
        }
    }

    /**
     * Get or creates the folder structure for a given asset path.
     *
     * @param sardine     the WebDAV client
     * @param endPointUrl the endpoint url
     * @param path        the asset path
     * @throws IOException if an error occurs
     */
    private void getOrCreateFolders(Sardine sardine, String endPointUrl, String path, ReplicationLog log) throws
        IOException {
        final String folderPath = StringUtils.substringBeforeLast(path, "/");
        final String[] folders = StringUtils.split(folderPath, "/");
        for (String folder : folders) {
            endPointUrl = StringUtils.appendIfMissing(endPointUrl, "/", "/") + folder;
            if (sardine.exists(endPointUrl)) {
                continue;
            }
            log.debug("Create missing WebDAV folder %s", endPointUrl);
            sardine.createDirectory(endPointUrl);
        }
    }

    /**
     * Delete a WebDAV resource.
     *
     * @param httpClientBuilder the Http client builder
     * @param transportUri      the endpoint including protocol and hostname
     * @param path              the path to the resource
     * @param log               the replication log
     * @throws ReplicationException if an error occurs
     */
    private void deleteWevDAV(HttpClientBuilder httpClientBuilder, String transportUri, String
        path, ReplicationLog log) throws ReplicationException {
        Sardine sardine = new SardineImpl(httpClientBuilder);
        try {
            log.info("Delete %s", transportUri + path);
            sardine.delete(transportUri + path);
        } catch (SardineException e) {
            if (e.getStatusCode() != HttpStatus.SC_NOT_FOUND) {
                throw new ReplicationException(e);
            }
        } catch (IOException e) {
            throw new ReplicationException(e);
        } finally {
            try {
                sardine.shutdown();
            } catch (IOException e) {
                log.warn("Error shut down WebDAV client %s", e.getMessage());
            }
        }
    }

    /**
     * Setup transport user and other credentials.
     */
    @Override
    protected CredentialsProvider createCredentialsProvider(DemandwareClient client, ReplicationLog log) {
        // set default user/pass
        if (StringUtils.isNotEmpty(client.getWebDavUser())) {
            log.debug("WebDAV auth user: %s", client.getWebDavUser());
            final CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(client.getWebDavUser(), client.getWebDavUserPassword()));
            return credsProvider;
        }
        return null;
    }
}
