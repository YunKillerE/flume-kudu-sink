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
import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.BitSet;
import java.util.Date;
import java.util.TimeZone;

import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.annotations.InterfaceAudience;
import org.apache.kudu.annotations.InterfaceStability;
import org.apache.kudu.util.Slice;

/**
 * RowResult represents one row from a scanner. Do not reuse or store the objects.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class RowResult {

  private static final int INDEX_RESET_LOCATION = -1;

  // Thread local DateFormat since they're not thread-safe.
  private static final ThreadLocal<DateFormat> DATE_FORMAT = new ThreadLocal<DateFormat>() {
    @Override
    protected DateFormat initialValue() {
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
      sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
      return sdf;
    }
  };

  private static final long MS_IN_S = 1000L;
  private static final long US_IN_S = 1000L * 1000L;
  private int index = INDEX_RESET_LOCATION;
  private int offset;
  private BitSet nullsBitSet;
  private final int rowSize;
  private final int[] columnOffsets;
  private final Schema schema;
  private final Slice rowData;
  private final Slice indirectData;

  /**
   * Prepares the row representation using the provided data. Doesn't copy data
   * out of the byte arrays. Package private.
   * @param schema Schema used to build the rowData
   * @param rowData The Slice of data returned by the tablet server
   * @param indirectData The full indirect data that contains the strings
   */
  RowResult(Schema schema, Slice rowData, Slice indirectData) {
    this.schema = schema;
    this.rowData = rowData;
    this.indirectData = indirectData;
    int columnOffsetsSize = schema.getColumnCount();
    if (schema.hasNullableColumns()) {
      columnOffsetsSize++;
    }
    this.rowSize = this.schema.getRowSize();
    columnOffsets = new int[columnOffsetsSize];
    // Empty projection, usually used for quick row counting.
    if (columnOffsetsSize == 0) {
      return;
    }
    int currentOffset = 0;
    columnOffsets[0] = currentOffset;
    // Pre-compute the columns offsets in rowData for easier lookups later.
    // If the schema has nullables, we also add the offset for the null bitmap at the end.
    for (int i = 1; i < columnOffsetsSize; i++) {
      int previousSize = schema.getColumnByIndex(i - 1).getType().getSize();
      columnOffsets[i] = previousSize + currentOffset;
      currentOffset += previousSize;
    }
  }

  /**
   * Package-protected, only meant to be used by the RowResultIterator
   */
  void advancePointer() {
    advancePointerTo(this.index + 1);
  }

  void resetPointer() {
    advancePointerTo(INDEX_RESET_LOCATION);
  }

  void advancePointerTo(int rowIndex) {
    this.index = rowIndex;
    this.offset = this.rowSize * this.index;
    if (schema.hasNullableColumns() && this.index != INDEX_RESET_LOCATION) {
      this.nullsBitSet = Bytes.toBitSet(
          this.rowData.getRawArray(),
          this.rowData.getRawOffset() +
              getCurrentRowDataOffsetForColumn(schema.getColumnCount()),
          schema.getColumnCount());
    }
  }

  int getCurrentRowDataOffsetForColumn(int columnIndex) {
    return this.offset + this.columnOffsets[columnIndex];
  }

  /**
   * Get the specified column's integer
   * @param columnName name of the column to get data for
   * @return an integer
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   */
  public int getInt(String columnName) {
    return getInt(this.schema.getColumnIndex(columnName));
  }

  /**
   * Get the specified column's integer
   * @param columnIndex Column index in the schema
   * @return an integer
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public int getInt(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    checkType(columnIndex, Type.INT32);
    return Bytes.getInt(this.rowData.getRawArray(),
        this.rowData.getRawOffset() + getCurrentRowDataOffsetForColumn(columnIndex));
  }

  /**
   * Get the specified column's short
   * @param columnName name of the column to get data for
   * @return a short
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   */
  public short getShort(String columnName) {
    return getShort(this.schema.getColumnIndex(columnName));
  }

  /**
   * Get the specified column's short
   * @param columnIndex Column index in the schema
   * @return a short
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public short getShort(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    checkType(columnIndex, Type.INT16);
    return Bytes.getShort(this.rowData.getRawArray(),
        this.rowData.getRawOffset() + getCurrentRowDataOffsetForColumn(columnIndex));
  }

  /**
   * Get the specified column's boolean
   * @param columnName name of the column to get data for
   * @return a boolean
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   */
  public boolean getBoolean(String columnName) {
    return getBoolean(this.schema.getColumnIndex(columnName));
  }

  /**
   * Get the specified column's boolean
   * @param columnIndex Column index in the schema
   * @return a boolean
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public boolean getBoolean(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    checkType(columnIndex, Type.BOOL);
    byte b = Bytes.getByte(this.rowData.getRawArray(),
                         this.rowData.getRawOffset() +
                             getCurrentRowDataOffsetForColumn(columnIndex));
    return b == 1;
  }

  /**
   * Get the specified column's byte
   * @param columnName name of the column to get data for
   * @return a byte
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   */
  public byte getByte(String columnName) {
    return getByte(this.schema.getColumnIndex(columnName));

  }

  /**
   * Get the specified column's byte
   * @param columnIndex Column index in the schema
   * @return a byte
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public byte getByte(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    checkType(columnIndex, Type.INT8);
    return Bytes.getByte(this.rowData.getRawArray(),
        this.rowData.getRawOffset() + getCurrentRowDataOffsetForColumn(columnIndex));
  }

  /**
   * Get the specified column's long
   *
   * If this is a UNIXTIME_MICROS column, the long value corresponds to a number of microseconds
   * since midnight, January 1, 1970 UTC.
   *
   * @param columnName name of the column to get data for
   * @return a positive long
   * @throws IllegalArgumentException if the column doesn't exist or is null
   */
  public long getLong(String columnName) {
    return getLong(this.schema.getColumnIndex(columnName));
  }

  /**
   * Get the specified column's long
   *
   * If this is a UNIXTIME_MICROS column, the long value corresponds to a number of microseconds
   * since midnight, January 1, 1970 UTC.
   *
   * @param columnIndex Column index in the schema
   * @return a positive long
   * @throws IllegalArgumentException if the column is null
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public long getLong(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    // Can't check type because this could be a long, string, or Timestamp.
    return Bytes.getLong(this.rowData.getRawArray(),
                         this.rowData.getRawOffset() +
                             getCurrentRowDataOffsetForColumn(columnIndex));
  }

  /**
   * Get the specified column's float
   * @param columnName name of the column to get data for
   * @return a float
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   */
  public float getFloat(String columnName) {
    return getFloat(this.schema.getColumnIndex(columnName));
  }

  /**
   * Get the specified column's float
   * @param columnIndex Column index in the schema
   * @return a float
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public float getFloat(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    checkType(columnIndex, Type.FLOAT);
    return Bytes.getFloat(this.rowData.getRawArray(),
                          this.rowData.getRawOffset() +
                              getCurrentRowDataOffsetForColumn(columnIndex));
  }

  /**
   * Get the specified column's double
   * @param columnName name of the column to get data for
   * @return a double
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   */
  public double getDouble(String columnName) {
    return getDouble(this.schema.getColumnIndex(columnName));

  }

  /**
   * Get the specified column's double
   * @param columnIndex Column index in the schema
   * @return a double
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public double getDouble(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    checkType(columnIndex, Type.DOUBLE);
    return Bytes.getDouble(this.rowData.getRawArray(),
                           this.rowData.getRawOffset() +
                               getCurrentRowDataOffsetForColumn(columnIndex));
  }

  /**
   * Get the schema used for this scanner's column projection.
   * @return a column projection as a schema.
   */
  public Schema getColumnProjection() {
    return this.schema;
  }

  /**
   * Get the specified column's string.
   * @param columnName name of the column to get data for
   * @return a string
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   */
  public String getString(String columnName) {
    return getString(this.schema.getColumnIndex(columnName));

  }

  /**
   * Get the specified column's string.
   * @param columnIndex Column index in the schema
   * @return a string
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public String getString(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    checkType(columnIndex, Type.STRING);
    // C++ puts a Slice in rowData which is 16 bytes long for simplity, but we only support ints.
    long offset = getLong(columnIndex);
    long length = rowData.getLong(getCurrentRowDataOffsetForColumn(columnIndex) + 8);
    assert offset < Integer.MAX_VALUE;
    assert length < Integer.MAX_VALUE;
    return Bytes.getString(indirectData.getRawArray(),
                           indirectData.getRawOffset() + (int)offset,
                           (int)length);
  }

  /**
   * Get a copy of the specified column's binary data.
   * @param columnName name of the column to get data for
   * @return a byte[] with the binary data.
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public byte[] getBinaryCopy(String columnName) {
    return getBinaryCopy(this.schema.getColumnIndex(columnName));

  }

  /**
   * Get a copy of the specified column's binary data.
   * @param columnIndex Column index in the schema
   * @return a byte[] with the binary data.
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public byte[] getBinaryCopy(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    // C++ puts a Slice in rowData which is 16 bytes long for simplicity,
    // but we only support ints.
    long offset = getLong(columnIndex);
    long length = rowData.getLong(getCurrentRowDataOffsetForColumn(columnIndex) + 8);
    assert offset < Integer.MAX_VALUE;
    assert length < Integer.MAX_VALUE;
    byte[] ret = new byte[(int)length];
    System.arraycopy(indirectData.getRawArray(), indirectData.getRawOffset() + (int) offset,
                     ret, 0, (int) length);
    return ret;
  }

  /**
   * Get the specified column's binary data.
   *
   * This doesn't copy the data and instead returns a ByteBuffer that wraps it.
   *
   * @param columnName name of the column to get data for
   * @return a ByteBuffer with the binary data.
   * @throws IllegalArgumentException if the column doesn't exist, is null,
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public ByteBuffer getBinary(String columnName) {
    return getBinary(this.schema.getColumnIndex(columnName));
  }

  /**
   * Get the specified column's binary data.
   *
   * This doesn't copy the data and instead returns a ByteBuffer that wraps it.
   *
   * @param columnIndex Column index in the schema
   * @return a ByteBuffer with the binary data.
   * @throws IllegalArgumentException if the column is null
   * or if the type doesn't match the column's type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public ByteBuffer getBinary(int columnIndex) {
    checkValidColumn(columnIndex);
    checkNull(columnIndex);
    checkType(columnIndex, Type.BINARY);
    // C++ puts a Slice in rowData which is 16 bytes long for simplicity,
    // but we only support ints.
    long offset = getLong(columnIndex);
    long length = rowData.getLong(getCurrentRowDataOffsetForColumn(columnIndex) + 8);
    assert offset < Integer.MAX_VALUE;
    assert length < Integer.MAX_VALUE;
    return ByteBuffer.wrap(indirectData.getRawArray(), indirectData.getRawOffset() + (int) offset,
        (int) length);
  }

  /**
   * Get if the specified column is NULL
   * @param columnName name of the column in the schema
   * @return true if the column cell is null and the column is nullable,
   * false otherwise
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public boolean isNull(String columnName) {
    return isNull(this.schema.getColumnIndex(columnName));
  }

  /**
   * Get if the specified column is NULL
   * @param columnIndex Column index in the schema
   * @return true if the column cell is null and the column is nullable,
   * false otherwise
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public boolean isNull(int columnIndex) {
    checkValidColumn(columnIndex);
    if (nullsBitSet == null) {
      return false;
    }
    return schema.getColumnByIndex(columnIndex).isNullable() &&
        nullsBitSet.get(columnIndex);
  }

  /**
   * Get the type of a column in this result.
   * @param columnName name of the column
   * @return a type
   */
  public Type getColumnType(String columnName) {
    return this.schema.getColumn(columnName).getType();
  }

  /**
   * Get the type of a column in this result.
   * @param columnIndex column index in the schema
   * @return a type
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  public Type getColumnType(int columnIndex) {
    return this.schema.getColumnByIndex(columnIndex).getType();
  }

  /**
   * Get the schema associated with this result.
   * @return a schema
   */
  public Schema getSchema() {
    return schema;
  }

  /**
   * @throws IndexOutOfBoundsException if the column doesn't exist
   */
  private void checkValidColumn(int columnIndex) {
    if (columnIndex >= schema.getColumnCount()) {
      throw new IndexOutOfBoundsException("Requested column is out of range, " +
          columnIndex + " out of " + schema.getColumnCount());
    }
  }

  /**
   * @throws IllegalArgumentException if the column is null
   */
  private void checkNull(int columnIndex) {
    if (!schema.hasNullableColumns()) {
      return;
    }
    if (isNull(columnIndex)) {
      ColumnSchema columnSchema = schema.getColumnByIndex(columnIndex);
      throw new IllegalArgumentException("The requested column (name: " + columnSchema.getName() +
          ", index: " + columnIndex + ") is null");
    }
  }

  private void checkType(int columnIndex, Type expectedType) {
    ColumnSchema columnSchema = schema.getColumnByIndex(columnIndex);
    Type columnType = columnSchema.getType();
    if (!columnType.equals(expectedType)) {
      throw new IllegalArgumentException("Column (name: " + columnSchema.getName() +
          ", index: " + columnIndex + ") is of type " +
          columnType.getName() + " but was requested as a type " + expectedType.getName());
    }
  }

  @Override
  public String toString() {
    return "RowResult index: " + this.index + ", size: " + this.rowSize;
  }

  /**
   * Transforms a timestamp into a string, whose formatting and timezone is consistent
   * across Kudu.
   * @param timestamp the timestamp, in microseconds
   * @return a string, in the format: YYYY-MM-DDTHH:MM:SS.ssssssZ
   */
  static String timestampToString(long timestamp) {
    long tsMillis = timestamp / MS_IN_S;
    long tsMicros = timestamp % US_IN_S;
    StringBuffer formattedTs = new StringBuffer();
    DATE_FORMAT.get().format(new Date(tsMillis), formattedTs, new FieldPosition(0));
    formattedTs.append(String.format(".%06dZ", tsMicros));
    return formattedTs.toString();
  }

  /**
   * Return the actual data from this row in a stringified key=value
   * form.
   */
  public String rowToString() {
    StringBuilder buf = new StringBuilder();
    for (int i = 0; i < schema.getColumnCount(); i++) {
      ColumnSchema col = schema.getColumnByIndex(i);
      if (i != 0) {
        buf.append(", ");
      }
      buf.append(col.getType().name());
      buf.append(" ").append(col.getName()).append("=");
      if (isNull(i)) {
        buf.append("NULL");
      } else {
        switch (col.getType()) {
          case INT8:
            buf.append(getByte(i));
            break;
          case INT16:
            buf.append(getShort(i));
            break;
          case INT32:
            buf.append(getInt(i));
            break;
          case INT64:
            buf.append(getLong(i));
            break;
          case UNIXTIME_MICROS: {
            buf.append(timestampToString(getLong(i)));
          } break;
          case STRING:
            buf.append(getString(i));
            break;
          case BINARY:
            buf.append(Bytes.pretty(getBinaryCopy(i)));
            break;
          case FLOAT:
            buf.append(getFloat(i));
            break;
          case DOUBLE:
            buf.append(getDouble(i));
            break;
          case BOOL:
            buf.append(getBoolean(i));
            break;
          default:
            buf.append("<unknown type!>");
            break;
        }
      }
    }
    return buf.toString();
  }

  /**
   * @return a string describing the location of this row result within
   * the iterator as well as its data.
   */
  public String toStringLongFormat() {
    StringBuilder buf = new StringBuilder(this.rowSize); // super rough estimation.
    buf.append(this.toString());
    buf.append("{");
    buf.append(rowToString());
    buf.append("}");
    return buf.toString();
  }
}
