/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.common.sink.config;

import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.CONTACT_POINTS;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.METADATA_SCHEMA_REFRESHED_KEYSPACES;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.METRICS_NODE_ENABLED;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.METRICS_SESSION_CQL_REQUESTS_INTERVAL;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.METRICS_SESSION_ENABLED;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_CIPHER_SUITES;

import com.datastax.oss.common.sink.ConfigException;
import com.datastax.oss.common.sink.util.SinkUtil;
import com.datastax.oss.common.sink.util.StringUtil;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.shaded.guava.common.base.Splitter;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connector configuration and validation. */
@SuppressWarnings("WeakerAccess")
public class CassandraSinkConfig {
  private static final Logger log = LoggerFactory.getLogger(CassandraSinkConfig.class);
  private static final Pattern TOPIC_KS_TABLE_SETTING_PATTERN =
      Pattern.compile(
          "topic\\.([a-zA-Z0-9._-]+)\\.([^.]+|\"[\"]+\")\\.([^.]+|\"[\"]+\")\\.(mapping|consistencyLevel|ttl|nullToUnset|useEventTimeForWrite|deletesEnabled|ttlTimeUnit|timestampTimeUnit|query)$");
  public static final Pattern TOPIC_CODEC_PATTERN =
      Pattern.compile(
          "topic\\.([a-zA-Z0-9._-]+)\\.(codec)\\.(locale|timeZone|timestamp|date|time|unit)$");

  private static final String DRIVER_CONFIG_PREFIX = "datastax-java-driver";

  static final String SSL_OPT_PREFIX = "ssl.";
  private static final String AUTH_OPT_PREFIX = "auth.";

  public static final String CONTACT_POINTS_OPT = "contactPoints";

  static final String PORT_OPT = "port";

  public static final String DC_OPT = "loadBalancing.localDc";
  static final String LOCAL_DC_DRIVER_SETTING =
      withDriverPrefix(DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER);

  static final String CONCURRENT_REQUESTS_OPT = "maxConcurrentRequests";

  static final String QUERY_EXECUTION_TIMEOUT_OPT = "queryExecutionTimeout";
  static final String QUERY_EXECUTION_TIMEOUT_DRIVER_SETTING =
      withDriverPrefix(DefaultDriverOption.REQUEST_TIMEOUT);
  public static final String QUERY_EXECUTION_TIMEOUT_DEFAULT = "30 seconds";

  static final String CONNECTION_POOL_LOCAL_SIZE = "connectionPoolLocalSize";
  static final String CONNECTION_POOL_LOCAL_SIZE_DRIVER_SETTING =
      withDriverPrefix(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE);
  public static final String CONNECTION_POOL_LOCAL_SIZE_DEFAULT = "4";

  static final String JMX_OPT = "jmx";
  public static final String JMX_CONNECTOR_DOMAIN_OPT = "jmxConnectorDomain";
  private static final String JMX_CONNECTOR_DOMAIN_OPT_DEFAULT = "com.datastax.oss.kafka.sink";
  static final String COMPRESSION_OPT = "compression";
  static final String COMPRESSION_DRIVER_SETTING =
      withDriverPrefix(DefaultDriverOption.PROTOCOL_COMPRESSION);
  public static final String COMPRESSION_DEFAULT = "none";

  static final String MAX_NUMBER_OF_RECORDS_IN_BATCH = "maxNumberOfRecordsInBatch";

  static final String METRICS_HIGHEST_LATENCY_OPT = "metricsHighestLatency";
  static final String METRICS_HIGHEST_LATENCY_DRIVER_SETTINGS =
      withDriverPrefix(DefaultDriverOption.METRICS_NODE_CQL_MESSAGES_HIGHEST);
  static final String METRICS_HIGHEST_LATENCY_DEFAULT = "35 seconds";

  static final String IGNORE_ERRORS = "ignoreErrors";

  public static final String SECURE_CONNECT_BUNDLE_OPT = "cloud.secureConnectBundle";
  static final String SECURE_CONNECT_BUNDLE_DRIVER_SETTING =
      withDriverPrefix(DefaultDriverOption.CLOUD_SECURE_CONNECT_BUNDLE);

  static final List<String> JAVA_DRIVER_SETTINGS_LIST_TYPE =
      ImmutableList.of(
          withDriverPrefix(METRICS_SESSION_ENABLED),
          withDriverPrefix(CONTACT_POINTS),
          withDriverPrefix(METADATA_SCHEMA_REFRESHED_KEYSPACES),
          withDriverPrefix(METRICS_NODE_ENABLED),
          withDriverPrefix(SSL_CIPHER_SUITES));

  public static final ConfigDef GLOBAL_CONFIG_DEF =
      new ConfigDef()
          .define(
              CONTACT_POINTS_OPT,
              ConfigDef.Type.LIST,
              Collections.EMPTY_LIST,
              ConfigDef.Importance.HIGH,
              "Initial contact points")
          .define(
              PORT_OPT,
              ConfigDef.Type.INT,
              9042,
              ConfigDef.Range.atLeast(1),
              ConfigDef.Importance.HIGH,
              "Port to connect to nodes")
          .define(
              DC_OPT,
              ConfigDef.Type.STRING,
              "",
              ConfigDef.Importance.HIGH,
              "The datacenter name (commonly dc1, dc2, etc.) local to the machine on which the connector is running")
          .define(
              CONCURRENT_REQUESTS_OPT,
              ConfigDef.Type.INT,
              500,
              ConfigDef.Range.atLeast(1),
              ConfigDef.Importance.HIGH,
              "The maximum number of requests to send at once")
          .define(
              JMX_OPT,
              ConfigDef.Type.BOOLEAN,
              true,
              ConfigDef.Importance.HIGH,
              "Whether to enable JMX reporting")
          .define(
              JMX_CONNECTOR_DOMAIN_OPT,
              ConfigDef.Type.STRING,
              JMX_CONNECTOR_DOMAIN_OPT_DEFAULT,
              ConfigDef.Importance.LOW,
              "Domain for JMX reporting")
          .define(
              COMPRESSION_OPT,
              ConfigDef.Type.STRING,
              "None",
              ConfigDef.Importance.HIGH,
              "None | LZ4 | Snappy")
          .define(
              QUERY_EXECUTION_TIMEOUT_OPT,
              ConfigDef.Type.INT,
              30,
              ConfigDef.Range.atLeast(1),
              ConfigDef.Importance.HIGH,
              "CQL statement execution timeout, in seconds")
          .define(
              METRICS_HIGHEST_LATENCY_OPT,
              ConfigDef.Type.INT,
              35,
              ConfigDef.Range.atLeast(1),
              ConfigDef.Importance.HIGH,
              "This is used to scale internal data structures for gathering metrics. "
                  + "It should be higher than queryExecutionTimeout. This parameter should be expressed in seconds.")
          .define(
              MAX_NUMBER_OF_RECORDS_IN_BATCH,
              ConfigDef.Type.INT,
              32,
              ConfigDef.Range.atLeast(1),
              ConfigDef.Importance.HIGH,
              "Maximum number of records that could be send in one batch request")
          .define(
              CONNECTION_POOL_LOCAL_SIZE,
              ConfigDef.Type.INT,
              4,
              ConfigDef.Range.atLeast(1),
              ConfigDef.Importance.HIGH,
              "Number of connections that driver maintains within a connection pool to each node in local dc")
          .define(
              IGNORE_ERRORS,
              ConfigDef.Type.STRING,
              "None",
              ConfigDef.Importance.HIGH,
              "Specifies which errors the connector should ignore when processing the record. "
                  + "Valid values are: "
                  + "None (never ignore errors), "
                  + "All (ignore all errors), "
                  + "Driver (ignore driver errors only, i.e. errors when writing to the database).")
          .define(
              SECURE_CONNECT_BUNDLE_OPT,
              ConfigDef.Type.STRING,
              "",
              ConfigDef.Importance.HIGH,
              "The location of the cloud secure bundle used to connect to Datastax Apache Cassandra as a service.");
  private static final Function<String, String> TO_SECONDS_CONVERTER =
      v -> String.format("%s seconds", v);

  static final String METRICS_INTERVAL_DEFAULT = "30 seconds";

  private final String instanceName;
  private final AbstractConfig globalConfig;
  private final Map<String, TopicConfig> topicConfigs;
  private final Map<String, String> javaDriverSettings;

  @Nullable private SslConfig sslConfig;

  private final AuthenticatorConfig authConfig;

  public CassandraSinkConfig(Map<String, String> settings) {
    try {
      log.debug("create CassandraSinkConfig for settings:{} ", settings);
      instanceName = settings.get(SinkUtil.NAME_OPT);
      // Walk through the settings and separate out "globals" from "topics", "ssl", and "auth".
      Map<String, String> globalSettings = new HashMap<>();
      Map<String, String> sslSettings = new HashMap<>();
      Map<String, String> authSettings = new HashMap<>();
      Map<String, Map<String, String>> topicSettings = new HashMap<>();
      javaDriverSettings = new HashMap<>();
      for (Map.Entry<String, String> entry : settings.entrySet()) {
        String name = entry.getKey();
        if (name.startsWith("topic.")) {
          String topicName = tryMatchTopicName(name);
          Map<String, String> topicMap =
              topicSettings.computeIfAbsent(topicName, t -> new HashMap<>());
          topicMap.put(name, entry.getValue());
        } else if (name.startsWith(SSL_OPT_PREFIX)) {
          sslSettings.put(name, entry.getValue());
        } else if (name.startsWith(AUTH_OPT_PREFIX)) {
          authSettings.put(name, entry.getValue());
        } else if (name.startsWith(DRIVER_CONFIG_PREFIX)) {
          addJavaDriverSetting(entry);
        } else {
          globalSettings.put(name, entry.getValue());
        }
      }

      // Put the global settings in an AbstractConfig and make/store a TopicConfig for every
      // topic settings map.
      globalConfig = new AbstractConfig(GLOBAL_CONFIG_DEF, globalSettings, false);

      populateDriverSettingsWithConnectorSettings(globalSettings);
      boolean cloud = isCloud();

      if (!cloud) {
        sslConfig = new SslConfig(sslSettings);
      }
      authConfig = new AuthenticatorConfig(authSettings);
      topicConfigs = new HashMap<>();
      topicSettings.forEach(
          (name, topicConfigMap) ->
              topicConfigs.put(name, new TopicConfig(name, topicConfigMap, cloud)));

      validateCompressionType();

      // Verify that we have a topic section for every topic we're subscribing to, if 'topics'
      // was provided. A user may use topics.regex to subscribe by pattern, in which case,
      // they're on their own.
      String topicsString = globalSettings.get("topics");
      if (topicsString != null) {
        List<String> topics = Splitter.on(",").trimResults().splitToList(topicsString);
        for (String topic : topics) {
          if (!topicConfigs.containsKey(topic)) {
            throw new ConfigException(
                "topics",
                topicsString,
                String.format("Missing topic settings (topic.%s.*) for topic %s", topic, topic));
          }
        }
      }

      if (cloud) {
        // Verify that if cloudSecureBundle specified the
        // other clashing properties (contactPoints, dc, ssl) are not set.
        validateCloudSettings(sslSettings);
      }

      // Verify that if contact-points are provided, local dc is also specified.
      List<String> contactPoints = getContactPoints();
      log.debug("contactPoints: {}", contactPoints);
      if (!contactPoints.isEmpty() && !getLocalDc().isPresent()) {
        throw new ConfigException(
            CONTACT_POINTS_OPT,
            contactPoints,
            String.format("When contact points is provided, %s must also be specified", DC_OPT));
      }
    } catch (org.apache.kafka.common.config.ConfigException err) {
      // convert Kafka config framework exception into our exception
      throw new ConfigException(err.getMessage(), err);
    }
  }

  private static final Splitter COMA_SPLITTER = Splitter.on(",");

  private void addJavaDriverSetting(Map.Entry<String, String> entry) {

    if (JAVA_DRIVER_SETTINGS_LIST_TYPE.contains(entry.getKey())) {
      putAsTypesafeListProperty(entry.getKey(), entry.getValue());
    } else {
      javaDriverSettings.put(entry.getKey(), entry.getValue());
    }
  }

  private void putAsTypesafeListProperty(@NonNull String key, @NonNull String value) {
    List<String> values = COMA_SPLITTER.splitToList(value);
    for (int i = 0; i < values.size(); i++) {
      javaDriverSettings.put(String.format("%s.%d", key, i), values.get(i).trim());
    }
  }

  private void validateCompressionType() {
    String compressionTypeValue = javaDriverSettings.get(COMPRESSION_DRIVER_SETTING);
    if (!(compressionTypeValue.toLowerCase().equals("none")
        || compressionTypeValue.toLowerCase().equals("snappy")
        || compressionTypeValue.toLowerCase().equals("lz4"))) {
      throw new ConfigException(
          COMPRESSION_OPT, compressionTypeValue, "valid values are none, snappy, lz4");
    }
  }

  private void populateDriverSettingsWithConnectorSettings(Map<String, String> connectorSettings) {
    deprecatedLocalDc(connectorSettings);
    deprecatedConnectionPoolSize(connectorSettings);
    deprecatedQueryExecutionTimeout(connectorSettings);
    deprecatedMetricsHighestLatency(connectorSettings);
    deprecatedCompression(connectorSettings);
    deprecatedSecureBundle(connectorSettings);

    if (getJmx()) {
      metricsSettings();
    }
  }

  private void deprecatedSecureBundle(Map<String, String> connectorSettings) {
    handleDeprecatedSetting(
        connectorSettings,
        SECURE_CONNECT_BUNDLE_OPT,
        SECURE_CONNECT_BUNDLE_DRIVER_SETTING,
        null,
        Function.identity());
  }

  private void deprecatedCompression(Map<String, String> connectorSettings) {
    handleDeprecatedSetting(
        connectorSettings,
        COMPRESSION_OPT,
        COMPRESSION_DRIVER_SETTING,
        COMPRESSION_DEFAULT,
        Function.identity());
  }

  private void deprecatedLocalDc(Map<String, String> connectorSettings) {
    handleDeprecatedSetting(
        connectorSettings, DC_OPT, LOCAL_DC_DRIVER_SETTING, null, Function.identity());
  }

  private void deprecatedConnectionPoolSize(Map<String, String> connectorSettings) {
    handleDeprecatedSetting(
        connectorSettings,
        CONNECTION_POOL_LOCAL_SIZE,
        CONNECTION_POOL_LOCAL_SIZE_DRIVER_SETTING,
        CONNECTION_POOL_LOCAL_SIZE_DEFAULT,
        Function.identity());
  }

  private void deprecatedQueryExecutionTimeout(Map<String, String> connectorSettings) {
    handleDeprecatedSetting(
        connectorSettings,
        QUERY_EXECUTION_TIMEOUT_OPT,
        QUERY_EXECUTION_TIMEOUT_DRIVER_SETTING,
        QUERY_EXECUTION_TIMEOUT_DEFAULT,
        TO_SECONDS_CONVERTER);
  }

  private void deprecatedMetricsHighestLatency(Map<String, String> connectorSettings) {
    handleDeprecatedSetting(
        connectorSettings,
        METRICS_HIGHEST_LATENCY_OPT,
        METRICS_HIGHEST_LATENCY_DRIVER_SETTINGS,
        METRICS_HIGHEST_LATENCY_DEFAULT,
        TO_SECONDS_CONVERTER);
  }

  private void metricsSettings() {
    String metricsEnabledDriverSetting = withDriverPrefix(METRICS_SESSION_ENABLED);

    // if user explicitly provided setting under datastax-java-driver do not add defaults
    if (javaDriverSettings
        .keySet()
        .stream()
        .noneMatch(v -> v.startsWith(metricsEnabledDriverSetting))) {
      javaDriverSettings.put(metricsEnabledDriverSetting + ".0", "cql-requests");
      javaDriverSettings.put(metricsEnabledDriverSetting + ".1", "cql-client-timeouts");
    }

    String sessionCqlRequestIntervalDriverSetting =
        withDriverPrefix(METRICS_SESSION_CQL_REQUESTS_INTERVAL);
    if (!javaDriverSettings.containsKey(sessionCqlRequestIntervalDriverSetting)) {
      javaDriverSettings.put(sessionCqlRequestIntervalDriverSetting, METRICS_INTERVAL_DEFAULT);
    }
  }

  private void handleDeprecatedSetting(
      @NonNull Map<String, String> connectorSettings,
      @NonNull String connectorDeprecatedSetting,
      @NonNull String driverSetting,
      @Nullable String defaultValue,
      @NonNull Function<String, String> deprecatedValueConverter) {
    // handle usage of deprecated setting
    if (connectorSettings.containsKey(connectorDeprecatedSetting)) {
      // put or override if setting with datastax-java-driver prefix provided
      javaDriverSettings.put(
          driverSetting,
          deprecatedValueConverter.apply(connectorSettings.get(connectorDeprecatedSetting)));
    }

    if (defaultValue != null) {
      // handle default if setting is not provided
      if (!javaDriverSettings.containsKey(driverSetting)) {
        javaDriverSettings.put(driverSetting, defaultValue);
      }
    }
  }

  public static String withDriverPrefix(DefaultDriverOption option) {
    return String.format("%s.%s", DRIVER_CONFIG_PREFIX, option.getPath());
  }

  private void validateCloudSettings(Map<String, String> sslSettings) {
    if (!getContactPoints().isEmpty()) {
      throw new ConfigException(
          String.format(
              "When %s parameter is specified you should not provide %s.",
              SECURE_CONNECT_BUNDLE_OPT, CONTACT_POINTS_OPT));
    }

    if (getLocalDc().isPresent()) {
      throw new ConfigException(
          String.format(
              "When %s parameter is specified you should not provide %s.",
              SECURE_CONNECT_BUNDLE_OPT, DC_OPT));
    }

    if (!sslSettings.isEmpty()) {
      throw new ConfigException(
          String.format(
              "When %s parameter is specified you should not provide any setting under %s.",
              SECURE_CONNECT_BUNDLE_OPT, SSL_OPT_PREFIX));
    }
  }

  private String tryMatchTopicName(String name) {
    Matcher m = TOPIC_KS_TABLE_SETTING_PATTERN.matcher(name);
    // match for topic.ks.table level setting
    if (m.matches()) {
      return m.group(1);
    } else {
      // otherwise it can be topic (codec) level setting
      Matcher m2 = TOPIC_CODEC_PATTERN.matcher(name);
      if (m2.matches()) {
        return m2.group(1);
      }
      throw new IllegalArgumentException(
          "The setting: "
              + name
              + " does not match topic.keyspace.table nor topic.codec regular expression pattern");
    }
  }

  public String getInstanceName() {
    return instanceName;
  }

  public int getPort() {
    return globalConfig.getInt(PORT_OPT);
  }

  public int getMaxConcurrentRequests() {
    return globalConfig.getInt(CONCURRENT_REQUESTS_OPT);
  }

  public enum IgnoreErrorsPolicy {
    ALL,
    NONE,
    DRIVER
  }

  public IgnoreErrorsPolicy getIgnoreErrors() {
    String ignoreErrors = globalConfig.getString(IGNORE_ERRORS);
    if ("none".equalsIgnoreCase(ignoreErrors)) {
      return IgnoreErrorsPolicy.NONE;
    } else if ("all".equalsIgnoreCase(ignoreErrors)) {
      return IgnoreErrorsPolicy.ALL;
    } else if ("driver".equalsIgnoreCase(ignoreErrors)) {
      return IgnoreErrorsPolicy.DRIVER;
    } else if ("false".equalsIgnoreCase(ignoreErrors)) {
      log.warn(
          "Setting {}=false is deprecated, please replace with {}=None",
          IGNORE_ERRORS,
          IGNORE_ERRORS);
      return IgnoreErrorsPolicy.NONE;
    } else if ("true".equalsIgnoreCase(ignoreErrors)) {
      log.warn(
          "Setting {}=true is deprecated, please replace with {}=Driver",
          IGNORE_ERRORS,
          IGNORE_ERRORS);
      return IgnoreErrorsPolicy.DRIVER;
    }
    throw new IllegalArgumentException(
        "Invalid value for setting "
            + IGNORE_ERRORS
            + ", expecting either All, None or Driver, got: "
            + ignoreErrors);
  }

  public boolean getJmx() {
    return globalConfig.getBoolean(JMX_OPT);
  }

  public String getJmxConnectorDomain() {
    return globalConfig.getString(JMX_CONNECTOR_DOMAIN_OPT);
  }

  public boolean isCloud() {
    return !StringUtil.isEmpty(javaDriverSettings.get(SECURE_CONNECT_BUNDLE_DRIVER_SETTING));
  }

  public List<String> getContactPoints() {
    return globalConfig.getList(CONTACT_POINTS_OPT);
  }

  public Optional<String> getLocalDc() {
    return Optional.ofNullable(javaDriverSettings.get(LOCAL_DC_DRIVER_SETTING))
        .filter(v -> !v.isEmpty());
  }

  public Map<String, TopicConfig> getTopicConfigs() {
    return topicConfigs;
  }

  public AuthenticatorConfig getAuthenticatorConfig() {
    return authConfig;
  }

  @Nullable
  public SslConfig getSslConfig() {
    return sslConfig;
  }

  public int getMaxNumberOfRecordsInBatch() {
    return globalConfig.getInt(MAX_NUMBER_OF_RECORDS_IN_BATCH);
  }

  @Override
  public String toString() {
    return String.format(
        "Global configuration:%n"
            + "        contactPoints: %s%n"
            + "        port: %s%n"
            + "        maxConcurrentRequests: %d%n"
            + "        maxNumberOfRecordsInBatch: %d%n"
            + "        jmx: %b%n"
            + "SSL configuration:%n%s%n"
            + "Authentication configuration:%n%s%n"
            + "Topic configurations:%n%s%n"
            + "datastax-java-driver configuration: %n%s",
        getContactPoints(),
        getPortToString(),
        getMaxConcurrentRequests(),
        getMaxNumberOfRecordsInBatch(),
        getJmx(),
        getSslConfigToString(),
        Splitter.on("\n")
            .splitToList(authConfig.toString())
            .stream()
            .map(line -> "        " + line)
            .collect(Collectors.joining("\n")),
        topicConfigs
            .values()
            .stream()
            .map(
                t ->
                    Splitter.on("\n")
                        .splitToList(t.toString())
                        .stream()
                        .map(line -> "        " + line)
                        .collect(Collectors.joining("\n")))
            .collect(Collectors.joining("\n")),
        javaDriverSettings
            .entrySet()
            .stream()
            .map(entry -> "      " + entry)
            .collect(Collectors.joining("\n")));
  }

  private String getSslConfigToString() {
    if (sslConfig != null) {
      return Splitter.on("\n")
          .splitToList(sslConfig.toString())
          .stream()
          .map(line -> "        " + line)
          .collect(Collectors.joining("\n"));
    } else {
      return "SslConfig not present";
    }
  }

  private String getPortToString() {
    if (isCloud()) {
      return String.format("%s will be ignored because you are using cloud", PORT_OPT);
    }
    return String.valueOf(getPort());
  }

  public Map<String, String> getJavaDriverSettings() {
    return javaDriverSettings;
  }
}
