/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.metrics.core.timeline.availability;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import org.apache.ambari.metrics.core.timeline.TimelineMetricConfiguration;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.manager.zk.HelixManagerProperty;
import org.apache.helix.participant.StateMachineEngine;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.helix.zookeeper.api.client.RealmAwareZkClient;
import org.apache.helix.zookeeper.api.client.ZkSecurityConfig;
import org.easymock.Capture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.core.classloader.annotations.PrepareForTest;

@RunWith(PowerMockRunner.class)
@PrepareForTest({HelixManagerFactory.class})
public class HelixManagerPropertyBuilderTest {

  private static final String CLUSTER = "ambari-metrics-cluster";
  private static final String INSTANCE = "collector";
  private static final String ZK_ADDRESS = "localhost:2181";

  @Test
  public void testSecureConfigurationUsesSasl() throws Exception {
    TimelineMetricConfiguration configuration = createNiceMock(TimelineMetricConfiguration.class);
    expect(configuration.getZkSessionTimeoutMillis()).andReturn(12000).anyTimes();
    expect(configuration.getZkConnectionTimeoutMillis()).andReturn(15000).anyTimes();
    expect(configuration.isSecurityEnabled()).andReturn(true).anyTimes();
    expect(configuration.getTimelineMetricsCollectorKerberosPrincipal()).andReturn("principal@EXAMPLE.COM").anyTimes();
    expect(configuration.getTimelineMetricsCollectorKerberosKeytab()).andReturn("/path/to/keytab").anyTimes();
    replay(configuration);

    HelixManagerProperty property = HelixManagerPropertyBuilder.build(configuration);

    HelixManager helixManager = createMock(HelixManager.class);
    StateMachineEngine stateMachineEngine = createMock(StateMachineEngine.class);
    ZkHelixPropertyStore<?> propertyStore = createNiceMock(ZkHelixPropertyStore.class);

    expect(helixManager.getStateMachineEngine()).andReturn(stateMachineEngine);
    stateMachineEngine.registerStateModelFactory(
      MetricCollectorHAController.DEFAULT_STATE_MODEL, anyObject());
    expectLastCall().once();
    helixManager.connect();
    expectLastCall().once();
    expect(helixManager.getHelixPropertyStore()).andReturn(propertyStore);

    replay(helixManager, stateMachineEngine, propertyStore);

    PowerMock.mockStatic(HelixManagerFactory.class);
    Capture<HelixManagerProperty> propertyCapture = newCapture();
    expect(HelixManagerFactory.getZKHelixManager(CLUSTER, INSTANCE, InstanceType.PARTICIPANT,
      ZK_ADDRESS, capture(propertyCapture))).andReturn(helixManager);
    PowerMock.replay(HelixManagerFactory.class);

    AggregationTaskRunner runner = new AggregationTaskRunner(INSTANCE, ZK_ADDRESS, CLUSTER, property);
    runner.initialize();

    HelixManagerProperty captured = propertyCapture.getValue();
    RealmAwareZkClient.RealmAwareZkClientConfig clientConfig = captured.getRealmAwareZkClientConfig();
    ZkSecurityConfig securityConfig = clientConfig.getZkSecurityConfig();

    org.junit.Assert.assertNotNull("Security config should be configured", securityConfig);
    org.junit.Assert.assertEquals(ZkSecurityConfig.AuthType.SASL, securityConfig.getAuthType());
    org.junit.Assert.assertEquals("principal@EXAMPLE.COM", securityConfig.getKerberosPrincipal());
    org.junit.Assert.assertEquals("/path/to/keytab", securityConfig.getKerberosKeytab());
    org.junit.Assert.assertEquals(RealmAwareZkClient.RealmAwareZkClientType.DEDICATED,
      clientConfig.getZkClientType());
    org.junit.Assert.assertEquals(12000, clientConfig.getSessionTimeout());
    org.junit.Assert.assertEquals(15000, clientConfig.getConnectionTimeout());

    PowerMock.verify(HelixManagerFactory.class);
    verify(helixManager, stateMachineEngine, propertyStore);
  }

  @Test
  public void testUnsecuredConfigurationUsesSharedClient() throws Exception {
    TimelineMetricConfiguration configuration = createNiceMock(TimelineMetricConfiguration.class);
    expect(configuration.getZkSessionTimeoutMillis()).andReturn(10000).anyTimes();
    expect(configuration.getZkConnectionTimeoutMillis()).andReturn(20000).anyTimes();
    expect(configuration.isSecurityEnabled()).andReturn(false).anyTimes();
    replay(configuration);

    HelixManagerProperty property = HelixManagerPropertyBuilder.build(configuration);

    HelixManager helixManager = createMock(HelixManager.class);
    StateMachineEngine stateMachineEngine = createMock(StateMachineEngine.class);
    ZkHelixPropertyStore<?> propertyStore = createNiceMock(ZkHelixPropertyStore.class);

    expect(helixManager.getStateMachineEngine()).andReturn(stateMachineEngine);
    stateMachineEngine.registerStateModelFactory(
      MetricCollectorHAController.DEFAULT_STATE_MODEL, anyObject());
    expectLastCall().once();
    helixManager.connect();
    expectLastCall().once();
    expect(helixManager.getHelixPropertyStore()).andReturn(propertyStore);

    replay(helixManager, stateMachineEngine, propertyStore);

    PowerMock.mockStatic(HelixManagerFactory.class);
    Capture<HelixManagerProperty> propertyCapture = newCapture();
    expect(HelixManagerFactory.getZKHelixManager(CLUSTER, INSTANCE, InstanceType.PARTICIPANT,
      ZK_ADDRESS, capture(propertyCapture))).andReturn(helixManager);
    PowerMock.replay(HelixManagerFactory.class);

    AggregationTaskRunner runner = new AggregationTaskRunner(INSTANCE, ZK_ADDRESS, CLUSTER, property);
    runner.initialize();

    HelixManagerProperty captured = propertyCapture.getValue();
    RealmAwareZkClient.RealmAwareZkClientConfig clientConfig = captured.getRealmAwareZkClientConfig();

    org.junit.Assert.assertNull("Security config should not be set", clientConfig.getZkSecurityConfig());
    org.junit.Assert.assertEquals(RealmAwareZkClient.RealmAwareZkClientType.SHARED,
      clientConfig.getZkClientType());
    org.junit.Assert.assertEquals(10000, clientConfig.getSessionTimeout());
    org.junit.Assert.assertEquals(20000, clientConfig.getConnectionTimeout());

    PowerMock.verify(HelixManagerFactory.class);
    verify(helixManager, stateMachineEngine, propertyStore);
  }
}
