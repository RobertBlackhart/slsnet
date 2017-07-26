/*
 * Copyright 2017-present Open Networking Laboratory
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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.incubator.net.intf.Interface;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.EncapsulationType;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.Host;
import org.onosproject.net.ResourceGroup;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.ConnectivityIntent;
import org.onosproject.net.intent.Constraint;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intent.SinglePointToMultiPointIntent;
import org.onosproject.net.intent.constraint.EncapsulationConstraint;
import org.onosproject.net.intent.constraint.PartialFailureConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * An implementation of L2NetworkOperationService.
 * Handles the execution order of the L2 Network operations generated by the
 * application.
 */
@Component(immediate = true, enabled = false)
public class SlsNetL2Forward {

    public static final String BROADCAST = "BCAST";
    public static final String UNICAST = "UNI";

    private final Logger log = LoggerFactory.getLogger(getClass());
    protected ApplicationId l2ForwardAppId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected SlsNetService slsnet;

    public static final ImmutableList<Constraint> PARTIAL_FAILURE_CONSTRAINT =
            ImmutableList.of(new PartialFailureConstraint());

    private Map<Key, SinglePointToMultiPointIntent> bctIntentsMap = Maps.newConcurrentMap();
    private Map<Key, MultiPointToSinglePointIntent> uniIntentsMap = Maps.newConcurrentMap();
    private Set<Key> toBePurgedIntentKeys = new HashSet<>();

    private final InternalSlsNetListener slsnetListener = new InternalSlsNetListener();

    @Activate
    public void activate() {
        l2ForwardAppId = coreService.registerApplication(slsnet.L2FORWARD_APP_ID);
        log.info("slsnet l2 forwaring starting with l2net app id {}", l2ForwardAppId.toString());

        slsnet.addListener(slsnetListener);

        refresh();

        log.info("slsnet l2forward started");
    }

    @Deactivate
    public void deactivate() {
        log.info("slsnet l2forward stopping");

        slsnet.removeListener(slsnetListener);

        for (Intent intent : bctIntentsMap.values()) {
            intentService.withdraw(intent);
            toBePurgedIntentKeys.add(intent.key());
        }
        for (Intent intent : uniIntentsMap.values()) {
            intentService.withdraw(intent);
            toBePurgedIntentKeys.add(intent.key());
        }
        for (Key key : toBePurgedIntentKeys) {
            Intent intentToPurge = intentService.getIntent(key);
            if (intentToPurge != null) {
                intentService.purge(intentToPurge);
            }
        }
        bctIntentsMap.clear();
        uniIntentsMap.clear();

        log.info("slsnet l2forward stopped");
    }

    public void refresh() {
        log.info("slsnet l2forward refresh");

        Map<Key, SinglePointToMultiPointIntent> newBctIntentsMap = Maps.newConcurrentMap();
        Map<Key, MultiPointToSinglePointIntent> newUniIntentsMap = Maps.newConcurrentMap();

        for (L2Network l2Network : slsnet.getL2Networks()) {
            // scans all l2network regardless of dirty flag
            for (SinglePointToMultiPointIntent intent : buildBrcIntents(l2Network)) {
                newBctIntentsMap.put(intent.key(), intent);
            }
            for (MultiPointToSinglePointIntent intent : buildUniIntents(l2Network,
                                                            hostsFromL2Network(l2Network))) {
                newUniIntentsMap.put(intent.key(), intent);
            }
            if (l2Network.dirty()) {
                l2Network.setDirty(false);
            }
        }

        boolean bctUpdated = false;
        for (SinglePointToMultiPointIntent intent : bctIntentsMap.values()) {
            SinglePointToMultiPointIntent newIntent = newBctIntentsMap.get(intent.key());
            if (newIntent == null) {
                log.info("slsnet l2forward withdraw broadcast intent: {}", intent);
                toBePurgedIntentKeys.add(intent.key());
                intentService.withdraw(intent);
                bctUpdated = true;
            }
        }
        for (SinglePointToMultiPointIntent intent : newBctIntentsMap.values()) {
            SinglePointToMultiPointIntent oldIntent = bctIntentsMap.get(intent.key());
            if (oldIntent == null ||
                    !oldIntent.filteredEgressPoints().equals(intent.filteredEgressPoints()) ||
                    !oldIntent.filteredIngressPoint().equals(intent.filteredIngressPoint()) ||
                    !oldIntent.selector().equals(intent.selector()) ||
                    !oldIntent.treatment().equals(intent.treatment()) ||
                    !oldIntent.constraints().equals(intent.constraints())) {
                log.info("slsnet l2forward submit broadcast intent: {}", intent);
                toBePurgedIntentKeys.remove(intent.key());
                intentService.submit(intent);
                bctUpdated = true;
            }
        }

        boolean uniUpdated = false;
        for (MultiPointToSinglePointIntent intent : uniIntentsMap.values()) {
            MultiPointToSinglePointIntent newIntent = newUniIntentsMap.get(intent.key());
            if (newIntent == null) {
                log.info("slsnet l2forward withdraw unicast intent: {}", intent);
                toBePurgedIntentKeys.add(intent.key());
                intentService.withdraw(intent);
                uniUpdated = true;
            }
        }
        for (MultiPointToSinglePointIntent intent : newUniIntentsMap.values()) {
            MultiPointToSinglePointIntent oldIntent = uniIntentsMap.get(intent.key());
            if (oldIntent == null ||
                    !oldIntent.filteredEgressPoint().equals(intent.filteredEgressPoint()) ||
                    !oldIntent.filteredIngressPoints().equals(intent.filteredIngressPoints()) ||
                    !oldIntent.selector().equals(intent.selector()) ||
                    !oldIntent.treatment().equals(intent.treatment()) ||
                    !oldIntent.constraints().equals(intent.constraints())) {
                log.info("slsnet l2forward submit unicast intent: {}", intent);
                toBePurgedIntentKeys.remove(intent.key());
                intentService.submit(intent);
                uniUpdated = true;
            }
        }

        if (bctUpdated) {
            bctIntentsMap = newBctIntentsMap;
        }
        if (uniUpdated) {
            uniIntentsMap = newUniIntentsMap;
        }
        checkIntentsPurge();
    }

    public void checkIntentsPurge() {
        // check intents to be purge
        if (!toBePurgedIntentKeys.isEmpty()) {
            Set<Key> purgedKeys = new HashSet<>();
            for (Key key : toBePurgedIntentKeys) {
                Intent intentToPurge = intentService.getIntent(key);
                if (intentToPurge == null) {
                    log.info("slsnet l2forward purged intent: key={}", key);
                    purgedKeys.add(key);
                } else {
                    log.info("slsnet l2forward try to purge intent: key={}", key);
                    intentService.purge(intentToPurge);
                }
            }
            toBePurgedIntentKeys.removeAll(purgedKeys);
        }
    }

    // Generates Unicast Intents and broadcast Intents for the L2 Network.

    private Set<Intent> generateL2NetworkIntents(L2Network l2Network) {
        return new ImmutableSet.Builder<Intent>()
            .addAll(buildBrcIntents(l2Network))
            .addAll(buildUniIntents(l2Network, hostsFromL2Network(l2Network)))
            .build();
    }

    // Build Boadcast Intents for a L2 Network.
    private Set<SinglePointToMultiPointIntent> buildBrcIntents(L2Network l2Network) {
        Set<Interface> interfaces = l2Network.interfaces();
        if (!l2Network.l2Forward() || interfaces.size() < 2) {
            return ImmutableSet.of();
        }
        Set<SinglePointToMultiPointIntent> brcIntents = Sets.newHashSet();
        ResourceGroup resourceGroup = ResourceGroup.of(l2Network.name());

        // Generates broadcast Intents from any network interface to other
        // network interface from the L2 Network.
        interfaces
            .forEach(src -> {
            FilteredConnectPoint srcFcp = buildFilteredConnectedPoint(src);
            Set<FilteredConnectPoint> dstFcps = interfaces.stream()
                    .filter(iface -> !iface.equals(src))
                    .map(this::buildFilteredConnectedPoint)
                    .collect(Collectors.toSet());
            Key key = buildKey(l2Network.name(), "BCAST", srcFcp.connectPoint(), MacAddress.BROADCAST);
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthDst(MacAddress.BROADCAST)
                    .build();
            SinglePointToMultiPointIntent.Builder intentBuilder = SinglePointToMultiPointIntent.builder()
                    .appId(l2ForwardAppId)
                    .key(key)
                    .selector(selector)
                    .filteredIngressPoint(srcFcp)
                    .filteredEgressPoints(dstFcps)
                    .constraints(PARTIAL_FAILURE_CONSTRAINT)
                    .priority(SlsNetService.PRI_L2NETWORK_BROADCAST)
                    .resourceGroup(resourceGroup);
            setEncap(intentBuilder, PARTIAL_FAILURE_CONSTRAINT, l2Network.encapsulationType());
            brcIntents.add(intentBuilder.build());
        });
        return brcIntents;
    }

    // Builds unicast Intents for a L2 Network.
    private Set<MultiPointToSinglePointIntent> buildUniIntents(L2Network l2Network, Set<Host> hosts) {
        Set<Interface> interfaces = l2Network.interfaces();
        if (!l2Network.l2Forward() || interfaces.size() < 2) {
            return ImmutableSet.of();
        }
        Set<MultiPointToSinglePointIntent> uniIntents = Sets.newHashSet();
        ResourceGroup resourceGroup = ResourceGroup.of(l2Network.name());
        hosts.forEach(host -> {
            FilteredConnectPoint hostFcp = buildFilteredConnectedPoint(host);
            Set<FilteredConnectPoint> srcFcps = interfaces.stream()
                    .map(this::buildFilteredConnectedPoint)
                    .filter(fcp -> !fcp.equals(hostFcp))
                    .collect(Collectors.toSet());
            Key key = buildKey(l2Network.name(), "UNI", hostFcp.connectPoint(), host.mac());
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthDst(host.mac()).build();
            MultiPointToSinglePointIntent.Builder intentBuilder = MultiPointToSinglePointIntent.builder()
                    .appId(l2ForwardAppId)
                    .key(key)
                    .selector(selector)
                    .filteredIngressPoints(srcFcps)
                    .filteredEgressPoint(hostFcp)
                    .constraints(PARTIAL_FAILURE_CONSTRAINT)
                    .priority(SlsNetService.PRI_L2NETWORK_UNICAST)
                    .resourceGroup(resourceGroup);
            setEncap(intentBuilder, PARTIAL_FAILURE_CONSTRAINT, l2Network.encapsulationType());
            uniIntents.add(intentBuilder.build());
        });

        return uniIntents;
    }

    // Intent generate utilities

    private Set<Host> hostsFromL2Network(L2Network l2Network) {
        Set<Interface> interfaces = l2Network.interfaces();
        return interfaces.stream()
                .map(this::hostsFromInterface)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    private Set<Host> hostsFromInterface(Interface iface) {
        return hostService.getConnectedHosts(iface.connectPoint())
                .stream()
                .filter(host -> host.vlan().equals(iface.vlan()))
                .collect(Collectors.toSet());
    }

    private Key buildKey(String l2NetworkName, String type, ConnectPoint cPoint, MacAddress dstMac) {
        return Key.of(l2NetworkName + "-" + type + "-" + cPoint.toString() + "-" + dstMac, l2ForwardAppId);
    }

    private void setEncap(ConnectivityIntent.Builder builder,
                                 List<Constraint> constraints, EncapsulationType encap) {
        // Constraints might be an immutable list, so a new modifiable list is created
        List<Constraint> newConstraints = new ArrayList<>(constraints);
        constraints.stream()
                .filter(c -> c instanceof EncapsulationConstraint)
                .forEach(newConstraints::remove);
        if (!encap.equals(EncapsulationType.NONE)) {
            newConstraints.add(new EncapsulationConstraint(encap));
        }
        // Submit new constraint list as immutable list
        builder.constraints(ImmutableList.copyOf(newConstraints));
    }

    private FilteredConnectPoint buildFilteredConnectedPoint(Interface iface) {
        Objects.requireNonNull(iface);
        TrafficSelector.Builder trafficSelector = DefaultTrafficSelector.builder();

        if (iface.vlan() != null && !iface.vlan().equals(VlanId.NONE)) {
            trafficSelector.matchVlanId(iface.vlan());
        }
        return new FilteredConnectPoint(iface.connectPoint(), trafficSelector.build());
    }

    protected FilteredConnectPoint buildFilteredConnectedPoint(Host host) {
        Objects.requireNonNull(host);
        TrafficSelector.Builder trafficSelector = DefaultTrafficSelector.builder();

        if (host.vlan() != null && !host.vlan().equals(VlanId.NONE)) {
            trafficSelector.matchVlanId(host.vlan());
        }
        return new FilteredConnectPoint(host.location(), trafficSelector.build());
    }

    // Dump command handler
    private void dump(String subject) {
        if (subject == "intents") {
            System.out.println("L2Forward Broadcast Intents:\n");
            for (SinglePointToMultiPointIntent intent: bctIntentsMap.values()) {
                System.out.println("    " + intent.key().toString() + ": "
                                   + intent.selector() + " | "
                                   + intent.filteredIngressPoint() + " -> "
                                   + intent.filteredEgressPoints());
            }
            System.out.println("");
            System.out.println("L2Forward Unicast Intents:\n");
            for (MultiPointToSinglePointIntent intent: uniIntentsMap.values()) {
                System.out.println("    " + intent.key().toString()
                                   + intent.selector() + " | " 
                                   + intent.filteredIngressPoints() + " -> "
                                   + intent.filteredEgressPoint());
            }
            System.out.println("");
            System.out.println("L2Forward Intents to Be Purged:\n");
            for (Key key: toBePurgedIntentKeys) {
                System.out.println("    " + key.toString());
            }
            System.out.println("");
        }
    }

    // Listener
    private class InternalSlsNetListener implements SlsNetListener {
        @Override
        public void event(SlsNetEvent event) {
            switch (event.type()) {
            case SLSNET_UPDATED:
                refresh();
                break;
            case SLSNET_IDLE:
                checkIntentsPurge();
                break;
            case SLSNET_DUMP:
                dump(event.subject());
                break;
            default:
                break;
            }
        }
    }

}
