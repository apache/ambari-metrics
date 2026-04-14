/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file to
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

package org.apache.hadoop.metrics2.sink.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.metrics.timeline.MetricSnapshot;
import org.apache.zookeeper.metrics.timeline.TimelineMetricsSink;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Timeline Metrics Sink for Apache ZooKeeper.
 * 
 * <p>This sink collects metrics from ZooKeeper and sends them to the Ambari Metrics Collector
 * (Timeline Service). It is designed to work with ZooKeeper's TimelineMetricsProvider.</p>
 * 
 * <p><b>Configuration:</b></p>
 * <p>This sink is configured via Properties passed from ZooKeeper's TimelineMetricsProvider:</p>
 * <pre>
 * # In zoo.cfg:
 * metricsProvider.className=org.apache.zookeeper.metrics.timeline.TimelineMetricsProvider
 * metricsProvider.timeline.sink.class=org.apache.hadoop.metrics2.sink.timeline.ZooKeeperTimelineMetricsSink
 * metricsProvider.timeline.collection.period=60
 * metricsProvider.timeline.hostname=zk1.example.com
 * metricsProvider.timeline.appId=zookeeper
 * metricsProvider.timeline.collector.hosts=collector1.example.com,collector2.example.com
 * metricsProvider.timeline.collector.protocol=http
 * metricsProvider.timeline.collector.port=6188
 * </pre>
 * 
 * <p><b>Lifecycle:</b></p>
 * <ol>
 *   <li>ZooKeeper's TimelineMetricsProvider instantiates this class via reflection</li>
 *   <li>{@link #configure(Properties)} loads configuration</li>
 *   <li>TimelineMetricsProvider periodically calls {@link #send(MetricSnapshot)} with metrics</li>
 *   <li>{@link #close()} is called on shutdown</li>
 * </ol>
 * 
 * @see org.apache.zookeeper.metrics.timeline.TimelineMetricsProvider
 * @see org.apache.zookeeper.metrics.timeline.MetricSnapshot
 */
public class ZooKeeperTimelineMetricsSink extends AbstractTimelineMetricsSink 
    implements TimelineMetricsSink {
  private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperTimelineMetricsSink.class);

  // CRITICAL: Custom ObjectMapper that uses Java field names instead of JAXB XML names
  // This ensures "metricValues" is serialized as "metricValues" NOT "metrics"
  private static final ObjectMapper zkMapper;

  static {
    zkMapper = new ObjectMapper();
    // Do NOT use JaxbAnnotationIntrospector - use default Jackson behavior
    zkMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  // Configuration fields
  private String hostName = "UNKNOWN.example.com";
  private String serviceName;
  private Collection<String> collectorHosts;
  private String collectorUri;
  private String protocol;
  private String port;
  private int timeoutSeconds;
  private Properties conf;

  // In-memory aggregation fields
  private int hostInMemoryAggregationPort;
  private boolean hostInMemoryAggregationEnabled;
  private String hostInMemoryAggregationProtocol;
  
  /**
   * Default constructor required for reflection-based instantiation.
   */
  public ZooKeeperTimelineMetricsSink() {
    // No-op constructor
  }

  /**
   * Configure the sink with properties from ZooKeeper.
   * 
   * <p>This method is called by ZooKeeper's TimelineMetricsProvider after instantiation.
   * All properties starting with "timeline." are passed here.</p>
   * 
   * @param configuration Properties from ZooKeeper configuration
   * @throws Exception if configuration fails
   */
  @Override
  public void configure(Properties configuration) throws Exception {
    this.conf = configuration;
    LOG.info("Initializing ZooKeeper Timeline metrics sink.");
    
    // Get hostname - use fallback if cannot be determined
    hostName = configuration.getProperty("timeline.hostname");
    if (hostName == null || hostName.trim().isEmpty()) {
      try {
        hostName = InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        LOG.error("Could not identify hostname. Using fallback: UNKNOWN.example.com", e);
        hostName = "UNKNOWN.example.com";
      }
    }
    
    // Get service name (appId)
    serviceName = configuration.getProperty("timeline.appId", "zookeeper");
    
    LOG.info("Identified hostname = " + hostName + ", serviceName = " + serviceName);
    
    // Initialize the collector write strategy
    super.init();
    
    // Load collector configs
    protocol = configuration.getProperty("timeline.collector.protocol", "http");

    String collectorHostStr = configuration.getProperty("timeline.collector.hosts");
    collectorHosts = parseHostsStringIntoCollection(collectorHostStr);

    port = configuration.getProperty("timeline.collector.port", "6188");

    hostInMemoryAggregationEnabled = Boolean.parseBoolean(
        configuration.getProperty("timeline.host_in_memory_aggregation", "false"));

    hostInMemoryAggregationPort = Integer.parseInt(
        configuration.getProperty("timeline.host_in_memory_aggregation_port", "61888"));

    hostInMemoryAggregationProtocol = configuration.getProperty(
        "timeline.host_in_memory_aggregation_protocol", "http");

    if (collectorHosts.isEmpty()) {
      LOG.warn("No Metric collector configured.");
    } else {
      if (protocol.contains("https") || hostInMemoryAggregationProtocol.contains("https")) {
        String trustStorePath = configuration.getProperty("timeline.truststore.path");
        String trustStoreType = configuration.getProperty("timeline.truststore.type");
        String trustStorePwd = configuration.getProperty("timeline.truststore.password");
        
        if (trustStorePath != null && trustStorePwd != null) {
          // Never log the password
          LOG.info("Loading truststore from: " + trustStorePath + " (type: " + 
                  (trustStoreType != null ? trustStoreType : "default") + ")");
          loadTruststore(trustStorePath.trim(), 
                        trustStoreType != null ? trustStoreType.trim() : null, 
                        trustStorePwd.trim());
          // Clear password from memory if possible (best effort)
          trustStorePwd = null;
        }
      }
      // Note: Collector host discovery is deferred to first metric send to avoid
      // blocking during configuration and prevent circular ZooKeeper connections
      LOG.info("Timeline metrics sink configured. Collector discovery will happen on first metric send.");
    }

    timeoutSeconds = Integer.parseInt(configuration.getProperty("timeline.timeout",
        String.valueOf(DEFAULT_POST_TIMEOUT_SECONDS)));
  }
  
  /**
   * Send metrics to the Timeline collector.
   * 
   * <p>This method is called by ZooKeeper's TimelineMetricsProvider with a snapshot
   * of metrics collected from ZooKeeper.</p>
   * 
   * @param snapshot Snapshot containing metrics to send
   * @throws Exception if sending metrics fails
   */
  @Override
  public void send(MetricSnapshot snapshot) throws Exception {
    if (snapshot == null) {
      LOG.warn("Received null metric snapshot");
      return;
    }
    
    TimelineMetrics metrics = convertSnapshotToTimelineMetrics(snapshot);
    
    if (metrics.getMetrics().isEmpty()) {
      LOG.debug("No metrics to send");
      return;
    }

    // Use custom emitMetrics that uses zkMapper instead of parent's JAXB mapper
    emitMetricsWithCustomMapper(metrics);
  }

  /**
   * Override to use ZooKeeper-specific ObjectMapper that serializes using Java field names.
   * This ensures "metricValues" field is sent as "metricValues" not "metrics" in JSON.
   */
  private void emitMetricsWithCustomMapper(TimelineMetrics metrics) {
    String connectUrl;
    boolean validCollectorHost = true;

    if (isHostInMemoryAggregationEnabled()) {
      String hostname = "localhost";
      if (getHostInMemoryAggregationProtocol().equalsIgnoreCase("https")) {
        hostname = getHostname();
      }
      connectUrl = constructTimelineMetricUri(getHostInMemoryAggregationProtocol(), hostname,
                                              String.valueOf(getHostInMemoryAggregationPort()));
    } else {
      String collectorHost = getCurrentCollectorHost();
      if (collectorHost == null) {
        validCollectorHost = false;
      }
      connectUrl = getCollectorUri(collectorHost);
    }

    if (validCollectorHost) {
      String jsonData = null;
      try {
        // Use zkMapper instead of parent's mapper to avoid JAXB XML naming
        jsonData = zkMapper.writeValueAsString(metrics);
      } catch (IOException e) {
        LOG.error("Unable to serialize metrics", e);
      }
      if (jsonData != null) {
        emitMetricsJson(connectUrl, jsonData);
      }
    }
  }
  
  /**
   * Convert ZooKeeper MetricSnapshot to Timeline Metrics format.
   * 
   * @param snapshot ZooKeeper metric snapshot
   * @return TimelineMetrics object ready to send to collector
   */
  private TimelineMetrics convertSnapshotToTimelineMetrics(MetricSnapshot snapshot) {
    TimelineMetrics timelineMetrics = new TimelineMetrics();
    long timestamp = snapshot.getTimestamp();
    
    // Convert counters - with null check for collection
    Map<String, Long> counters = snapshot.getCounters();
    if (counters != null) {
      for (Map.Entry<String, Long> entry : counters.entrySet()) {
        String metricName = entry.getKey();
        Long value = entry.getValue();
        if (metricName != null && value != null) {
          TimelineMetric metric = createTimelineMetric(metricName, timestamp, value.doubleValue());
          metric.setType("COUNTER");
          timelineMetrics.addOrMergeTimelineMetric(metric);
        }
      }
    }
    
    // Convert gauges - with null check for collection
    Map<String, Double> gauges = snapshot.getGauges();
    if (gauges != null) {
      for (Map.Entry<String, Double> entry : gauges.entrySet()) {
        String metricName = entry.getKey();
        Double value = entry.getValue();
        if (metricName != null && value != null) {
          TimelineMetric metric = createTimelineMetric(metricName, timestamp, value);
          metric.setType("GAUGE");
          timelineMetrics.addOrMergeTimelineMetric(metric);
        }
      }
    }
    
    // Convert summaries - with null check for collection
    Map<String, Double> summaries = snapshot.getSummaries();
    if (summaries != null) {
      for (Map.Entry<String, Double> entry : summaries.entrySet()) {
        String metricName = entry.getKey();
        Double value = entry.getValue();
        if (metricName != null && value != null) {
          TimelineMetric metric = createTimelineMetric(metricName, timestamp, value);
          metric.setType("GAUGE"); // Summaries are treated as gauges
          timelineMetrics.addOrMergeTimelineMetric(metric);
        }
      }
    }
    
    return timelineMetrics;
  }
  
  /**
   * Create a TimelineMetric object.
   * 
   * @param metricName Name of the metric
   * @param timestamp Timestamp in milliseconds
   * @param value Metric value
   * @return TimelineMetric object
   */
  private TimelineMetric createTimelineMetric(String metricName, long timestamp, double value) {
    TimelineMetric metric = new TimelineMetric();
    metric.setMetricName(metricName);
    metric.setHostName(hostName);
    metric.setAppId(serviceName);
    metric.setStartTime(timestamp);
    metric.getMetricValues().put(timestamp, value);
    return metric;
  }
  
  @Override
  protected String getCollectorUri(String host) {
    return constructTimelineMetricUri(protocol, host, port);
  }
  
  @Override
  protected String getCollectorProtocol() {
    return protocol;
  }
  
  @Override
  protected int getTimeoutSeconds() {
    return timeoutSeconds;
  }
  
  @Override
  protected String getZookeeperQuorum() {
    return conf != null ? conf.getProperty("timeline.zookeeper.quorum") : null;
  }
  
  @Override
  protected Collection<String> getConfiguredCollectorHosts() {
    return collectorHosts;
  }
  
  @Override
  protected String getCollectorPort() {
    return port;
  }
  
  @Override
  protected String getHostname() {
    return hostName;
  }
  
  @Override
  protected boolean isHostInMemoryAggregationEnabled() {
    return hostInMemoryAggregationEnabled;
  }
  
  @Override
  protected int getHostInMemoryAggregationPort() {
    return hostInMemoryAggregationPort;
  }
  
  @Override
  protected String getHostInMemoryAggregationProtocol() {
    return hostInMemoryAggregationProtocol;
  }
  
  /**
   * Close the sink and release resources.
   * 
   * @throws Exception if closing fails
   */
  @Override
  public void close() throws Exception {
    LOG.info("Closing ZooKeeperTimelineMetricsSink");
    // No additional cleanup needed - parent class handles connection cleanup
  }
}
