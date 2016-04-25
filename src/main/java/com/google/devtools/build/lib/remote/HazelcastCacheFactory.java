// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientNetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.map.MapInterceptor;
import com.hazelcast.internal.ascii.rest.RestValue;

import java.util.concurrent.ConcurrentMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * A factory class for providing a {@link ConcurrentMap} object implemented by Hazelcast.
 * Hazelcast will work as a distributed memory cache.
 */
final class HazelcastCacheFactory {

  private static final String CACHE_NAME = "hazelcast-build-cache";

  static ConcurrentMap<String, byte[]> create(RemoteOptions options) {
    HazelcastInstance instance;
    if (options.hazelcastNode != null) {
      // If --hazelast_node is then create a client instance.
      ClientConfig config = new ClientConfig();
      ClientNetworkConfig net = config.getNetworkConfig();
      net.addAddress(options.hazelcastNode.split(","));
      instance = HazelcastClient.newHazelcastClient(config);
    } else {
      // Otherwise create a default instance. This is going to look at
      // -Dhazelcast.config=some-hazelcast.xml for configuration.
      instance = Hazelcast.newHazelcastInstance();
    }
    IMap<String, byte[]> cache = instance.getMap(CACHE_NAME);
    return cache;
  }

  static public class SynchronizedWrites<K, V> implements ConcurrentMap<K, V> {
    private final IMap<K, V> backingMap;

    SynchronizedWrites(IMap<K, V> backingMap) {
      this.backingMap = backingMap;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      return backingMap.entrySet();
    }

    @Override
    public Set<K> keySet() {
      return backingMap.keySet();
    }

    @Override
    public void clear() {
      backingMap.clear();
    }

    @Override
    public int size() {
      return backingMap.size();
    }

    @Override
    public boolean isEmpty() {
      return backingMap.isEmpty();
    }

    @Override
    public Collection<V> values() {
      return backingMap.values();
    }

    @Override
    public void putAll(Map<? extends K,? extends V> m) {
      backingMap.putAll(m);
    }

    @Override
    public V put(K key, V value) {
      backingMap.lock(key);
      V v = backingMap.put(key, value);
      backingMap.unlock(key);
      return v;
    }

    @Override
    public V get(Object key) {
      return backingMap.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
      return backingMap.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      return backingMap.containsValue(value);
    }

    @Override
    public V putIfAbsent(K key, V value) {
      backingMap.lock(key);
      if (backingMap.containsKey(key)) {
        System.out.println("Skipped:" + key);
        backingMap.unlock(key);
        return value;
      }
      V v = backingMap.putIfAbsent(key, value);
      backingMap.unlock(key);
      return v;
    }

    @Override
    public V replace(K key, V value) {
      backingMap.lock(key);
      V v = backingMap.replace(key, value);
      backingMap.unlock(key);
      return v;
    }

    @Override
    public boolean replace(K key, V oldvalue, V newvalue) {
      backingMap.lock(key);
      boolean b = backingMap.replace(key, oldvalue, newvalue);
      backingMap.unlock(key);
      return b;
    }

    @Override
    public V remove(Object k) {
      return backingMap.remove(k);
    }

    @Override
    public boolean remove(Object k, Object v) {
      return backingMap.remove(k, v);
    }
  }
}
