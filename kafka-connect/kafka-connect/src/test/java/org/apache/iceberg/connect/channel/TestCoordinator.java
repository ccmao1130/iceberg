/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.connect.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DataOperations;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.connect.events.AvroUtil;
import org.apache.iceberg.connect.events.CommitComplete;
import org.apache.iceberg.connect.events.CommitToTable;
import org.apache.iceberg.connect.events.DataComplete;
import org.apache.iceberg.connect.events.DataWritten;
import org.apache.iceberg.connect.events.Event;
import org.apache.iceberg.connect.events.PayloadType;
import org.apache.iceberg.connect.events.StartCommit;
import org.apache.iceberg.connect.events.TableReference;
import org.apache.iceberg.connect.events.TopicPartitionOffset;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Types.StructType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.sink.SinkTaskContext;
import org.junit.jupiter.api.Test;

public class TestCoordinator extends ChannelTestBase {

  @Test
  public void testCommitAppend() {
    assertThat(table.snapshots()).isEmpty();

    OffsetDateTime ts = EventTestUtil.now();
    UUID commitId =
        coordinatorTest(ImmutableList.of(EventTestUtil.createDataFile()), ImmutableList.of(), ts);
    table.refresh();

    assertThat(producer.history()).hasSize(3);
    assertCommitTable(1, commitId, ts);
    assertCommitComplete(2, commitId, ts);

    List<Snapshot> snapshots = ImmutableList.copyOf(table.snapshots());
    assertThat(snapshots).hasSize(1);

    Snapshot snapshot = snapshots.get(0);
    assertThat(snapshot.operation()).isEqualTo(DataOperations.APPEND);
    assertThat(snapshot.addedDataFiles(table.io())).hasSize(1);
    assertThat(snapshot.addedDeleteFiles(table.io())).isEmpty();

    assertThat(snapshot.summary())
        .containsEntry(COMMIT_ID_SNAPSHOT_PROP, commitId.toString())
        .containsEntry(OFFSETS_SNAPSHOT_PROP, "{\"0\":3}")
        .containsEntry(VALID_THROUGH_TS_SNAPSHOT_PROP, ts.toString());
  }

  @Test
  public void testCommitDelta() {
    OffsetDateTime ts = EventTestUtil.now();
    UUID commitId =
        coordinatorTest(
            ImmutableList.of(EventTestUtil.createDataFile()),
            ImmutableList.of(EventTestUtil.createDeleteFile()),
            ts);

    assertThat(producer.history()).hasSize(3);
    assertCommitTable(1, commitId, ts);
    assertCommitComplete(2, commitId, ts);

    List<Snapshot> snapshots = ImmutableList.copyOf(table.snapshots());
    assertThat(snapshots).hasSize(1);

    Snapshot snapshot = snapshots.get(0);
    assertThat(snapshot.operation()).isEqualTo(DataOperations.OVERWRITE);
    assertThat(snapshot.addedDataFiles(table.io())).hasSize(1);
    assertThat(snapshot.addedDeleteFiles(table.io())).hasSize(1);

    assertThat(snapshot.summary())
        .containsEntry(COMMIT_ID_SNAPSHOT_PROP, commitId.toString())
        .containsEntry(OFFSETS_SNAPSHOT_PROP, "{\"0\":3}")
        .containsEntry(VALID_THROUGH_TS_SNAPSHOT_PROP, ts.toString());
  }

  @Test
  public void testCommitNoFiles() {
    OffsetDateTime ts = EventTestUtil.now();
    UUID commitId = coordinatorTest(ImmutableList.of(), ImmutableList.of(), ts);

    assertThat(producer.history()).hasSize(2);
    assertCommitComplete(1, commitId, ts);

    assertThat(table.snapshots()).isEmpty();
  }

  @Test
  public void testCommitError() {
    // this spec isn't registered with the table
    PartitionSpec badPartitionSpec =
        PartitionSpec.builderFor(SCHEMA).withSpecId(1).identity("id").build();
    DataFile badDataFile =
        DataFiles.builder(badPartitionSpec)
            .withPath(UUID.randomUUID() + ".parquet")
            .withFormat(FileFormat.PARQUET)
            .withFileSizeInBytes(100L)
            .withRecordCount(5)
            .build();

    coordinatorTest(ImmutableList.of(badDataFile), ImmutableList.of(), null);

    // no commit messages sent
    assertThat(producer.history()).hasSize(1);

    assertThat(table.snapshots()).isEmpty();
  }

  private void assertCommitTable(int idx, UUID commitId, OffsetDateTime ts) {
    byte[] bytes = producer.history().get(idx).value();
    Event commitTable = AvroUtil.decode(bytes);
    assertThat(commitTable.type()).isEqualTo(PayloadType.COMMIT_TO_TABLE);
    CommitToTable commitToTablePayload = (CommitToTable) commitTable.payload();
    assertThat(commitToTablePayload.commitId()).isEqualTo(commitId);
    assertThat(commitToTablePayload.tableReference().identifier().toString())
        .isEqualTo(TABLE_IDENTIFIER.toString());
    assertThat(commitToTablePayload.validThroughTs()).isEqualTo(ts);
  }

  private void assertCommitComplete(int idx, UUID commitId, OffsetDateTime ts) {
    byte[] bytes = producer.history().get(idx).value();
    Event commitComplete = AvroUtil.decode(bytes);
    assertThat(commitComplete.type()).isEqualTo(PayloadType.COMMIT_COMPLETE);
    CommitComplete commitCompletePayload = (CommitComplete) commitComplete.payload();
    assertThat(commitCompletePayload.commitId()).isEqualTo(commitId);
    assertThat(commitCompletePayload.validThroughTs()).isEqualTo(ts);
  }

  private UUID coordinatorTest(
      List<DataFile> dataFiles, List<DeleteFile> deleteFiles, OffsetDateTime ts) {
    when(config.commitIntervalMs()).thenReturn(0);
    when(config.commitTimeoutMs()).thenReturn(Integer.MAX_VALUE);

    SinkTaskContext context = mock(SinkTaskContext.class);
    Coordinator coordinator =
        new Coordinator(catalog, config, ImmutableList.of(), clientFactory, context);
    coordinator.start();

    // init consumer after subscribe()
    initConsumer();

    coordinator.process();

    assertThat(producer.transactionCommitted()).isTrue();
    assertThat(producer.history()).hasSize(1);

    byte[] bytes = producer.history().get(0).value();
    Event commitRequest = AvroUtil.decode(bytes);
    assertThat(commitRequest.type()).isEqualTo(PayloadType.START_COMMIT);

    UUID commitId = ((StartCommit) commitRequest.payload()).commitId();

    Event commitResponse =
        new Event(
            config.connectGroupId(),
            new DataWritten(
                StructType.of(),
                commitId,
                new TableReference("catalog", ImmutableList.of("db"), "tbl"),
                dataFiles,
                deleteFiles));
    bytes = AvroUtil.encode(commitResponse);
    consumer.addRecord(new ConsumerRecord<>(CTL_TOPIC_NAME, 0, 1, "key", bytes));

    Event commitReady =
        new Event(
            config.connectGroupId(),
            new DataComplete(
                commitId, ImmutableList.of(new TopicPartitionOffset("topic", 1, 1L, ts))));
    bytes = AvroUtil.encode(commitReady);
    consumer.addRecord(new ConsumerRecord<>(CTL_TOPIC_NAME, 0, 2, "key", bytes));

    when(config.commitIntervalMs()).thenReturn(0);

    coordinator.process();

    return commitId;
  }

  @Test
  public void testCoordinatorRunning() {
    TopicPartition tp0 = new TopicPartition(SRC_TOPIC_NAME, 0);
    TopicPartition tp1 = new TopicPartition(SRC_TOPIC_NAME, 1);
    TopicPartition tp2 = new TopicPartition(SRC_TOPIC_NAME, 2);

    // Assigning three topic partitions tp0, tp1, and tp2. This will be elected as leader as it has
    // tp0.
    sourceConsumer.rebalance(Lists.newArrayList(tp0, tp1, tp2));
    assertThat(mockIcebergSinkTask.isCoordinatorRunning()).isTrue();

    // Now revoking the partition 2, this should not close the coordinator as this task still has
    // the zeroth partition
    sourceConsumer.rebalance(Lists.newArrayList(tp0, tp1));
    assertThat(mockIcebergSinkTask.isCoordinatorRunning()).isTrue();

    // Now finally revoking partition zero and this should result in the closure of the coordinator
    sourceConsumer.rebalance(ImmutableList.of(tp1));
    assertThat(mockIcebergSinkTask.isCoordinatorRunning()).isFalse();
  }
}
