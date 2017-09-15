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

import static org.apache.kudu.client.ExternalConsistencyMode.CLIENT_PROPAGATED;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.kudu.annotations.InterfaceAudience;
import org.apache.kudu.annotations.InterfaceStability;
import org.apache.kudu.util.AsyncUtil;
import org.apache.kudu.util.Slice;

/**
 * An {@code AsyncKuduSession} belongs to a specific {@link AsyncKuduClient}, and represents a
 * context in which all write data access should take place. Within a session,
 * multiple operations may be accumulated and batched together for better
 * efficiency. Settings like timeouts, priorities, and trace IDs are also set
 * per session.
 *
 * <p>{@code AsyncKuduSession} is separate from {@link AsyncKuduClient} because, in a multi-threaded
 * application, different threads may need to concurrently execute
 * transactions. Similar to a JDBC "session", transaction boundaries will be
 * delineated on a per-session basis -- in between a "BeginTransaction" and
 * "Commit" call on a given session, all operations will be part of the same
 * transaction. Meanwhile another concurrent session object can safely run
 * non-transactional work or other transactions without interfering.
 *
 * <p>Therefore, this class is <b>not</b> thread-safe.
 *
 * <p>Additionally, there is a guarantee that writes from different sessions do not
 * get batched together into the same RPCs -- this means that latency-sensitive
 * clients can run through the same {@link AsyncKuduClient} object as throughput-oriented
 * clients, perhaps by setting the latency-sensitive session's timeouts low and
 * priorities high. Without the separation of batches, a latency-sensitive
 * single-row insert might get batched along with 10MB worth of inserts from the
 * batch writer, thus delaying the response significantly.
 *
 * <p>Timeouts are handled differently depending on the flush mode.
 * With {@link SessionConfiguration.FlushMode#AUTO_FLUSH_SYNC AUTO_FLUSH_SYNC}, the timeout is set
 * on each {@linkplain #apply apply}()'d operation.
 * With {@link SessionConfiguration.FlushMode#AUTO_FLUSH_BACKGROUND AUTO_FLUSH_BACKGROUND} and
 * {@link SessionConfiguration.FlushMode#MANUAL_FLUSH MANUAL_FLUSH}, the timeout is assigned to a
 * whole batch of operations upon {@linkplain #flush flush}()'ing. It means that in a situation
 * with a timeout of 500ms and a flush interval of 1000ms, an operation can be outstanding for up to
 * 1500ms before being timed out.
 *
 * <p><strong>Warning: a note on out-of-order operations</strong>
 *
 * <p>When using {@code AsyncKuduSession}, it is not difficult to trigger concurrent flushes on
 * the same session. The result is that operations applied in a particular order within a single
 * session may be applied in a different order on the server side, even for a single tablet. To
 * prevent this behavior, ensure that only one flush is outstanding at a given time (the maximum
 * concurrent flushes per {@code AsyncKuduSession} is hard-coded to 2).
 *
 * <p>If operation interleaving would be unacceptable for your application, consider using one of
 * the following strategies to avoid it:
 *
 * <ol>
 * <li>When using {@link SessionConfiguration.FlushMode#MANUAL_FLUSH MANUAL_FLUSH} mode,
 * wait for one {@link #flush flush()} to {@code join()} before triggering another flush.
 * <li>When using {@link SessionConfiguration.FlushMode#AUTO_FLUSH_SYNC AUTO_FLUSH_SYNC}
 * mode, wait for each {@link #apply apply()} to {@code join()} before applying another operation.
 * <li>Consider not using
 * {@link SessionConfiguration.FlushMode#AUTO_FLUSH_BACKGROUND AUTO_FLUSH_BACKGROUND} mode.
 * <li>Make your application resilient to out-of-order application of writes.
 * <li>Avoid applying an {@link Operation} on a particular row until any previous write to that
 * row has been successfully flushed.
 * </ol>
 *
 * <p>For more information on per-session operation interleaving, see
 * <a href="https://issues.apache.org/jira/browse/KUDU-1767">KUDU-1767</a>.
 */
@InterfaceAudience.Public
@InterfaceStability.Unstable
@NotThreadSafe
public class AsyncKuduSession implements SessionConfiguration {

  public static final Logger LOG = LoggerFactory.getLogger(AsyncKuduSession.class);
  private static final Range<Float> PERCENTAGE_RANGE = Range.closed(0.0f, 1.0f);

  private final AsyncKuduClient client;
  private final Random randomizer = new Random();
  private final ErrorCollector errorCollector;
  private int interval = 1000;
  private int mutationBufferSpace = 1000; // TODO express this in terms of data size.
  private float mutationBufferLowWatermarkPercentage = 0.5f;
  private int mutationBufferLowWatermark;
  private FlushMode flushMode;
  private ExternalConsistencyMode consistencyMode;
  private long timeoutMs;

  /**
   * Protects internal state from concurrent access. {@code AsyncKuduSession} is not threadsafe
   * from the application's perspective, but because internally async timers and async flushing
   * tasks may access the session concurrently with the application, synchronization is still
   * needed.
   */
  private final Object monitor = new Object();

  /**
   * Tracks the currently active buffer.
   *
   * When in mode {@link FlushMode#AUTO_FLUSH_BACKGROUND} or {@link FlushMode#AUTO_FLUSH_SYNC},
   * {@code AsyncKuduSession} uses double buffering to improve write throughput. While the
   * application is {@link #apply}ing operations to one buffer (the {@code activeBuffer}), the
   * second buffer is either being flushed, or if it has already been flushed, it waits in the
   * {@link #inactiveBuffers} queue. When the currently active buffer is flushed,
   * {@code activeBuffer} is set to {@code null}. On the next call to {@code apply}, an inactive
   * buffer is taken from {@code inactiveBuffers} and made the new active buffer. If both
   * buffers are still flushing, then the {@code apply} call throws {@link PleaseThrottleException}.
   */
  @GuardedBy("monitor")
  private Buffer activeBuffer;

  /**
   * The buffers. May either be active (pointed to by {@link #activeBuffer},
   * inactive (in the {@link #inactiveBuffers}) queue, or flushing.
   */
  private final Buffer bufferA = new Buffer();
  private final Buffer bufferB = new Buffer();

  /**
   * Queue containing flushed, inactive buffers. May be accessed from callbacks (I/O threads).
   * We restrict the session to only two buffers, so {@link BlockingQueue#add} can
   * be used without chance of failure.
   */
  private final BlockingQueue<Buffer> inactiveBuffers = new ArrayBlockingQueue<>(2, false);

  /**
   * Deferred used to notify on flush events. Atomically swapped and completed every time a buffer
   * is flushed. This can be used to notify handlers of {@link PleaseThrottleException} that more
   * capacity may be available in the active buffer.
   */
  private final AtomicReference<Deferred<Void>> flushNotification =
      new AtomicReference<>(new Deferred<Void>());

  /**
   * Tracks whether the session has been closed.
   */
  private volatile boolean closed = false;

  private boolean ignoreAllDuplicateRows = false;

  /**
   * Package-private constructor meant to be used via AsyncKuduClient
   * @param client client that creates this session
   */
  AsyncKuduSession(AsyncKuduClient client) {
    this.client = client;
    flushMode = FlushMode.AUTO_FLUSH_SYNC;
    consistencyMode = CLIENT_PROPAGATED;
    timeoutMs = client.getDefaultOperationTimeoutMs();
    inactiveBuffers.add(bufferA);
    inactiveBuffers.add(bufferB);
    errorCollector = new ErrorCollector(mutationBufferSpace);
    setMutationBufferLowWatermark(this.mutationBufferLowWatermarkPercentage);
  }

  @Override
  public FlushMode getFlushMode() {
    return this.flushMode;
  }

  @Override
  public void setFlushMode(FlushMode flushMode) {
    if (hasPendingOperations()) {
      throw new IllegalArgumentException("Cannot change flush mode when writes are buffered");
    }
    this.flushMode = flushMode;
  }

  @Override
  public void setExternalConsistencyMode(ExternalConsistencyMode consistencyMode) {
    if (hasPendingOperations()) {
      throw new IllegalArgumentException("Cannot change consistency mode " +
          "when writes are buffered");
    }
    this.consistencyMode = consistencyMode;
  }

  @Override
  public void setMutationBufferSpace(int size) {
    if (hasPendingOperations()) {
      throw new IllegalArgumentException("Cannot change the buffer" +
          " size when operations are buffered");
    }
    this.mutationBufferSpace = size;
    // Reset the low watermark, using the same percentage as before.
    setMutationBufferLowWatermark(mutationBufferLowWatermarkPercentage);
  }

  @Override
  public void setMutationBufferLowWatermark(float mutationBufferLowWatermarkPercentage) {
    if (hasPendingOperations()) {
      throw new IllegalArgumentException("Cannot change the buffer" +
          " low watermark when operations are buffered");
    } else if (!PERCENTAGE_RANGE.contains(mutationBufferLowWatermarkPercentage)) {
      throw new IllegalArgumentException("The low watermark must be between 0 and 1 inclusively");
    }
    this.mutationBufferLowWatermarkPercentage = mutationBufferLowWatermarkPercentage;
    this.mutationBufferLowWatermark =
        (int)(this.mutationBufferLowWatermarkPercentage * mutationBufferSpace);
  }

  /**
   * Lets us set a specific seed for tests
   * @param seed
   */
  @VisibleForTesting
  void setRandomSeed(long seed) {
    this.randomizer.setSeed(seed);
  }

  @Override
  public void setFlushInterval(int interval) {
    this.interval = interval;
  }

  @Override
  public void setTimeoutMillis(long timeout) {
    this.timeoutMs = timeout;
  }

  @Override
  public long getTimeoutMillis() {
    return this.timeoutMs;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public boolean isIgnoreAllDuplicateRows() {
    return ignoreAllDuplicateRows;
  }

  @Override
  public void setIgnoreAllDuplicateRows(boolean ignoreAllDuplicateRows) {
    this.ignoreAllDuplicateRows = ignoreAllDuplicateRows;
  }

  @Override
  public int countPendingErrors() {
    return errorCollector.countErrors();
  }

  @Override
  public RowErrorsAndOverflowStatus getPendingErrors() {
    return errorCollector.getErrors();
  }

  /**
   * Flushes the buffered operations and marks this session as closed.
   * See the javadoc on {@link #flush()} on how to deal with exceptions coming out of this method.
   * @return a Deferred whose callback chain will be invoked when.
   * everything that was buffered at the time of the call has been flushed.
   */
  public Deferred<List<OperationResponse>> close() {
    if (!closed) {
      closed = true;
      client.removeSession(this);
    }
    return flush();
  }

  /**
   * Returns a buffer to the inactive queue after flushing.
   * @param buffer the buffer to return to the inactive queue.
   */
  private void queueBuffer(Buffer buffer) {
    buffer.callbackFlushNotification();
    Deferred<Void> localFlushNotification = flushNotification.getAndSet(new Deferred<Void>());
    inactiveBuffers.add(buffer);
    localFlushNotification.callback(null);
  }

  /**
   * Callback which waits for all tablet location lookups to complete, groups all operations into
   * batches by tablet, and dispatches them. When all of the batches are complete, a deferred is
   * fired and the buffer is added to the inactive queue.
   */
  private final class TabletLookupCB implements Callback<Void, Object> {
    private final AtomicInteger lookupsOutstanding;
    private final Buffer buffer;
    private final Deferred<List<BatchResponse>> deferred;

    public TabletLookupCB(Buffer buffer, Deferred<List<BatchResponse>> deferred) {
      this.lookupsOutstanding = new AtomicInteger(buffer.getOperations().size());
      this.buffer = buffer;
      this.deferred = deferred;
    }

    @Override
    public Void call(Object unused) throws Exception {
      if (lookupsOutstanding.decrementAndGet() != 0) {
        return null;
      }

      // The final tablet lookup is complete. Batch all of the buffered
      // operations into their respective tablet, and then send the batches.

      // Group the operations by tablet.
      Map<Slice, Batch> batches = new HashMap<>();
      List<OperationResponse> opsFailedInLookup = new ArrayList<>();

      for (BufferedOperation bufferedOp : buffer.getOperations()) {
        Operation operation = bufferedOp.getOperation();
        if (bufferedOp.tabletLookupFailed()) {
          Exception failure = bufferedOp.getTabletLookupFailure();
          RowError error;
          if (failure instanceof NonCoveredRangeException) {
            // TODO: this should be something different than NotFound so that
            // applications can distinguish from updates on missing rows.
            error = new RowError(Status.NotFound(failure.getMessage()), operation);
          } else {
            LOG.warn("unexpected tablet lookup failure for operation {}", operation, failure);
            error = new RowError(Status.RuntimeError(failure.getMessage()), operation);
          }
          OperationResponse response = new OperationResponse(0, null, 0, operation, error);
          // Add the row error to the error collector if the session is in background flush mode,
          // and complete the operation's deferred with the error response. The ordering between
          // adding to the error collector and completing the deferred should not matter since
          // applications should be using one or the other method for error handling, not both.
          if (flushMode == FlushMode.AUTO_FLUSH_BACKGROUND) {
            errorCollector.addError(error);
          }
          operation.callback(response);
          opsFailedInLookup.add(response);
          continue;
        }
        LocatedTablet tablet = bufferedOp.getTablet();
        Slice tabletId = new Slice(tablet.getTabletId());

        Batch batch = batches.get(tabletId);
        if (batch == null) {
          batch = new Batch(operation.getTable(), tablet, ignoreAllDuplicateRows);
          batches.put(tabletId, batch);
        }
        batch.add(operation);
      }

      List<Deferred<BatchResponse>> batchResponses = new ArrayList<>(batches.size() + 1);
      if (!opsFailedInLookup.isEmpty()) {
        batchResponses.add(Deferred.fromResult(new BatchResponse(opsFailedInLookup)));
      }

      for (Batch batch : batches.values()) {
        if (timeoutMs != 0) {
          batch.deadlineTracker.reset();
          batch.setTimeoutMillis(timeoutMs);
        }
        addBatchCallbacks(batch);
        batchResponses.add(client.sendRpcToTablet(batch));
      }

      // On completion of all batches, fire the completion deferred, and add the buffer
      // back to the inactive buffers queue. This frees it up for new inserts.
      AsyncUtil.addBoth(
          Deferred.group(batchResponses),
          new Callback<Void, Object>() {
            @Override
            public Void call(Object responses) {
              queueBuffer(buffer);
              deferred.callback(responses);
              return null;
            }
          });

      return null;
    }
  }

  /**
   * Flush buffered writes.
   * @return a {@link Deferred} whose callback chain will be invoked when all applied operations at
   *         the time of the call have been flushed.
   */
  public Deferred<List<OperationResponse>> flush() {
    Buffer buffer;
    Deferred<Void> nonActiveBufferFlush;
    synchronized (monitor) {
      nonActiveBufferFlush = getNonActiveFlushNotification();
      buffer = activeBuffer;
      activeBuffer = null;
    }

    final Deferred<List<OperationResponse>> activeBufferFlush = buffer == null ?
        Deferred.<List<OperationResponse>>fromResult(ImmutableList.<OperationResponse>of()) :
        doFlush(buffer);

    return AsyncUtil.addBothDeferring(nonActiveBufferFlush,
        new Callback<Deferred<List<OperationResponse>>, Object>() {
          @Override
          public Deferred<List<OperationResponse>> call(Object arg) {
            return activeBufferFlush;
          }
        });
  }

  /**
   * Flushes a write buffer. This method takes ownership of the buffer, no other concurrent access
   * is allowed.
   *
   * @param buffer the buffer to flush, must not be modified once passed to this method
   * @return the operation responses
   */
  private Deferred<List<OperationResponse>> doFlush(Buffer buffer) {
    LOG.debug("flushing buffer: {}", buffer);
    if (buffer.getOperations().isEmpty()) {
      // no-op.
      return Deferred.<List<OperationResponse>>fromResult(ImmutableList.<OperationResponse>of());
    }

    Deferred<List<BatchResponse>> batchResponses = new Deferred<>();
    Callback<Void, Object> tabletLookupCB = new TabletLookupCB(buffer, batchResponses);

    for (BufferedOperation bufferedOperation : buffer.getOperations()) {
      AsyncUtil.addBoth(bufferedOperation.getTabletLookup(), tabletLookupCB);
    }

    return batchResponses.addCallback(ConvertBatchToListOfResponsesCB.getInstance());
  }

  /**
   * Callback used to send a list of OperationResponse instead of BatchResponse since the
   * latter is an implementation detail.
   */
  private static class ConvertBatchToListOfResponsesCB implements Callback<List<OperationResponse>,
                                                                           List<BatchResponse>> {
    private static final ConvertBatchToListOfResponsesCB INSTANCE =
        new ConvertBatchToListOfResponsesCB();

    @Override
    public List<OperationResponse> call(List<BatchResponse> batchResponses) throws Exception {
      // First compute the size of the union of all the lists so that we don't trigger expensive
      // list growths while adding responses to it.
      int size = 0;
      for (BatchResponse batchResponse : batchResponses) {
        size += batchResponse.getIndividualResponses().size();
      }

      ArrayList<OperationResponse> responses = new ArrayList<>(size);
      for (BatchResponse batchResponse : batchResponses) {
        responses.addAll(batchResponse.getIndividualResponses());
      }

      return responses;
    }

    @Override
    public String toString() {
      return "ConvertBatchToListOfResponsesCB";
    }

    public static ConvertBatchToListOfResponsesCB getInstance() {
      return INSTANCE;
    }
  }

  @Override
  public boolean hasPendingOperations() {
    synchronized (monitor) {
      return activeBuffer == null ? inactiveBuffers.size() < 2 :
             activeBuffer.getOperations().size() > 0 || !inactiveBufferAvailable();
    }
  }

  /**
   * Apply the given operation.
   * <p>
   * The behavior of this method depends on the configured
   * {@link SessionConfiguration.FlushMode FlushMode}. Regardless
   * of flush mode, however, {@code apply()} may begin to perform processing in the background
   * for the call (e.g looking up the tablet location, etc).
   * @param operation operation to apply
   * @return a Deferred to track this operation
   * @throws KuduException if an error happens or {@link PleaseThrottleException} is triggered
   * @see SessionConfiguration.FlushMode FlushMode
   */
  public Deferred<OperationResponse> apply(final Operation operation) throws KuduException {
    Preconditions.checkNotNull(operation, "Can not apply a null operation");

    // Freeze the row so that the client can not concurrently modify it while it is in flight.
    operation.getRow().freeze();

    // If immediate flush mode, send the operation directly.
    if (flushMode == FlushMode.AUTO_FLUSH_SYNC) {
      if (timeoutMs != 0) {
        operation.setTimeoutMillis(timeoutMs);
      }
      operation.setExternalConsistencyMode(this.consistencyMode);
      operation.setIgnoreAllDuplicateRows(ignoreAllDuplicateRows);
      return client.sendRpcToTablet(operation)
          .addErrback(new SingleOperationErrCallback(operation));
    }

    // Kick off a location lookup.
    Deferred<LocatedTablet> tablet = client.getTabletLocation(operation.getTable(),
                                                              operation.partitionKey(),
                                                              timeoutMs);

    // Holds a buffer that should be flushed outside the synchronized block, if necessary.
    Buffer fullBuffer = null;
    try {
      synchronized (monitor) {
        if (activeBuffer == null) {
          // If the active buffer is null then we recently flushed. Check if there
          // is an inactive buffer available to replace as the active.
          if (inactiveBufferAvailable()) {
            refreshActiveBuffer();
          } else {
            Status statusServiceUnavailable =
                Status.ServiceUnavailable("All buffers are currently flushing");
            // This can happen if the user writes into a buffer, flushes it, writes
            // into the second, flushes it, and immediately tries to write again.
            throw new PleaseThrottleException(statusServiceUnavailable,
                                              null, operation, flushNotification.get());
          }
        }

        if (flushMode == FlushMode.MANUAL_FLUSH) {
          if (activeBuffer.getOperations().size() < mutationBufferSpace) {
            activeBuffer.getOperations().add(new BufferedOperation(tablet, operation));
          } else {
            Status statusIllegalState =
                Status.IllegalState("MANUAL_FLUSH is enabled but the buffer is too big");
            throw new NonRecoverableException(statusIllegalState);
          }
        } else {
          assert flushMode == FlushMode.AUTO_FLUSH_BACKGROUND;
          int activeBufferSize = activeBuffer.getOperations().size();

          if (activeBufferSize >= mutationBufferSpace) {
            // Save the active buffer into fullBuffer so that it gets flushed when we leave this
            // synchronized block.
            fullBuffer = activeBuffer;
            activeBuffer = null;
            activeBufferSize = 0;
            if (inactiveBufferAvailable()) {
              refreshActiveBuffer();
            } else {
              Status statusServiceUnavailable =
                  Status.ServiceUnavailable("All buffers are currently flushing");
              throw new PleaseThrottleException(statusServiceUnavailable,
                                                null, operation, flushNotification.get());
            }
          }

          if (mutationBufferLowWatermark < mutationBufferSpace && // low watermark is enabled
              activeBufferSize >= mutationBufferLowWatermark &&   // buffer is over low water mark
              !inactiveBufferAvailable()) {                       // no inactive buffers

            // Check if we are over the low water mark.
            int randomWatermark = activeBufferSize + 1 +
                                  randomizer.nextInt(mutationBufferSpace -
                                                     mutationBufferLowWatermark);

            if (randomWatermark > mutationBufferSpace) {
              Status statusServiceUnavailable =
                  Status.ServiceUnavailable("The previous buffer hasn't been flushed and the " +
                      "current buffer is over the low watermark, please retry later");
              throw new PleaseThrottleException(statusServiceUnavailable,
                                                null, operation, flushNotification.get());
            }
          }

          activeBuffer.getOperations().add(new BufferedOperation(tablet, operation));

          if (activeBufferSize + 1 >= mutationBufferSpace && inactiveBufferAvailable()) {
            // If the operation filled the buffer, then flush it.
            Preconditions.checkState(fullBuffer == null);
            fullBuffer = activeBuffer;
            activeBuffer = null;
            activeBufferSize = 0;
          } else if (activeBufferSize == 0) {
            // If this is the first operation in the buffer, start a background flush timer.
            client.newTimeout(activeBuffer.getFlusherTask(), interval);
          }
        }
      }
    } finally {
      // Flush the buffer outside of the synchronized block, if required.
      if (fullBuffer != null) {
        doFlush(fullBuffer);
      }
    }
    return operation.getDeferred();
  }

  /**
   * Returns {@code true} if there is an inactive buffer available.
   * @return true if there is currently an inactive buffer available
   */
  private boolean inactiveBufferAvailable() {
    return inactiveBuffers.peek() != null;
  }

  /**
   * Refreshes the active buffer. This should only be called after a
   * {@link #flush()} when the active buffer is {@code null}, there is an
   * inactive buffer available (see {@link #inactiveBufferAvailable()}, and
   * {@link #monitor} is locked.
   */
  @GuardedBy("monitor")
  private void refreshActiveBuffer() {
    Preconditions.checkState(activeBuffer == null);
    activeBuffer = inactiveBuffers.remove();
    activeBuffer.reset();
  }

  /**
   * Returns a flush notification for the currently non-active buffers.
   * This is used during manual {@link #flush} calls to ensure that all buffers (not just the active
   * buffer) are fully flushed before completing.
   */
  @GuardedBy("monitor")
  private Deferred<Void> getNonActiveFlushNotification() {
    final Deferred<Void> notificationA = bufferA.getFlushNotification();
    final Deferred<Void> notificationB = bufferB.getFlushNotification();
    if (activeBuffer == null) {
      // Both buffers are either flushing or inactive.
      return AsyncUtil.addBothDeferring(notificationA, new Callback<Deferred<Void>, Object>() {
        @Override
        public Deferred<Void> call(Object obj) throws Exception {
          return notificationB;
        }
      });
    } else if (activeBuffer == bufferA) {
      return notificationB;
    } else {
      return notificationA;
    }
  }

  /**
   * Creates callbacks to handle a multi-put and adds them to the request.
   * @param request the request for which we must handle the response
   */
  private void addBatchCallbacks(final Batch request) {
    final class BatchCallback implements Callback<BatchResponse, BatchResponse> {
      public BatchResponse call(final BatchResponse response) {
        LOG.trace("Got a Batch response for {} rows", request.operations.size());
        if (response.getWriteTimestamp() != 0) {
          AsyncKuduSession.this.client.updateLastPropagatedTimestamp(response.getWriteTimestamp());
        }

        // Send individualized responses to all the operations in this batch.
        for (OperationResponse operationResponse : response.getIndividualResponses()) {
          if (flushMode == FlushMode.AUTO_FLUSH_BACKGROUND && operationResponse.hasRowError()) {
            errorCollector.addError(operationResponse.getRowError());
          }

          // Fire the callback after collecting the error so that the error is visible should the
          // callback interrogate the error collector.
          operationResponse.getOperation().callback(operationResponse);
        }

        return response;
      }

      @Override
      public String toString() {
        return "apply batch response";
      }
    }

    final class BatchErrCallback implements Callback<Object, Exception> {
      @Override
      public Object call(Exception e) {
        // If the exception we receive is a KuduException we're going to build OperationResponses.
        Status status = null;
        List<OperationResponse> responses = null;
        boolean handleKuduException = e instanceof KuduException;
        if (handleKuduException) {
          status = ((KuduException) e).getStatus();
          responses = new ArrayList<>(request.operations.size());
        }

        for (Operation operation : request.operations) {
          // Same comment as in BatchCallback regarding the ordering of when to callback.
          if (handleKuduException) {
            RowError rowError = new RowError(status, operation);
            OperationResponse response = new OperationResponse(0, null, 0, operation, rowError);
            errorCollector.addError(rowError);
            responses.add(response);

            operation.callback(response);
          } else {
            // We have no idea what the exception is so we'll just send it up.
            operation.errback(e);
          }
        }

        // Note that returning an object that's not an exception will make us leave the
        // errback chain. Effectively, the BatchResponse below will end up as part of the list
        // passed to ConvertBatchToListOfResponsesCB.
        return handleKuduException ? new BatchResponse(responses) : e;
      }

      @Override
      public String toString() {
        return "apply batch error response";
      }
    }

    request.getDeferred().addCallbacks(new BatchCallback(), new BatchErrCallback());
  }

  /**
   * Analogous to BatchErrCallback above but for AUTO_FLUSH_SYNC which doesn't handle lists of
   * operations and responses.
   */
  private final class SingleOperationErrCallback implements Callback<Object, Exception> {

    private final Operation operation;

    private SingleOperationErrCallback(Operation operation) {
      this.operation = operation;
    }

    @Override
    public Object call(Exception e) throws Exception {
      if (e instanceof KuduException) {
        Status status = ((KuduException) e).getStatus();
        RowError rowError = new RowError(status, operation);
        return new OperationResponse(0, null, 0, operation, rowError);
      }
      return e;
    }
  }

  /**
   * A FlusherTask is created for each active buffer in mode
   * {@link FlushMode#AUTO_FLUSH_BACKGROUND}.
   */
  private final class FlusherTask implements TimerTask {
    public void run(final Timeout timeout) {
      Buffer buffer = null;
      synchronized (monitor) {
        if (activeBuffer == null) {
          return;
        }
        if (activeBuffer.getFlusherTask() == this) {
          buffer = activeBuffer;
          activeBuffer = null;
        }
      }

      if (buffer != null) {
        doFlush(buffer);
      }
    }
  }

  /**
   * The {@code Buffer} consists of a list of operations, an optional pointer to a flush task,
   * and a flush notification.
   *
   * The {@link #flusherTask} is used in mode {@link FlushMode#AUTO_FLUSH_BACKGROUND} to point to
   * the background flusher task assigned to the buffer when it becomes active and the first
   * operation is applied to it. When the flusher task executes after the timeout, it checks
   * that the currently active buffer's flusher task points to itself before executing the flush.
   * This protects against the background task waking up after one or more manual flushes and
   * attempting to flush the active buffer.
   *
   * The {@link #flushNotification} deferred is used when executing manual {@link #flush}es to
   * ensure that non-active buffers are fully flushed. {@code flushNotification} is completed
   * when this buffer is successfully flushed. When the buffer is promoted from inactive to active,
   * the deferred is replaced with a new one to indicate that the buffer is not yet flushed.
   *
   * Buffer is externally synchronized. When the active buffer, {@link #monitor}
   * synchronizes access to it.
   */
  private final class Buffer {
    private final List<BufferedOperation> operations = new ArrayList<>();

    private FlusherTask flusherTask = null;

    private Deferred<Void> flushNotification = Deferred.fromResult(null);

    public List<BufferedOperation> getOperations() {
      return operations;
    }

    @GuardedBy("monitor")
    public FlusherTask getFlusherTask() {
      if (flusherTask == null) {
        flusherTask = new FlusherTask();
      }
      return flusherTask;
    }

    /**
     * Returns a {@link Deferred} which will be completed when this buffer is flushed. If the buffer
     * is inactive (its flush is complete and it has been enqueued into {@link #inactiveBuffers}),
     * then the deferred will already be complete.
     */
    public Deferred<Void> getFlushNotification() {
      return flushNotification;
    }

    /**
     * Completes the buffer's flush notification. Should be called when the buffer has been
     * successfully flushed.
     */
    public void callbackFlushNotification() {
      LOG.trace("buffer flush notification fired: {}", this);
      flushNotification.callback(null);
    }

    /**
     * Resets the buffer's internal state. Should be called when the buffer is promoted from
     * inactive to active.
     */
    @GuardedBy("monitor")
    public void reset() {
      LOG.trace("buffer reset: {}", this);
      operations.clear();
      flushNotification = new Deferred<>();
      flusherTask = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
                        .add("operations", operations.size())
                        .add("flusherTask", flusherTask)
                        .add("flushNotification", flushNotification)
                        .toString();
    }
  }

  /**
   * Container class holding all the state associated with a buffered operation.
   */
  private static final class BufferedOperation {
    /** Holds either a {@link LocatedTablet} or the failure exception if the lookup failed. */
    private Object tablet = null;
    private final Deferred<Void> tabletLookup;
    private final Operation operation;

    public BufferedOperation(Deferred<LocatedTablet> tablet,
                             Operation operation) {
      tabletLookup = AsyncUtil.addBoth(tablet, new Callback<Void, Object>() {
        @Override
        public Void call(final Object tablet) {
          BufferedOperation.this.tablet = tablet;
          return null;
        }
      });
      this.operation = Preconditions.checkNotNull(operation);
    }

    /**
     * @return {@code true} if the tablet lookup failed.
     */
    public boolean tabletLookupFailed() {
      return !(tablet instanceof LocatedTablet);
    }

    /**
     * @return the located tablet
     * @throws ClassCastException if the tablet lookup failed,
     *         check with {@link #tabletLookupFailed} before calling
     */
    public LocatedTablet getTablet() {
      return (LocatedTablet) tablet;
    }

    /**
     * @return the cause of the failed lookup
     * @throws ClassCastException if the tablet lookup succeeded,
     *         check with {@link #tabletLookupFailed} before calling
     */
    public Exception getTabletLookupFailure() {
      return (Exception) tablet;
    }

    public Deferred<Void> getTabletLookup() {
      return tabletLookup;
    }

    public Operation getOperation() {
      return operation;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
                        .add("tablet", tablet)
                        .add("operation", operation)
                        .toString();
    }
  }
}
