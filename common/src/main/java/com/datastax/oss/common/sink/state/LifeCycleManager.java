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
package com.datastax.oss.common.sink.state;

import static com.datastax.dse.driver.api.core.config.DseDriverOption.AUTH_PROVIDER_SASL_PROPERTIES;
import static com.datastax.dse.driver.api.core.config.DseDriverOption.AUTH_PROVIDER_SERVICE;
import static com.datastax.oss.common.sink.config.TableConfig.MAPPING_OPT;
import static com.datastax.oss.common.sink.util.UUIDUtil.generateClientId;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.AUTH_PROVIDER_CLASS;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.AUTH_PROVIDER_PASSWORD;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.AUTH_PROVIDER_USER_NAME;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_CIPHER_SUITES;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_ENGINE_FACTORY_CLASS;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_HOSTNAME_VALIDATION;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_KEYSTORE_PASSWORD;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_KEYSTORE_PATH;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_TRUSTSTORE_PASSWORD;
import static com.datastax.oss.driver.api.core.config.DefaultDriverOption.SSL_TRUSTSTORE_PATH;

import com.codahale.metrics.MetricRegistry;
import com.datastax.dse.driver.api.core.config.DseDriverOption;
import com.datastax.dse.driver.internal.core.auth.DseGssApiAuthProvider;
import com.datastax.oss.common.sink.AbstractSinkTask;
import com.datastax.oss.common.sink.ConfigException;
import com.datastax.oss.common.sink.config.AuthenticatorConfig;
import com.datastax.oss.common.sink.config.CassandraSinkConfig;
import com.datastax.oss.common.sink.config.ContactPointsValidator;
import com.datastax.oss.common.sink.config.SslConfig;
import com.datastax.oss.common.sink.config.TableConfig;
import com.datastax.oss.common.sink.config.TopicConfig;
import com.datastax.oss.common.sink.ssl.SessionBuilder;
import com.datastax.oss.common.sink.util.SinkUtil;
import com.datastax.oss.common.sink.util.StringUtil;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.internal.core.auth.PlainTextAuthProvider;
import com.datastax.oss.driver.internal.core.config.typesafe.DefaultDriverConfigLoader;
import com.datastax.oss.driver.internal.core.config.typesafe.DefaultProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.shaded.guava.common.annotations.VisibleForTesting;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import com.datastax.oss.dsbulk.codecs.api.ConvertingCodecFactory;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that is responsible for setting up / cleaning up state when a {@link AbstractSinkTask}
 * starts up or shuts down.
 */
public class LifeCycleManager {

  private static final Logger log = LoggerFactory.getLogger(LifeCycleManager.class);
  private static final ConcurrentMap<String, InstanceState> INSTANCE_STATES =
      new ConcurrentHashMap<>();
  private static MetricRegistry metricRegistry = new MetricRegistry();

  /** This is a utility class that no one should instantiate. */
  private LifeCycleManager() {}

  /**
   * Perform setup needed before a CassandraSinkTask is ready to handle records. Primarily, get or
   * create an {@link InstanceState} and register the task with the owning InstanceState.
   *
   * @param task the task
   * @param props connector instance properties, from the connector config file (for
   *     connect-standalone) or a config stored in Kafka itself (connect-distributed).
   * @return the {@link InstanceState} that owns this task.
   */
  public static InstanceState startTask(AbstractSinkTask task, Map<String, String> props) {
    InstanceState instanceState =
        INSTANCE_STATES.computeIfAbsent(
            props.get(SinkUtil.NAME_OPT),
            x -> {
              CassandraSinkConfig config = new CassandraSinkConfig(props);
              CqlSession session = buildCqlSession(config, task.version(), task.applicationName());
              return buildInstanceState(session, config);
            });
    instanceState.registerTask(task);
    return instanceState;
  }

  /**
   * Perform any cleanup needed when a task is terminated.
   *
   * @param instanceState the owning {@link InstanceState}
   * @param task the task
   */
  public static void stopTask(InstanceState instanceState, AbstractSinkTask task) {
    log.debug("Unregistering task");
    if (instanceState != null && instanceState.unregisterTaskAndCheckIfLast(task)) {
      INSTANCE_STATES.remove(instanceState.getConfig().getInstanceName());
    }
    log.debug("Task is no longer registered with Connector instance.");
  }

  /**
   * Validate that the mapping in the given tableConfig references columns that exist in the table,
   * and that every primary key column in the table has a mapping.
   *
   * @param table the TableMetadata of the table
   * @param tableConfig the TableConfig to evaluate
   * @return true if all columns in the table are mapped, false otherwise.
   */
  @VisibleForTesting
  static boolean validateMappingColumns(TableMetadata table, TableConfig tableConfig) {
    Map<CqlIdentifier, CqlIdentifier> mapping = tableConfig.getMapping();

    // The columns in the mapping are the keys. Check that each exists in the table.
    String nonExistentCols =
        mapping
            .keySet()
            .stream()
            .filter(col -> !table.getColumn(col).isPresent())
            .filter(col -> !SinkUtil.isTtlMappingColumn(col))
            .filter(col -> !SinkUtil.isTimestampMappingColumn(col))
            .map(c -> c.asCql(true))
            .collect(Collectors.joining(", "));
    if (!StringUtil.isEmpty(nonExistentCols)) {
      throw new ConfigException(
          tableConfig.getSettingPath(MAPPING_OPT),
          tableConfig.getMappingString(),
          String.format(
              "The following columns do not exist in table %s: %s",
              tableConfig.getTable().asInternal(), nonExistentCols));
    }

    // Now verify that each column that makes up the primary key in the table has a
    // reference in the mapping.
    String nonExistentKeyCols =
        table
            .getPrimaryKey()
            .stream()
            .filter(col -> !mapping.containsKey(col.getName()))
            .map(col -> col.getName().toString())
            .collect(Collectors.joining(", "));
    if (!StringUtil.isEmpty(nonExistentKeyCols)) {
      throw new ConfigException(
          tableConfig.getSettingPath(MAPPING_OPT),
          tableConfig.getMappingString(),
          String.format(
              "The following columns are part of the primary key but are not mapped: %s",
              nonExistentKeyCols));
    }

    return mapping.keySet().size() == table.getColumns().size();
  }

  /**
   * Construct an INSERT CQL statement based on the mapping for the target table.
   *
   * @param config the config
   * @return INSERT CQL string
   */
  @VisibleForTesting
  @NonNull
  static String makeInsertStatement(TableConfig config) {
    Map<CqlIdentifier, CqlIdentifier> mapping = config.getMapping();
    StringBuilder statementBuilder = new StringBuilder("INSERT INTO ");
    statementBuilder
        .append(config.getKeyspace().asCql(true))
        .append('.')
        .append(config.getTable().asCql(true))
        .append('(');

    // Add the column names, which are the keys in the mapping. As we do so, collect the
    // bind variable names (e.g. :col) in a buffer (to achieve consistent order).
    StringBuilder valuesBuilder = new StringBuilder();
    boolean isFirst = true;
    for (CqlIdentifier col : mapping.keySet()) {
      if (SinkUtil.isTtlMappingColumn(col) || SinkUtil.isTimestampMappingColumn(col)) {
        continue;
      }
      if (!isFirst) {
        statementBuilder.append(',');
        valuesBuilder.append(',');
      }
      isFirst = false;
      String colCql = col.asCql(true);
      statementBuilder.append(colCql);
      valuesBuilder.append(':').append(colCql);
    }
    statementBuilder.append(") VALUES (").append(valuesBuilder.toString()).append(")");
    appendWriteTime(config, statementBuilder);
    appendTtl(config, statementBuilder);
    return statementBuilder.toString();
  }

  private static void appendWriteTime(TableConfig config, StringBuilder statementBuilder) {
    if (config.useEventTimeForWrite()) {
      statementBuilder.append(" USING TIMESTAMP :").append(SinkUtil.TIMESTAMP_VARNAME);
    }
  }

  private static void appendTtl(TableConfig config, StringBuilder statementBuilder) {
    if (config.hasTtlMappingColumn()) {
      statementBuilder.append(" AND TTL :").append(SinkUtil.TTL_VARNAME);
    } else if (config.getTtl() != -1) {
      statementBuilder.append(" AND TTL ").append(config.convertTtlToSeconds(config.getTtl()));
    }
  }

  /**
   * Construct an UPDATE CQL statement based on the mapping for the target table. Used for COUNTER
   * table updates.
   *
   * @param config the config
   * @return UPDATE CQL string
   */
  @VisibleForTesting
  @NonNull
  static String makeUpdateCounterStatement(TableConfig config, TableMetadata table) {
    if (config.getTtl() != -1 || config.hasTtlMappingColumn()) {
      throw new ConfigException("Cannot set ttl when updating a counter table");
    }

    // Create an UPDATE statement that looks like this:
    // UPDATE ks.table SET col1 = col1 + :col1, col2 = col2 + :col2, ...
    // WHERE pk1 = :pk1 AND pk2 = :pk2 ...

    Map<CqlIdentifier, CqlIdentifier> mapping = config.getMapping();
    StringBuilder statementBuilder = new StringBuilder("UPDATE ");
    statementBuilder
        .append(config.getKeyspace().asCql(true))
        .append('.')
        .append(config.getTable().asCql(true))
        .append(" SET ");

    List<CqlIdentifier> pks =
        table.getPrimaryKey().stream().map(ColumnMetadata::getName).collect(Collectors.toList());

    // Walk through the columns and add the "col1 = col1 + :col1" fragments for
    // all non-pk columns.
    boolean isFirst = true;
    for (CqlIdentifier col : mapping.keySet()) {
      if (pks.contains(col)) {
        continue;
      }
      if (!isFirst) {
        statementBuilder.append(',');
      }
      isFirst = false;
      String colAsCql = col.asCql(true);
      statementBuilder
          .append(colAsCql)
          .append(" = ")
          .append(colAsCql)
          .append(" + :")
          .append(colAsCql);
    }

    // Add the WHERE clause, covering pk columns.
    statementBuilder.append(" WHERE ");
    isFirst = true;
    for (CqlIdentifier col : pks) {
      if (!isFirst) {
        statementBuilder.append(" AND ");
      }
      isFirst = false;
      String colAsCql = col.asCql(true);
      statementBuilder.append(colAsCql).append(" = :").append(colAsCql);
    }

    return statementBuilder.toString();
  }

  /**
   * Construct a DELETE CQL statement based on the mapping for the target table.
   *
   * @param config the config
   * @return DELETE CQL string
   */
  @VisibleForTesting
  @NonNull
  static String makeDeleteStatement(TableConfig config, TableMetadata table) {

    // Create a DELETE statement that looks like this:
    // DELETE FROM ks.table
    // WHERE pk1 = :pk1 AND pk2 = :pk2 ...

    StringBuilder statementBuilder = new StringBuilder("DELETE FROM ");
    statementBuilder
        .append(config.getKeyspace().asCql(true))
        .append('.')
        .append(config.getTable().asCql(true));

    List<CqlIdentifier> pks =
        table.getPrimaryKey().stream().map(ColumnMetadata::getName).collect(Collectors.toList());

    // Add the WHERE clause, covering pk columns.
    statementBuilder.append(" WHERE ");
    boolean isFirst = true;
    for (CqlIdentifier col : pks) {
      if (!isFirst) {
        statementBuilder.append(" AND ");
      }
      isFirst = false;
      String colAsCql = col.asCql(true);
      statementBuilder.append(colAsCql).append(" = :").append(colAsCql);
    }

    return statementBuilder.toString();
  }

  /**
   * Find the desired table's metadata from the session if possible.
   *
   * @param session the session
   * @param tableConfig the config of the desired table
   * @return metadata of the table, if present
   * @throws ConfigException if the table or keyspace doesn't exist
   */
  @VisibleForTesting
  @NonNull
  static TableMetadata getTableMetadata(CqlSession session, TableConfig tableConfig) {
    CqlIdentifier keyspaceName = tableConfig.getKeyspace();
    CqlIdentifier tableName = tableConfig.getTable();
    Metadata metadata = session.getMetadata();
    Optional<? extends KeyspaceMetadata> keyspace = metadata.getKeyspace(keyspaceName);
    if (!keyspace.isPresent()) {
      String lowerCaseKeyspaceName = keyspaceName.asInternal().toLowerCase();
      if (metadata.getKeyspace(lowerCaseKeyspaceName).isPresent()) {
        throw new ConfigException(
            String.format(
                "Keyspace %s does not exist, however a keyspace %s was found. Update the config to use %s if desired.",
                keyspaceName.asInternal(), lowerCaseKeyspaceName, lowerCaseKeyspaceName));
      } else {
        throw new ConfigException(
            String.format("Keyspace %s does not exist.", keyspaceName.asInternal()));
      }
    }
    Optional<? extends TableMetadata> table = keyspace.get().getTable(tableName);
    if (!table.isPresent()) {
      String lowerCaseTableName = tableName.asInternal().toLowerCase();
      if (keyspace.get().getTable(lowerCaseTableName).isPresent()) {
        throw new ConfigException(
            String.format(
                "Table %s does not exist, however a table %s was found. Update the config to use %s if desired.",
                tableName.asInternal(), lowerCaseTableName, lowerCaseTableName));
      } else {
        throw new ConfigException(
            String.format("Table %s does not exist.", tableName.asInternal()));
      }
    }
    return table.get();
  }

  private static boolean isCounterTable(TableMetadata table) {
    return table.getColumns().values().stream().anyMatch(c -> c.getType() == DataTypes.COUNTER);
  }

  /**
   * Perform heavy lifting of creating an InstanceState:
   *
   * <ul>
   *   <li>Verify that all nodes are DSE or DDAC
   *   <li>Walk through each {@link TopicConfig}:
   *       <ul>
   *         <li>Prepare insert, update, delete statements for each table mapping in each topic
   *         <li>Deduce the primary key for each table
   *         <li>Create the RecordMapper for each mapping
   *         <li>Create the codec-registry
   *       </ul>
   * </ul>
   *
   * @param session the session
   * @param config the sink config
   * @return a new InstanceState
   */
  @NonNull
  private static InstanceState buildInstanceState(CqlSession session, CassandraSinkConfig config) {

    // Compute the primary keys of all tables being mapped to (across topics).
    Map<String, List<CqlIdentifier>> primaryKeys = new HashMap<>();

    // Walk through topic-configs to create TopicState's. This involves computing the
    // codec-registry and the following for each mapped table:
    // cql for insert-update statements
    // cql for delete statements
    // prepared-statement for insert/update requests
    // prepared-statement for delete requests, if deletesEnabled is true and all columns are mapped.
    Map<String, TopicState> topicStates = new ConcurrentHashMap<>();
    List<CompletionStage<Void>> futures =
        config
            .getTopicConfigs()
            .values()
            .stream()
            .map(
                topicConfig -> {
                  ConvertingCodecFactory codecFactory = topicConfig.createCodecFactory();
                  TopicState topicState = new TopicState(codecFactory);
                  topicStates.put(topicConfig.getTopicName(), topicState);

                  return topicConfig
                      .getTableConfigs()
                      .stream()
                      .map(
                          tableConfig -> {
                            TableMetadata table = getTableMetadata(session, tableConfig);
                            // Save off the primary key of the table, if we haven't done so
                            // already.
                            String keyspaceAndTable = tableConfig.getKeyspaceAndTable();
                            if (!primaryKeys.containsKey(keyspaceAndTable)) {
                              primaryKeys.put(
                                  keyspaceAndTable,
                                  table
                                      .getPrimaryKey()
                                      .stream()
                                      .map(ColumnMetadata::getName)
                                      .collect(Collectors.toList()));
                            }

                            return prepareStatementsAsync(
                                session,
                                topicState,
                                tableConfig,
                                table,
                                primaryKeys.get(keyspaceAndTable));
                          })
                      .collect(Collectors.toList());
                })
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

    // Wait for all of the prepares to complete and topicStates to be up-to-date.
    futures.forEach(
        f -> {
          try {
            f.toCompletableFuture().join();
          } catch (CompletionException e) {
            // The exception wraps an underlying runtime exception. Throw *that*.
            throw (RuntimeException) e.getCause();
          }
        });

    return new InstanceState(config, session, topicStates, metricRegistry);
  }

  /**
   * Create a new {@link CqlSession} based on the config
   *
   * @param config the sink config
   * @param version the version of the application
   * @param applicationName the name of the application
   * @return a new CqlSession
   */
  @VisibleForTesting
  @NonNull
  public static CqlSession buildCqlSession(
      CassandraSinkConfig config, String version, String applicationName) {
    log.info("CassandraSinkTask starting with config:\n{}\n", config.toString());
    SslConfig sslConfig = config.getSslConfig();
    CqlSessionBuilder builder =
        new SessionBuilder(sslConfig)
            .withApplicationVersion(version)
            .withApplicationName(applicationName)
            .withClientId(generateClientId(config.getInstanceName()));

    ContactPointsValidator.validateContactPoints(config.getContactPoints());

    if (sslConfig != null && sslConfig.requireHostnameValidation()) {
      // if requireHostnameValidation then InetSocketAddress must be resolved
      config
          .getContactPoints()
          .stream()
          .map(hostStr -> new InetSocketAddress(hostStr, config.getPort()))
          .forEach(builder::addContactPoint);
    } else {
      config
          .getContactPoints()
          .stream()
          .map(hostStr -> InetSocketAddress.createUnresolved(hostStr, config.getPort()))
          .forEach(builder::addContactPoint);
    }

    ProgrammaticDriverConfigLoaderBuilder configLoaderBuilder =
        dseProgrammaticBuilderWithFallback(
            ConfigFactory.parseMap(config.getJavaDriverSettings(), "Connector properties"));

    processAuthenticatorConfig(config, configLoaderBuilder);
    if (sslConfig != null) {
      processSslConfig(sslConfig, configLoaderBuilder);
    }
    builder.withConfigLoader(configLoaderBuilder.build());

    return builder.build();
  }

  /**
   * Process ssl settings in the config; essentially map them to settings in the session builder.
   *
   * @param sslConfig the ssl config
   * @param configLoaderBuilder the config loader builder
   */
  private static void processSslConfig(
      SslConfig sslConfig, ProgrammaticDriverConfigLoaderBuilder configLoaderBuilder) {
    if (sslConfig.getProvider() == SslConfig.Provider.JDK) {
      configLoaderBuilder.withString(SSL_ENGINE_FACTORY_CLASS, "DefaultSslEngineFactory");
      List<String> cipherSuites = sslConfig.getCipherSuites();
      if (!cipherSuites.isEmpty()) {
        configLoaderBuilder.withStringList(SSL_CIPHER_SUITES, cipherSuites);
      }
      configLoaderBuilder
          .withBoolean(SSL_HOSTNAME_VALIDATION, sslConfig.requireHostnameValidation())
          .withString(SSL_TRUSTSTORE_PASSWORD, sslConfig.getTruststorePassword())
          .withString(SSL_KEYSTORE_PASSWORD, sslConfig.getKeystorePassword());

      Path truststorePath = sslConfig.getTruststorePath();
      if (truststorePath != null) {
        configLoaderBuilder.withString(SSL_TRUSTSTORE_PATH, truststorePath.toString());
      }
      Path keystorePath = sslConfig.getKeystorePath();
      if (keystorePath != null) {
        configLoaderBuilder.withString(SSL_KEYSTORE_PATH, keystorePath.toString());
      }
    }
  }

  /**
   * Process auth settings in the config; essentially map them to settings in the session builder.
   *
   * @param config the sink config
   * @param configLoaderBuilder the config loader builder
   */
  private static void processAuthenticatorConfig(
      CassandraSinkConfig config, ProgrammaticDriverConfigLoaderBuilder configLoaderBuilder) {
    AuthenticatorConfig authConfig = config.getAuthenticatorConfig();
    if (authConfig.getProvider() == AuthenticatorConfig.Provider.PLAIN) {
      configLoaderBuilder
          .withClass(AUTH_PROVIDER_CLASS, PlainTextAuthProvider.class)
          .withString(AUTH_PROVIDER_USER_NAME, authConfig.getUsername())
          .withString(AUTH_PROVIDER_PASSWORD, authConfig.getPassword());
    } else if (authConfig.getProvider() == AuthenticatorConfig.Provider.GSSAPI) {
      Path keyTabPath = authConfig.getKeyTabPath();
      Map<String, String> loginConfig;
      if (keyTabPath == null) {
        // Rely on the ticket cache.
        ImmutableMap.Builder<String, String> loginConfigBuilder =
            ImmutableMap.<String, String>builder()
                .put("useTicketCache", "true")
                .put("refreshKrb5Config", "true")
                .put("renewTGT", "true");
        if (!authConfig.getPrincipal().isEmpty()) {
          loginConfigBuilder.put("principal", authConfig.getPrincipal());
        }
        loginConfig = loginConfigBuilder.build();
      } else {
        // Authenticate with the keytab file
        loginConfig =
            ImmutableMap.of(
                "principal",
                authConfig.getPrincipal(),
                "useKeyTab",
                "true",
                "refreshKrb5Config",
                "true",
                "keyTab",
                authConfig.getKeyTabPath().toString());
      }
      configLoaderBuilder
          .withClass(AUTH_PROVIDER_CLASS, DseGssApiAuthProvider.class)
          .withString(AUTH_PROVIDER_SERVICE, authConfig.getService())
          .withStringMap(
              AUTH_PROVIDER_SASL_PROPERTIES, ImmutableMap.of("javax.security.sasl.qop", "auth"))
          .withStringMap(DseDriverOption.AUTH_PROVIDER_LOGIN_CONFIGURATION, loginConfig);
    }
  }

  /**
   * Prepare insert or update (depending on whether or not the table is a COUNTER table), and delete
   * statements asynchronously.
   *
   * @param session the session
   * @param topicState the topic state (e.g. topic-related state produced from the config).
   * @param tableConfig the table settings within the topic
   * @param table the table metadata
   * @param primaryKey the primary key of the table
   * @return a future
   */
  @NonNull
  private static CompletionStage<Void> prepareStatementsAsync(
      CqlSession session,
      TopicState topicState,
      TableConfig tableConfig,
      TableMetadata table,
      List<CqlIdentifier> primaryKey) {

    // for custom query DELETE not supported yet
    boolean allColumnsMapped = false;
    if (!tableConfig.isQueryProvided()) {
      allColumnsMapped = validateMappingColumns(table, tableConfig);
      validateTtlConfig(tableConfig);
    }

    String insertUpdateStatement = getInsertUpdateStatement(tableConfig, table);

    CompletionStage<? extends PreparedStatement> insertUpdateFuture =
        session.prepareAsync(insertUpdateStatement);
    CompletionStage<? extends PreparedStatement> deleteFuture;
    String deleteStatement = makeDeleteStatement(tableConfig, table);
    if (tableConfig.isDeletesEnabled() && allColumnsMapped) {
      deleteFuture = session.prepareAsync(deleteStatement);
    } else {
      // Make a dummy future that's already completed since there is no work to do here.
      CompletableFuture<PreparedStatement> dummyFuture = new CompletableFuture<>();
      dummyFuture.complete(null);
      deleteFuture = dummyFuture;
    }
    return insertUpdateFuture
        .thenAcceptBoth(
            deleteFuture,
            (preparedInsertUpdate, preparedDelete) ->
                topicState.createRecordMapper(
                    tableConfig, primaryKey, preparedInsertUpdate, preparedDelete))
        .exceptionally(
            e -> {
              String statements =
                  deleteFuture.toCompletableFuture().join() != null
                      ? String.format("%s or %s", insertUpdateStatement, deleteStatement)
                      : insertUpdateStatement;
              throw new RuntimeException(
                  String.format("Prepare failed for statement: %s", statements), e.getCause());
            });
  }

  @NonNull
  private static String getInsertUpdateStatement(TableConfig tableConfig, TableMetadata table) {
    // if user provides query explicitly it has priority over any connector specific query
    // construction logic
    return tableConfig
        .getQuery()
        .orElseGet(
            () ->
                isCounterTable(table)
                    ? makeUpdateCounterStatement(tableConfig, table)
                    : makeInsertStatement(tableConfig));
  }

  private static void validateTtlConfig(TableConfig config) {
    if (config.hasTtlMappingColumn() && config.getTtl() != -1) {
      log.warn(
          "You provided ttl configuration both for '.mapping' and '.ttl' settings for topic: {} keyspace: {} table: {}. "
              + "The ttl config from .mapping will be used.",
          config.getTopicName(),
          config.getKeyspace(),
          config.getTable());
    }
  }

  @VisibleForTesting
  public static void cleanMetrics() {
    metricRegistry = new MetricRegistry();
  }

  @NonNull
  private static ProgrammaticDriverConfigLoaderBuilder dseProgrammaticBuilderWithFallback(
      Config properties) {
    ConfigFactory.invalidateCaches();
    return new DefaultProgrammaticDriverConfigLoaderBuilder(
        () ->
            ConfigFactory.defaultApplication()
                .withFallback(properties)
                .withFallback(ConfigFactory.parseResourcesAnySyntax("dse-reference"))
                .withFallback(ConfigFactory.defaultReference()),
        DefaultDriverConfigLoader.DEFAULT_ROOT_PATH);
  }
}
