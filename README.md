# Simple Leaf-Spine Network Application
Lee Yongjae, 2017-06-15,08-10.
NOTE: Renamed from SlsNet to SimpleFabric at 2017-12-05.


## SONA Fabric 설계

### 최종 목표
- Leaf-Spine Network을 자동으로 설정한다.
- East-West Traffic 상태에 따라 효율적인 경로 설정을 제공한다. (ECMP인 경우에도 해당?)
- Leaf-Spine Network의 상태를 쉽게 파악할 수 있다.
- 스위치 또는 포트 장애에 끊김없이 대처할 수 있다.

### 필요한 기능
- L2 Unicast: intra-rack (intra-subnet) untagged communication when the destination is known.
- L2 Broadcast: intra-rack (intra-subnet) untagged communication when the destination host is unknown.
- ARP: ARP packets will be sent to the controller and App reponds on virtual gatway IPs.
- L3 Unicast: inter-rack (inter-subnet) untagged communication when the destination is known.

### 가정
- 시스코 스위치가 OpenFlow 1.3을 지원하지만 multi-table은 지원하지 않고 single table만 지원할 가능성이 높음 -- 이 경우 ECMP 지원은 불가능함
 

## Cisco Switch

Switch: Nexus 9000 Series
- Spine Switch: N9K-C9332PQ 
- Leaf Switch: N9K-C9372PX-E


Configuration
```txt
feature openflow

no cdp enable

hardware access-list tcam region racl 0
hardware access-list tcam region e-racl 0
hardware access-list tcam region span 0
hardware access-list tcam region redirect 0
hardware access-list tcam region ns-qos 0
hardware access-list tcam region ns-vqos 0
hardware access-list tcam region ns-l3qos 0
hardware access-list tcam region vpc-convergence 0
hardware access-list tcam region rp-qos 0
hardware access-list tcam region rp-ipv6-qos 0
hardware access-list tcam region rp-mac-qos 0
hardware access-list tcam region openflow 512 double-wide

openflow
  switch 1 pipeline 203
    statistics collection-interval 10
    datapath-id 0x123 4
    controller ipv4 10.10.108.140 port 6653 security none
    of-port interface Ethernet1/1
    of-port interface Ethernet1/2
    of-port interface Ethernet1/3
    of-port interface Ethernet1/4
    protocol-version 1.3
```

N9K-C9332PQ 의 40G Port는 switchport 를 지정해야 vlan 1에 소속되어 정상처리됨
```txt
  interface Ethernet1/31
  switchport
  mode openflow
  no shutdown

  interface Ethernet1/32
  switchport
  mode openflow
  no shutdown
```

Hardware Features
```txt
leaf2# show openflow hardware capabilities pipeline 201

  Max Interfaces: 1000
  Aggregated Statistics: NO

  Pipeline ID: 201
    Pipeline Max Flows: 3001
    Max Flow Batch Size: 300
    Statistics Max Polling Rate (flows/sec): 1024
    Pipeline Default Statistics Collect Interval: 7

    Flow table ID: 0

    Max Flow Batch Size: 300
    Max Flows: 3001
    Bind Subintfs: FALSE                              
    Primary Table: TRUE                               
    Table Programmable: TRUE                               
    Miss Programmable: TRUE                               
    Number of goto tables: 0                                  
    goto table id:      
    Stats collection time for full table (sec): 3

    Match Capabilities                                  Match Types
    ------------------                                  -----------
    ethernet mac destination                            optional    
    ethernet mac source                                 optional    
    ethernet type                                       optional    
    VLAN ID                                             optional    
    VLAN priority code point                            optional    
    IP DSCP                                             optional    
    IP protocol                                         optional    
    IPv4 source address                                 lengthmask  
    IPv4 destination address                            lengthmask  
    source port                                         optional    
    destination port                                    optional    
    in port (virtual or physical)                       optional    
    wildcard all matches                                optional    

    Actions                                             Count Limit             Order
    specified interface                                     64                    20
    controller                                               1                    20
    divert a copy of pkt to application                      1                    20

    set eth source mac                                       1                    10
    set eth destination mac                                  1                    10
    set vlan id                                              1                    10

    pop vlan tag                                             1                    10

    drop packet                                              1                    20


    Miss actions                                        Count Limit             Order
    use normal forwarding                                    1                     0
    controller                                               1                    20

    drop packet                                              1                    20
```

## Topology

<table>
<tr><td>
Network Diagram
</td><td>
Mininet Model: <a href="mininet-simplefabric.py"><code>mininet-simplefabric.py</code></a>
</td></tr>
<tr><td>
<pre>
       EH1
      /   \
     /     \
  [SS1]   [SS2]
    |  \ /  |
    |   X   |
    |  / \  |
  [LS1]   [LS2]
   +- H11  +- H21
   +- H12  +- H22
   +- H13  +- H23
   +- H14  +- H24
   +- D11  +- D21
   +- D12  +- D22 
</pre></td>
<td><pre>
   h31(10.0.0.31/24)    h32(10.0.0.32/24)
           |                    |
           |                    |
  [ss1(10.0.0.1/24)]   [ss2(10.0.0.2/24)]
           |        \ /         |
           |         X          |
           |        / \         |
  [s10(10.0.1.1/24)]   [s20(10.0.2.1/24)]
   +- h11(10.0.1.11)    +- h21(10.0.2.21)
   +- h12(10.0.1.12)    +- h22(10.0.2.22)
   +- h13(10.0.1.13)    +- h23(10.0.2.23)
   +- h14(10.0.1.14)    +- h24(10.0.2.24)
   +- d11(10.0.1.111)   +- d21(10.0.2.221)
   +- d12(10.0.1.112)   +- d22(10.0.2.222)
</pre></td></tr>
</table>

- LSn acts as L2 switch for Hnm and L3 Subnet Router for Hnm  
- SSn acts as inter-Subnet L3 Router for LSns and Use EH1 as Default Router


<br/>

## SimpleFabric Application


### ONOS Core Source Patch for Cisco Issue

apply [onos.patch](onos.patch) and rebuild onos


### SimpleFabric App Build, Install and Activate

in `onos-app-simplefabric` directory
- BUILD:
   - `mvn clean compile install`
- INSTALL TO ONOS AND ACTIVATE APP:
   - `onos-app localhost install target/onos-app-simplefabric-1.13.0-SNAPSHOT.oar`
   - `onos-app localhost activate org.onosproject.simplefabric`

Or use [install_simplefabric.sh](install_simplefabric.sh) script
- Assuming
   - onos sources are located in ../onos
   - onos is installed at /opt/onos-1.11.0 and /opt/onos is symbolic link to it
   - system is Redhat or CentOS and controllable with `service onos [start|stop]` command
- `./install_simplefabric.sh -r` to reinistall ONOS from `../onos/buck-out/gen/tools/package/onos-package/onos.tar.gz`
- `./install_simplefabric.sh [netcfg-json-file]` to 
   - rebuild SimpleFabric app
   - install and activate SimpleFabric on onos
   - install the network config json file (default: SimpleFabric env or network-cfg.json) to /opt/onos/config/ 
   - restart ONOS to apply new SimpleFabric app and network config

Following app are auto activated by SimpleFabric app's dependency
- OpenFlow Provider (for OpenFlow Controller) --> Optical inforamtion model
- LLDP Link Provider (for auto Regi/Deregi Links)
- Host Location Provider (for auto regi host from ARP)

If onos is updated, apply update for external app maven build, at onos/ source directory
- `onos-buck-publish-local`
- ~~`mcis` or `mvn clean install -DskipTests -Dcheckstyle.skip`(2017-08-16 버전 ONOS에서는 필요 없음)~~

### ONOS Network Configuration

설정 파일
- Mininet Test: [network-cfg.json](network-cfg.json)
- 분당 Testbed: [bundang-cfg.json](bundang-cfg.json)

설정 항목
- devices : 유효한 device 목록
- ports : 유효한 한 port 목록; interface name을 지정하여 l2Network 구성시 사용
- app : simplefabric
  - l2Network : ipSubnet 을 할달할 물리적 L2 Network에 속하는 Interface 정보
     - interfaces : l2Network 에 속하는 ports의 interface name 들을 지정
     - l2Forward : false 로 지정하면 L2Forwarding 관련 Intents 생성을 차단 (Cisco용)
  - ipSubnet : Local IP Subnet 정보
     - gatewayIp : 해당 subnet에서의 virtual gateway ip 를 지정
     - l2NetworkName : 해단 subnet 이 속해 있는 l2Network 을 지정
  - borderRoute : 외부로 나가는 Route 정보
     - gatewayIp : 외부 peer측 gateway의 ip; 내부 peer측은 이 gatewayIp 가 속하는 ipSubnet의
       virtual gateway ip 가 사용됨
  - virtualGatewayMacAddress : virtual gateway의 공통 mac address

적용 방법
- to update: `onos-netcfg localhost network-cfg.json`
  - each call updates loaded network config (onos netcfg to see loaded config)
  - updated values are immediately applied to existing entries
- to clean: `onos-netcfg localhost delete`
- to be applied at onos restarts, copy `network-cfg.json` to `${ONOS_HOME}/config/`


### Cisco OpenFlow 기능의 제약

- Instruction 에서 ClearDiffered 를 사용할 수 없음
   - FlowObjective 를 사용하는 경우에 문제가 되는데, 기본적으 PacketService 등록시 해당 기능이 사용됨
   - OpenFlow Pineline Driver 에서 해당 Operation을 빼도록 Driver를 수정해야 함.

- Selecttor 에서 Switch 단위로 IPv4 또는 IPv6 중 한가지만 사용할 수 있음
   - 스위치 설정 중 `hardware access-list tcam region openflow 1024`
     대신 `hardware access-list tcam region openflow-ipv6 1024`
     를 사용하며 IPv6 로만 동작함

- Selector 에 L2 Src/Dst MAC 을 사용할 수 없음 (삭제: 2017-08-07)
   - L2 Forwarding 을 구성할 수 없고, IP 조건식만 사용해야함
   - 기존적으로 사용되는 IntentCompiler 인 LinkCollectionCompiler 에서 
     각 Hop 단계에서의 Treatment 를 기준으로 다음 단계에서 Selector를 사용하는데, 문제가됨
   - --> tcam 설정시 double-wide 를 설정하면 됨: `hardware access-list tcam region openflow 1024 double-wide`

- Instruction 에서 PushVlan 을 사용할 수 없음
   - Intents 에서 EncapsulationType.VLAN 을 사용할 수 없음
   - L2Mac 및 PushVlan 문제로, 기본 IntentCompiler LinkCollectopnCompiler 를 수정하여
     최초의 Selector를 모든 단계에서 사용하도록 코드 수정이 필요 (1줄)
   - --> Selector Mac 제약이 없어졌으므로, 관련 코드 수정 불필요 (2017-08-07)

- Single Table
   - Cisco Pipeline 202 에서는 테이블을 분리하여 사용할 수 있는 것 처럼 나와 있으나, 안됨.
   - --> tcam double-wide 설정 관련하여 변동 사항이 있는지 확인 필요 (2017-08-07)



### 구현된 기능

- L2 Network Forwarding
  - L2 Network 내의 Broadcast 메시지 전송
  - L2 Network 내의 Dst Mac 기준 메시지 전송

- Neighbour Message Handling
  - Host간 ARP 전달 및 Virtual Gateway IP 에 대한 ARP 응답 처리
  - Virtual Gateway IP 에 대한 ICMP ECHO (ping) 요청에 대한 응답 처리

- L3 Reactive Routing
  - Subnet 내부 IP 통신 (L2 Network Forwarding 에서 처리되는 경우 비활성화)
  - Local Subnet 간 IP 통신
  - Local Subnet - External Router 가 Route 에 따른 IP 통신을 모두 Reactive 방식으로 처리



### 분당 TB 에서의 증상 (Cisco 스위치 적용시의 증상)

- Cisco Switch 에서 40G InPort 관련 매칭 문제
  - 40G 포트에 대해서 IN_PORT 매칭 조건이 정상 동작하지 않음
  - IN_PORT matching 을 포함하지 않도록 패치를 적용 (2017-12-01)

```
diff --git a/core/net/src/main/java/org/onosproject/net/intent/impl/compiler/LinkCollectionCompiler.java b/core/net/src/main/java/org/onosproject/net/intent
index 75b3eba..8b411cb 100644
--- a/core/net/src/main/java/org/onosproject/net/intent/impl/compiler/LinkCollectionCompiler.java
+++ b/core/net/src/main/java/org/onosproject/net/intent/impl/compiler/LinkCollectionCompiler.java
@@ -706,8 +706,7 @@ public abstract class LinkCollectionCompiler<T> {
         TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment
                 .builder();
         TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector
-                .builder(intent.selector())
-                .matchInPort(inPort);
+                .builder(intent.selector());
 
         if (!intent.applyTreatmentOnEgress()) {
             manageMpIntent(selectorBuilder,
```

  - IngressPorts 에 대해서는 매칭을 적용하고 싶을 때, 다음과 같은 패치를 적용

```
diff --git a/core/net/src/main/java/org/onosproject/net/intent/impl/compiler/LinkCollectionCompiler.java b/core/net/src/main/java/org/onosproject/net/intent
index 75b3eba..be0e70e 100644
--- a/core/net/src/main/java/org/onosproject/net/intent/impl/compiler/LinkCollectionCompiler.java
+++ b/core/net/src/main/java/org/onosproject/net/intent/impl/compiler/LinkCollectionCompiler.java
@@ -706,8 +706,12 @@ public abstract class LinkCollectionCompiler<T> {
         TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment
                 .builder();
         TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector
-                .builder(intent.selector())
-                .matchInPort(inPort);
+                .builder(intent.selector());
+
+        /* To add inPort selector for ingressPoints only */
+        if (intent.ingressPoints().contains(new ConnectPoint(deviceId, inPort))) {
+            selectorBuilder.matchInPort(inPort);
+        }
 
         if (!intent.applyTreatmentOnEgress()) {
             manageMpIntent(selectorBuilder,
```

- Switch의 BGP 설정과의 간섭 문제 (Cleared)
  - 기존 BGP 설정에서 처리하고 있던 IP 를 Openflow 로 처리하고자 설정 하면 의도치 않은 패킷 흐름이 발생
  - 192.168.1.6 노드(BGP대역)의 포트를 Openflow port 로 변경 하고, SimpleFabric 에서 해당대역을 추가한뒤
    같은 스위치 내에 있는, 192.168.101.3 노드(기존의 Non-BGP, Openflow-Only 대역)와 통신을 하게 하면
    - FlowRule 상으로 동일 스위치 내에서 전송하는 FlowRule 이 내려가고
    - Flow Stat 상에 Packet Count 에도 정상적으로 Count 되나
    - 실제로는 Spine Swith 의 해당 Leaf Switch 연동 포트 쪽에서 패킷이 들어오는 형태로 전송 되어
    - Reactive Routing에서 이를 처리하면서 들어온 포트로 내보내는 형태의 Invalid한 FlowRule 이 생성되는
      문제가 발생
  - 동일 노드에서 기존 BGP 대역의 192.168.1.6 주소가 아닌 새로운 대역의 IP 192.168.102.6 을 할당하면
    정상적으로 처리됨
  - --> 망구성시 BGP 대역과 Openflow 상의 처리 대역이 중복되지 않아여 한다. (2017-08-31)   
  

- Link 장애 테스트 (cleared)
  - Leaf switch 에서 spine switch 방향 port를 down 시키면, 다른쪽 spine switch 쪽으로 즉시 우회됨
     - down 시켰던 port 를 up 시키면 십수초(Cisco Switch에서의 Link Up 시간인 듯) 후 원래 방향쪽으로 돌아옴
     - 우회로가 있는 상태에서는 intents framework 이 잘 동작하는 것으로 보임
  - Leaf switch 에서 spine switch 뱡향 port 2개를 모두 down 시키면, 관련 intents들이 withdrawn 으로 전환됨
     - link가 죽어도 Reactive Routing 기능의 packet forwarding 에 의해, 트래픽 전달이 수행됨 (RRT=3ms 이상)
     - link를 다시 살려놓아도 installed 상태로 돌아오지 않음 !!!
     - simplefabric 에서 routeIntents 로 관리하던 항목이 withdrawn 으로 바뀌면, routeIntents 에서 삭제하고 purge 시키는 기능 필요
       - 해당 기능을 추가하고, SimpleFabricManager에서 idle event 시 refresh() 먼저 확인하고,
       - 각 sub 모듈의 의 idle event 처리시에도 refresh를 수행하도록 변경
     - --> FAIL 된 intents는 remove 되고, 이후 Reactive Routing 처리에 따라 복구됨 (2017-08-16)

- subnet간 통신이 안됨 (cleared)
  - flow rule 까지 적용된 것으로 보이나, 통신은 안되는 듯
  - Leaf->Spine 은 되나, Spine->Leaf 전송이 안됨
  - Spine Switch (N9K-C9332PQ) Port에 switchport 를 지정해야 vlan 1에 소속되고 정상 처리됨 (2017-08-11)
    (참고: 아마도 "An ALE 40G trunk port sends tagged packets on the native VLAN of the port. Normally, untagged packets are sent on the native VLAN" 관련일 듯;  https://www.cisco.com/c/en/us/td/docs/switches/datacenter/nexus9000/sw/ale_ports/b_Limitations_for_ALE_Uplink_Ports_on_Cisco_Nexus_9000_Series_Switches.html)

```txt
  interface Ethernet1/31
  switchport
  mode openflow
  no shutdown

  interface Ethernet1/32
  switchport
  mode openflow
  no shutdown
```

- CONTROLLER 로의 패킷 Forwarding 이 매우 느리게 나타남 (cleared)
  - virtual gateway ip 로의 ping의 지연이 심함 (200ms~2000ms, hosts unreachable)
  - 이와 관련하여, Host의 ARP 메시지 발생시 관련 전송에 심한 지연이 나타남 (700~1700ms)
  - 지연이 있거나 drop 이 있는 듯
  - ** --> rate-limit 을 꺼야 함 ** (2017-08-10)


## ONOS/SimpleFabric Monitoring 

- watchd는 ONOS, SimpleFabric APP, Device, Link 에 대한 Monitoring 기능을 제공
- watchcli 는 SimpleFabricWatchd 에 접속하여, 상태를 조회하는 UI를 제공
- checknet 는 설정 파일에 등록한 host에 들어가 ping 을 통해 host 간 전송상태를 확인하는 기능을 제공

* 기존의 SonaWatcher를 복제하여, 필요한 수정을 적용하고 repository 내에 [watchd/](watchd/) [watchcli/](watchcli/) 로 추가
```
BRANCHED FROM: https://github.com/snsol2/sonaWatchd.git
               commit 9edfdfa7c3b3de3e370d3061159c062f9f737f6c
               at 2017-08-23 by Lee Yongjae, setup74@telcoware.com.
```


### 설치방법
ssh key 생성 및 배포를 위해서 setup tool을 사용한다.
- 서버 실행 장비에서는 `watchd/ssh_key_setup.py` 파일을 실행시킨다.
- ~~클라이언트 실행 환경에서는 `watchcli/ssh_key_setup.py` 파일을 실행시킨다.~~

Python 환경 설정
- Python2.7을 사용하며, 버전 호환성 문제를 피하기 위하여, `/usr/local/bin/python` 을 호출하여 사용한다.
  시스템에서 해당 이름으로 적절한 python을 symbolic link 하여 놓는다.
- Pip install 명령을 사용하여, 다음 python package 를 install 한다.
  - watchd: SlackClient

기본적으로, watchd와 watchcli 는 모두 ONOS를 동작중인 노드에서 수행하는 형태로 설정되어 있다.
- Remote 서버 연동형태로 구성하려면 각각의 설정파일을 변경한다.
- 환경 변수를 사용하여 설정 파일을 지정할 수 있다:
  - watchd 설정 파일: `SIMPLEFABRIC_WATCHD_CFG` (기본값: config.ini)
  - watchcli 설정 파일: `SIMPLEFABRIC_WATCHCLI_CFG` (기본값: cli_config.ini)
  - checknet 설정 파일: `SIMPLEFABRIC_CHECKNET_CFG` (기본값: checknet_config.ini)

모니터링 대상 Device, Link 를 지정하기 위해 반드시 watchd 설정 파일의 `ONOS` 섹션에 있는 `device_list` 와 `link_list` 를 설정하여야 한다.


### Server

in watchd/
- 실행: `./SimpleFabricWatcher.py start`
- 종료: `./SimpleFabricWatcher.py stop`
- 재시작: `./SimpleFabricWatcher.py restart`

### Client
in watchcli/    
- 실행: `./SimpleFabricWatchcli.py`
- 종료: cli main 화면에서 Esc 키 입력 또는 Exit 메뉴 선택

### Check Network by Hosts-Hosts Ping
in checknet/
- 실행: `./SimpleFabricCheckNet` (수행후 종료)

