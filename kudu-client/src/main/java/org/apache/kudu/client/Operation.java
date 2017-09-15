// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.kudu.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.UnsafeByteOperations;

import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.WireProtocol;
import org.apache.kudu.WireProtocol.RowOperationsPB;
import org.apache.kudu.annotations.InterfaceAudience;
import org.apache.kudu.annotations.InterfaceStability;
import org.apache.kudu.client.Statistics.Statistic;
import org.apache.kudu.client.Statistics.TabletStatistics;
import org.apache.kudu.tserver.Tserver;
import org.apache.kudu.util.Pair;

/**
 * Base class for the RPCs that related to WriteRequestPB. It contains almost all the logic
 * and knows how to serialize its child classes.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public abstract class Operation extends KuduRpc<OperationResponse> {
  /**
   * This size will be set when serialize is called. It stands for the size of the row in this
   * operation.
   */
  private long rowOperationSizeBytes = 0;

  enum ChangeType {
    INSERT((byte)RowOperationsPB.Type.INSERT.getNumber()),
    UPDATE((byte)RowOperationsPB.Type.UPDATE.getNumber()),
    DELETE((byte)RowOperationsPB.Type.DELETE.getNumber()),
    SPLIT_ROWS((byte)RowOperationsPB.Type.SPLIT_ROW.getNumber()),
    UPSERT((byte)RowOperationsPB.Type.UPSERT.getNumber()),
    RANGE_LOWER_BOUND((byte) RowOperationsPB.Type.RANGE_LOWER_BOUND.getNumber()),
    RANGE_UPPER_BOUND((byte) RowOperationsPB.Type.RANGE_UPPER_BOUND.getNumber()),
    EXCLUSIVE_RANGE_LOWER_BOUND(
        (byte) RowOperationsPB.Type.EXCLUSIVE_RANGE_LOWER_BOUND.getNumber()),
    INCLUSIVE_RANGE_UPPER_BOUND(
        (byte) RowOperationsPB.Type.INCLUSIVE_RANGE_UPPER_BOUND.getNumber());

    ChangeType(byte encodedByte) {
      this.encodedByte = encodedByte;
    }

    byte toEncodedByte() {
      return encodedByte;
    }

    /** The byte used to encode this in a RowOperationsPB */
    private byte encodedByte;
  }

  static final String METHOD = "Write";

  private final PartialRow row;

  /** See {@link SessionConfiguration#setIgnoreAllDuplicateRows(boolean)} */
  boolean ignoreAllDuplicateRows = false;

  /**
   * Package-private constructor. Subclasses need to be instantiated via AsyncKuduSession
   * @param table table with the schema to use for this operation
   */
  Operation(KuduTable table) {
    super(table);
    this.row = table.getSchema().newPartialRow();
  }

  /** See {@link SessionConfiguration#setIgnoreAllDuplicateRows(boolean)} */
  void setIgnoreAllDuplicateRows(boolean ignoreAllDuplicateRows) {
    this.ignoreAllDuplicateRows = ignoreAllDuplicateRows;
  }

  /**
   * Classes extending Operation need to have a specific ChangeType
   * @return Operation's ChangeType
   */
  abstract ChangeType getChangeType();

  /**
   * Returns the size in bytes of this operation's row after serialization.
   * @return size in bytes
   * @throws IllegalStateException thrown if this RPC hasn't been serialized eg sent to a TS
   */
  long getRowOperationSizeBytes() {
    if (this.rowOperationSizeBytes == 0) {
      throw new IllegalStateException("This row hasn't been serialized yet");
    }
    return this.rowOperationSizeBytes;
  }

  @Override
  String serviceName() {
    return TABLET_SERVER_SERVICE_NAME;
  }

  @Override
  String method() {
    return METHOD;
  }

  @Override
  Message createRequestPB() {
    final Tserver.WriteRequestPB.Builder builder =
        createAndFillWriteRequestPB(ImmutableList.of(this));
    this.rowOperationSizeBytes = builder.getRowOperations().getRows().size() +
        builder.getRowOperations().getIndirectData().size();
    builder.setTabletId(UnsafeByteOperations.unsafeWrap(getTablet().getTabletIdAsBytes()));
    builder.setExternalConsistencyMode(this.externalConsistencyMode.pbVersion());
    if (this.propagatedTimestamp != AsyncKuduClient.NO_TIMESTAMP) {
      builder.setPropagatedTimestamp(this.propagatedTimestamp);
    }
    return builder.build();
  }

  @Override
  Pair<OperationResponse, Object> deserialize(CallResponse callResponse,
                                              String tsUUID) throws KuduException {
    Tserver.WriteResponsePB.Builder builder = Tserver.WriteResponsePB.newBuilder();
    readProtobuf(callResponse.getPBMessage(), builder);
    Tserver.WriteResponsePB.PerRowErrorPB error = null;
    if (builder.getPerRowErrorsCount() != 0) {
      error = builder.getPerRowErrors(0);
      if (ignoreAllDuplicateRows &&
          error.getError().getCode() == WireProtocol.AppStatusPB.ErrorCode.ALREADY_PRESENT) {
        error = null;
      }
    }
    OperationResponse response = new OperationResponse(deadlineTracker.getElapsedMillis(), tsUUID,
                                                       builder.getTimestamp(), this, error);
    return new Pair<OperationResponse, Object>(
        response, builder.hasError() ? builder.getError() : null);
  }

  @Override
  public byte[] partitionKey() {
    return this.getTable().getPartitionSchema().encodePartitionKey(row);
  }

  @Override
  boolean isRequestTracked() {
    return true;
  }

  /**
   * Get the underlying row to modify.
   * @return a partial row that will be sent with this Operation
   */
  public PartialRow getRow() {
    return this.row;
  }

  @Override
  void updateStatistics(Statistics statistics, OperationResponse response) {
    String tabletId = this.getTablet().getTabletId();
    String tableName = this.getTable().getName();
    TabletStatistics tabletStatistics = statistics.getTabletStatistics(tableName, tabletId);
    if (response == null) {
      tabletStatistics.incrementStatistic(Statistic.OPS_ERRORS, 1);
      tabletStatistics.incrementStatistic(Statistic.RPC_ERRORS, 1);
      return;
    }
    tabletStatistics.incrementStatistic(Statistic.WRITE_RPCS, 1);
    if (response.hasRowError()) {
      // If ignoreAllDuplicateRows is set, the already_present exception will be
      // discarded and wont't be recorded here
      tabletStatistics.incrementStatistic(Statistic.OPS_ERRORS, 1);
    } else {
      tabletStatistics.incrementStatistic(Statistic.WRITE_OPS, 1);
    }
    tabletStatistics.incrementStatistic(Statistic.BYTES_WRITTEN, getRowOperationSizeBytes());
  }

  /**
   * Helper method that puts a list of Operations together into a WriteRequestPB.
   * @param operations The list of ops to put together in a WriteRequestPB
   * @return A fully constructed WriteRequestPB containing the passed rows, or
   *         null if no rows were passed.
   */
  static Tserver.WriteRequestPB.Builder createAndFillWriteRequestPB(List<Operation> operations) {
    if (operations == null || operations.isEmpty()) {
      return null;
    }
    Schema schema = operations.get(0).table.getSchema();
    RowOperationsPB rowOps = new OperationsEncoder().encodeOperations(operations);
    if (rowOps == null) {
      return null;
    }

    Tserver.WriteRequestPB.Builder requestBuilder = Tserver.WriteRequestPB.newBuilder();
    requestBuilder.setSchema(ProtobufHelper.schemaToPb(schema));
    requestBuilder.setRowOperations(rowOps);
    return requestBuilder;
  }

  static class OperationsEncoder {
    private Schema schema;
    private ByteBuffer rows;
    // We're filling this list as we go through the operations in encodeRow() and at the same time
    // compute the total size, which will be used to right-size the array in toPB().
    private List<ByteBuffer> indirect;
    private long indirectWrittenBytes;

    /**
     * Initializes the state of the encoder based on the schema and number of operations to encode.
     *
     * @param schema the schema of the table which the operations belong to.
     * @param numOperations the number of operations.
     */
    private void init(Schema schema, int numOperations) {
      this.schema = schema;

      // Set up the encoded data.
      // Estimate a maximum size for the data. This is conservative, but avoids
      // having to loop through all the operations twice.
      final int columnBitSetSize = Bytes.getBitSetSize(schema.getColumnCount());
      int sizePerRow = 1 /* for the op type */ + schema.getRowSize() + columnBitSetSize;
      if (schema.hasNullableColumns()) {
        // nullsBitSet is the same size as the columnBitSet
        sizePerRow += columnBitSetSize;
      }

      // TODO: would be more efficient to use a buffer which "chains" smaller allocations
      // instead of a doubling buffer like BAOS.
      this.rows = ByteBuffer.allocate(sizePerRow * numOperations)
                            .order(ByteOrder.LITTLE_ENDIAN);
      this.indirect = new ArrayList<>(schema.getVarLengthColumnCount() * numOperations);
    }

    /**
     * Builds the row operations protobuf message with encoded operations.
     * @return the row operations protobuf message.
     */
    private RowOperationsPB toPB() {
      RowOperationsPB.Builder rowOpsBuilder = RowOperationsPB.newBuilder();

      // TODO: we could avoid a copy here by using an implementation that allows
      // zero-copy on a slice of an array.
      rows.limit(rows.position());
      rows.flip();
      rowOpsBuilder.setRows(ByteString.copyFrom(rows));
      if (indirect.size() > 0) {
        // TODO: same as above, we could avoid a copy here by using an implementation that allows
        // zero-copy on a slice of an array.
        byte[] indirectData = new byte[(int)indirectWrittenBytes];
        int offset = 0;
        for (ByteBuffer bb : indirect) {
          int bbSize = bb.remaining();
          bb.get(indirectData, offset, bbSize);
          offset += bbSize;
        }
        rowOpsBuilder.setIndirectData(UnsafeByteOperations.unsafeWrap(indirectData));
      }
      return rowOpsBuilder.build();
    }

    private void encodeRow(PartialRow row, ChangeType type) {
      rows.put(type.toEncodedByte());
      rows.put(Bytes.fromBitSet(row.getColumnsBitSet(), schema.getColumnCount()));
      if (schema.hasNullableColumns()) {
        rows.put(Bytes.fromBitSet(row.getNullsBitSet(), schema.getColumnCount()));
      }
      int colIdx = 0;
      byte[] rowData = row.getRowAlloc();
      int currentRowOffset = 0;
      for (ColumnSchema col : row.getSchema().getColumns()) {
        // Keys should always be specified, maybe check?
        if (row.isSet(colIdx) && !row.isSetToNull(colIdx)) {
          if (col.getType() == Type.STRING || col.getType() == Type.BINARY) {
            ByteBuffer varLengthData = row.getVarLengthData().get(colIdx);
            varLengthData.reset();
            rows.putLong(indirectWrittenBytes);
            int bbSize = varLengthData.remaining();
            rows.putLong(bbSize);
            indirect.add(varLengthData);
            indirectWrittenBytes += bbSize;
          } else {
            // This is for cols other than strings
            rows.put(rowData, currentRowOffset, col.getType().getSize());
          }
        }
        currentRowOffset += col.getType().getSize();
        colIdx++;
      }
    }

    public RowOperationsPB encodeOperations(List<Operation> operations) {
      if (operations == null || operations.isEmpty()) {
        return null;
      }
      init(operations.get(0).table.getSchema(), operations.size());
      for (Operation operation : operations) {
        encodeRow(operation.row, operation.getChangeType());
      }
      return toPB();
    }

    public RowOperationsPB encodeRangePartitions(
        List<CreateTableOptions.RangePartition> rangePartitions,
        List<PartialRow> splitRows) {

      if (splitRows.isEmpty() && rangePartitions.isEmpty()) {
        return null;
      }

      Schema schema = splitRows.isEmpty() ? rangePartitions.get(0).getLowerBound().getSchema()
                                          : splitRows.get(0).getSchema();
      init(schema, splitRows.size() + 2 * rangePartitions.size());

      for (PartialRow row : splitRows) {
        encodeRow(row, ChangeType.SPLIT_ROWS);
      }

      for (CreateTableOptions.RangePartition partition : rangePartitions) {
        encodeRow(partition.getLowerBound(),
                  partition.getLowerBoundType() == RangePartitionBound.INCLUSIVE_BOUND ?
                      ChangeType.RANGE_LOWER_BOUND :
                      ChangeType.EXCLUSIVE_RANGE_LOWER_BOUND);
        encodeRow(partition.getUpperBound(),
                  partition.getUpperBoundType() == RangePartitionBound.EXCLUSIVE_BOUND ?
                      ChangeType.RANGE_UPPER_BOUND :
                      ChangeType.INCLUSIVE_RANGE_UPPER_BOUND);
      }

      return toPB();
    }

    public RowOperationsPB encodeLowerAndUpperBounds(PartialRow lowerBound,
                                                     PartialRow upperBound,
                                                     RangePartitionBound lowerBoundType,
                                                     RangePartitionBound upperBoundType) {
      init(lowerBound.getSchema(), 2);
      encodeRow(lowerBound,
                lowerBoundType == RangePartitionBound.INCLUSIVE_BOUND ?
                    ChangeType.RANGE_LOWER_BOUND :
                    ChangeType.EXCLUSIVE_RANGE_LOWER_BOUND);
      encodeRow(upperBound,
                upperBoundType == RangePartitionBound.EXCLUSIVE_BOUND ?
                    ChangeType.RANGE_UPPER_BOUND :
                    ChangeType.INCLUSIVE_RANGE_UPPER_BOUND);
      return toPB();
    }
  }
}
