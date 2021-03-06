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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Predicate;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.*;
import org.apache.cassandra.cql3.Operator;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.monitoring.MonitorableImpl;
import org.apache.cassandra.db.partitions.*;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.db.transform.StoppingTransformation;
import org.apache.cassandra.db.transform.Transformation;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.index.Index;
import org.apache.cassandra.index.IndexNotAvailableException;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.metrics.TableMetrics;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.schema.IndexMetadata;
import org.apache.cassandra.schema.UnknownIndexException;
import org.apache.cassandra.service.ClientWarn;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;

/**
 * General interface for storage-engine read commands (common to both range and
 * single partition commands).
 * <p>
 * This contains all the informations needed to do a local read.
 */
public abstract class ReadCommand extends MonitorableImpl implements ReadQuery
{
    private static final int TEST_ITERATION_DELAY_MILLIS = Integer.parseInt(System.getProperty("cassandra.test.read_iteration_delay_ms", "0"));
    protected static final Logger logger = LoggerFactory.getLogger(ReadCommand.class);
    public static final IVersionedSerializer<ReadCommand> serializer = new Serializer();

    private final Kind kind;
    private final CFMetaData metadata;
    private final int nowInSec;

    private final ColumnFilter columnFilter;
    private final RowFilter rowFilter;
    private final DataLimits limits;

    // SecondaryIndexManager will attempt to provide the most selective of any available indexes
    // during execution. Here we also store an the results of that lookup to repeating it over
    // the lifetime of the command.
    protected Optional<IndexMetadata> index = Optional.empty();

    // Flag to indicate whether the index manager has been queried to select an index for this
    // command. This is necessary as the result of that lookup may be null, in which case we
    // still don't want to repeat it.
    private boolean indexManagerQueried = false;

    private boolean isDigestQuery;
    // if a digest query, the version for which the digest is expected. Ignored if not a digest.
    private int digestVersion;
    private final boolean isForThrift;

    protected static abstract class SelectionDeserializer
    {
        public abstract ReadCommand deserialize(DataInputPlus in, int version, boolean isDigest, int digestVersion, boolean isForThrift, CFMetaData metadata, int nowInSec, ColumnFilter columnFilter, RowFilter rowFilter, DataLimits limits, Optional<IndexMetadata> index) throws IOException;
    }

    protected enum Kind
    {
        SINGLE_PARTITION (SinglePartitionReadCommand.selectionDeserializer),
        PARTITION_RANGE  (PartitionRangeReadCommand.selectionDeserializer);

        private final SelectionDeserializer selectionDeserializer;

        Kind(SelectionDeserializer selectionDeserializer)
        {
            this.selectionDeserializer = selectionDeserializer;
        }
    }

    protected ReadCommand(Kind kind,
                          boolean isDigestQuery,
                          int digestVersion,
                          boolean isForThrift,
                          CFMetaData metadata,
                          int nowInSec,
                          ColumnFilter columnFilter,
                          RowFilter rowFilter,
                          DataLimits limits)
    {
        this.kind = kind;
        this.isDigestQuery = isDigestQuery;
        this.digestVersion = digestVersion;
        this.isForThrift = isForThrift;
        this.metadata = metadata;
        this.nowInSec = nowInSec;
        this.columnFilter = columnFilter;
        this.rowFilter = rowFilter;
        this.limits = limits;
    }

    protected abstract void serializeSelection(DataOutputPlus out, int version) throws IOException;
    protected abstract long selectionSerializedSize(int version);

    /**
     * Creates a new <code>ReadCommand</code> instance with new limits.
     *
     * @param newLimits the new limits
     * @return a new <code>ReadCommand</code> with the updated limits
     */
    public abstract ReadCommand withUpdatedLimit(DataLimits newLimits);

    /**
     * The metadata for the table queried.
     *
     * @return the metadata for the table queried.
     */
    public CFMetaData metadata()
    {
        return metadata;
    }

    /**
     * The time in seconds to use as "now" for this query.
     * <p>
     * We use the same time as "now" for the whole query to avoid considering different
     * values as expired during the query, which would be buggy (would throw of counting amongst other
     * things).
     *
     * @return the time (in seconds) to use as "now".
     */
    public int nowInSec()
    {
        return nowInSec;
    }

    /**
     * The configured timeout for this command.
     *
     * @return the configured timeout for this command.
     */
    public abstract long getTimeout();

    /**
     * A filter on which (non-PK) columns must be returned by the query.
     *
     * @return which columns must be fetched by this query.
     */
    public ColumnFilter columnFilter()
    {
        return columnFilter;
    }

    /**
     * Filters/Resrictions on CQL rows.
     * <p>
     * This contains the restrictions that are not directly handled by the
     * {@code ClusteringIndexFilter}. More specifically, this includes any non-PK column
     * restrictions and can include some PK columns restrictions when those can't be
     * satisfied entirely by the clustering index filter (because not all clustering columns
     * have been restricted for instance). If there is 2ndary indexes on the table,
     * one of this restriction might be handled by a 2ndary index.
     *
     * @return the filter holding the expression that rows must satisfy.
     */
    public RowFilter rowFilter()
    {
        return rowFilter;
    }

    /**
     * The limits set on this query.
     *
     * @return the limits set on this query.
     */
    public DataLimits limits()
    {
        return limits;
    }

    /**
     * Whether this query is a digest one or not.
     *
     * @return Whether this query is a digest query.
     */
    public boolean isDigestQuery()
    {
        return isDigestQuery;
    }

    /**
     * If the query is a digest one, the requested digest version.
     *
     * @return the requested digest version if the query is a digest. Otherwise, this can return
     * anything.
     */
    public int digestVersion()
    {
        return digestVersion;
    }

    /**
     * Sets whether this command should be a digest one or not.
     *
     * @param isDigestQuery whether the command should be set as a digest one or not.
     * @return this read command.
     */
    public ReadCommand setIsDigestQuery(boolean isDigestQuery)
    {
        this.isDigestQuery = isDigestQuery;
        return this;
    }

    /**
     * Sets the digest version, for when digest for that command is requested.
     * <p>
     * Note that we allow setting this independently of setting the command as a digest query as
     * this allows us to use the command as a carrier of the digest version even if we only call
     * setIsDigestQuery on some copy of it.
     *
     * @param digestVersion the version for the digest is this command is used for digest query..
     * @return this read command.
     */
    public ReadCommand setDigestVersion(int digestVersion)
    {
        this.digestVersion = digestVersion;
        return this;
    }

    /**
     * Whether this query is for thrift or not.
     *
     * @return whether this query is for thrift.
     */
    public boolean isForThrift()
    {
        return isForThrift;
    }

    /**
     * The clustering index filter this command to use for the provided key.
     * <p>
     * Note that that method should only be called on a key actually queried by this command
     * and in practice, this will almost always return the same filter, but for the sake of
     * paging, the filter on the first key of a range command might be slightly different.
     *
     * @param key a partition key queried by this command.
     *
     * @return the {@code ClusteringIndexFilter} to use for the partition of key {@code key}.
     */
    public abstract ClusteringIndexFilter clusteringIndexFilter(DecoratedKey key);

    /**
     * Returns a copy of this command.
     *
     * @return a copy of this command.
     */
    public abstract ReadCommand copy();

    protected abstract UnfilteredPartitionIterator queryStorage(ColumnFamilyStore cfs, ReadExecutionController executionController);

    protected abstract int oldestUnrepairedTombstone();

    public ReadResponse createResponse(UnfilteredPartitionIterator iterator)
    {
        return isDigestQuery()
             ? ReadResponse.createDigestResponse(iterator, this)
             : ReadResponse.createDataResponse(iterator, this);
    }

    public long indexSerializedSize(int version)
    {
        if (index.isPresent())
            return IndexMetadata.serializer.serializedSize(index.get(), version);
        else
            return 0;
    }

    public Index getIndex(ColumnFamilyStore cfs)
    {
        // if we've already consulted the index manager, and it returned a valid index
        // the result should be cached here.
        if(index.isPresent())
            return cfs.indexManager.getIndex(index.get());

        // if no cached index is present, but we've already consulted the index manager
        // then no registered index is suitable for this command, so just return null.
        if (indexManagerQueried)
            return null;

        // do the lookup, set the flag to indicate so and cache the result if not null
        Index selected = cfs.indexManager.getBestIndexFor(this);
        indexManagerQueried = true;

        if (selected == null)
            return null;

        index = Optional.of(selected.getIndexMetadata());
        return selected;
    }

    /**
     * If the index manager for the CFS determines that there's an applicable
     * 2i that can be used to execute this command, call its (optional)
     * validation method to check that nothing in this command's parameters
     * violates the implementation specific validation rules.
     */
    public void maybeValidateIndex()
    {
        Index index = getIndex(Keyspace.openAndGetStore(metadata));
        if (null != index)
            index.validate(this);
    }

    /**
     * Executes this command on the local host.
     *
     * @param executionController the execution controller spanning this command
     *
     * @return an iterator over the result of executing this command locally.
     */
    @SuppressWarnings("resource") // The result iterator is closed upon exceptions (we know it's fine to potentially not close the intermediary
                                  // iterators created inside the try as long as we do close the original resultIterator), or by closing the result.
    public UnfilteredPartitionIterator executeLocally(ReadExecutionController executionController)
    {
        long startTimeNanos = System.nanoTime();

        ColumnFamilyStore cfs = Keyspace.openAndGetStore(metadata());
        Index index = getIndex(cfs);

        Index.Searcher searcher = null;
        if (index != null)
        {
            if (!cfs.indexManager.isIndexQueryable(index))
                throw new IndexNotAvailableException(index);

            searcher = index.searcherFor(this);
            Tracing.trace("Executing read on {}.{} using index {}", cfs.metadata.ksName, cfs.metadata.cfName, index.getIndexMetadata().name);
        }

        UnfilteredPartitionIterator resultIterator = searcher == null
                                         ? queryStorage(cfs, executionController)
                                         : searcher.search(executionController);

        try
        {
            resultIterator = withStateTracking(resultIterator);
            resultIterator = withMetricsRecording(withoutPurgeableTombstones(resultIterator, cfs), cfs.metric, startTimeNanos);

            // If we've used a 2ndary index, we know the result already satisfy the primary expression used, so
            // no point in checking it again.
            RowFilter updatedFilter = searcher == null
                                    ? rowFilter()
                                    : index.getPostIndexQueryFilter(rowFilter());

            // TODO: We'll currently do filtering by the rowFilter here because it's convenient. However,
            // we'll probably want to optimize by pushing it down the layer (like for dropped columns) as it
            // would be more efficient (the sooner we discard stuff we know we don't care, the less useless
            // processing we do on it).
            return limits().filter(updatedFilter.filter(resultIterator, nowInSec()), nowInSec());
        }
        catch (RuntimeException | Error e)
        {
            resultIterator.close();
            throw e;
        }
    }

    protected abstract void recordLatency(TableMetrics metric, long latencyNanos);

    public PartitionIterator executeInternal(ReadExecutionController controller)
    {
        return UnfilteredPartitionIterators.filter(executeLocally(controller), nowInSec());
    }

    public ReadExecutionController executionController()
    {
        return ReadExecutionController.forCommand(this);
    }

    /**
     * Wraps the provided iterator so that metrics on what is scanned by the command are recorded.
     * This also log warning/trow TombstoneOverwhelmingException if appropriate.
     */
    private UnfilteredPartitionIterator withMetricsRecording(UnfilteredPartitionIterator iter, final TableMetrics metric, final long startTimeNanos)
    {
        class MetricRecording extends Transformation<UnfilteredRowIterator>
        {
            private final int failureThreshold = DatabaseDescriptor.getTombstoneFailureThreshold();
            private final int warningThreshold = DatabaseDescriptor.getTombstoneWarnThreshold();

            private final boolean respectTombstoneThresholds = !SchemaConstants.isSystemKeyspace(ReadCommand.this.metadata().ksName);

            private int liveRows = 0;
            private int tombstones = 0;

            private DecoratedKey currentKey;

            @Override
            public UnfilteredRowIterator applyToPartition(UnfilteredRowIterator iter)
            {
                currentKey = iter.partitionKey();
                return Transformation.apply(iter, this);
            }

            @Override
            public Row applyToStatic(Row row)
            {
                return applyToRow(row);
            }

            @Override
            public Row applyToRow(Row row)
            {
                if (row.hasLiveData(ReadCommand.this.nowInSec()))
                    ++liveRows;

                for (Cell cell : row.cells())
                {
                    if (!cell.isLive(ReadCommand.this.nowInSec()))
                        countTombstone(row.clustering());
                }
                return row;
            }

            @Override
            public RangeTombstoneMarker applyToMarker(RangeTombstoneMarker marker)
            {
                countTombstone(marker.clustering());
                return marker;
            }

            private void countTombstone(ClusteringPrefix clustering)
            {
                ++tombstones;
                if (tombstones > failureThreshold && respectTombstoneThresholds)
                {
                    String query = ReadCommand.this.toCQLString();
                    Tracing.trace("Scanned over {} tombstones for query {}; query aborted (see tombstone_failure_threshold)", failureThreshold, query);
                    throw new TombstoneOverwhelmingException(tombstones, query, ReadCommand.this.metadata(), currentKey, clustering);
                }
            }

            @Override
            public void onClose()
            {
                recordLatency(metric, System.nanoTime() - startTimeNanos);

                metric.tombstoneScannedHistogram.update(tombstones);
                metric.liveScannedHistogram.update(liveRows);

                boolean warnTombstones = tombstones > warningThreshold && respectTombstoneThresholds;
                if (warnTombstones)
                {
                    String msg = String.format("Read %d live rows and %d tombstone cells for query %1.512s (see tombstone_warn_threshold)", liveRows, tombstones, ReadCommand.this.toCQLString());
                    ClientWarn.instance.warn(msg);
                    logger.warn(msg);
                }

                Tracing.trace("Read {} live and {} tombstone cells{}", liveRows, tombstones, (warnTombstones ? " (see tombstone_warn_threshold)" : ""));
            }
        };

        return Transformation.apply(iter, new MetricRecording());
    }

    protected class CheckForAbort extends StoppingTransformation<BaseRowIterator<?>>
    {
        protected BaseRowIterator<?> applyToPartition(BaseRowIterator<?> partition)
        {
            if (maybeAbort())
            {
                partition.close();
                return null;
            }

            return partition;
        }

        protected Row applyToRow(Row row)
        {
            return maybeAbort() ? null : row;
        }

        private boolean maybeAbort()
        {
            if (TEST_ITERATION_DELAY_MILLIS > 0)
                maybeDelayForTesting();

            if (isAborted())
            {
                stop();
                return true;
            }

            return false;
        }
    }

    protected UnfilteredPartitionIterator withStateTracking(UnfilteredPartitionIterator iter)
    {
        return Transformation.apply(iter, new CheckForAbort());
    }

    protected UnfilteredRowIterator withStateTracking(UnfilteredRowIterator iter)
    {
        return Transformation.apply(iter, new CheckForAbort());
    }

    private void maybeDelayForTesting()
    {
        if (!metadata.ksName.startsWith("system"))
            FBUtilities.sleepQuietly(TEST_ITERATION_DELAY_MILLIS);
    }

    /**
     * Creates a message for this command.
     */
    public abstract MessageOut<ReadCommand> createMessage();

    protected abstract void appendCQLWhereClause(StringBuilder sb);

    // Skip purgeable tombstones. We do this because it's safe to do (post-merge of the memtable and sstable at least), it
    // can save us some bandwith, and avoid making us throw a TombstoneOverwhelmingException for purgeable tombstones (which
    // are to some extend an artefact of compaction lagging behind and hence counting them is somewhat unintuitive).
    protected UnfilteredPartitionIterator withoutPurgeableTombstones(UnfilteredPartitionIterator iterator, ColumnFamilyStore cfs)
    {
        final boolean isForThrift = iterator.isForThrift();
        class WithoutPurgeableTombstones extends PurgeFunction
        {
            public WithoutPurgeableTombstones()
            {
                super(isForThrift, nowInSec(), cfs.gcBefore(nowInSec()), oldestUnrepairedTombstone(), cfs.getCompactionStrategyManager().onlyPurgeRepairedTombstones());
            }

            protected Predicate<Long> getPurgeEvaluator()
            {
                return time -> true;
            }
        }
        return Transformation.apply(iterator, new WithoutPurgeableTombstones());
    }

    /**
     * Recreate the CQL string corresponding to this query.
     * <p>
     * Note that in general the returned string will not be exactly the original user string, first
     * because there isn't always a single syntax for a given query,  but also because we don't have
     * all the information needed (we know the non-PK columns queried but not the PK ones as internally
     * we query them all). So this shouldn't be relied too strongly, but this should be good enough for
     * debugging purpose which is what this is for.
     */
    public String toCQLString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(columnFilter());
        sb.append(" FROM ").append(metadata().ksName).append('.').append(metadata.cfName);
        appendCQLWhereClause(sb);

        if (limits() != DataLimits.NONE)
            sb.append(' ').append(limits());
        return sb.toString();
    }

    // Monitorable interface
    public String name()
    {
        return toCQLString();
    }

    private static class Serializer implements IVersionedSerializer<ReadCommand>
    {
        private static int digestFlag(boolean isDigest)
        {
            return isDigest ? 0x01 : 0;
        }

        private static boolean isDigest(int flags)
        {
            return (flags & 0x01) != 0;
        }

        private static int thriftFlag(boolean isForThrift)
        {
            return isForThrift ? 0x02 : 0;
        }

        private static boolean isForThrift(int flags)
        {
            return (flags & 0x02) != 0;
        }

        private static int indexFlag(boolean hasIndex)
        {
            return hasIndex ? 0x04 : 0;
        }

        private static boolean hasIndex(int flags)
        {
            return (flags & 0x04) != 0;
        }

        public void serialize(ReadCommand command, DataOutputPlus out, int version) throws IOException
        {
            out.writeByte(command.kind.ordinal());
            out.writeByte(digestFlag(command.isDigestQuery()) | thriftFlag(command.isForThrift()) | indexFlag(command.index.isPresent()));
            if (command.isDigestQuery())
                out.writeUnsignedVInt(command.digestVersion());
            CFMetaData.serializer.serialize(command.metadata(), out, version);
            out.writeInt(command.nowInSec());
            ColumnFilter.serializer.serialize(command.columnFilter(), out, version);
            RowFilter.serializer.serialize(command.rowFilter(), out, version);
            DataLimits.serializer.serialize(command.limits(), out, version, command.metadata.comparator);
            if (command.index.isPresent())
                IndexMetadata.serializer.serialize(command.index.get(), out, version);

            command.serializeSelection(out, version);
        }

        public ReadCommand deserialize(DataInputPlus in, int version) throws IOException
        {
            Kind kind = Kind.values()[in.readByte()];
            int flags = in.readByte();
            boolean isDigest = isDigest(flags);
            boolean isForThrift = isForThrift(flags);
            boolean hasIndex = hasIndex(flags);
            int digestVersion = isDigest ? (int)in.readUnsignedVInt() : 0;
            CFMetaData metadata = CFMetaData.serializer.deserialize(in, version);
            int nowInSec = in.readInt();
            ColumnFilter columnFilter = ColumnFilter.serializer.deserialize(in, version, metadata);
            RowFilter rowFilter = RowFilter.serializer.deserialize(in, version, metadata);
            DataLimits limits = DataLimits.serializer.deserialize(in, version,  metadata.comparator);
            Optional<IndexMetadata> index = hasIndex
                                          ? deserializeIndexMetadata(in, version, metadata)
                                          : Optional.empty();

            return kind.selectionDeserializer.deserialize(in, version, isDigest, digestVersion, isForThrift, metadata, nowInSec, columnFilter, rowFilter, limits, index);
        }

        private Optional<IndexMetadata> deserializeIndexMetadata(DataInputPlus in, int version, CFMetaData cfm) throws IOException
        {
            try
            {
                return Optional.of(IndexMetadata.serializer.deserialize(in, version, cfm));
            }
            catch (UnknownIndexException e)
            {
                logger.info("Couldn't find a defined index on {}.{} with the id {}. " +
                            "If an index was just created, this is likely due to the schema not " +
                            "being fully propagated. Local read will proceed without using the " +
                            "index. Please wait for schema agreement after index creation.",
                            cfm.ksName, cfm.cfName, e.indexId);
                return Optional.empty();
            }
        }

        public long serializedSize(ReadCommand command, int version)
        {
            return 2 // kind + flags
                 + (command.isDigestQuery() ? TypeSizes.sizeofUnsignedVInt(command.digestVersion()) : 0)
                 + CFMetaData.serializer.serializedSize(command.metadata(), version)
                 + TypeSizes.sizeof(command.nowInSec())
                 + ColumnFilter.serializer.serializedSize(command.columnFilter(), version)
                 + RowFilter.serializer.serializedSize(command.rowFilter(), version)
                 + DataLimits.serializer.serializedSize(command.limits(), version, command.metadata.comparator)
                 + command.selectionSerializedSize(version)
                 + command.indexSerializedSize(version);
        }
    }
}
