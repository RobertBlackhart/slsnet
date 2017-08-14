/*
 * Copyright 2015-present Open Networking Foundation
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
import com.google.common.collect.Maps;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.EthType;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP;
import org.onlab.packet.ICMP6;
import org.onlab.packet.IPv4;
import org.onlab.packet.IPv6;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.Ip6Address;
import org.onlab.packet.Ip6Prefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.incubator.net.routing.Route;
import org.onosproject.incubator.net.routing.RouteService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.EncapsulationType;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.Host;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.Constraint;
import org.onosproject.net.intent.constraint.PartialFailureConstraint;
import org.onosproject.net.intent.constraint.EncapsulationConstraint;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.Port;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;


/**
 * SlsNetReactiveRouting handles L3 Reactive Routing.
 */
@Component(immediate = true, enabled = false)
public class SlsNetReactiveRouting {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private ApplicationId reactiveAppId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected SlsNetService slsnet;

    private static final ImmutableList<Constraint> REACTIVE_CONSTRAINTS
            = ImmutableList.of(new PartialFailureConstraint());

    private Set<FlowRule> interceptFlowRules = new HashSet<>();
    private Map<IpPrefix, RouteIntent> routeIntents = Maps.newConcurrentMap();
    private Set<Key> toBePurgedIntentKeys = new HashSet<>();
            // NOTE: manage purged intents by key for intentService.getIntent() supports key only

    private final InternalSlsNetListener slsnetListener = new InternalSlsNetListener();
    private ReactiveRoutingProcessor processor = new ReactiveRoutingProcessor();

    private class RouteIntent {
        private MultiPointToSinglePointIntent intent;
        private final IpAddress nextHopIp;
        private final MacAddress nextHopMac;

        RouteIntent(MultiPointToSinglePointIntent intent, IpAddress nextHopIp, MacAddress nextHopMac) {
            this.intent = intent;
            this.nextHopIp = nextHopIp;
            this.nextHopMac = nextHopMac;
        }
        public MultiPointToSinglePointIntent intent() {
            return intent;
        }
        public IpAddress nextHopIp() {
            return nextHopIp;
        }
        public MacAddress nextHopMac() {
            return nextHopMac;
        }
        public void setIntent(MultiPointToSinglePointIntent intent) {
            this.intent = intent;
        }
    }

    @Activate
    public void activate() {
        reactiveAppId = coreService.registerApplication(slsnet.REACTIVE_APP_ID);
        log.info("slsnet reactive routing starting with app id {}", reactiveAppId.toString());

        // NOTE: may not clear at init for MIGHT generate pending_remove garbages
        // clear all previous intents and flow rules
        withdrawAllReactiveIntents();
        //flowRuleService.removeFlowRulesById(reactiveAppId);
        checkIntentsPurge();

        processor = new ReactiveRoutingProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(2));
        slsnet.addListener(slsnetListener);

        registerIntercepts();
        refreshIntercepts();

        log.info("slsnet reactive routing started");
    }

    @Deactivate
    public void deactivate() {
        log.info("slsnet reactive routing stopping");

        packetService.removeProcessor(processor);
        slsnet.removeListener(slsnetListener);

        withdrawIntercepts();

        // NOTE: may not clear at init for MIGHT generate pending_remove garbages
        // withdraw all my intents and flow rules
        withdrawAllReactiveIntents();
        // OR
        //for (RouteIntent routeIntent : routeIntents.values()) {
        //    log.info("slsnet l2forward withdraw unicast intent: {}", intent);
        //    toBePurgedIntentKeys.add(routeIntent.intent().key());
        //    intentService.withdraw(routeIntent.intent());
        //}
        flowRuleService.removeFlowRulesById(reactiveAppId);
        routeIntents.clear();
        checkIntentsPurge();

        processor = null;

        log.info("slsnet reactive routing stopped");
    }

    /**
     * Request packet in via the PacketService.
     */
    private void registerIntercepts() {
        // register default intercepts on packetService for broder routing intercepts

        packetService.requestPackets(
            DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
            PacketPriority.REACTIVE, reactiveAppId);

        if (slsnet.ALLOW_IPV6) {
            packetService.requestPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV6).build(),
                PacketPriority.REACTIVE, reactiveAppId);
        }

        log.info("slsnet reactive routing ip packet intercepts started");
    }

    /**
     * Cancel request for packet in via PacketService.
     */
    private void withdrawIntercepts() {
        // unregister default intercepts on packetService

        packetService.cancelPackets(
            DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
            PacketPriority.REACTIVE, reactiveAppId);

        if (slsnet.ALLOW_IPV6) {
            packetService.cancelPackets(
                DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV6).build(),
                PacketPriority.REACTIVE, reactiveAppId);
        }

        log.info("slsnet reactive routing ip packet intercepts stopped");
    }

    /**
     * Refresh device flow rules for reative intercepts on local ipSubnets.
     */
    private void refreshIntercepts() {
        if (slsnet.getVirtualGatewayMacAddress() == null) {
            log.warn("slsnet reactive routing refresh intercepts skipped "
                     + "for virtual gateway mac address unknown");
        }

        Set<FlowRule> newInterceptFlowRules = new HashSet<>();
        for (Device device : deviceService.getAvailableDevices()) {
            for (IpSubnet subnet : slsnet.getIpSubnets()) {
                newInterceptFlowRules.add(generateInterceptFlowRule(device.id(), subnet.ipPrefix()));
                // check if this devices has the ipSubnet, then add ip broadcast flue rule
                L2Network l2Network = slsnet.findL2Network(subnet.l2NetworkName());
                if (l2Network != null && l2Network.contains(device.id())) {
                    newInterceptFlowRules.add(generateIpBctFlowRule(device.id(), subnet.ipPrefix()));
                }
                // JUST FOR FLOW RULE TEST ONLY
                //newInterceptFlowRules.add(generateTestFlowRule(device.id(), subnet.ipPrefix()));
            }
            for (Route route : slsnet.getBorderRoutes()) {
                newInterceptFlowRules.add(generateInterceptFlowRule(device.id(), route.prefix()));
            }
        }

        if (!newInterceptFlowRules.equals(interceptFlowRules)) {
            interceptFlowRules.stream()
                .filter(rule -> !newInterceptFlowRules.contains(rule))
                .forEach(rule -> {
                    flowRuleService.removeFlowRules(rule);
                    log.info("slsnet reactive routing remove intercept flow rule: {}", rule);
                });
            newInterceptFlowRules.stream()
                .filter(rule -> !interceptFlowRules.contains(rule))
                .forEach(rule -> {
                    flowRuleService.applyFlowRules(rule);
                    log.info("slsnet reactive routing apply intercept flow rule: {}", rule);
                });
            interceptFlowRules = newInterceptFlowRules;
        }
    }

    private FlowRule generateInterceptFlowRule(DeviceId deviceId, IpPrefix prefix) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        if (SlsNetService.VIRTUAL_GATEWAY_ETH_ADDRESS_SELECTOR) {
            selector.matchEthDst(slsnet.getVirtualGatewayMacAddress());
        }
        if (prefix.isIp4()) {
            selector.matchEthType(Ethernet.TYPE_IPV4);
            if (prefix.prefixLength() > 0) {
                selector.matchIPDst(prefix);
            }
        } else {
            selector.matchEthType(Ethernet.TYPE_IPV6);
            if (prefix.prefixLength() > 0) {
                selector.matchIPv6Dst(prefix);
            }
        }
        FlowRule rule = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .withPriority(reactivePriority(prefix.prefixLength(), slsnet.PRI_REACTIVE_INTERCEPT))
                .withSelector(selector.build())
                .withTreatment(DefaultTrafficTreatment.builder().punt().build())
                .fromApp(reactiveAppId)
                .makePermanent()
                .forTable(0).build();
        return rule;
    }

    private FlowRule generateIpBctFlowRule(DeviceId deviceId, IpPrefix prefix) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        IpPrefix bctPrefix;
        if (prefix.isIp4()) {
            bctPrefix = Ip4Prefix.valueOf(prefix.getIp4Prefix().address().toInt() |
                                              ~Ip4Address.makeMaskPrefix(prefix.prefixLength()).toInt(),
                                          Ip4Address.BIT_LENGTH);
            selector.matchEthType(Ethernet.TYPE_IPV4);
            selector.matchIPDst(bctPrefix);
        } else {
            byte[] p = prefix.getIp6Prefix().address().toOctets();
            byte[] m = Ip6Address.makeMaskPrefix(prefix.prefixLength()).toOctets();
            for (int i = 0; i < p.length; i++) {
                 p[i] |= ~m[i];
            }
            bctPrefix = Ip6Prefix.valueOf(p, Ip6Address.BIT_LENGTH);
            selector.matchEthType(Ethernet.TYPE_IPV6);
            selector.matchIPv6Dst(bctPrefix);
        }
        TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder();
        Set<ConnectPoint> newEgressPoints = new HashSet<>();
        for (Port port : deviceService.getPorts(deviceId)) {
            treatment.setOutput(port.number());
        }
        FlowRule rule = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .withPriority(reactivePriority(bctPrefix.prefixLength(), slsnet.PRI_REACTIVE_ROUTE))
                .withSelector(selector.build())
                .withTreatment(treatment.build())
                .fromApp(reactiveAppId)
                .makePermanent()
                .forTable(0).build();
        return rule;
    }

    // JUST FOR FLOW RULE TEST ONLY
    /*
    private FlowRule generateTestFlowRule(DeviceId deviceId, IpPrefix prefix) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        if (SlsNetService.VIRTUAL_GATEWAY_ETH_ADDRESS_SELECTOR) {
            selector.matchEthDst(slsnet.getVirtualGatewayMacAddress());
        }
        if (prefix.isIp4()) {
            selector.matchEthType(Ethernet.TYPE_IPV4);
            if (prefix.prefixLength() > 0) {
                selector.matchIPDst(prefix);
            }
        } else {
            selector.matchEthType(Ethernet.TYPE_IPV6);
            if (prefix.prefixLength() > 0) {
                selector.matchIPv6Dst(prefix);
            }
        }
        FlowRule rule = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .withPriority(reactivePriority(prefix.prefixLength(), slsnet.PRI_REACTIVE_INTERCEPT))
                .withSelector(selector.build())
                .withTreatment(DefaultTrafficTreatment.builder()
                        .setEthSrc(MacAddress.valueOf("11:22:33:44:55:66"))
                        .setEthDst(MacAddress.valueOf("12:34:56:78:9a:bc"))
                        .setOutput(PortNumber.portNumber(1))
                        .build())
                .fromApp(reactiveAppId)
                .makePermanent()
                .forTable(0).build();
        return rule;
    }
    */

    /**
     * Refresh routes by examining network resource status.
     */
    private void refreshRouteIntents() {
        Set<IpPrefix> prefixToRemove = new HashSet<>();

        for (Map.Entry<IpPrefix, RouteIntent> entry : routeIntents.entrySet()) {
            RouteIntent routeIntent = entry.getValue();
            MultiPointToSinglePointIntent intent = routeIntent.intent();
            do {
                // dummy loop to break on remove cases
                if (!deviceService.isAvailable(intent.egressPoint().deviceId())) {
                    log.trace("slsnet reactive routing refresh route intents; remove intent for no device: key={}",
                             intent.key());
                    break;
                }
                if (slsnet.findL2Network(intent.egressPoint(), VlanId.NONE) == null) {
                    log.trace("slsnet reactive routing refresh route intents; "
                              + "remove intent for egress point not available: key={}", intent.key());
                    break;
                }
                // check if nextHopIp mac or connection point is changed
                Set<Host> hosts = hostService.getHostsByIp(routeIntent.nextHopIp());
                if (hosts.isEmpty()) {
                    log.trace("slsnet reactive routing refresh route intents; "
                              + "remove intent for host entry not found: key={}", intent.key());
                    break;
                }
                Host host = hosts.iterator().next();
                if (!host.mac().equals(routeIntent.nextHopMac) ||
                    !intent.egressPoint().equals((ConnectPoint) host.location())) {
                    log.trace("slsnet reactive routing refresh route intents; "
                              + "remove intent for host mac or egress point changed: key={}", intent.key());
                    break;
                }
                // check if ingress point set is changed
                Set<ConnectPoint> newIngressPoints = new HashSet<>();
                for (ConnectPoint cp : intent.ingressPoints()) {
                    if (slsnet.findL2Network(cp, VlanId.NONE) != null) {
                         newIngressPoints.add(cp);
                    }
                }
                if (newIngressPoints.isEmpty()) {
                    log.trace("slsnet reactive routing refresh route intents; "
                              + "remove intent for no ingress nor egress point available: key={}", intent.key());
                    break;
                }
                // update ingress points
                if (!newIngressPoints.equals(intent.ingressPoints())) {
                    MultiPointToSinglePointIntent updatedIntent =
                        MultiPointToSinglePointIntent.builder()
                            .appId(reactiveAppId)
                            .key(intent.key())
                            .selector(intent.selector())
                            .treatment(intent.treatment())
                            .ingressPoints(intent.ingressPoints())
                            .egressPoint(intent.egressPoint())
                            .priority(intent.priority())
                            .constraints(intent.constraints())
                            .build();
                    log.trace("slsnet reactive routing refresh route update intent: key={} updatedIntent={}",
                            intent.key(), updatedIntent);
                    routeIntent.setIntent(updatedIntent);
                    toBePurgedIntentKeys.remove(updatedIntent.key());   // may remove from old purged entry
                    intentService.submit(updatedIntent);
                }
                // this intent is valid
                continue;

            } while (false);
            // remote entry for current status is not value
            prefixToRemove.add(entry.getKey());
            toBePurgedIntentKeys.add(intent.key());
            intentService.withdraw(intent);
        }
        /* clean up intents */
        for (IpPrefix prefix : prefixToRemove) {
            routeIntents.remove(prefix);
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
                    log.info("slsnet reactive routing purged intent: key={}", key);
                    purgedKeys.add(key);
                } else {
                    log.info("slsnet reactive routing try to purge intent: key={}", key);
                    intentService.purge(intentToPurge);
                }
            }
            toBePurgedIntentKeys.removeAll(purgedKeys);
        }
    }

    public void withdrawAllReactiveIntents() {
        // check all intents if mine
        Set<Intent> myIntents = new HashSet<>();
        for (Intent intent : intentService.getIntents()) {
            if (intent.appId().equals(reactiveAppId)) {
                myIntents.add(intent);
            }
        }
        // withdraw all my intents
        for (Intent intent : myIntents) {
            switch (intentService.getIntentState(intent.key())) {
            case FAILED:
                intentService.withdraw(intent);
                toBePurgedIntentKeys.add(intent.key());
                break;
            case WITHDRAWN:
                intentService.purge(intent);
                toBePurgedIntentKeys.add(intent.key());
                break;
            case INSTALL_REQ:
            case INSTALLED:
            case INSTALLING:
            case RECOMPILING:
            case COMPILING:
                intentService.withdraw(intent);
                toBePurgedIntentKeys.add(intent.key());
                break;
            case WITHDRAW_REQ:
            case WITHDRAWING:
                toBePurgedIntentKeys.add(intent.key());
                break;
            case PURGE_REQ:
            case CORRUPT:
            default:
                // no action
                break;
            }
        }
    }

    /**
     * Reactive Packet Handling.
     */
    private class ReactiveRoutingProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null) {
                return;
            }
            ConnectPoint srcCp = pkt.receivedFrom();
            IpAddress srcIp;
            IpAddress dstIp;

            switch (EthType.EtherType.lookup(ethPkt.getEtherType())) {
            case IPV4:
                IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
                srcIp = IpAddress.valueOf(ipv4Packet.getSourceAddress());
                dstIp = IpAddress.valueOf(ipv4Packet.getDestinationAddress());
                break;
            case IPV6:
                IPv6 ipv6Packet = (IPv6) ethPkt.getPayload();
                srcIp = IpAddress.valueOf(IpAddress.Version.INET6, ipv6Packet.getSourceAddress());
                dstIp = IpAddress.valueOf(IpAddress.Version.INET6, ipv6Packet.getDestinationAddress());
                break;
            default:
                return;  // ignore unknow ether type packets
            }

            if (!checkVirtualGatewayIpPacket(pkt, srcIp, dstIp)) {
                ipPacketReactiveProcessor(context, ethPkt, srcCp, srcIp, dstIp);
            }
        }
    }

    /**
     * handle Packet with dstIp=virtualGatewayIpAddresses.
     * returns true(handled) or false(not for virtual gateway)
     */
    private boolean checkVirtualGatewayIpPacket(InboundPacket pkt, IpAddress srcIp, IpAddress dstIp) {
        Ethernet ethPkt = pkt.parsed();  // assume valid

        if (!ethPkt.getDestinationMAC().equals(slsnet.getVirtualGatewayMacAddress())
            || !slsnet.isVirtualGatewayIpAddress(dstIp)) {
            return false;

         } else if (dstIp.isIp4()) {
            IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
            if (ipv4Packet.getProtocol() == IPv4.PROTOCOL_ICMP) {
                ICMP icmpPacket = (ICMP) ipv4Packet.getPayload();

                if (icmpPacket.getIcmpType() == ICMP.TYPE_ECHO_REQUEST) {
                    log.info("slsnet reactive routing IPV4 ICMP ECHO request to virtual gateway: "
                              + "srcIp={} dstIp={} proto={}", srcIp, dstIp, ipv4Packet.getProtocol());
                    TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                .setOutput(pkt.receivedFrom().port()).build();
                    OutboundPacket packet =
                        new DefaultOutboundPacket(pkt.receivedFrom().deviceId(), treatment,
                                ByteBuffer.wrap(icmpPacket.buildIcmpReply(pkt.parsed()).serialize()));
                    packetService.emit(packet);
                    return true;
                }
            }
            log.warn("slsnet reactive routing IPV4 packet to virtual gateway dropped: "
                     + "srcIp={} dstIp={} proto={}", srcIp, dstIp, ipv4Packet.getProtocol());
            return true;

         } else if (dstIp.isIp6()) {
            // TODO: not tested yet (2017-07-20)
            IPv6 ipv6Packet = (IPv6) ethPkt.getPayload();
            if (ipv6Packet.getNextHeader() == IPv6.PROTOCOL_ICMP6) {
                ICMP6 icmp6Packet = (ICMP6) ipv6Packet.getPayload();

                if (icmp6Packet.getIcmpType() == ICMP6.ECHO_REQUEST) {
                    log.info("slsnet reactive routing IPV6 ICMP6 ECHO request to virtual gateway: "
                              + "srcIp={} dstIp={} nextHeader={}", srcIp, dstIp, ipv6Packet.getNextHeader());
                    TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                .setOutput(pkt.receivedFrom().port()).build();
                    OutboundPacket packet =
                        new DefaultOutboundPacket(pkt.receivedFrom().deviceId(), treatment,
                                ByteBuffer.wrap(icmp6Packet.buildIcmp6Reply(pkt.parsed()).serialize()));
                    packetService.emit(packet);
                    return true;
                }
            }
            log.warn("slsnet reactive routing IPV6 packet to virtual gateway dropped: "
                     + "srcIp={} dstIp={} nextHeader={}", srcIp, dstIp, ipv6Packet.getNextHeader());
            return true;

        }
        return false;  // unknown traffic
    }

    /**
     * Routes packet reactively.
     */
    private void ipPacketReactiveProcessor(PacketContext context, Ethernet ethPkt,
                                           ConnectPoint srcCp, IpAddress srcIp, IpAddress dstIp) {
        /* check reactive handling and forward packet */
        log.trace("slsnet reactive routing ip packet: srcCp={} srcIp={} dstIp={} srcCp={}", srcCp, srcIp, dstIp);
        EncapsulationType encap = EncapsulationType.NONE;
        IpSubnet srcSubnet = slsnet.findIpSubnet(srcIp);
        IpSubnet dstSubnet = slsnet.findIpSubnet(dstIp);
        if (dstSubnet != null) {
            // destination is local subnet ip
            if (SlsNetService.ALLOW_ETH_ADDRESS_SELECTOR && dstSubnet.equals(srcSubnet)) {
                // NOTE: if ALLOW_ETH_ADDRESS_SELECTOR=false; l2Forward is always false
                L2Network l2Network = slsnet.findL2Network(dstSubnet.l2NetworkName());
                if (l2Network != null && l2Network.l2Forward()) {
                    // within same subnet and to be handled by L2Forward
                    // no reactive route action but do forward packet for L2Forward do not handle packet
                    forwardPacketToDstIp(context, dstIp, false);
                    return;
                }
                // may use ethPkt's ethSrc mac as srcMac BUT NEED to resolve conflict with inter-subnet case */
            }
            encap = dstSubnet.encapsulation();
            if (encap == EncapsulationType.NONE && srcSubnet != null) {
               encap = srcSubnet.encapsulation();
            }
            setUpConnectivity(srcCp, dstIp.toIpPrefix(), dstIp, slsnet.getVirtualGatewayMacAddress(), encap);
        } else {
            // destination is external network
            if (srcSubnet == null) {
                log.warn("slsnet reactive routing srcIp and dstIp are both NON-LOCAL; ignore: srcIp={} dstIp={}",
                         srcIp, dstIp);
                return;
            }
            Route route = routeService.longestPrefixMatch(dstIp);
            if (route == null) {
                log.warn("slsnet reactive routing route unknown in routeServce: dstIp={}", dstIp);
                route = slsnet.findBorderRoute(dstIp);
                if (route == null) {
                    log.warn("slsnet reactive routing route unknown in slsnet.findBorderRoute(): dstIp={}", dstIp);
                    return;
                }
            }
            encap = srcSubnet.encapsulation();
            setUpConnectivity(srcCp, route.prefix(), route.nextHop(), slsnet.getVirtualGatewayMacAddress(), encap);
        }
        forwardPacketToDstIp(context, dstIp, true);
    }

    /**
     * Emits the specified packet onto the network.
     */
    private void forwardPacketToDstIp(PacketContext context, IpAddress dstIp, boolean updateMac) {
        if (!slsnet.isIpAddressLocal(dstIp)) {
            Route route = routeService.longestPrefixMatch(dstIp);
            if (route == null) {
                log.warn("slsnet reactive routing forward packet route to dstIp unknown: dstIp={}", dstIp);
                return;
            }
            dstIp = route.nextHop();
        }
        Set<Host> hosts = hostService.getHostsByIp(dstIp);
        Host dstHost;
        if (!hosts.isEmpty()) {
            dstHost = hosts.iterator().next();
        } else {
            // NOTE: hostService.requestMac(dstIp); NOT IMPLEMENTED in ONOS HostManager.java; do it myself
            log.warn("slsnet reactive routing forward packet dstIp host_mac unknown: dstIp={}", dstIp);
            hostService.startMonitoringIp(dstIp);
            slsnet.requestMac(dstIp);
            // CONSIDER: make flood on all port of the dstHost's L2Network
            return;
        }
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(dstHost.location().port()).build();
        OutboundPacket outPacket;
        if (updateMac) {
            // NOTE: eth address update by treatment is NOT applied, so update mac myself
            outPacket = new DefaultOutboundPacket(dstHost.location().deviceId(), treatment,
                                ByteBuffer.wrap(context.inPacket().parsed()
                                       .setSourceMACAddress(slsnet.getVirtualGatewayMacAddress())
                                       .setDestinationMACAddress(dstHost.mac()).serialize()));
        } else {
            outPacket = new DefaultOutboundPacket(dstHost.location().deviceId(), treatment,
                                context.inPacket().unparsed());
        }
        log.info("slsnet reactive routing forward packet: dstHost={} outPacket={}", dstHost, outPacket);
        packetService.emit(outPacket);
    }

    /**
     * Update intents for connectivity.
     *
     * ToHost: prefix = destHostIp.toIpPrefix(), nextHopIp = destHostIp
     * ToInternet: prefix = route.prefix(), nextHopIp = route.nextHopIp
     */
    private void setUpConnectivity(ConnectPoint srcCp, IpPrefix prefix, IpAddress nextHopIp,
                                   MacAddress treatmentSrcMac, EncapsulationType encap) {
        MacAddress nextHopMac = null;
        ConnectPoint egressPoint = null;
        for (Host host : hostService.getHostsByIp(nextHopIp)) {
            if (host.mac() != null) {
                nextHopMac = host.mac();
                egressPoint = host.location();
                break;
            }
        }
        if (nextHopMac == null || egressPoint == null) {
            log.trace("slsnet reactive routing nextHopCP and Mac unknown: prefix={} nextHopIp={}", prefix, nextHopIp);
            hostService.startMonitoringIp(nextHopIp);
            slsnet.requestMac(nextHopIp);
            return;
        }

        RouteIntent existingRouteIntent = routeIntents.get(prefix);
        if (existingRouteIntent != null
                && existingRouteIntent.intent().egressPoint().equals(egressPoint)
                && existingRouteIntent.nextHopIp().equals(nextHopIp)
                && existingRouteIntent.nextHopMac().equals(nextHopMac)) {
            MultiPointToSinglePointIntent existingIntent = existingRouteIntent.intent();
            log.trace("slsnet reactive routing update mp2p intent: prefix={} srcCp={}", prefix, srcCp);
            Set<ConnectPoint> ingressPoints = existingIntent.ingressPoints();
            if (!ingressPoints.contains(srcCp) && ingressPoints.add(srcCp)) {
                MultiPointToSinglePointIntent updatedIntent =
                        MultiPointToSinglePointIntent.builder()
                                .appId(reactiveAppId)
                                .key(existingIntent.key())
                                .selector(existingIntent.selector())
                                .treatment(existingIntent.treatment())
                                .ingressPoints(ingressPoints)
                                .egressPoint(egressPoint)
                                .priority(existingIntent.priority())
                                .constraints(buildConstraints(REACTIVE_CONSTRAINTS, encap))
                                .build();

                log.trace("slsnet reactive routing update mp2p intent: prefix={} srcCp={} updatedIntent={}",
                          prefix, srcCp, updatedIntent);
                routeIntents.put(prefix, new RouteIntent(updatedIntent, nextHopIp, nextHopMac));
                toBePurgedIntentKeys.remove(updatedIntent.key());
                intentService.submit(updatedIntent);
            }
            // If adding ingressConnectPoint to ingressPoints failed, it
            // because between the time interval from checking existing intent
            // to generating new intent, onos updated this intent due to other
            // packet-in and the new intent also includes the
            // ingressConnectPoint. This will not affect reactive routing.
        } else {
            Key key = Key.of(prefix.toString(), reactiveAppId);
            TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
            if (SlsNetService.VIRTUAL_GATEWAY_ETH_ADDRESS_SELECTOR) {
               selector.matchEthDst(slsnet.getVirtualGatewayMacAddress());
            }
            if (prefix.isIp4()) {
                selector.matchEthType(Ethernet.TYPE_IPV4);
                if (prefix.prefixLength() > 0) {
                    selector.matchIPDst(prefix);
                }
            } else {
                selector.matchEthType(Ethernet.TYPE_IPV6);
                if (prefix.prefixLength() > 0) {
                    selector.matchIPv6Dst(prefix);
                }
            }
            Set<ConnectPoint> ingressPoints = new HashSet<>();
            ingressPoints.add(srcCp);
            MultiPointToSinglePointIntent newIntent = MultiPointToSinglePointIntent.builder()
                    .appId(reactiveAppId)
                    .key(key)
                    .selector(selector.build())
                    .treatment(generateSetMacTreatment(nextHopMac, treatmentSrcMac))
                    .ingressPoints(ingressPoints)
                    .egressPoint(egressPoint)
                    .priority(reactivePriority(prefix.prefixLength(), slsnet.PRI_REACTIVE_ROUTE))
                    .constraints(buildConstraints(REACTIVE_CONSTRAINTS, encap))
                    .build();

           log.trace("slsnet reactive routing generate mp2p intent: prefix={} srcCp={} "
                     + "newIntent={} nextHopIp={} nextHopMac={}", prefix, srcCp, newIntent, nextHopIp, nextHopMac);
           routeIntents.put(prefix, new RouteIntent(newIntent, nextHopIp, nextHopMac));
           toBePurgedIntentKeys.remove(newIntent.key());
           intentService.submit(newIntent);
       }
    }

    // generate treatement to target
    private TrafficTreatment generateSetMacTreatment(MacAddress dstMac, MacAddress srcMac) {
        return DefaultTrafficTreatment.builder()
                   // NOTE: Cisco Switch requires both src and dst mac set
                   .setEthSrc(srcMac)
                   .setEthDst(dstMac)
                   .build();
    }

    // monitor border peers for routeService lookup to be effective
    private void monitorBorderPeers() {
        for (Route route : slsnet.getBorderRoutes()) {
            hostService.startMonitoringIp(route.nextHop());
            slsnet.requestMac(route.nextHop());
        }
    }

    // priority calculator
    private int reactivePriority(int prefixLength, int useCaseOffset) {
        return slsnet.PRI_REACTIVE_BASE + prefixLength * slsnet.PRI_REACTIVE_STEP + useCaseOffset;
    }

    // constraints generator
    private List<Constraint> buildConstraints(List<Constraint> constraints, EncapsulationType encap) {
        if (!encap.equals(EncapsulationType.NONE)) {
            List<Constraint> newConstraints = new ArrayList<>(constraints);
            constraints.stream()
                .filter(c -> c instanceof EncapsulationConstraint)
                .forEach(newConstraints::remove);
            newConstraints.add(new EncapsulationConstraint(encap));
            return ImmutableList.copyOf(newConstraints);
        }
        return constraints;
    }

    // Dump Cli Handler
    private void dump(String subject) {
        if (subject == "intents") {
            System.out.println("Reactive Routing Route Intents:\n");
            for (Map.Entry<IpPrefix, RouteIntent> entry: routeIntents.entrySet()) {
                System.out.println("    " + entry.getKey().toString()
                                   + " to " + entry.getValue().intent().egressPoint().toString()
                                   + " " + entry.getValue().nextHopMac().toString()
                                   + " (" + entry.getValue().nextHopIp().toString()
                                   + ") from " + entry.getValue().intent().ingressPoints().toString());
            }
            System.out.println("");

            System.out.println("Reactive Routing Intercept Flow Rules:\n");
            for (FlowRule rule : interceptFlowRules) {
                System.out.println("    " + rule.selector().toString());
            }
            System.out.println("");
            System.out.println("Reactive Routing Intents to Be Purged:\n");
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
                refreshIntercepts();
                refreshRouteIntents();
                break;
            case SLSNET_IDLE:
                checkIntentsPurge();
                monitorBorderPeers();
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

