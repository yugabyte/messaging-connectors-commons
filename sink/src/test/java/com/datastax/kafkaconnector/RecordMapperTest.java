/*
 * Copyright DataStax, Inc.
 *
 *   This software is subject to the below license agreement.
 *   DataStax may make changes to the agreement from time to time,
 *   and will post the amended terms at
 *   https://www.datastax.com/terms/datastax-apache-kafka-connector-license-terms.
 */
package com.datastax.kafkaconnector;

import static com.datastax.oss.driver.api.core.DefaultProtocolVersion.V4;
import static com.google.common.collect.Lists.newArrayList;
import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.ZERO;
import static java.math.RoundingMode.HALF_EVEN;
import static java.time.Instant.EPOCH;
import static java.time.ZoneOffset.UTC;
import static java.util.Locale.US;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.dsbulk.commons.codecs.string.StringToIntegerCodec;
import com.datastax.dsbulk.commons.codecs.string.StringToLongCodec;
import com.datastax.dsbulk.commons.codecs.util.CodecUtils;
import com.datastax.dsbulk.commons.codecs.util.CqlTemporalFormat;
import com.datastax.dsbulk.commons.codecs.util.OverflowStrategy;
import com.datastax.dsbulk.commons.codecs.util.TemporalFormat;
import com.datastax.dsbulk.commons.codecs.util.ZonedTemporalFormat;
import com.datastax.kafkaconnector.record.Record;
import com.datastax.kafkaconnector.record.RecordMetadata;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.codec.CodecNotFoundException;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.codec.TypeCodecs;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import com.google.common.collect.ImmutableMap;
import io.netty.util.concurrent.FastThreadLocal;
import java.nio.ByteBuffer;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.kafka.common.config.ConfigException;
import org.assertj.core.util.Sets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class RecordMapperTest {
  private static final String F1 = "field1";
  private static final String F2 = "field2";
  private static final String F3 = "field3";

  private static final CqlIdentifier F1_IDENT = CqlIdentifier.fromInternal(F1);
  private static final CqlIdentifier F2_IDENT = CqlIdentifier.fromInternal(F2);
  private static final CqlIdentifier F3_IDENT = CqlIdentifier.fromInternal(F3);

  private static final CqlIdentifier C1 = CqlIdentifier.fromInternal("col1");
  private static final CqlIdentifier C2 = CqlIdentifier.fromInternal("col2");
  private static final CqlIdentifier C3 = CqlIdentifier.fromInternal("My Fancy Column Name");

  private final TypeCodec codec1 = mock(StringToIntegerCodec.class);
  private final TypeCodec codec2 = mock(StringToLongCodec.class);
  private final TypeCodec codec3 = TypeCodecs.TEXT;

  private final List<CqlIdentifier> primaryKeys = Arrays.asList(C1, C3);
  private final List<String> nullStrings = newArrayList("");

  private Mapping mapping;
  private Record record;
  private PreparedStatement insertUpdateStatement;
  private BoundStatementBuilder insertUpdateBoundStatementBuilder;
  private BoundStatement insertUpdateBoundStatement;

  private PreparedStatement deleteStatement;
  private BoundStatementBuilder deleteBoundStatementBuilder;
  private BoundStatement deleteBoundStatement;

  private ColumnDefinitions insertUpdateVariables;
  private ArgumentCaptor<CqlIdentifier> variableCaptor;
  private ArgumentCaptor<ByteBuffer> valueCaptor;
  private RecordMetadata recordMetadata;
  private final FastThreadLocal<NumberFormat> formatter =
      CodecUtils.getNumberFormatThreadLocal("#,###.##", US, HALF_EVEN, true);

  @BeforeEach
  void setUp() {
    variableCaptor = ArgumentCaptor.forClass(CqlIdentifier.class);
    valueCaptor = ArgumentCaptor.forClass(ByteBuffer.class);

    recordMetadata =
        new TestRecordMetadata(
            ImmutableMap.of(
                F1, GenericType.STRING, F2, GenericType.STRING, F3, GenericType.STRING));

    insertUpdateBoundStatementBuilder = mock(BoundStatementBuilder.class);
    insertUpdateBoundStatement = mock(BoundStatement.class);
    mapping = mock(Mapping.class);
    record = mock(Record.class);
    insertUpdateStatement = mock(PreparedStatement.class);
    insertUpdateVariables = mock(ColumnDefinitions.class);

    when(insertUpdateStatement.boundStatementBuilder())
        .thenReturn(insertUpdateBoundStatementBuilder);
    when(insertUpdateBoundStatementBuilder.protocolVersion()).thenReturn(V4);
    when(insertUpdateBoundStatementBuilder.build()).thenReturn(insertUpdateBoundStatement);

    when(insertUpdateBoundStatement.isSet(C1)).thenReturn(true);
    when(insertUpdateBoundStatement.isSet(C2)).thenReturn(true);
    when(insertUpdateBoundStatement.isSet(C3)).thenReturn(true);
    when(insertUpdateStatement.getVariableDefinitions()).thenReturn(insertUpdateVariables);
    ColumnDefinition c1Def = mock(ColumnDefinition.class);
    ColumnDefinition c2Def = mock(ColumnDefinition.class);
    ColumnDefinition c3Def = mock(ColumnDefinition.class);
    when(insertUpdateVariables.contains(C1)).thenReturn(true);
    when(insertUpdateVariables.contains(C2)).thenReturn(true);
    when(insertUpdateVariables.contains(C3)).thenReturn(true);
    when(insertUpdateVariables.get(C1)).thenReturn(c1Def);
    when(insertUpdateVariables.get(C2)).thenReturn(c2Def);
    when(insertUpdateVariables.get(C3)).thenReturn(c3Def);

    when(c1Def.getType()).thenReturn(DataTypes.INT);
    when(c2Def.getType()).thenReturn(DataTypes.BIGINT);
    when(c3Def.getType()).thenReturn(DataTypes.TEXT);

    when(insertUpdateVariables.get(0)).thenReturn(c1Def);
    when(insertUpdateVariables.get(1)).thenReturn(c2Def);
    when(insertUpdateVariables.get(2)).thenReturn(c3Def);

    when(c1Def.getName()).thenReturn(C1);
    when(c2Def.getName()).thenReturn(C2);
    when(c3Def.getName()).thenReturn(C3);
    when(insertUpdateVariables.size()).thenReturn(3);

    when(record.getFieldValue(F1)).thenReturn("42");
    when(record.getFieldValue(F2)).thenReturn("4242");
    when(record.getFieldValue(F3)).thenReturn("foo");
    when(record.getTimestamp()).thenReturn(null);

    when(mapping.fieldToColumns(F1_IDENT)).thenReturn(Collections.singleton(C1));
    when(mapping.fieldToColumns(F2_IDENT)).thenReturn(Collections.singleton(C2));
    when(mapping.fieldToColumns(F3_IDENT)).thenReturn(Collections.singleton(C3));

    when(mapping.columnToField(C1)).thenReturn(F1_IDENT);
    when(mapping.columnToField(C2)).thenReturn(F2_IDENT);
    when(mapping.columnToField(C3)).thenReturn(F3_IDENT);

    when(mapping.codec(C1, DataTypes.INT, GenericType.STRING)).thenReturn(codec1);
    when(mapping.codec(C2, DataTypes.BIGINT, GenericType.STRING)).thenReturn(codec2);
    when(mapping.codec(C3, DataTypes.TEXT, GenericType.STRING)).thenReturn(codec3);

    // emulate the behavior of a ConvertingCodec (StringToXCodec)
    when(codec1.encode(any(), any()))
        .thenAnswer(
            invocation -> {
              String s = invocation.getArgument(0);
              if (s == null) {
                return null;
              }
              return TypeCodecs.INT.encode(Integer.parseInt(s), invocation.getArgument(1));
            });
    when(codec2.encode(any(), any()))
        .thenAnswer(
            invocation -> {
              String s = invocation.getArgument(0);
              if (s == null) {
                return null;
              }
              return TypeCodecs.BIGINT.encode(Long.parseLong(s), invocation.getArgument(1));
            });

    deleteBoundStatementBuilder = mock(BoundStatementBuilder.class);
    deleteBoundStatement = mock(BoundStatement.class);
    deleteStatement = mock(PreparedStatement.class);
    when(deleteStatement.boundStatementBuilder()).thenReturn(deleteBoundStatementBuilder);
    when(deleteBoundStatementBuilder.protocolVersion()).thenReturn(V4);
    when(deleteBoundStatementBuilder.build()).thenReturn(deleteBoundStatement);
    when(deleteBoundStatement.isSet(C1)).thenReturn(true);
    when(deleteBoundStatement.isSet(C3)).thenReturn(true);
    ColumnDefinitions deleteVariables = mock(ColumnDefinitions.class);
    when(deleteStatement.getVariableDefinitions()).thenReturn(deleteVariables);
    when(deleteVariables.contains(C1)).thenReturn(true);
    when(deleteVariables.contains(C3)).thenReturn(true);
    when(deleteVariables.get(C1)).thenReturn(c1Def);
    when(deleteVariables.get(C3)).thenReturn(c3Def);
  }

  @Test
  void should_map_regular_fields() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, true, true, false);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder, times(3))
        .setBytesUnsafe(variableCaptor.capture(), valueCaptor.capture());
    assertParameter(0, C1, TypeCodecs.INT.encode(42, V4));
    assertParameter(1, C2, TypeCodecs.BIGINT.encode(4242L, V4));
    assertParameter(2, C3, TypeCodecs.TEXT.encode("foo", V4));
  }

  @Test
  void should_insert_when_non_null_fields_map_to_non_pk() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    RecordMapper mapper =
        new RecordMapper(
            insertUpdateStatement, deleteStatement, primaryKeys, mapping, true, true, false);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder, times(3))
        .setBytesUnsafe(variableCaptor.capture(), valueCaptor.capture());
    assertParameter(0, C1, TypeCodecs.INT.encode(42, V4));
    assertParameter(1, C2, TypeCodecs.BIGINT.encode(4242L, V4));
    assertParameter(2, C3, TypeCodecs.TEXT.encode("foo", V4));
  }

  @Test
  void should_insert_when_non_null_fields_map_to_pk_and_no_delete_statement() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    when(record.getFieldValue(F2)).thenReturn(null);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, true, true, false);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder, times(2))
        .setBytesUnsafe(variableCaptor.capture(), valueCaptor.capture());
    assertParameter(0, C1, TypeCodecs.INT.encode(42, V4));
    assertParameter(1, C3, TypeCodecs.TEXT.encode("foo", V4));
  }

  @Test
  void should_delete_when_non_null_fields_map_to_pk() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    when(record.getFieldValue(F2)).thenReturn(null);
    RecordMapper mapper =
        new RecordMapper(
            insertUpdateStatement, deleteStatement, primaryKeys, mapping, true, true, false);

    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(deleteBoundStatement);
    verify(deleteBoundStatementBuilder, times(2))
        .setBytesUnsafe(variableCaptor.capture(), valueCaptor.capture());
    assertParameter(0, C1, TypeCodecs.INT.encode(42, V4));
    assertParameter(1, C3, TypeCodecs.TEXT.encode("foo", V4));
  }

  @Test
  void should_bind_mapped_numeric_timestamp() {
    when(record.fields()).thenReturn(set(F1));
    when(insertUpdateVariables.get(C1).getType()).thenReturn(DataTypes.BIGINT);
    // timestamp is 123456 minutes before unix epoch
    when(record.getFieldValue(F1)).thenReturn("-123456");
    StringToLongCodec codec =
        spy(
            new StringToLongCodec(
                TypeCodecs.BIGINT,
                formatter,
                OverflowStrategy.REJECT,
                HALF_EVEN,
                CqlTemporalFormat.DEFAULT_INSTANCE,
                UTC,
                MINUTES,
                EPOCH.atZone(UTC),
                ImmutableMap.of("true", true, "false", false),
                newArrayList(ONE, ZERO),
                nullStrings));
    when(mapping.codec(C1, DataTypes.BIGINT, GenericType.STRING)).thenReturn(codec);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, true, true, true);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder)
        .setBytesUnsafe(C1, TypeCodecs.BIGINT.encode(-123456L, V4));
  }

  @Test
  void should_bind_mapped_numeric_timestamp_with_custom_unit_and_epoch() {
    when(record.fields()).thenReturn(set(F1));
    when(insertUpdateVariables.get(C1).getType()).thenReturn(DataTypes.BIGINT);
    // timestamp is one minute before year 2000
    when(record.getFieldValue(F1)).thenReturn("-1");
    Instant millennium = Instant.parse("2000-01-01T00:00:00Z");
    StringToLongCodec codec =
        spy(
            new StringToLongCodec(
                TypeCodecs.BIGINT,
                formatter,
                OverflowStrategy.REJECT,
                HALF_EVEN,
                CqlTemporalFormat.DEFAULT_INSTANCE,
                UTC,
                MINUTES,
                millennium.atZone(UTC),
                ImmutableMap.of("true", true, "false", false),
                newArrayList(ONE, ZERO),
                nullStrings));
    when(mapping.codec(C1, DataTypes.BIGINT, GenericType.STRING)).thenReturn(codec);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, true, true, true);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder).setBytesUnsafe(C1, TypeCodecs.BIGINT.encode(-1L, V4));
  }

  @Test
  void should_bind_mapped_alphanumeric_timestamp() {
    when(record.fields()).thenReturn(set(F1));
    when(insertUpdateVariables.get(C1).getType()).thenReturn(DataTypes.BIGINT);
    when(record.getFieldValue(F1)).thenReturn("2017-01-02T00:00:02");
    StringToLongCodec codec =
        spy(
            new StringToLongCodec(
                TypeCodecs.BIGINT,
                formatter,
                OverflowStrategy.REJECT,
                HALF_EVEN,
                CqlTemporalFormat.DEFAULT_INSTANCE,
                UTC,
                MILLISECONDS,
                EPOCH.atZone(UTC),
                ImmutableMap.of("true", true, "false", false),
                newArrayList(ONE, ZERO),
                nullStrings));
    when(mapping.codec(C1, DataTypes.BIGINT, GenericType.STRING)).thenReturn(codec);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, true, true, true);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder)
        .setBytesUnsafe(
            C1, TypeCodecs.BIGINT.encode(Instant.parse("2017-01-02T00:00:02Z").toEpochMilli(), V4));
  }

  @Test
  void should_bind_mapped_alphanumeric_timestamp_with_custom_pattern() {
    when(record.fields()).thenReturn(set(F1));
    when(insertUpdateVariables.get(C1).getType()).thenReturn(DataTypes.BIGINT);
    when(record.getFieldValue(F1)).thenReturn("20171123-123456");
    TemporalFormat timestampFormat =
        new ZonedTemporalFormat(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"), UTC);
    StringToLongCodec codec =
        spy(
            new StringToLongCodec(
                TypeCodecs.BIGINT,
                formatter,
                OverflowStrategy.REJECT,
                HALF_EVEN,
                timestampFormat,
                UTC,
                MILLISECONDS,
                EPOCH.atZone(UTC),
                ImmutableMap.of("true", true, "false", false),
                newArrayList(ONE, ZERO),
                nullStrings));
    when(mapping.codec(C1, DataTypes.BIGINT, GenericType.STRING)).thenReturn(codec);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, true, true, true);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder)
        .setBytesUnsafe(
            C1, TypeCodecs.BIGINT.encode(Instant.parse("2017-11-23T12:34:56Z").toEpochMilli(), V4));
  }

  @Test
  void should_map_null_to_unset() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    when(record.getFieldValue(F2)).thenReturn(null);
    when(insertUpdateStatement.getPartitionKeyIndices()).thenReturn(Arrays.asList(0, 2));
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, true, true, false);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder, times(2))
        .setBytesUnsafe(variableCaptor.capture(), valueCaptor.capture());
    assertParameter(0, C1, TypeCodecs.INT.encode(42, V4));
    assertParameter(1, C3, TypeCodecs.TEXT.encode("foo", V4));
  }

  @Test
  void should_map_null_to_null() {
    List<CqlIdentifier> primaryKeys = Arrays.asList(C2, C3);
    when(record.fields()).thenReturn(set(F1));
    when(record.getFieldValue(F1)).thenReturn(null);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, true, true);
    Statement result = mapper.map(recordMetadata, record);
    assertThat(result).isSameAs(insertUpdateBoundStatement);
    verify(insertUpdateBoundStatementBuilder)
        .setBytesUnsafe(variableCaptor.capture(), valueCaptor.capture());
    assertParameter(0, C1, null);
  }

  @Test
  void should_return_unmappable_statement_when_mapping_fails() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    when(mapping.codec(C3, DataTypes.TEXT, GenericType.STRING))
        .thenThrow(CodecNotFoundException.class);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, true, false);
    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(CodecNotFoundException.class);
    verify(insertUpdateBoundStatementBuilder, times(2))
        .setBytesUnsafe(variableCaptor.capture(), valueCaptor.capture());
    assertParameter(0, C1, TypeCodecs.INT.encode(42, V4));
    assertParameter(1, C2, TypeCodecs.BIGINT.encode(4242L, V4));
  }

  @Test
  void should_return_unmappable_statement_when_pk_column_null() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    when(record.getFieldValue(F1)).thenReturn(null);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, true, false);
    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("Primary key column col1 cannot be mapped to null");
  }

  @Test
  void should_return_unmappable_statement_when_pk_column_unmapped() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    when(insertUpdateBoundStatement.isSet(C3)).thenReturn(false);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, true, false);

    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(
            "Primary key column(s) \"My Fancy Column Name\" cannot be left unmapped");
  }

  @Test
  void should_return_unmappable_statement_when_extra_field() {
    when(record.fields()).thenReturn(set(F1, F2, F3));
    when(mapping.fieldToColumns(F3_IDENT)).thenReturn(null);
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, false, false);
    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(
            "Extraneous field 'field3' was found in record. "
                + "Please declare it explicitly in the mapping.");
  }

  @Test
  void should_return_unmappable_statement_when_extra_field_key() {
    when(record.fields()).thenReturn(set(F1, F2, F3, "key.__self"));
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, false, false);
    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(
            "Extraneous field 'key' was found in record. "
                + "Please declare it explicitly in the mapping.");
  }

  @Test
  void should_return_unmappable_statement_when_extra_field_value() {
    when(record.fields()).thenReturn(set(F1, F2, F3, "value.__self"));
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, false, false);
    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(
            "Extraneous field 'value' was found in record. "
                + "Please declare it explicitly in the mapping.");
  }

  @Test
  void should_return_unmappable_statement_when_missing_field() {
    when(record.fields()).thenReturn(set(F1, F2));
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, true, false);
    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(
            "Required field 'field3' (mapped to column \"My Fancy Column Name\") was missing from record. "
                + "Please remove it from the mapping.");
  }

  @Test
  void should_return_unmappable_statement_when_missing_field_key() {
    when(mapping.fieldToColumns(CqlIdentifier.fromInternal("key.__self")))
        .thenReturn(Collections.singleton(C1));
    when(mapping.columnToField(C1)).thenReturn(CqlIdentifier.fromInternal("key.__self"));
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, true, false);
    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(
            "Required field 'key' (mapped to column col1) was missing from record. "
                + "Please remove it from the mapping.");
  }

  @Test
  void should_return_unmappable_statement_when_missing_field_value() {
    when(mapping.fieldToColumns(CqlIdentifier.fromInternal("value.__self")))
        .thenReturn(Collections.singleton(C1));
    when(mapping.columnToField(C1)).thenReturn(CqlIdentifier.fromInternal("value.__self"));
    RecordMapper mapper =
        new RecordMapper(insertUpdateStatement, null, primaryKeys, mapping, false, true, false);
    assertThatThrownBy(() -> mapper.map(recordMetadata, record))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(
            "Required field 'value' (mapped to column col1) was missing from record. "
                + "Please remove it from the mapping.");
  }

  private void assertParameter(
      int index, CqlIdentifier expectedVariable, ByteBuffer expectedValue) {
    assertThat(variableCaptor.getAllValues().get(index)).isEqualTo(expectedVariable);
    assertThat(valueCaptor.getAllValues().get(index)).isEqualTo(expectedValue);
  }

  private static Set<String> set(String... fields) {
    return Sets.newLinkedHashSet(fields);
  }
}