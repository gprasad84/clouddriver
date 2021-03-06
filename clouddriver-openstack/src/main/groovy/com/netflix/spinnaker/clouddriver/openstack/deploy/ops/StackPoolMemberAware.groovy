/*
 * Copyright 2016 Target, Inc.
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
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.openstack.deploy.ops

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.netflix.spinnaker.clouddriver.openstack.deploy.description.servergroup.MemberData
import com.netflix.spinnaker.clouddriver.openstack.security.OpenstackCredentials
import org.openstack4j.model.network.ext.ListenerV2
import org.openstack4j.model.network.ext.LoadBalancerV2

trait StackPoolMemberAware {

  /**
   * Build pool member resources for the given load balancers.
   * @param credentials
   * @param region
   * @param subnetId
   * @param lbIds
   * @param portParser
   * @return
   */
  List<MemberData> buildMemberData(OpenstackCredentials credentials, String region, String subnetId, List<String> lbIds, Closure portParser) {
    lbIds.collectMany { loadBalancerId ->
      LoadBalancerV2 loadBalancer = credentials.provider.getLoadBalancer(region, loadBalancerId)
      loadBalancer.listeners.collect { item ->
        ListenerV2 listener = credentials.provider.getListener(region, item.id)
        String internalPort = portParser(listener.description).internalPort
        String poolId = listener.defaultPoolId
        new MemberData(subnetId: subnetId ?: loadBalancer.vipSubnetId, externalPort: listener.protocolPort.toString(), internalPort: internalPort, poolId: poolId)
      }
    }
  }

  /**
   * Build pool member resources for the given load balancers.
   * @param credentials
   * @param region
   * @param lbIds
   * @param portParser
   * @return
   */
  List<MemberData> buildMemberData(OpenstackCredentials credentials, String region, List<String> lbIds, Closure portParser) {
    buildMemberData(credentials, region, null, lbIds, portParser)
  }

  /**
   * Convert a list of pool members to an embeddable heat template.
   * @param memberData
   * @return
   */
  String buildPoolMemberTemplate(List<MemberData> memberData) {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
    Map<String, Object> parameters = [address: [type: "string", description: "Server address for autoscaling group resource"]]
    Map<String, Object> resources = memberData.collectEntries {
      [
        //unique per listener/app port pair. Behavior is unknown if multiple load balancers are attached to
        //the server group and have the same port mapping. Don't do that.
        ("member-$it.externalPort-$it.internalPort"): [
          type      : "OS::Neutron::LBaaS::PoolMember",
          properties: [
            address      : [get_param: "address"],
            pool         : it.poolId,
            protocol_port: it.internalPort,
            subnet       : it.subnetId
          ]
        ]
      ]
    }
    Map<String, Object> memberTemplate = [
      heat_template_version: "2016-04-08",
      description          : "Pool members for autoscaling group resource",
      parameters           : parameters,
      resources            : resources]
    mapper.writeValueAsString(memberTemplate)
  }
}
