/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.metrics.core.timeline.source.cache;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.EnumSet;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.event.CacheEvent;
import org.ehcache.event.CacheEventListener;
import org.ehcache.event.EventType;
import org.ehcache.event.EventFiring;
import org.ehcache.event.EventOrdering;
import org.ehcache.expiry.Expirations;

public class InternalMetricsCache {
  private static final Log LOG = LogFactory.getLog(InternalMetricsCache.class);
  private final String instanceName;
  private final Integer internalCacheEntryCount;
  private volatile boolean isCacheInitialized = false;
  private Cache<InternalMetricCacheKey, InternalMetricCacheValue> cache;
  private final Lock lock = new ReentrantLock();
  private static final int LOCK_TIMEOUT_SECONDS = 2;

  public InternalMetricsCache(String instanceName, Integer internalCacheEntryCount) {
    this.instanceName = instanceName;
    this.internalCacheEntryCount = internalCacheEntryCount;
    initialize();
  }

  private void initialize() {
    // Check in case of contention to avoid ObjectExistsException
    if (isCacheInitialized) {
      throw new RuntimeException("Cannot initialize internal cache twice");
    }

    CacheManager manager = CacheManagerBuilder.newCacheManagerBuilder()
            .build(true);

    // Create a Cache specifying its configuration.
    CacheConfiguration<InternalMetricCacheKey, InternalMetricCacheValue> cacheConfig =
            CacheConfigurationBuilder.newCacheConfigurationBuilder(
                    InternalMetricCacheKey.class, InternalMetricCacheValue.class,
                    ResourcePoolsBuilder.newResourcePoolsBuilder().heap(internalCacheEntryCount, EntryUnit.ENTRIES)
            ).withExpiry(Expirations.noExpiration()).build();

    cache = manager.createCache(instanceName, cacheConfig);
    cache.getRuntimeConfiguration().registerCacheEventListener(new InternalCacheEvictionListener(), EventOrdering.ORDERED, EventFiring.SYNCHRONOUS, EnumSet.of(EventType.EVICTED));

    LOG.info("Registering internal metrics cache with provider: name = " +
            instanceName);

    isCacheInitialized = true;
  }

  public InternalMetricCacheValue getInternalMetricCacheValue(InternalMetricCacheKey key) {
    return cache.get(key);
  }

  public Collection<TimelineMetrics> evictAll() {
    TimelineMetrics metrics = new TimelineMetrics();
    try {
      if (lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        try {
          Iterator<Cache.Entry<InternalMetricCacheKey, InternalMetricCacheValue>> iterator = cache.iterator();
          while (iterator.hasNext()) {
            Cache.Entry<InternalMetricCacheKey, InternalMetricCacheValue> entry = iterator.next();
            TimelineMetric metric = new TimelineMetric();
            InternalMetricCacheKey key = entry.getKey();
            metric.setMetricName(key.getMetricName());
            metric.setAppId(key.getAppId());
            metric.setInstanceId(key.getInstanceId());
            metric.setHostName(key.getHostname());
            metric.setStartTime(key.getStartTime());
            InternalMetricCacheValue value = cache.get(key);
            metric.setMetricValues(value.getMetricValues());
            metrics.getMetrics().add(metric);
            iterator.remove();
          }
        } finally {
          lock.unlock();
        }
      } else {
        LOG.warn("evictAll: Unable to acquire lock on the cache instance. " +
          "Giving up after " + LOCK_TIMEOUT_SECONDS + " seconds.");
      }
    } catch (InterruptedException e) {
      LOG.warn("Interrupted while waiting to acquire lock");
    }

    return Collections.singletonList(metrics);
  }

  public void putAll(Collection<TimelineMetrics> metrics) {
    try {
      if (lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        try {
          if (metrics != null) {
            for (TimelineMetrics timelineMetrics : metrics) {
              for (TimelineMetric timelineMetric : timelineMetrics.getMetrics()) {
                InternalMetricCacheKey key = new InternalMetricCacheKey(
                  timelineMetric.getMetricName(),
                  timelineMetric.getAppId(),
                  timelineMetric.getInstanceId(),
                  timelineMetric.getHostName(),
                  timelineMetric.getStartTime()
                );

                InternalMetricCacheValue value = cache.get(key);
                if (value != null) {
                  value.addMetricValues(timelineMetric.getMetricValues());
                } else {
                  value = new InternalMetricCacheValue();
                  value.setMetricValues(timelineMetric.getMetricValues());
                  cache.put(key, value);
                }
              }
            }
          }
        } finally {
          lock.unlock();
        }
      } else {
        LOG.warn("putAll: Unable to acquire lock on the cache instance. " +
          "Giving up after " + LOCK_TIMEOUT_SECONDS + " seconds.");
      }
    } catch (InterruptedException e) {
      LOG.warn("Interrupted while waiting to acquire lock");
    }
  }

  class InternalCacheEvictionListener implements CacheEventListener<InternalMetricCacheKey, InternalMetricCacheValue> {
    @Override
    public void onEvent(CacheEvent<? extends InternalMetricCacheKey, ? extends InternalMetricCacheValue> event) {
      if (event.getType() == EventType.EVICTED) {
        InternalMetricCacheKey key = event.getKey();
        LOG.warn("Evicting element from internal metrics cache, metric => " + key
                .getMetricName() + ", startTime = " + new Date(key.getStartTime()));
      }
    }
  }
}
