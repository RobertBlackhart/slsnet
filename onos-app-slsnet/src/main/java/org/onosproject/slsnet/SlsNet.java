/*
 * Copyright 2014-present Open Networking Laboratory
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
package org.onosproject.slsnet;

import com.google.common.collect.ImmutableList;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onosproject.app.ApplicationService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.incubator.component.ComponentService;
import org.onosproject.incubator.net.intf.InterfaceService;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.intentsync.IntentSynchronizationService;
import org.onosproject.routing.RoutingService;
import org.slf4j.Logger;

import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Component for the SDN-IP peering application.
 */
@Component(immediate = true)
public class SlsNet {

    public static final String APP_ID = "org.onosproject.slsnet";
    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ApplicationService applicationService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigService networkConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentSynchronizationService intentSynchronizer;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry cfgRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentService componentService;

    private PeerConnectivityManager peerConnectivity;

    private ApplicationId appId;

    private final List<String> components = ImmutableList.of(
            "org.onosproject.routing.bgp.BgpSessionManager",
            "org.onosproject.routing.impl.BgpSpeakerNeighbourHandler",
            org.onosproject.slsnet.SdnIpFib.class.getName()
    );

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(APP_ID);

        components.forEach(name -> componentService.activate(appId, name));
        peerConnectivity = new PeerConnectivityManager(appId,
                                                       intentSynchronizer,
                                                       networkConfigService,
                coreService.registerApplication(RoutingService.ROUTER_APP_ID),
                                                       interfaceService);
        peerConnectivity.start();

        applicationService.registerDeactivateHook(appId,
                () -> intentSynchronizer.removeIntentsByAppId(appId));

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        components.forEach(name -> componentService.deactivate(appId, name));

        peerConnectivity.stop();

        log.info("Stopped");
    }
}
