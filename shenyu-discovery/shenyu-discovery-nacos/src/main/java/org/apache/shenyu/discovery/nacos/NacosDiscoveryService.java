/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.discovery.nacos;

import com.google.gson.JsonObject;
import org.apache.shenyu.common.utils.GsonUtils;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.apache.shenyu.common.exception.ShenyuException;
import org.apache.shenyu.discovery.api.ShenyuDiscoveryService;
import org.apache.shenyu.discovery.api.config.DiscoveryConfig;
import org.apache.shenyu.discovery.api.listener.DataChangedEventListener;
import org.apache.shenyu.discovery.api.listener.DiscoveryDataChangedEvent;
import org.apache.shenyu.spi.Join;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * The type Nacos for shenyu discovery service.
 */
@Join
public class NacosDiscoveryService implements ShenyuDiscoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosDiscoveryService.class);

    private static final String NAMESPACE = "nacosNameSpace";

    private final ConcurrentMap<String, EventListener> listenerMap = new ConcurrentHashMap<>();

    private NamingService namingService;

    private String groupName;

    private final ConcurrentMap<String, List<Instance>> instanceListMap = new ConcurrentHashMap<>();

    @Override
    public void init(final DiscoveryConfig config) {
        Properties properties = config.getProps();
        Properties nacosProperties = new Properties();
        this.groupName = properties.getProperty("groupName", "SHENYU_GROUP");
        String serverAddr = config.getServerList();
        nacosProperties.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
        nacosProperties.put(PropertyKeyConst.NAMESPACE, properties.getProperty(NAMESPACE, ""));
        nacosProperties.put(PropertyKeyConst.USERNAME, properties.getProperty(PropertyKeyConst.USERNAME, ""));
        nacosProperties.put(PropertyKeyConst.PASSWORD, properties.getProperty(PropertyKeyConst.PASSWORD, ""));
        nacosProperties.put(PropertyKeyConst.ACCESS_KEY, properties.getProperty(PropertyKeyConst.ACCESS_KEY, ""));
        nacosProperties.put(PropertyKeyConst.SECRET_KEY, properties.getProperty(PropertyKeyConst.SECRET_KEY, ""));
        try {
            this.namingService = NamingFactory.createNamingService(nacosProperties);
            LOGGER.info("Nacos naming service initialized success");
        } catch (NacosException e) {
            LOGGER.error("Error initializing Nacos naming service", e);
            throw new ShenyuException(e);
        }
    }

    @Override
    public void watch(final String key, final DataChangedEventListener listener) {
        try {
            if (!listenerMap.containsKey(key)) {
                List<Instance> initialInstances = namingService.selectInstances(key, groupName, true);
                instanceListMap.put(key, initialInstances);
                EventListener nacosListener = event -> {
                    if (event instanceof NamingEvent) {
                        try {
                            List<Instance> previousInstances = instanceListMap.get(key);
                            List<Instance> currentInstances = namingService.selectInstances(key, groupName, true);
                            compareInstances(previousInstances, currentInstances, listener);
                            instanceListMap.put(key, currentInstances);
                        } catch (NacosException e) {
                            throw new ShenyuException(e);
                        }
                    }
                };
                namingService.subscribe(key, groupName, nacosListener);
                listenerMap.put(key, nacosListener);
                LOGGER.info("Subscribed to Nacos updates for key: {}", key);
            }
        } catch (NacosException e) {
            LOGGER.error("nacosDiscoveryService error watching key: {}", key, e);
            throw new ShenyuException(e);
        }
    }

    @Override
    public void unwatch(final String key) {
        try {
            EventListener nacosListener = listenerMap.get(key);
            if (Objects.nonNull(nacosListener)) {
                namingService.unsubscribe(key, groupName, nacosListener);
                listenerMap.remove(key);
                LOGGER.info("Nacos Unwatch key: {}", key);
            }
        } catch (NacosException e) {
            LOGGER.error("Error removing Nacos service listener: {}", e.getMessage(), e);
            throw new ShenyuException(e);
        }
    }

    @Override
    public void register(final String key, final String value) {
        try {
            Instance instance = GsonUtils.getInstance().fromJson(value, Instance.class);
            namingService.registerInstance(key, groupName, instance);
            LOGGER.info("Registering service with key: {} and value: {}", key, value);
        } catch (NacosException e) {
            LOGGER.error("Error registering Nacos service instance: {}", e.getMessage(), e);
            throw new ShenyuException(e);
        }
    }

    @Override
    public List<String> getRegisterData(final String key) {
        try {
            List<Instance> instances = namingService.selectInstances(key, groupName, true);
            List<String> registerData = new ArrayList<>();
            for (Instance instance : instances) {
                String data = buildInstanceInfoJson(instance);
                registerData.add(data);
            }
            return registerData;
        } catch (NacosException e) {
            LOGGER.error("Error getting Nacos service instances: {}", e.getMessage(), e);
            throw new ShenyuException(e);
        }
    }

    @Override
    public Boolean exists(final String key) {
        try {
            List<Instance> instances = namingService.selectInstances(key, groupName, true);
            return !instances.isEmpty();
        } catch (NacosException e) {
            LOGGER.error("Error checking Nacos service existence: {}", e.getMessage(), e);
            throw new ShenyuException(e);
        }
    }

    @Override
    public void shutdown() {
        try {
            if (Objects.nonNull(namingService)) {
                for (Map.Entry<String, EventListener> entry : listenerMap.entrySet()) {
                    String key = entry.getKey();
                    EventListener listener = entry.getValue();
                    namingService.unsubscribe(key, groupName, listener);
                }
                listenerMap.clear();
                namingService.shutDown();
                LOGGER.info("Shutting down NacosDiscoveryService");
            }
        } catch (NacosException e) {
            LOGGER.error("Error shutting down NacosDiscoveryService", e);
            throw new ShenyuException(e);
        }
    }

    private void compareInstances(final List<Instance> previousInstances, final List<Instance> currentInstances, final DataChangedEventListener listener) {
        Set<Instance> addedInstances = currentInstances.stream()
                .filter(item -> !previousInstances.contains(item))
                .collect(Collectors.toSet());
        if (!addedInstances.isEmpty()) {
            for (Instance instance: addedInstances) {
                DiscoveryDataChangedEvent dataChangedEvent = new DiscoveryDataChangedEvent(instance.getServiceName(),
                        buildInstanceInfoJson(instance), DiscoveryDataChangedEvent.Event.ADDED);
                listener.onChange(dataChangedEvent);
            }
        }

        Set<Instance> deletedInstances = previousInstances.stream()
                .filter(item -> !currentInstances.contains(item))
                .collect(Collectors.toSet());
        if (!deletedInstances.isEmpty()) {
            for (Instance instance: deletedInstances) {
                instance.setHealthy(false);
                DiscoveryDataChangedEvent dataChangedEvent = new DiscoveryDataChangedEvent(instance.getServiceName(),
                        buildInstanceInfoJson(instance), DiscoveryDataChangedEvent.Event.DELETED);
                listener.onChange(dataChangedEvent);
            }
        }

        Set<Instance> updatedInstances = currentInstances.stream()
                .filter(currentInstance -> previousInstances.stream()
                        .anyMatch(previousInstance -> currentInstance.getInstanceId().equals(previousInstance.getInstanceId()) && !currentInstance.equals(previousInstance)))
                .collect(Collectors.toSet());
        if (!updatedInstances.isEmpty()) {
            for (Instance instance: updatedInstances) {
                DiscoveryDataChangedEvent dataChangedEvent = new DiscoveryDataChangedEvent(instance.getServiceName(),
                        buildInstanceInfoJson(instance), DiscoveryDataChangedEvent.Event.UPDATED);
                listener.onChange(dataChangedEvent);
            }
        }
    }

    private String buildInstanceInfoJson(final Instance instance) {
        JsonObject instanceJson = new JsonObject();
        instanceJson.addProperty("url", instance.getIp() + ":" + instance.getPort());
        // status 0:true, 1:false
        instanceJson.addProperty("status", instance.isHealthy() ? 0 : 1);
        instanceJson.addProperty("weight", instance.getWeight());

        return GsonUtils.getInstance().toJson(instanceJson);
    }
}





