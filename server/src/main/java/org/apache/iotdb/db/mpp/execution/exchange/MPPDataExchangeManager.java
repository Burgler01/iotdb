/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.mpp.execution.exchange;

import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.commons.client.IClientManager;
import org.apache.iotdb.commons.client.sync.SyncDataNodeMPPDataExchangeServiceClient;
import org.apache.iotdb.db.mpp.execution.driver.DriverContext;
import org.apache.iotdb.db.mpp.execution.exchange.sink.DownStreamChannelIndex;
import org.apache.iotdb.db.mpp.execution.exchange.sink.DownStreamChannelLocation;
import org.apache.iotdb.db.mpp.execution.exchange.sink.ISinkHandle;
import org.apache.iotdb.db.mpp.execution.exchange.sink.LocalSinkHandle;
import org.apache.iotdb.db.mpp.execution.exchange.sink.ShuffleSinkHandle;
import org.apache.iotdb.db.mpp.execution.exchange.sink.SinkHandle;
import org.apache.iotdb.db.mpp.execution.exchange.source.ISourceHandle;
import org.apache.iotdb.db.mpp.execution.exchange.source.LocalSourceHandle;
import org.apache.iotdb.db.mpp.execution.exchange.source.SourceHandle;
import org.apache.iotdb.db.mpp.execution.fragment.FragmentInstanceContext;
import org.apache.iotdb.db.mpp.execution.memory.LocalMemoryManager;
import org.apache.iotdb.db.mpp.metric.QueryMetricsManager;
import org.apache.iotdb.db.utils.SetThreadName;
import org.apache.iotdb.mpp.rpc.thrift.MPPDataExchangeService;
import org.apache.iotdb.mpp.rpc.thrift.TAcknowledgeDataBlockEvent;
import org.apache.iotdb.mpp.rpc.thrift.TEndOfDataBlockEvent;
import org.apache.iotdb.mpp.rpc.thrift.TFragmentInstanceId;
import org.apache.iotdb.mpp.rpc.thrift.TGetDataBlockRequest;
import org.apache.iotdb.mpp.rpc.thrift.TGetDataBlockResponse;
import org.apache.iotdb.mpp.rpc.thrift.TNewDataBlockEvent;
import org.apache.iotdb.tsfile.read.common.block.column.TsBlockSerde;

import org.apache.commons.lang3.Validate;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static org.apache.iotdb.db.mpp.common.FragmentInstanceId.createFullId;
import static org.apache.iotdb.db.mpp.metric.DataExchangeCostMetricSet.GET_DATA_BLOCK_TASK_SERVER;
import static org.apache.iotdb.db.mpp.metric.DataExchangeCostMetricSet.ON_ACKNOWLEDGE_DATA_BLOCK_EVENT_TASK_SERVER;
import static org.apache.iotdb.db.mpp.metric.DataExchangeCostMetricSet.SEND_NEW_DATA_BLOCK_EVENT_TASK_SERVER;
import static org.apache.iotdb.db.mpp.metric.DataExchangeCountMetricSet.GET_DATA_BLOCK_NUM_SERVER;
import static org.apache.iotdb.db.mpp.metric.DataExchangeCountMetricSet.ON_ACKNOWLEDGE_DATA_BLOCK_NUM_SERVER;
import static org.apache.iotdb.db.mpp.metric.DataExchangeCountMetricSet.SEND_NEW_DATA_BLOCK_NUM_SERVER;

public class MPPDataExchangeManager implements IMPPDataExchangeManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(MPPDataExchangeManager.class);

  // region =========== MPPDataExchangeServiceImpl ===========

  /** Handle thrift communications. */
  class MPPDataExchangeServiceImpl implements MPPDataExchangeService.Iface {

    private final QueryMetricsManager QUERY_METRICS = QueryMetricsManager.getInstance();

    @Override
    public TGetDataBlockResponse getDataBlock(TGetDataBlockRequest req) throws TException {
      long startTime = System.nanoTime();
      try (SetThreadName fragmentInstanceName =
          new SetThreadName(
              createFullId(
                  req.sourceFragmentInstanceId.queryId,
                  req.sourceFragmentInstanceId.fragmentId,
                  req.sourceFragmentInstanceId.instanceId))) {
        LOGGER.debug(
            "[ProcessGetTsBlockRequest] sequence ID in [{}, {})",
            req.getStartSequenceId(),
            req.getEndSequenceId());
        if (!shuffleSinkHandles.containsKey(req.getSourceFragmentInstanceId())) {
          throw new TException(
              "Source fragment instance not found. Fragment instance ID: "
                  + req.getSourceFragmentInstanceId()
                  + ".");
        }
        TGetDataBlockResponse resp = new TGetDataBlockResponse();
        SinkHandle sinkHandle =
            (SinkHandle) shuffleSinkHandles.get(req.getSourceFragmentInstanceId());
        for (int i = req.getStartSequenceId(); i < req.getEndSequenceId(); i++) {
          try {
            ByteBuffer serializedTsBlock = sinkHandle.getSerializedTsBlock(i);
            resp.addToTsBlocks(serializedTsBlock);
          } catch (IllegalStateException | IOException e) {
            throw new TException(e);
          }
        }
        return resp;
      } finally {
        QUERY_METRICS.recordDataExchangeCost(
            GET_DATA_BLOCK_TASK_SERVER, System.nanoTime() - startTime);
        QUERY_METRICS.recordDataBlockNum(
            GET_DATA_BLOCK_NUM_SERVER, req.getEndSequenceId() - req.getStartSequenceId());
      }
    }

    @Override
    public void onAcknowledgeDataBlockEvent(TAcknowledgeDataBlockEvent e) {
      long startTime = System.nanoTime();
      try (SetThreadName fragmentInstanceName =
          new SetThreadName(
              createFullId(
                  e.sourceFragmentInstanceId.queryId,
                  e.sourceFragmentInstanceId.fragmentId,
                  e.sourceFragmentInstanceId.instanceId))) {
        LOGGER.debug(
            "Acknowledge data block event received, for data blocks whose sequence ID in [{}, {}) from {}.",
            e.getStartSequenceId(),
            e.getEndSequenceId(),
            e.getSourceFragmentInstanceId());
        if (!shuffleSinkHandles.containsKey(e.getSourceFragmentInstanceId())) {
          LOGGER.debug(
              "received ACK event but target FragmentInstance[{}] is not found.",
              e.getSourceFragmentInstanceId());
          return;
        }
        ((SinkHandle) shuffleSinkHandles.get(e.getSourceFragmentInstanceId()))
            .acknowledgeTsBlock(e.getStartSequenceId(), e.getEndSequenceId());
      } catch (Throwable t) {
        LOGGER.warn(
            "ack TsBlock [{}, {}) failed.", e.getStartSequenceId(), e.getEndSequenceId(), t);
        throw t;
      } finally {
        QUERY_METRICS.recordDataExchangeCost(
            ON_ACKNOWLEDGE_DATA_BLOCK_EVENT_TASK_SERVER, System.nanoTime() - startTime);
        QUERY_METRICS.recordDataBlockNum(
            ON_ACKNOWLEDGE_DATA_BLOCK_NUM_SERVER, e.getEndSequenceId() - e.getStartSequenceId());
      }
    }

    @Override
    public void onNewDataBlockEvent(TNewDataBlockEvent e) throws TException {
      long startTime = System.nanoTime();
      try (SetThreadName fragmentInstanceName =
          new SetThreadName(createFullIdFrom(e.targetFragmentInstanceId, e.targetPlanNodeId))) {
        LOGGER.debug(
            "New data block event received, for plan node {} of {} from {}.",
            e.getTargetPlanNodeId(),
            e.getTargetFragmentInstanceId(),
            e.getSourceFragmentInstanceId());

        Map<String, ISourceHandle> sourceHandleMap =
            sourceHandles.get(e.getTargetFragmentInstanceId());
        SourceHandle sourceHandle =
            sourceHandleMap == null
                ? null
                : (SourceHandle) sourceHandleMap.get(e.getTargetPlanNodeId());

        if (sourceHandle == null || sourceHandle.isAborted() || sourceHandle.isFinished()) {
          // In some scenario, when the SourceHandle sends the data block ACK event, its upstream
          // may
          // have already been stopped. For example, in the query whit LimitOperator, the downstream
          // FragmentInstance may be finished, although the upstream is still working.
          LOGGER.debug(
              "received NewDataBlockEvent but the downstream FragmentInstance[{}] is not found",
              e.getTargetFragmentInstanceId());
          return;
        }

        sourceHandle.updatePendingDataBlockInfo(e.getStartSequenceId(), e.getBlockSizes());
      } finally {
        QUERY_METRICS.recordDataExchangeCost(
            SEND_NEW_DATA_BLOCK_EVENT_TASK_SERVER, System.nanoTime() - startTime);
        QUERY_METRICS.recordDataBlockNum(SEND_NEW_DATA_BLOCK_NUM_SERVER, e.getBlockSizes().size());
      }
    }

    @Override
    public void onEndOfDataBlockEvent(TEndOfDataBlockEvent e) throws TException {
      try (SetThreadName fragmentInstanceName =
          new SetThreadName(createFullIdFrom(e.targetFragmentInstanceId, e.targetPlanNodeId))) {
        LOGGER.debug(
            "End of data block event received, for plan node {} of {} from {}.",
            e.getTargetPlanNodeId(),
            e.getTargetFragmentInstanceId(),
            e.getSourceFragmentInstanceId());

        Map<String, ISourceHandle> sourceHandleMap =
            sourceHandles.get(e.getTargetFragmentInstanceId());
        SourceHandle sourceHandle =
            sourceHandleMap == null
                ? null
                : (SourceHandle) sourceHandleMap.get(e.getTargetPlanNodeId());

        if (sourceHandle == null || sourceHandle.isAborted() || sourceHandle.isFinished()) {
          LOGGER.debug(
              "received onEndOfDataBlockEvent but the downstream FragmentInstance[{}] is not found",
              e.getTargetFragmentInstanceId());
          return;
        }

        sourceHandle.setNoMoreTsBlocks(e.getLastSequenceId());
      }
    }
  }

  // endregion

  // region =========== listener ===========

  public interface SourceHandleListener {
    void onFinished(ISourceHandle sourceHandle);

    void onAborted(ISourceHandle sourceHandle);

    void onFailure(ISourceHandle sourceHandle, Throwable t);
  }

  public interface SinkHandleListener {
    void onFinish(ISinkHandle sinkHandle);

    void onEndOfBlocks(ISinkHandle sinkHandle);

    Optional<Throwable> onAborted(ISinkHandle sinkHandle);

    void onFailure(ISinkHandle sinkHandle, Throwable t);
  }

  /** Listen to the state changes of a source handle. */
  class SourceHandleListenerImpl implements SourceHandleListener {

    private final IMPPDataExchangeManagerCallback<Throwable> onFailureCallback;

    public SourceHandleListenerImpl(IMPPDataExchangeManagerCallback<Throwable> onFailureCallback) {
      this.onFailureCallback = onFailureCallback;
    }

    @Override
    public void onFinished(ISourceHandle sourceHandle) {
      LOGGER.debug("[ScHListenerOnFinish]");
      Map<String, ISourceHandle> sourceHandleMap =
          sourceHandles.get(sourceHandle.getLocalFragmentInstanceId());
      if (sourceHandleMap == null
          || sourceHandleMap.remove(sourceHandle.getLocalPlanNodeId()) == null) {
        LOGGER.debug("[ScHListenerAlreadyReleased]");
      }

      if (sourceHandleMap != null && sourceHandleMap.isEmpty()) {
        sourceHandles.remove(sourceHandle.getLocalFragmentInstanceId());
      }
    }

    @Override
    public void onAborted(ISourceHandle sourceHandle) {
      LOGGER.debug("[ScHListenerOnAbort]");
      onFinished(sourceHandle);
    }

    @Override
    public void onFailure(ISourceHandle sourceHandle, Throwable t) {
      LOGGER.warn("Source handle failed due to: ", t);
      if (onFailureCallback != null) {
        onFailureCallback.call(t);
      }
    }
  }

  /**
   * Listen to the state changes of a source handle of pipeline. Since we register nothing in the
   * exchangeManager, so we don't need to remove it too.
   */
  static class PipelineSourceHandleListenerImpl implements SourceHandleListener {

    private final IMPPDataExchangeManagerCallback<Throwable> onFailureCallback;

    public PipelineSourceHandleListenerImpl(
        IMPPDataExchangeManagerCallback<Throwable> onFailureCallback) {
      this.onFailureCallback = onFailureCallback;
    }

    @Override
    public void onFinished(ISourceHandle sourceHandle) {
      LOGGER.debug("[ScHListenerOnFinish]");
    }

    @Override
    public void onAborted(ISourceHandle sourceHandle) {
      LOGGER.debug("[ScHListenerOnAbort]");
    }

    @Override
    public void onFailure(ISourceHandle sourceHandle, Throwable t) {
      LOGGER.warn("Source handle failed due to: ", t);
      if (onFailureCallback != null) {
        onFailureCallback.call(t);
      }
    }
  }

  /** Listen to the state changes of a sink handle. */
  class SinkHandleListenerImpl implements SinkHandleListener {

    private final FragmentInstanceContext context;
    private final IMPPDataExchangeManagerCallback<Throwable> onFailureCallback;

    public SinkHandleListenerImpl(
        FragmentInstanceContext context,
        IMPPDataExchangeManagerCallback<Throwable> onFailureCallback) {
      this.context = context;
      this.onFailureCallback = onFailureCallback;
    }

    @Override
    public void onFinish(ISinkHandle sinkHandle) {
      LOGGER.debug("[SkHListenerOnFinish]");
      removeFromMPPDataExchangeManager(sinkHandle);
      context.finished();
    }

    @Override
    public void onEndOfBlocks(ISinkHandle sinkHandle) {
      LOGGER.debug("[SkHListenerOnEndOfTsBlocks]");
      context.transitionToFlushing();
    }

    @Override
    public Optional<Throwable> onAborted(ISinkHandle sinkHandle) {
      LOGGER.debug("[SkHListenerOnAbort]");
      removeFromMPPDataExchangeManager(sinkHandle);
      return context.getFailureCause();
    }

    private void removeFromMPPDataExchangeManager(ISinkHandle sinkHandle) {
      if (shuffleSinkHandles.remove(sinkHandle.getLocalFragmentInstanceId()) == null) {
        LOGGER.debug("[RemoveNoSinkHandle]");
      } else {
        LOGGER.debug("[RemoveSinkHandle]");
      }
    }

    @Override
    public void onFailure(ISinkHandle sinkHandle, Throwable t) {
      // TODO: (xingtanzjr) should we remove the sinkHandle from MPPDataExchangeManager ?
      LOGGER.warn("Sink handle failed due to", t);
      if (onFailureCallback != null) {
        onFailureCallback.call(t);
      }
    }
  }

  /**
   * Listen to the state changes of a sink handle of pipeline. And since the finish of pipeline sink
   * handle doesn't equal the finish of the whole fragment, therefore we don't need to notify
   * fragment context. But if it's aborted or failed, it can lead to the total fail.
   */
  static class PipelineSinkHandleListenerImpl implements SinkHandleListener {

    private final FragmentInstanceContext context;
    private final IMPPDataExchangeManagerCallback<Throwable> onFailureCallback;

    public PipelineSinkHandleListenerImpl(
        FragmentInstanceContext context,
        IMPPDataExchangeManagerCallback<Throwable> onFailureCallback) {
      this.context = context;
      this.onFailureCallback = onFailureCallback;
    }

    @Override
    public void onFinish(ISinkHandle sinkHandle) {
      LOGGER.debug("[SkHListenerOnFinish]");
    }

    @Override
    public void onEndOfBlocks(ISinkHandle sinkHandle) {
      LOGGER.debug("[SkHListenerOnEndOfTsBlocks]");
    }

    @Override
    public Optional<Throwable> onAborted(ISinkHandle sinkHandle) {
      LOGGER.debug("[SkHListenerOnAbort]");
      return context.getFailureCause();
    }

    @Override
    public void onFailure(ISinkHandle sinkHandle, Throwable t) {
      LOGGER.warn("Sink handle failed due to", t);
      if (onFailureCallback != null) {
        onFailureCallback.call(t);
      }
    }
  }

  // endregion

  // region =========== MPPDataExchangeManager ===========

  private final LocalMemoryManager localMemoryManager;
  private final Supplier<TsBlockSerde> tsBlockSerdeFactory;
  private final ExecutorService executorService;
  private final IClientManager<TEndPoint, SyncDataNodeMPPDataExchangeServiceClient>
      mppDataExchangeServiceClientManager;
  private final Map<TFragmentInstanceId, Map<String, ISourceHandle>> sourceHandles;
  /** Each FI has only one ShuffleSinkHandle. So we can use TFragmentInstanceId as the key. */
  private final Map<TFragmentInstanceId, ISinkHandle> shuffleSinkHandles;

  /** One ShuffleSinkHandle could have multiple ISinkHandles as its channel. We use String of */
  private final Map<String, ISinkHandle> sinkHandles;

  private MPPDataExchangeServiceImpl mppDataExchangeService;

  public MPPDataExchangeManager(
      LocalMemoryManager localMemoryManager,
      Supplier<TsBlockSerde> tsBlockSerdeFactory,
      ExecutorService executorService,
      IClientManager<TEndPoint, SyncDataNodeMPPDataExchangeServiceClient>
          mppDataExchangeServiceClientManager) {
    this.localMemoryManager = Validate.notNull(localMemoryManager);
    this.tsBlockSerdeFactory = Validate.notNull(tsBlockSerdeFactory);
    this.executorService = Validate.notNull(executorService);
    this.mppDataExchangeServiceClientManager =
        Validate.notNull(mppDataExchangeServiceClientManager);
    sourceHandles = new ConcurrentHashMap<>();
    shuffleSinkHandles = new ConcurrentHashMap<>();
  }

  public MPPDataExchangeServiceImpl getOrCreateMPPDataExchangeServiceImpl() {
    if (mppDataExchangeService == null) {
      mppDataExchangeService = new MPPDataExchangeServiceImpl();
    }
    return mppDataExchangeService;
  }

  @Override
  public synchronized ISinkHandle createLocalSinkHandleForFragment(
      TFragmentInstanceId localFragmentInstanceId,
      TFragmentInstanceId remoteFragmentInstanceId,
      String remotePlanNodeId,
      // TODO: replace with callbacks to decouple MPPDataExchangeManager from
      // FragmentInstanceContext
      FragmentInstanceContext instanceContext) {
    if (shuffleSinkHandles.containsKey(localFragmentInstanceId)) {
      throw new IllegalStateException(
          "Local sink handle for " + localFragmentInstanceId + " exists.");
    }

    LOGGER.debug(
        "Create local sink handle to plan node {} of {} for {}",
        remotePlanNodeId,
        remoteFragmentInstanceId,
        localFragmentInstanceId);

    SharedTsBlockQueue queue;
    Map<String, ISourceHandle> sourceHandleMap = sourceHandles.get(remoteFragmentInstanceId);
    LocalSourceHandle localSourceHandle =
        sourceHandleMap == null ? null : (LocalSourceHandle) sourceHandleMap.get(remotePlanNodeId);
    if (localSourceHandle != null) {
      LOGGER.debug("Get SharedTsBlockQueue from local source handle");
      queue =
          ((LocalSourceHandle) sourceHandles.get(remoteFragmentInstanceId).get(remotePlanNodeId))
              .getSharedTsBlockQueue();
    } else {
      LOGGER.debug("Create shared tsblock queue");
      queue =
          new SharedTsBlockQueue(remoteFragmentInstanceId, remotePlanNodeId, localMemoryManager);
    }

    LocalSinkHandle localSinkHandle =
        new LocalSinkHandle(
            localFragmentInstanceId,
            queue,
            new SinkHandleListenerImpl(instanceContext, instanceContext::failed));
    shuffleSinkHandles.put(localFragmentInstanceId, localSinkHandle);
    return localSinkHandle;
  }

  /**
   * As we know the upstream and downstream node of shared queue, we don't need to put it into the
   * sinkHandle map.
   */
  public ISinkHandle createLocalSinkHandleForPipeline(
      DriverContext driverContext, String planNodeId) {
    LOGGER.debug("Create local sink handle for {}", driverContext.getDriverTaskID());
    SharedTsBlockQueue queue =
        new SharedTsBlockQueue(
            driverContext.getDriverTaskID().getFragmentInstanceId().toThrift(),
            planNodeId,
            localMemoryManager);
    return new LocalSinkHandle(
        queue,
        new PipelineSinkHandleListenerImpl(
            driverContext.getFragmentInstanceContext(), driverContext::failed));
  }

  @Override
  public ISinkHandle createShuffleSinkHandle(
      List<DownStreamChannelLocation> downStreamChannelLocationList,
      DownStreamChannelIndex downStreamChannelIndex,
      ShuffleSinkHandle.ShuffleStrategyEnum shuffleStrategyEnum,
      TFragmentInstanceId localFragmentInstanceId,
      String localPlanNodeId,
      // TODO: replace with callbacks to decouple MPPDataExchangeManager from
      // FragmentInstanceContext
      FragmentInstanceContext instanceContext) {
    if (shuffleSinkHandles.containsKey(localFragmentInstanceId)) {
      throw new IllegalStateException(
          "ShuffleSinkHandle for " + localFragmentInstanceId + " exists.");
    }

    SinkHandle sinkHandle =
        new SinkHandle(
            downStreamChannelLocationList,
            downStreamChannelIndex,
            shuffleStrategyEnum,
            localPlanNodeId,
            localFragmentInstanceId,
            localMemoryManager,
            executorService,
            tsBlockSerdeFactory.get(),
            new SinkHandleListenerImpl(instanceContext, instanceContext::failed),
            mppDataExchangeServiceClientManager);
    shuffleSinkHandles.put(localFragmentInstanceId, sinkHandle);
    return sinkHandle;
  }

  private ISinkHandle createHandleForShuffleSink(
      TFragmentInstanceId localFragmentInstanceId,
      String localPlanNodeId,
      DownStreamChannelLocation downStreamChannelLocation) {}

  /**
   * As we know the upstream and downstream node of shared queue, we don't need to put it into the
   * sourceHandle map.
   */
  public ISourceHandle createLocalSourceHandleForPipeline(
      SharedTsBlockQueue queue, DriverContext context) {
    LOGGER.debug("Create local source handle for {}", context.getDriverTaskID());
    return new LocalSourceHandle(
        queue,
        new PipelineSourceHandleListenerImpl(context::failed),
        context.getDriverTaskID().toString());
  }

  @Override
  public synchronized ISourceHandle createLocalSourceHandleForFragment(
      TFragmentInstanceId localFragmentInstanceId,
      String localPlanNodeId,
      TFragmentInstanceId remoteFragmentInstanceId,
      IMPPDataExchangeManagerCallback<Throwable> onFailureCallback) {
    if (sourceHandles.containsKey(localFragmentInstanceId)
        && sourceHandles.get(localFragmentInstanceId).containsKey(localPlanNodeId)) {
      throw new IllegalStateException(
          "Source handle for plan node "
              + localPlanNodeId
              + " of "
              + localFragmentInstanceId
              + " exists.");
    }

    LOGGER.debug(
        "Create local source handle from {} for plan node {} of {}",
        remoteFragmentInstanceId,
        localPlanNodeId,
        localFragmentInstanceId);
    SharedTsBlockQueue queue;
    if (shuffleSinkHandles.containsKey(remoteFragmentInstanceId)) {
      LOGGER.debug("Get shared tsblock queue from local sink handle");
      queue =
          ((LocalSinkHandle) shuffleSinkHandles.get(remoteFragmentInstanceId))
              .getSharedTsBlockQueue();
    } else {
      LOGGER.debug("Create shared tsblock queue");
      queue = new SharedTsBlockQueue(localFragmentInstanceId, localPlanNodeId, localMemoryManager);
    }
    LocalSourceHandle localSourceHandle =
        new LocalSourceHandle(
            localFragmentInstanceId,
            localPlanNodeId,
            queue,
            new SourceHandleListenerImpl(onFailureCallback));
    sourceHandles
        .computeIfAbsent(localFragmentInstanceId, key -> new ConcurrentHashMap<>())
        .put(localPlanNodeId, localSourceHandle);
    return localSourceHandle;
  }

  @Override
  public ISourceHandle createSourceHandle(
      TFragmentInstanceId localFragmentInstanceId,
      String localPlanNodeId,
      TEndPoint remoteEndpoint,
      TFragmentInstanceId remoteFragmentInstanceId,
      IMPPDataExchangeManagerCallback<Throwable> onFailureCallback) {
    Map<String, ISourceHandle> sourceHandleMap = sourceHandles.get(localFragmentInstanceId);
    if (sourceHandleMap != null && sourceHandleMap.containsKey(localPlanNodeId)) {
      throw new IllegalStateException(
          "Source handle for plan node "
              + localPlanNodeId
              + " of "
              + localFragmentInstanceId
              + " exists.");
    }

    LOGGER.debug(
        "Create source handle from {} for plan node {} of {}",
        remoteFragmentInstanceId,
        localPlanNodeId,
        localFragmentInstanceId);

    SourceHandle sourceHandle =
        new SourceHandle(
            remoteEndpoint,
            remoteFragmentInstanceId,
            localFragmentInstanceId,
            localPlanNodeId,
            localMemoryManager,
            executorService,
            tsBlockSerdeFactory.get(),
            new SourceHandleListenerImpl(onFailureCallback),
            mppDataExchangeServiceClientManager);
    sourceHandles
        .computeIfAbsent(localFragmentInstanceId, key -> new ConcurrentHashMap<>())
        .put(localPlanNodeId, sourceHandle);
    return sourceHandle;
  }

  /**
   * Release all the related resources, including data blocks that are not yet fetched by downstream
   * fragment instances.
   *
   * <p>This method should be called when a fragment instance finished in an abnormal state.
   */
  public void forceDeregisterFragmentInstance(TFragmentInstanceId fragmentInstanceId) {
    LOGGER.debug("[StartForceReleaseFIDataExchangeResource]");
    ISinkHandle sinkHandle = shuffleSinkHandles.get(fragmentInstanceId);
    if (sinkHandle != null) {
      sinkHandle.abort();
      shuffleSinkHandles.remove(fragmentInstanceId);
    }
    Map<String, ISourceHandle> planNodeIdToSourceHandle = sourceHandles.get(fragmentInstanceId);
    if (planNodeIdToSourceHandle != null) {
      for (Entry<String, ISourceHandle> entry : planNodeIdToSourceHandle.entrySet()) {
        LOGGER.debug("[CloseSourceHandle] {}", entry.getKey());
        entry.getValue().abort();
      }
      sourceHandles.remove(fragmentInstanceId);
    }
    LOGGER.debug("[EndForceReleaseFIDataExchangeResource]");
  }

  /** @param suffix should be like [PlanNodeId].SourceHandle/SinHandle */
  public static String createFullIdFrom(TFragmentInstanceId fragmentInstanceId, String suffix) {
    return createFullId(
            fragmentInstanceId.queryId,
            fragmentInstanceId.fragmentId,
            fragmentInstanceId.instanceId)
        + "."
        + suffix;
  }

  // endregion
}
