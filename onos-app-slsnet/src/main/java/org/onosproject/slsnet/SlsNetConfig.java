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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onosproject.net.EncapsulationType;
import org.onosproject.incubator.net.routing.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Configuration object for prefix config.
 */
public class SlsNetConfig extends Config<ApplicationId> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final String L2NETWORKS = "l2Networks";
    private static final String NAME = "name";
    private static final String INTERFACES = "interfaces";
    private static final String IP4SUBNETS = "ip4Subnets";
    private static final String IP6SUBNETS = "ip6Subnets";
    private static final String BORDERINTERFACES = "borderInterfaces";
    private static final String BORDERROUTES = "borderRoutes";
    private static final String IPPREFIX = "ipPrefix";
    private static final String TYPE = "type";
    private static final String GATEWAYIP = "gatewayIp";
    private static final String VIRTUALGATEWAYMACADDRESS =
                               "virtualGatewayMacAddress";

    /**
     * Returns all l2Networks in this configuration.
     *
     * @return A set of L2Network.
     */
    public Set<L2Network> getL2Networks() {
        Set<L2Network> l2Networks = Sets.newHashSet();
        JsonNode l2NetworkNode = object.get(L2NETWORKS);
        if (l2NetworkNode == null) {
            return l2Networks;
        }

        l2NetworkNode.forEach(jsonNode -> {
            String name = jsonNode.get(NAME).asText();

            Set<String> ifaces = Sets.newHashSet();
            JsonNode l2NetworkIfaces = jsonNode.path(INTERFACES);
            if (!l2NetworkIfaces.toString().isEmpty()) {
                l2NetworkIfaces.forEach(ifacesNode -> ifaces.add(new String(ifacesNode.asText())));
            }

            l2Networks.add(new L2Network(name, ifaces, EncapsulationType.NONE));
        });
        return l2Networks;
    }

    /**
     * Gets the set of configured local IPv4 prefixes.
     *
     * @return IPv4 prefixes
     */
    public Set<IpSubnet> ip4Subnets() {
        Set<IpSubnet> prefixes = Sets.newHashSet();

        JsonNode prefixesNode = object.get(IP4SUBNETS);
        if (prefixesNode == null) {
            log.warn("ip4Subnets is null!");
            return prefixes;
        }

        prefixesNode.forEach(jsonNode -> {
            prefixes.add(new IpSubnet(
                    IpPrefix.valueOf(jsonNode.get(IPPREFIX).asText()),
                    IpSubnet.IpPrefixType.valueOf("PRIVATE"),
                    IpAddress.valueOf(jsonNode.get(GATEWAYIP).asText())));
        });

        return prefixes;
    }

    /**
     * Gets the set of configured local IPv6 prefixes.
     *
     * @return IPv6 prefixes
     */
    public Set<IpSubnet> ip6Subnets() {
        Set<IpSubnet> prefixes = Sets.newHashSet();

        JsonNode prefixesNode = object.get(IP6SUBNETS);
        if (prefixesNode == null) {
            /* no warning for ip6 case is not implemented */
            /*log.warn("ip6LocalPrefixes is null!"); */
            return prefixes;
        }

        prefixesNode.forEach(jsonNode -> {
            prefixes.add(new IpSubnet(
                    IpPrefix.valueOf(jsonNode.get(IPPREFIX).asText()),
                    IpSubnet.IpPrefixType.valueOf("PRIVATE"),
                    IpAddress.valueOf(jsonNode.get(GATEWAYIP).asText())));
        });

        return prefixes;
    }

    /**
     *  Gets of the external gateway interfaces.
     *
     * @return interface names
     */
    public Set<String> getBorderInterfaces() {
        Set<String> ifaces = Sets.newHashSet();

        JsonNode routeIfaces = object.get(BORDERINTERFACES);
        if (routeIfaces == null) {
            return ifaces;
        }
        routeIfaces.forEach(ifacesNode -> ifaces.add(ifacesNode.asText()));

        return ifaces;
    }

    /**
     * Returns all routes in this configuration.
     *
     * @return A set of route.
     */
    public Set<Route> getBorderRoutes() {
        Set<Route> routes = Sets.newHashSet();

        JsonNode routesNode = object.get(BORDERROUTES);
        if (routesNode == null) {
            /* no warning for ip6 case is not implemented */
            /*log.warn("ip6LocalPrefixes is null!"); */
            return routes;
        }

        routesNode.forEach(jsonNode -> {
            try {
                routes.add(new Route(Route.Source.STATIC,
                      IpPrefix.valueOf(jsonNode.path(IPPREFIX).asText()),
                      IpAddress.valueOf(jsonNode.path(GATEWAYIP).asText())));
            } catch (IllegalArgumentException e) {
                // Ignores routes that cannot be parsed correctly
            }
        });

        return routes;
    }

    /**
     *  Gets of the virtual gateway MAC address.
     *
     * @return virtual gateway MAC address
     */
    public MacAddress virtualGatewayMacAddress() {
        return MacAddress.valueOf(
                object.get(VIRTUALGATEWAYMACADDRESS).asText());
    }

}