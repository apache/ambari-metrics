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

import org.apache.ambari.metrics.core.timeline.MetricsSystemInitializationException;
import org.apache.ambari.metrics.core.timeline.TimelineMetricConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.helix.manager.zk.HelixManagerProperty;
import org.apache.helix.manager.zk.ZNRecordSerializer;
import org.apache.helix.zookeeper.api.client.RealmAwareZkClient;
import org.apache.helix.zookeeper.api.client.ZkSecurityConfig;

final class HelixManagerPropertyBuilder {

  private HelixManagerPropertyBuilder() {
  }

  static HelixManagerProperty build(TimelineMetricConfiguration configuration) {
    RealmAwareZkClient.RealmAwareZkClientConfig clientConfig =
      new RealmAwareZkClient.RealmAwareZkClientConfig();
    clientConfig.setZkSerializer(new ZNRecordSerializer());
    clientConfig.setSessionTimeout(configuration.getZkSessionTimeoutMillis());
    clientConfig.setConnectionTimeout(configuration.getZkConnectionTimeoutMillis());

    if (configuration.isSecurityEnabled()) {
      clientConfig.setZkSecurityConfig(buildSecurityConfig(configuration));
      clientConfig.setZkClientType(RealmAwareZkClient.RealmAwareZkClientType.DEDICATED);
    } else {
      clientConfig.setZkClientType(RealmAwareZkClient.RealmAwareZkClientType.SHARED);
    }

    return new HelixManagerProperty(clientConfig);
  }

  private static ZkSecurityConfig buildSecurityConfig(TimelineMetricConfiguration configuration) {
    String principal = configuration.getTimelineMetricsCollectorKerberosPrincipal();
    String keytab = configuration.getTimelineMetricsCollectorKerberosKeytab();

    if (StringUtils.isBlank(principal) || StringUtils.isBlank(keytab)) {
      throw new MetricsSystemInitializationException(
        "AMS Kerberos principal and keytab must be configured when security is enabled");
    }

    ZkSecurityConfig securityConfig = new ZkSecurityConfig();
    securityConfig.setAuthType(ZkSecurityConfig.AuthType.SASL);
    securityConfig.setKerberosPrincipal(principal);
    securityConfig.setKerberosKeytab(keytab);
    securityConfig.setZkSaslEnabled(true);
    return securityConfig;
  }
}
