/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.ws.discovery.listeners;

import javax.xml.namespace.QName;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.endpoint.ServerLifeCycleListener;
import org.apache.cxf.ws.discovery.internal.WSDiscoveryServiceImpl;

/**
 * 
 */
public class WSDiscoveryServerListener implements ServerLifeCycleListener {
    static WSDiscoveryServiceImpl staticService;
    
    final Bus bus;
    volatile WSDiscoveryServiceImpl service;
    
    
    public WSDiscoveryServerListener(Bus bus) {
        this.bus = bus;
    }
    
    private synchronized WSDiscoveryServiceImpl getService() {
        if (service == null) {
            service = bus.getExtension(WSDiscoveryServiceImpl.class);
            if (service == null) {
                service = getStaticService();
                bus.setExtension(service, WSDiscoveryServiceImpl.class);
            }
        }
        return service;
    }

    private static WSDiscoveryServiceImpl getStaticService() {
        if (staticService == null) {
            Bus bus = BusFactory.newInstance().createBus();
            staticService = new WSDiscoveryServiceImpl(bus);
        }
        return staticService;
    }

    public void startServer(Server server) {
        QName sn = server.getEndpoint().getEndpointInfo().getInterface().getName();
        if ("http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01".equals(sn.getNamespaceURI())) {
            return;
        }
        getService().serverStarted(server);
    }

    public void stopServer(Server server) {
        QName sn = server.getEndpoint().getEndpointInfo().getInterface().getName();
        if ("http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01".equals(sn.getNamespaceURI())) {
            return;
        }
        getService().serverStopped(server);
    }
}
