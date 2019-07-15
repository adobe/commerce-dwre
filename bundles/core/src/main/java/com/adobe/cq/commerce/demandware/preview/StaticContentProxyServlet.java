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

package com.adobe.cq.commerce.demandware.preview;

import com.adobe.cq.commerce.demandware.DemandwareClient;
import com.adobe.cq.commerce.demandware.DemandwareClientProvider;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.BufferedOutputStream;
import java.io.IOException;

/**
 * Simple proxy for static content assets (images, js, css) with relative URLs to Demandware.
 */
@Component(label = "Demandware Static Content Proxy Servlet", immediate = true)
@SlingServlet(paths = {"/on/demandware"}, extensions = {"static"}, methods = "GET", generateComponent = false)
public class StaticContentProxyServlet extends SlingSafeMethodsServlet {
    
    private static final Logger LOG = LoggerFactory.getLogger(StaticContentProxyServlet.class);
    
    @Reference
    private DemandwareClientProvider clientProvider;
    
    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException,
            IOException {
        
        RequestPathInfo pathInfo = request.getRequestPathInfo();
        LOG.debug("Proxy static content for {}", pathInfo.toString());
        final String remoteUri = DemandwareClient.DEFAULT_SCHEMA + clientProvider.getDemandwareClientByInstanceId(clientProvider.getInstanceId(getPage(request))) + pathInfo.getResourcePath() +
                "." + pathInfo.getExtension() + pathInfo.getSuffix();
        
        final CloseableHttpClient httpClient = clientProvider.getDefaultClient().getHttpClient();
        CloseableHttpResponse responseObj = null;
        BufferedOutputStream output = null;
        try {
            final RequestBuilder requestBuilder = RequestBuilder.get();
            requestBuilder.setUri(remoteUri);
            final HttpUriRequest requestObj = requestBuilder.build();
            
            responseObj = httpClient.execute(requestObj);
            final HttpEntity responseObjEntity = responseObj.getEntity();
            if (responseObjEntity != null) {
                response.setContentType(responseObjEntity.getContentType().getValue());
                response.setContentLength((int) responseObjEntity.getContentLength());
                output = new BufferedOutputStream(response.getOutputStream());
                final byte[] bytes = EntityUtils.toByteArray(responseObjEntity);
                output.write(bytes, 0, bytes.length);
            }
        } finally {
            HttpClientUtils.closeQuietly(responseObj);
            HttpClientUtils.closeQuietly(httpClient);
            IOUtils.closeQuietly(output);
        }
    }
    
    private Page getPage(SlingHttpServletRequest request) {
        String path = getRequestedPath(request);
        
        ResourceResolver resourceResolver = request.getResourceResolver();
        Resource resource = resourceResolver.resolve(path);
        
        PageManager pageManager = resource.getResourceResolver().adaptTo(PageManager.class);
        return pageManager.getPage(path);
    }
    
    private String getRequestedPath(SlingHttpServletRequest request) {
        String referer = request.getHeader("Referer");
        String host = request.getHeader("Host");
        return StringUtils.substringBetween(referer, host, ".html");
    }
}
