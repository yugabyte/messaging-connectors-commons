/*
 * Copyright DataStax, Inc.
 *
 * This software is subject to the below license agreement.
 * DataStax may make changes to the agreement from time to time,
 * and will post the amended terms at
 * https://www.datastax.com/terms/datastax-dse-bulk-utility-license-terms.
 */
package com.datastax.kafkaconnector;

import com.datastax.dse.driver.api.core.DseSession;
import com.datastax.kafkaconnector.codecs.KafkaCodecRegistry;
import com.datastax.kafkaconnector.config.DseSinkConfig;
import com.datastax.kafkaconnector.config.TopicConfig;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.apache.kafka.common.KafkaException;
import org.jetbrains.annotations.NotNull;

/** Container for a session, config, etc. */
class InstanceState {
  private final DseSession session;
  private final DseSinkConfig config;
  private final Map<String, TopicState> topicStates;
  private final Semaphore requestBarrier;
  private final Set<DseSinkTask> tasks;

  InstanceState(
      @NotNull DseSinkConfig config,
      @NotNull DseSession session,
      @NotNull Map<String, TopicState> topicStates) {
    this.session = session;
    this.config = config;
    this.topicStates = topicStates;
    this.requestBarrier = new Semaphore(getConfig().getMaxConcurrentRequests());
    tasks = Sets.newConcurrentHashSet();
  }

  void registerTask(DseSinkTask task) {
    tasks.add(task);
  }

  void unregisterTask(DseSinkTask task) {
    tasks.remove(task);
  }

  Set<DseSinkTask> getTasks() {
    return tasks;
  }

  @NotNull
  DseSinkConfig getConfig() {
    return config;
  }

  @NotNull
  DseSession getSession() {
    return session;
  }

  @NotNull
  Semaphore getRequestBarrier() {
    return requestBarrier;
  }

  @NotNull
  TopicConfig getTopicConfig(String topicName) {
    TopicConfig topicConfig = this.config.getTopicConfigs().get(topicName);
    if (topicConfig == null) {
      throw new KafkaException(
          String.format(
              "Connector has no configuration for record topic '%s'. Please update the configuration and restart.",
              topicName));
    }
    return topicConfig;
  }

  @NotNull
  KafkaCodecRegistry getCodecRegistry(String topicName) {
    return getTopicState(topicName).getCodecRegistry();
  }

  @NotNull
  String getInsertStatement(String topicName) {
    return getTopicState(topicName).getInsertStatement();
  }

  @NotNull
  PreparedStatement getPreparedInsertStatement(String topicName) {
    return getTopicState(topicName).getPreparedStatement();
  }

  private TopicState getTopicState(String topicName) {
    TopicState topicState = topicStates.get(topicName);
    if (topicState == null) {
      throw new KafkaException(
          String.format(
              "Connector has no configuration for record topic '%s'. Please update the configuration and restart.",
              topicName));
    }
    return topicState;
  }
}