/**
 * Copyright 2017 LinkedIn Corp. All rights reserved.
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
 */

package com.github.ambry.store;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.account.Account;
import com.github.ambry.account.Container;
import com.github.ambry.server.StatsReportType;
import com.github.ambry.server.StatsSnapshot;
import com.github.ambry.utils.MockTime;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.SystemTime;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Throttler;
import com.github.ambry.utils.Time;
import com.github.ambry.utils.Utils;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.github.ambry.store.CuratedLogIndexState.*;
import static com.github.ambry.store.StoreStats.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;


/**
 * Tests for {@link BlobStoreStats}. Tests both segmented and non segmented log use cases.
 */
@RunWith(Parameterized.class)
public class BlobStoreStatsTest {
  private static final long TEST_TIME_INTERVAL_IN_MS = DELAY_BETWEEN_LAST_MODIFIED_TIMES_MS / 2;
  private static final long BUCKET_SPAN_IN_MS = Time.MsPerSec;
  private static final long QUEUE_PROCESSOR_PERIOD_IN_Ms = 100;
  private static final StoreMetrics METRICS = new StoreMetrics(new MetricRegistry());
  private static final long DEFAULT_WAIT_TIMEOUT_SECS = Time.SecsPerMin;
  private final Map<String, Throttler> throttlers = new HashMap<>();
  private final DiskIOScheduler diskIOScheduler = new DiskIOScheduler(throttlers);
  private final ScheduledExecutorService indexScannerScheduler = Utils.newScheduler(1, true);
  private final ScheduledExecutorService queueProcessorScheduler = Utils.newScheduler(1, true);
  private final boolean bucketingEnabled;
  private CuratedLogIndexState state;
  private File tempDir;

  /**
   * Creates a temporary directory and sets up some test state.
   * @throws IOException
   */
  public BlobStoreStatsTest(boolean isBucketingEnabled) throws IOException, StoreException {
    tempDir = StoreTestUtils.createTempDirectory("blobStoreStatsDir-" + TestUtils.getRandomString(10));
    state = new CuratedLogIndexState(true, tempDir, true, true);
    bucketingEnabled = isBucketingEnabled;
  }

  private BlobStoreStats setupBlobStoreStats(int bucketCount, long logSegmentForecastOffsetMs) {
    return new BlobStoreStats("", state.index, bucketCount, BUCKET_SPAN_IN_MS, logSegmentForecastOffsetMs,
        QUEUE_PROCESSOR_PERIOD_IN_Ms, DEFAULT_WAIT_TIMEOUT_SECS, true, state.time, indexScannerScheduler,
        queueProcessorScheduler, diskIOScheduler, METRICS);
  }

  /**
   * Running for both segmented and non-segmented log.
   * @return an array with both {@code false} and {@code true}.
   */
  @Parameterized.Parameters
  public static List<Object[]> data() {
    return Arrays.asList(new Object[][]{{false}, {true}});
  }

  /**
   * Releases all resources and deletes the temporary directory.
   * @throws InterruptedException
   * @throws IOException
   * @throws StoreException
   */
  @After
  public void cleanup() throws InterruptedException, IOException, StoreException {
    indexScannerScheduler.shutdown();
    if (!indexScannerScheduler.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
      fail("Could not terminate index scanner task after BlobStoreStats shutdown");
    }
    queueProcessorScheduler.shutdown();
    if (!queueProcessorScheduler.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
      fail("Could not terminate queue processor task after BlobStoreStats shutdown");
    }
    state.destroy();
    assertTrue(tempDir.getAbsolutePath() + " could not be deleted", StoreTestUtils.cleanDirectory(tempDir, true));
  }

  /**
   * Test {@link BlobStoreStats} can be initialized (with bucketing enabled/disabled) and closed properly.
   * @throws InterruptedException
   */
  @Test
  public void testInitializationAndClose() throws InterruptedException {
    int bucketCount = bucketingEnabled ? 1 : 0;
    final CountDownLatch scanStartedLatch = new CountDownLatch(1);
    MockThrottler mockThrottler = new MockThrottler(scanStartedLatch, new CountDownLatch(0));
    throttlers.put(BlobStoreStats.IO_SCHEDULER_JOB_TYPE, mockThrottler);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(bucketCount, 0);
    if (bucketingEnabled) {
      // IndexScanner should be started if bucketing is enabled
      assertTrue("IndexScanner took too long to start", scanStartedLatch.await(5, TimeUnit.SECONDS));
    } else {
      // IndexScanner should not be started if bucketing is disabled
      assertEquals("IndexScanner should not be started", 0, mockThrottler.throttleCount.get());
    }
    blobStoreStats.close();
  }

  /**
   * Basic test to verify reported valid size information per container by BlobStoreStats.
   * @throws StoreException
   */
  @Test
  public void testContainerValidDataSize() throws StoreException {
    assumeTrue(!bucketingEnabled);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(0, 0);
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    // advance time
    advanceTimeToNextSecond();
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    blobStoreStats.close();
  }

  /**
   * Basic test to verify reported valid size information per log segment by BlobStoreStats.
   * @throws StoreException
   */
  @Test
  public void testLogSegmentValidDataSize() throws StoreException {
    assumeTrue(!bucketingEnabled);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(0, 0);
    long currentTimeInMs = state.time.milliseconds();
    for (long i = 0; i <= currentTimeInMs + TEST_TIME_INTERVAL_IN_MS; i += TEST_TIME_INTERVAL_IN_MS) {
      TimeRange timeRange = new TimeRange(i, 0L);
      verifyAndGetLogSegmentValidSize(blobStoreStats, timeRange);
    }
    blobStoreStats.close();
  }

  /**
   * Tests to verify the correctness of reported stats after new puts via the following steps:
   * 1. Verify reported stats and record the total valid size prior to adding the new puts.
   * 2. Add new puts.
   * 3. Verify reported stats and record the total valid size after new puts are added.
   * 4. Verify the delta of total valid size prior to adding the new puts and after matches with the expected delta.
   * @throws StoreException
   */
  @Test
  public void testValidDataSizeAfterPuts() throws StoreException {
    assumeTrue(!bucketingEnabled);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(0, 0);
    // advance time to the next second for deletes/expiration to take effect
    advanceTimeToNextSecond();
    long timeInMsBeforePuts = state.time.milliseconds();
    long totalLogSegmentValidSizeBeforePuts =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsBeforePuts, 0L));
    long totalContainerValidSizeBeforePuts = verifyAndGetContainerValidSize(blobStoreStats, timeInMsBeforePuts);

    // 3 puts
    state.addPutEntries(3, PUT_RECORD_SIZE, Utils.Infinite_Time);

    long timeInMsAfterPuts = state.time.milliseconds();
    long totalLogSegmentValidSizeAfterPuts =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsAfterPuts, 0L));
    long totalContainerValidSizeAfterPuts = verifyAndGetContainerValidSize(blobStoreStats, timeInMsAfterPuts);
    long expectedIncrement = 3 * PUT_RECORD_SIZE;
    assertEquals("Put entries are not properly counted for log segment valid size", totalLogSegmentValidSizeAfterPuts,
        totalLogSegmentValidSizeBeforePuts + expectedIncrement);
    assertEquals("Put entries are not properly counted for container valid size", totalContainerValidSizeAfterPuts,
        totalContainerValidSizeBeforePuts + expectedIncrement);
    blobStoreStats.close();
  }

  /**
   * Tests to verify the correctness of reported stats with puts that is going to expire via the following steps:
   * 1. Verify reported stats and record the total valid size before adding the new expiring puts.
   * 2. Add new expiring and non-expiring puts.
   * 3. Verify the new puts are being reported correctly.
   * 4. Advance time to let the expiration take effect.
   * 5. Verify reported stats and record the total valid size after new puts are expired.
   * 6. Verify the reported total valid size difference before the new puts and after.
   * @throws StoreException
   */
  @Test
  public void testValidDataSizeAfterExpiration() throws StoreException {
    assumeTrue(!bucketingEnabled);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(0, 0);
    // advance time to the next second for previous deletes/expiration to take effect
    advanceTimeToNextSecond();
    long timeInMsBeforePuts = state.time.milliseconds();
    long totalLogSegmentValidSizeBeforePuts =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsBeforePuts, 0L));
    long totalContainerValidSizeBeforePuts = verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());

    // 1 put with no expiry
    state.addPutEntries(1, PUT_RECORD_SIZE, Utils.Infinite_Time);
    // 3 puts that will expire in 20 seconds (note the two puts should be in the same index segment)
    long expiresAtInMs = state.time.milliseconds() + TimeUnit.SECONDS.toMillis(20);
    state.addPutEntries(3, PUT_RECORD_SIZE, expiresAtInMs);

    // advance time to exactly the time of expiration, all new puts should still be valid
    state.advanceTime(expiresAtInMs - state.time.milliseconds());

    long expectedDeltaAfterPut = 4 * PUT_RECORD_SIZE;
    long timeInMsAfterPuts = state.time.milliseconds();
    long totalLogSegmentValidSizeAfterPuts =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsAfterPuts, 0L));
    long totalContainerValidSizeAfterPuts = verifyAndGetContainerValidSize(blobStoreStats, timeInMsAfterPuts);
    assertEquals("Put entries with expiry are not properly counted for log segment valid size",
        totalLogSegmentValidSizeAfterPuts, totalLogSegmentValidSizeBeforePuts + expectedDeltaAfterPut);
    assertEquals("Put entries with expiry are not properly counted for container valid size",
        totalContainerValidSizeAfterPuts, totalContainerValidSizeBeforePuts + expectedDeltaAfterPut);

    // advance time to the next second for expiration to take effect
    advanceTimeToNextSecond();

    long expectedDeltaAfterExpiration = PUT_RECORD_SIZE;
    long timeInMsAfterExpiration = state.time.milliseconds();
    long totalLogSegmentValidSizeAfterExpiration =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsAfterExpiration, 0L));
    long totalContainerValidSizeAfterExpiration =
        verifyAndGetContainerValidSize(blobStoreStats, timeInMsAfterExpiration);
    assertEquals("Expired put entries are not properly counted for log segment valid size",
        totalLogSegmentValidSizeAfterExpiration, totalLogSegmentValidSizeBeforePuts + expectedDeltaAfterExpiration);
    assertEquals("Expired put entries are not properly counted for container valid size",
        totalContainerValidSizeAfterExpiration, totalContainerValidSizeBeforePuts + expectedDeltaAfterExpiration);
    blobStoreStats.close();
  }

  /**
   * Tests to verify the correctness of reported stats with after new deletes via the following steps:
   * 1. Add new puts that are going to be deleted later.
   * 2. Verify reported stats and record the total valid size before new deletes.
   * 3. Perform the deletes.
   * 4. Verify reported stats after the deletes but at a time point before the deletes are relevant.
   * 5. Verify reported stats and record the total valid size after the deletes.
   * 6. Verify the delta of total valid size prior to the new deletes and after matches with the expected delta.
   * @throws StoreException
   */
  @Test
  public void testValidDataSizeAfterDeletes() throws StoreException {
    assumeTrue(!bucketingEnabled);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(0, 0);
    int numEntries =
        state.getMaxInMemElements() - state.referenceIndex.lastEntry().getValue().size() + state.getMaxInMemElements()
            - 2;
    state.addPutEntries(numEntries, PUT_RECORD_SIZE, Utils.Infinite_Time);

    long timeInMsBeforeDeletes = state.time.milliseconds();
    long totalLogSegmentValidSizeBeforeDeletes =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsBeforeDeletes, 0L));
    long totalContainerValidSizeBeforeDeletes = verifyAndGetContainerValidSize(blobStoreStats, timeInMsBeforeDeletes);

    // advance time to the next seconds before adding the deletes
    advanceTimeToNextSecond();
    // 2 deletes from the last index segment
    state.addDeleteEntry(state.getIdToDeleteFromIndexSegment(state.referenceIndex.lastKey(), false));
    state.addDeleteEntry(state.getIdToDeleteFromIndexSegment(state.referenceIndex.lastKey(), false));

    long expectedDeltaBeforeDeletesRelevant = 2 * DELETE_RECORD_SIZE;
    long totalLogSegmentValidSizeBeforeDeletesRelevant =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsBeforeDeletes, 0L));
    long totalContainerValidSizeBeforeDeletesRelevant =
        verifyAndGetContainerValidSize(blobStoreStats, timeInMsBeforeDeletes);
    assertEquals("Delete entries are not properly counted for log segment valid size",
        totalLogSegmentValidSizeBeforeDeletesRelevant,
        totalLogSegmentValidSizeBeforeDeletes + expectedDeltaBeforeDeletesRelevant);
    assertEquals("Delete entries are not properly counted for container valid size",
        totalContainerValidSizeBeforeDeletesRelevant, totalContainerValidSizeBeforeDeletes);

    // advance time to the next second for deletes/expiration to take effect
    advanceTimeToNextSecond();

    long timeInMsAfterDeletes = state.time.milliseconds();
    long totalLogSegmentValidSizeAfterDeletes =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsAfterDeletes, 0L));
    long totalContainerValidSizeAfterDeletes = verifyAndGetContainerValidSize(blobStoreStats, timeInMsAfterDeletes);
    long expectedLogSegmentDecrement = 2 * (PUT_RECORD_SIZE - DELETE_RECORD_SIZE);
    long expectedContainerDecrement = 2 * PUT_RECORD_SIZE;
    assertEquals("Delete entries are not properly counted for log segment valid size",
        totalLogSegmentValidSizeAfterDeletes, totalLogSegmentValidSizeBeforeDeletes - expectedLogSegmentDecrement);
    assertEquals("Delete entries are not properly counted for container valid size",
        totalContainerValidSizeAfterDeletes, totalContainerValidSizeBeforeDeletes - expectedContainerDecrement);
    blobStoreStats.close();
  }

  /**
   * Tests to verify the correctness of reported stats with after new undeletes via the following steps:
   * 1. Add new 2 new PUTs and 1 TTL_UPDATE
   * 2. Delete these 2 newly added PUTs
   * 3. Verify reported stats and records the total valid size before new undeletes.
   * 4. Undelete these 2 PUTs.
   * 5. Verify reported stats and record the total valid size after the undeletes.
   * 6. Verify the delta of the total valid size prior to the new undeletes matches the expected value.
   * @throws Exception
   */
  @Test
  public void testValidDataSizeAfterUndeletes() throws Exception {
    assumeTrue(!bucketingEnabled);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(0, 0);

    // Undelete two deleted blob, it will bring two PUT blob back to life.
    List<IndexEntry> puts = state.addPutEntries(2, PUT_RECORD_SIZE, Utils.Infinite_Time);
    advanceTimeToNextSecond();
    MockId firstId = (MockId) puts.get(0).getKey();
    MockId secondId = (MockId) puts.get(1).getKey();
    // Now add a TTL_UPDATE record for first ID
    state.makePermanent(firstId, false);

    // Now delete those two
    state.addDeleteEntry(firstId);
    state.addDeleteEntry(secondId);
    // Make sure deletes are taking effect
    advanceTimeToNextSecond();

    long timeInMsBeforeUndeletes = state.time.milliseconds();
    long totalLogSegmentValidSizeBeforeUndeletes =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsBeforeUndeletes, 0L));
    long totalContainerValidSizeBeforeUndeletes =
        verifyAndGetContainerValidSize(blobStoreStats, timeInMsBeforeUndeletes);

    state.addUndeleteEntry(firstId);
    state.addUndeleteEntry(secondId);

    long timeInMsAfterUndeletes = state.time.milliseconds();
    long expectedContainerIncrement = 2 * PUT_RECORD_SIZE;
    long expectedLogSegmentIncrement =
        2 * PUT_RECORD_SIZE - 2 * DELETE_RECORD_SIZE + 2 * UNDELETE_RECORD_SIZE + TTL_UPDATE_RECORD_SIZE;
    long totalLogSegmentValidSizeAfterUndeletes =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsAfterUndeletes, 0L));
    long totalContainerValidSizeAfterUndeletes = verifyAndGetContainerValidSize(blobStoreStats, timeInMsAfterUndeletes);
    assertEquals("Undelete entries are not properly counted for container valid size",
        totalContainerValidSizeAfterUndeletes, totalContainerValidSizeBeforeUndeletes + expectedContainerIncrement);
    assertEquals("Undelete entries are not properly counted for log segment valid size",
        totalLogSegmentValidSizeAfterUndeletes, totalLogSegmentValidSizeBeforeUndeletes + expectedLogSegmentIncrement);

    // Add another UNDELETE to second ID would not change validSize
    state.addDeleteEntry(secondId, null, (short) 2);
    totalLogSegmentValidSizeAfterUndeletes =
        verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(timeInMsAfterUndeletes, 0L));
    totalContainerValidSizeAfterUndeletes = verifyAndGetContainerValidSize(blobStoreStats, timeInMsAfterUndeletes);
    assertEquals("Undelete entries are not properly counted for container valid size",
        totalContainerValidSizeAfterUndeletes, totalContainerValidSizeBeforeUndeletes + expectedContainerIncrement);
    assertEquals("Undelete entries are not properly counted for log segment valid size",
        totalLogSegmentValidSizeAfterUndeletes, totalLogSegmentValidSizeBeforeUndeletes + expectedLogSegmentIncrement);
    blobStoreStats.close();
  }

  /**
   * Basic test to verify that the {@link BlobStoreStats} can scan the index, populate the buckets and use these buckets
   * to report stats correctly.
   * @throws StoreException
   * @throws InterruptedException
   */
  @Test
  public void testBucketingBasic() throws StoreException, InterruptedException {
    assumeTrue(bucketingEnabled);
    final CountDownLatch scanStartedLatch = new CountDownLatch(1);
    MockThrottler mockThrottler = new MockThrottler(scanStartedLatch, new CountDownLatch(0));
    throttlers.put(BlobStoreStats.IO_SCHEDULER_JOB_TYPE, mockThrottler);
    long logSegmentForecastOffsetMs = state.time.milliseconds();
    int bucketCount = 2 * (int) (logSegmentForecastOffsetMs / BUCKET_SPAN_IN_MS);
    long expiresAtInMs = ((long) bucketCount - 2) * BUCKET_SPAN_IN_MS;

    // add 3 puts with expiry
    state.addPutEntries(3, PUT_RECORD_SIZE, expiresAtInMs);
    // add records so that we will have two consecutive deletes and undeletes for each PUT
    List<IndexEntry> puts = state.addPutEntries(2, PUT_RECORD_SIZE, Utils.Infinite_Time);
    MockId id1 = (MockId) puts.get(0).getKey();
    MockId id2 = (MockId) puts.get(1).getKey();

    state.addDeleteEntry(id1);
    state.addDeleteEntry(id1, null, (short) 1);

    state.addDeleteEntry(id2);
    state.addUndeleteEntry(id2);
    state.addUndeleteEntry(id2, (short) 2);

    int expectedThrottleCount = state.referenceIndex.size();
    long logSegmentForecastStartTimeInMs = state.time.milliseconds() - logSegmentForecastOffsetMs;
    long logSegmentForecastEndTimeInMs = logSegmentForecastStartTimeInMs + bucketCount * BUCKET_SPAN_IN_MS;
    long containerForecastEndTimeInMs = state.time.milliseconds() + bucketCount * BUCKET_SPAN_IN_MS;
    BlobStoreStats blobStoreStats = setupBlobStoreStats(bucketCount, logSegmentForecastOffsetMs);
    // proceed only when the scan is started
    assertTrue("IndexScanner took too long to start", scanStartedLatch.await(5, TimeUnit.SECONDS));
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    for (long i = state.beginningTime; i <= state.time.milliseconds() + TEST_TIME_INTERVAL_IN_MS;
        i += TEST_TIME_INTERVAL_IN_MS) {
      verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(i, 0L));
    }
    // advance time to let the added puts to expire
    long timeToLiveInMs = expiresAtInMs - state.time.milliseconds() < 0 ? 0 : expiresAtInMs - state.time.milliseconds();
    state.advanceTime(timeToLiveInMs + Time.MsPerSec);
    for (long i = state.beginningTime; i <= state.time.milliseconds() + TEST_TIME_INTERVAL_IN_MS;
        i += TEST_TIME_INTERVAL_IN_MS) {
      verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(i, 0L));
    }
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    // advance time near the end of log segment forecast time
    state.advanceTime(logSegmentForecastEndTimeInMs - state.time.milliseconds() - 1);
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), Time.MsPerSec));
    // advance time near the end of container forecast time
    state.advanceTime(containerForecastEndTimeInMs - state.time.milliseconds() - 1);
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    assertEquals("Throttle count mismatch from expected value", expectedThrottleCount,
        mockThrottler.throttleCount.get());
    blobStoreStats.close();
  }

  /**
   * Test to verify new entries after a scan are properly counted to keep the current {@link ScanResults} relevant
   * before the next scan. In addition, verify that requests within the coverage are served without triggering any scan.
   * @throws StoreException
   * @throws InterruptedException
   */
  @Test
  public void testBucketingWithNewEntriesAfterScan() throws StoreException, InterruptedException {
    assumeTrue(bucketingEnabled);
    CountDownLatch scanStartedLatch = new CountDownLatch(1);
    CountDownLatch queueProcessedLatch = new CountDownLatch(1);
    MockThrottler mockThrottler = new MockThrottler(scanStartedLatch, new CountDownLatch(0));
    throttlers.put(BlobStoreStats.IO_SCHEDULER_JOB_TYPE, mockThrottler);
    // add a put that will expire immediately
    state.addPutEntries(1, PUT_RECORD_SIZE, state.time.milliseconds());
    advanceTimeToNextSecond();
    int bucketCount = 2 * (int) (state.time.milliseconds() / BUCKET_SPAN_IN_MS);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(bucketCount, state.time.milliseconds());
    // proceed only when the scan is started
    assertTrue("IndexScanner took too long to start", scanStartedLatch.await(5, TimeUnit.SECONDS));
    advanceTimeToNextSecond();
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0L));
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    int throttleCountBeforeRequests = mockThrottler.throttleCount.get();
    // add 3 put that are expiring within forecast range
    long expiresAtInMs = ((long) bucketCount - 2) * BUCKET_SPAN_IN_MS;
    List<IndexEntry> newPutEntries = state.addPutEntries(3, PUT_RECORD_SIZE, expiresAtInMs);
    // add a put that will expire outside of the forecast coverage
    newPutEntries.addAll(state.addPutEntries(1, PUT_RECORD_SIZE, ((long) bucketCount) * BUCKET_SPAN_IN_MS));
    for (IndexEntry entry : newPutEntries) {
      blobStoreStats.handleNewPutEntry(entry.getValue());
    }
    // add a long expired put
    List<IndexEntry> expiredPutEntries = state.addPutEntries(1, PUT_RECORD_SIZE, 0);
    // add a put that will expire immediately
    expiredPutEntries.addAll(state.addPutEntries(1, PUT_RECORD_SIZE, state.time.milliseconds()));
    for (IndexEntry entry : expiredPutEntries) {
      blobStoreStats.handleNewPutEntry(entry.getValue());
    }
    advanceTimeToNextSecond();
    // delete the first new put that is expiring
    MockId firstPutWithExpiry = getIdToDelete(newPutEntries.get(0).getKey());
    newDelete(blobStoreStats, firstPutWithExpiry);
    // delete the puts that are already expired
    for (IndexEntry entry : expiredPutEntries) {
      newDelete(blobStoreStats, (MockId) entry.getKey());
    }
    // add two blobs that will expired fairly soon, testing TTL_UPDATE and DELETE
    long soonExpiredTimeInMs = state.time.milliseconds() + (10 * BUCKET_SPAN_IN_MS);
    List<IndexEntry> soonExpiredPutEntries = state.addPutEntries(2, PUT_RECORD_SIZE, soonExpiredTimeInMs);
    for (IndexEntry entry : soonExpiredPutEntries) {
      blobStoreStats.handleNewPutEntry(entry.getValue());
      newTtlUpdate(blobStoreStats, (MockId) entry.getKey());
    }
    // Delete first entry
    newDelete(blobStoreStats, getIdToDelete(soonExpiredPutEntries.get(0).getKey()));
    advanceTimeToNextSecond();
    newDelete(blobStoreStats, (MockId) soonExpiredPutEntries.get(0).getKey(), (short) 2);
    advanceTimeToNextSecond();

    // Testing UNDELETE and DELETE
    // P, D, U
    // P, U
    // P, T, D, U
    // P, D, U, T
    // P, T, D, U, U
    // P, D, D, U
    // P, D, U, D, U
    long expiredForUndeleteEntries = state.time.milliseconds() + 100 * BUCKET_SPAN_IN_MS;
    List<IndexEntry> toUndeleteEntries = state.addPutEntries(7, PUT_RECORD_SIZE, expiredForUndeleteEntries);
    for (IndexEntry entry : toUndeleteEntries) {
      blobStoreStats.handleNewPutEntry(entry.getValue());
    }
    MockId id1 = getIdToDelete(toUndeleteEntries.get(0).getKey());
    newDelete(blobStoreStats, id1);
    newUndelete(blobStoreStats, id1);

    MockId id2 = (MockId) toUndeleteEntries.get(1).getKey();
    newUndelete(blobStoreStats, id2, (short) 1);

    MockId id3 = (MockId) toUndeleteEntries.get(2).getKey();
    newTtlUpdate(blobStoreStats, id3);
    newDelete(blobStoreStats, getIdToDelete(id3));
    newUndelete(blobStoreStats, id3);

    MockId id4 = (MockId) toUndeleteEntries.get(3).getKey();
    newDelete(blobStoreStats, getIdToDelete(id4));
    newUndelete(blobStoreStats, id4);
    newTtlUpdate(blobStoreStats, id4);

    MockId id5 = (MockId) toUndeleteEntries.get(4).getKey();
    newTtlUpdate(blobStoreStats, id5);
    newDelete(blobStoreStats, getIdToDelete(id5));
    newUndelete(blobStoreStats, id5);
    newUndelete(blobStoreStats, id5, (short) 2);

    MockId id6 = (MockId) toUndeleteEntries.get(5).getKey();
    newDelete(blobStoreStats, getIdToDelete(id6));
    advanceTimeToNextSecond();
    newDelete(blobStoreStats, id6, (short) 1);
    newUndelete(blobStoreStats, id6);
    advanceTimeToNextSecond();

    MockId id7 = (MockId) toUndeleteEntries.get(6).getKey();
    newDelete(blobStoreStats, getIdToDelete(id7));
    newUndelete(blobStoreStats, id7);
    newDelete(blobStoreStats, id7);
    newUndelete(blobStoreStats, id7);

    // a probe put with a latch to inform us about the state of the queue
    blobStoreStats.handleNewPutEntry(new MockIndexValue(queueProcessedLatch, state.index.getCurrentEndOffset()));
    assertTrue("QueueProcessor took too long to process the new entries",
        queueProcessedLatch.await(Long.MAX_VALUE, TimeUnit.SECONDS));
    for (long i = 0; i <= state.time.milliseconds() + TEST_TIME_INTERVAL_IN_MS; i += TEST_TIME_INTERVAL_IN_MS) {
      verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(i, 0L));
    }
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    // add 3 put with no expiry
    newPutEntries = state.addPutEntries(3, PUT_RECORD_SIZE, Utils.Infinite_Time);
    for (IndexEntry entry : newPutEntries) {
      blobStoreStats.handleNewPutEntry(entry.getValue());
    }
    // delete one of the newly added put
    newDelete(blobStoreStats, state.getIdToDeleteFromIndexSegment(state.referenceIndex.lastKey(), false));
    queueProcessedLatch = new CountDownLatch(1);
    // a probe put with a latch to inform us about the state of the queue
    blobStoreStats.handleNewPutEntry(new MockIndexValue(queueProcessedLatch, state.index.getCurrentEndOffset()));
    assertTrue("QueueProcessor took too long to process the new entries",
        queueProcessedLatch.await(Long.MAX_VALUE, TimeUnit.SECONDS));
    // note: advance time only after all new deletes are added to avoid the scenario where an index segment's last
    // modified time changes after being processed by BlobStoreStats. E.g. if the following operations are made in
    // order: new put, new delete, advance time, new put, etc..., then there is a chance where BlobStoreStats processed
    // the new delete with t1 but CuratedLogIndexState could bump the last modified time to t2 (if the new put after
    // advancing the time is still in the same index segment as the delete).
    advanceTimeToNextSecond();
    for (long i = 0; i <= state.time.milliseconds() + TEST_TIME_INTERVAL_IN_MS; i += TEST_TIME_INTERVAL_IN_MS) {
      verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(i, 0L));
    }
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    // advance time beyond expiration of the blobs and verify no double counting for expiration and delete
    long timeToLiveInMs = expiresAtInMs - state.time.milliseconds() < 0 ? 0 : expiresAtInMs - state.time.milliseconds();
    state.advanceTime(timeToLiveInMs + Time.MsPerSec);
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0L));
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    assertEquals("Throttle count mismatch from expected value", throttleCountBeforeRequests,
        mockThrottler.throttleCount.get());
    blobStoreStats.close();
  }

  /**
   * Test to verify that new entries that got added while scanning are being counted properly. That is, no double
   * counting or miscounting. The behavior is verified via the following steps:
   * 1. Start the scan.
   * 2. Once the scan is started it will be put on hold and various new entries will be added.
   * 3. Resume the scan and wait for the scan to finish the first check point and start working towards the second
   *    checkpoint. The scan is held again once it starts working towards the second checkpoint.
   * 5. More new entries will be added.
   * 6. Resume the scan and wait for all added entries to be processed by the queue processor.
   * 4. Perform various checks to verify the reported stats.
   * @throws StoreException
   * @throws InterruptedException
   */
  @Test
  public void testBucketingWithNewEntriesDuringScan() throws StoreException, InterruptedException {
    assumeTrue(bucketingEnabled);
    CountDownLatch scanStartedLatch = new CountDownLatch(1);
    CountDownLatch scanHoldLatch = new CountDownLatch(1);
    CountDownLatch secondCheckpointLatch = new CountDownLatch(1);
    CountDownLatch secondCheckpointHoldLatch = new CountDownLatch(1);
    int throttleCountAtInterest = state.referenceIndex.size() + 1;
    throttlers.put(BlobStoreStats.IO_SCHEDULER_JOB_TYPE,
        new MockThrottler(scanStartedLatch, scanHoldLatch, secondCheckpointLatch, secondCheckpointHoldLatch,
            throttleCountAtInterest));
    int bucketCount = 2 * (int) (state.time.milliseconds() / BUCKET_SPAN_IN_MS);
    BlobStoreStats blobStoreStats = setupBlobStoreStats(bucketCount, state.time.milliseconds());
    // proceed only when the scan is started
    assertTrue("IndexScanner took too long to start", scanStartedLatch.await(5, TimeUnit.SECONDS));
    // 1 new put that with expiry
    long expiresAtInMs = ((long) bucketCount - 2) * BUCKET_SPAN_IN_MS;
    List<IndexEntry> newPutEntries = state.addPutEntries(1, PUT_RECORD_SIZE, expiresAtInMs);
    // 6 new puts with no expiry
    newPutEntries.addAll(state.addPutEntries(6, PUT_RECORD_SIZE, Utils.Infinite_Time));
    for (IndexEntry entry : newPutEntries) {
      blobStoreStats.handleNewPutEntry(entry.getValue());
    }
    List<MockId> newDeletes = new ArrayList<>();
    // 1 delete from the first index segment
    newDeletes.add(state.getIdToDeleteFromIndexSegment(state.referenceIndex.firstKey(), false));
    // 1 delete from the last index segment
    newDeletes.add(state.getIdToDeleteFromIndexSegment(state.referenceIndex.lastKey(), false));
    for (MockId idToDelete : newDeletes) {
      if (idToDelete != null) {
        newDelete(blobStoreStats, idToDelete);
      }
    }
    // continue the scan towards the first checkpoint
    scanHoldLatch.countDown();
    assertTrue("IndexScanner took too long to start the second checkpoint",
        secondCheckpointLatch.await(5, TimeUnit.SECONDS));
    // add more entries after the second checkpoint is taken
    // 3 new puts with no expiry
    newPutEntries = state.addPutEntries(3, PUT_RECORD_SIZE, Utils.Infinite_Time);
    for (IndexEntry entry : newPutEntries) {
      blobStoreStats.handleNewPutEntry(entry.getValue());
    }
    newDeletes.clear();
    // 1 delete from the last index segment
    newDeletes.add(state.getIdToDeleteFromIndexSegment(state.referenceIndex.lastKey(), false));
    for (MockId idToDelete : newDeletes) {
      if (idToDelete != null) {
        newDelete(blobStoreStats, idToDelete);
      }
    }
    // continue the scan towards the second checkpoint
    secondCheckpointHoldLatch.countDown();
    // a probe put with a latch to inform us about the state of the queue
    CountDownLatch queueProcessedLatch = new CountDownLatch(1);
    blobStoreStats.handleNewPutEntry(new MockIndexValue(queueProcessedLatch, state.index.getCurrentEndOffset()));
    assertTrue("QueueProcessor took too long to process the new entries",
        queueProcessedLatch.await(3, TimeUnit.SECONDS));
    // verifying log segment from beginning time of the state, since most of the deletes and expiration starting from this time.
    for (long i = state.beginningTime; i <= state.time.milliseconds() + TEST_TIME_INTERVAL_IN_MS;
        i += TEST_TIME_INTERVAL_IN_MS) {
      verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(i, 0L));
    }
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    long timeToLiveInMs = expiresAtInMs - state.time.milliseconds() < 0 ? 0 : expiresAtInMs - state.time.milliseconds();
    state.advanceTime(timeToLiveInMs + Time.MsPerSec);
    advanceTimeToNextSecond();
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    blobStoreStats.close();
  }

  /**
   * Tests to verify requests inside and outside of the forecast coverage can still be served properly while scanning.
   * @throws Exception
   */
  @Test
  public void testBucketingCoverageTransition() throws Exception {
    assumeTrue(bucketingEnabled);
    CountDownLatch scanStartedLatch = new CountDownLatch(1);
    // do not hold the initial scan
    MockThrottler mockThrottler = new MockThrottler(scanStartedLatch, new CountDownLatch(0), 50, 100);
    throttlers.put(BlobStoreStats.IO_SCHEDULER_JOB_TYPE, mockThrottler);
    // add a put that is going to expire in 20 seconds
    long expiresAtInMs = state.time.milliseconds() + TimeUnit.SECONDS.toMillis(20);
    state.addPutEntries(1, PUT_RECORD_SIZE, expiresAtInMs);
    long initialScanTimeInMs = state.time.milliseconds();
    BlobStoreStats blobStoreStats = setupBlobStoreStats(1, 0);
    int expectedThrottleCount = state.referenceIndex.size();
    // proceed only when the scan is started
    assertTrue("IndexScanner took too long to start", scanStartedLatch.await(5, TimeUnit.SECONDS));
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(initialScanTimeInMs, 0L));
    verifyAndGetContainerValidSize(blobStoreStats, initialScanTimeInMs);
    state.advanceTime(expiresAtInMs - state.time.milliseconds());
    // hold the next scan
    CountDownLatch scanHoldLatch = new CountDownLatch(1);
    scanStartedLatch = new CountDownLatch(1);
    mockThrottler.holdLatch = scanHoldLatch;
    mockThrottler.startedLatch = scanStartedLatch;
    mockThrottler.isThrottlerStarted = false;
    assertTrue("IndexScanner took too long to start", scanStartedLatch.await(5, TimeUnit.SECONDS));
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(initialScanTimeInMs, 0L));
    verifyAndGetContainerValidSize(blobStoreStats, initialScanTimeInMs);
    // expectedThrottleCount + 1 because the next scan already started and the throttle count is incremented
    assertEquals("Throttle count mismatch from expected value", expectedThrottleCount + 1,
        mockThrottler.throttleCount.get());
    // request something outside of the forecast coverage
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(0L, 0L));
    verifyAndGetContainerValidSize(blobStoreStats, 0L);
    // resume the scan and make a request that will wait for the scan to complete
    scanHoldLatch.countDown();
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0L));
    // first two are from the two bucketing scans and the later three are from the requests that are outside of
    // forecast coverage
    expectedThrottleCount = (2 + 3) * expectedThrottleCount;
    assertEquals("Throttle count mismatch from expected value", expectedThrottleCount,
        mockThrottler.throttleCount.get());
    blobStoreStats.close();
  }

  @Test
  public void testWithLowIndexEntries() throws StoreException, IOException {
    state.destroy();
    assertTrue(tempDir.getAbsolutePath() + " could not be deleted", StoreTestUtils.cleanDirectory(tempDir, true));
    tempDir = StoreTestUtils.createTempDirectory("blobStoreStatsDir-" + TestUtils.getRandomString(10));
    state = new CuratedLogIndexState(true, tempDir, false, false, true, true, false);
    int bucketCount = bucketingEnabled ? 1 : 0;
    BlobStoreStats blobStoreStats = setupBlobStoreStats(bucketCount, 0);
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0));
    blobStoreStats.close();
    state.addPutEntries(3, PUT_RECORD_SIZE, Utils.Infinite_Time);
    blobStoreStats = setupBlobStoreStats(bucketCount, 0);
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0));
    blobStoreStats.close();
  }

  @Test
  public void testBucketingWithEmptyIndexToBegin()
      throws InterruptedException, StoreException, IOException, TimeoutException {
    assumeTrue(bucketingEnabled);
    state.destroy();
    assertTrue(tempDir.getAbsolutePath() + " could not be deleted", StoreTestUtils.cleanDirectory(tempDir, true));
    tempDir = StoreTestUtils.createTempDirectory("blobStoreStatsDir-" + TestUtils.getRandomString(10));
    state = new CuratedLogIndexState(true, tempDir, false, false, true, true, false);
    MockThrottler mockThrottler = new MockThrottler(new CountDownLatch(0), new CountDownLatch(0));
    throttlers.put(BlobStoreStats.IO_SCHEDULER_JOB_TYPE, mockThrottler);
    int bucketCount = 50;
    BlobStoreStats blobStoreStats = setupBlobStoreStats(bucketCount, 0);
    // Make sure the index scanner is finished and we can enqueue
    if (!TestUtils.checkAndSleep(() -> blobStoreStats.isRecentEntryQueueEnabled(), 10000)) {
      throw new TimeoutException("Time out to wait for IndexScanner to finish");
    }
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0));
    long expiresAtInMs = state.time.milliseconds() + ((long) bucketCount - 2) * BUCKET_SPAN_IN_MS;
    // 3 new puts with expiry
    List<IndexEntry> newPutEntries = state.addPutEntries(3, PUT_RECORD_SIZE, expiresAtInMs);
    // 3 new put with no expiry
    newPutEntries.addAll(state.addPutEntries(3, PUT_RECORD_SIZE, Utils.Infinite_Time));
    for (IndexEntry entry : newPutEntries) {
      blobStoreStats.handleNewPutEntry(entry.getValue());
    }
    // delete one of the put with expiry
    MockId putWithExpiry = getIdToDelete(newPutEntries.get(0).getKey());
    newDelete(blobStoreStats, putWithExpiry);
    // delete one of the put without expiry
    MockId putWithoutExpiry = getIdToDelete(newPutEntries.get(newPutEntries.size() - 1).getKey());
    newDelete(blobStoreStats, putWithoutExpiry);
    // a probe put with a latch to inform us about the state of the queue
    CountDownLatch queueProcessedLatch = new CountDownLatch(1);
    blobStoreStats.handleNewPutEntry(new MockIndexValue(queueProcessedLatch, state.index.getCurrentEndOffset()));
    assertTrue("QueueProcessor took too long to process the new entries",
        queueProcessedLatch.await(3, TimeUnit.SECONDS));
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0));
    advanceTimeToNextSecond();
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0));
    long timeToLiveInMs = expiresAtInMs - state.time.milliseconds() < 0 ? 0 : expiresAtInMs - state.time.milliseconds();
    state.advanceTime(timeToLiveInMs + Time.MsPerSec);
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0));
    blobStoreStats.close();
  }

  /**
   * Test to verify that separate scan is triggered to answer the request after waiting for scan timed out.
   * @throws InterruptedException
   * @throws StoreException
   */
  @Test(timeout = 5000)
  public void testBucketingWaitTimeout() throws InterruptedException, StoreException {
    assumeTrue(bucketingEnabled);
    CountDownLatch scanStartedLatch = new CountDownLatch(1);
    CountDownLatch scanHoldLatch = new CountDownLatch(1);
    MockThrottler mockThrottler = new MockThrottler(scanStartedLatch, scanHoldLatch);
    throttlers.put(BlobStoreStats.IO_SCHEDULER_JOB_TYPE, mockThrottler);
    int expectedMinimumThrottleCount = 2 * state.referenceIndex.size();
    BlobStoreStats blobStoreStats =
        new BlobStoreStats("", state.index, 10, BUCKET_SPAN_IN_MS, 0, QUEUE_PROCESSOR_PERIOD_IN_Ms, 1, true, state.time,
            indexScannerScheduler, queueProcessorScheduler, diskIOScheduler, METRICS);
    // proceed only when the scan is started
    assertTrue("IndexScanner took too long to start", scanStartedLatch.await(5, TimeUnit.SECONDS));
    advanceTimeToNextSecond();
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0));
    scanHoldLatch.countDown();
    assertTrue("Throttle count is lower than the expected minimum throttle count",
        mockThrottler.throttleCount.get() >= expectedMinimumThrottleCount);
    blobStoreStats.close();
  }

  /**
   * Test to verify that once the {@link BlobStoreStats} is closed (or closing), requests throw {@link StoreException}.
   */
  @Test(timeout = 1000)
  public void testRequestOnClosing() {
    int bucketCount = bucketingEnabled ? 1 : 0;
    BlobStoreStats blobStoreStats = setupBlobStoreStats(bucketCount, 0);
    blobStoreStats.close();
    try {
      verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
      fail("Expected StoreException thrown upon request when BlobStoreStats is closing");
    } catch (StoreException e) {
      assertEquals("Mismatch on expected error code", StoreErrorCodes.Store_Shutting_Down, e.getErrorCode());
    }
    try {
      verifyAndGetLogSegmentValidSize(blobStoreStats, new TimeRange(state.time.milliseconds(), 0));
      fail("Expected StoreException thrown upon request when BlobStoreStats is closing");
    } catch (StoreException e) {
      assertEquals("Mismatch on expected error code", StoreErrorCodes.Store_Shutting_Down, e.getErrorCode());
    }
  }

  /**
   * Test to verify {@link BlobStoreStats} is resolving the given {@link TimeRange} correctly and the appropriate action
   * is taken. That is, use the readily available {@link ScanResults} to answer the request or trigger a on demand scan.
   * Specifically the following cases are tested (plus cases when boundaries touch each other):
   *    [_______]  [_______]     [________]   [______]              [______]  <--- forecast range
   *  [___]      ,       [___] ,   [___]    ,         [___] , [___]           <--- given time range
   * @throws InterruptedException
   */
  @Test
  public void testTimeRangeResolutionWithStats() throws InterruptedException, StoreException {
    assumeTrue(bucketingEnabled);
    CountDownLatch scanStartedLatch = new CountDownLatch(1);
    MockThrottler mockThrottler = new MockThrottler(scanStartedLatch, new CountDownLatch(0));
    throttlers.put(BlobStoreStats.IO_SCHEDULER_JOB_TYPE, mockThrottler);
    long logSegmentForecastStartTimeMs = state.time.milliseconds();
    // advance time to ensure the log segment forecast start time is > 0
    state.advanceTime(20000);
    long logSegmentForecastEndTimeMs = 10 * BUCKET_SPAN_IN_MS + logSegmentForecastStartTimeMs;
    BlobStoreStats blobStoreStats = setupBlobStoreStats(10, 20000);
    // proceed only when the scan is started
    assertTrue("IndexScanner took too long to start", scanStartedLatch.await(5, TimeUnit.SECONDS));
    // ensure the scan is complete before proceeding
    verifyAndGetContainerValidSize(blobStoreStats, state.time.milliseconds());
    int throttleCountBeforeRequests = mockThrottler.throttleCount.get();
    TimeRange timeRange = new TimeRange(logSegmentForecastStartTimeMs, Time.MsPerSec);
    assertEquals("Unexpected collection time", timeRange.getEndTimeInMs(),
        blobStoreStats.getValidDataSizeByLogSegment(timeRange).getFirst().longValue());
    timeRange = new TimeRange(logSegmentForecastEndTimeMs, Time.MsPerSec);
    assertEquals("Unexpected collection time", logSegmentForecastEndTimeMs - BUCKET_SPAN_IN_MS,
        blobStoreStats.getValidDataSizeByLogSegment(timeRange).getFirst().longValue());
    timeRange = new TimeRange((logSegmentForecastStartTimeMs + logSegmentForecastEndTimeMs) / 2, Time.MsPerSec);
    assertEquals("Unexpected collection time", timeRange.getEndTimeInMs(),
        blobStoreStats.getValidDataSizeByLogSegment(timeRange).getFirst().longValue());
    // time range end time is equal to the start of forecast range
    timeRange = new TimeRange(logSegmentForecastStartTimeMs - Time.MsPerSec, Time.MsPerSec);
    assertEquals("Unexpected collection time", timeRange.getEndTimeInMs(),
        blobStoreStats.getValidDataSizeByLogSegment(timeRange).getFirst().longValue());
    // all previous time range are inside the forecast range
    assertEquals("Throttle count mismatch from expected value", throttleCountBeforeRequests,
        mockThrottler.throttleCount.get());
    // time range start time is equal the end of forecast range (considered to be outside of forecast range)
    timeRange = new TimeRange(logSegmentForecastEndTimeMs + Time.MsPerSec, Time.MsPerSec);
    assertEquals("Unexpected collection time", timeRange.getEndTimeInMs(),
        blobStoreStats.getValidDataSizeByLogSegment(timeRange).getFirst().longValue());
    timeRange = new TimeRange(logSegmentForecastEndTimeMs + TimeUnit.SECONDS.toMillis(5), Time.MsPerSec);
    assertEquals("Unexpected collection time", timeRange.getEndTimeInMs(),
        blobStoreStats.getValidDataSizeByLogSegment(timeRange).getFirst().longValue());
    timeRange = new TimeRange(logSegmentForecastStartTimeMs - TimeUnit.SECONDS.toMillis(5), Time.MsPerSec);
    assertEquals("Unexpected collection time", timeRange.getEndTimeInMs(),
        blobStoreStats.getValidDataSizeByLogSegment(timeRange).getFirst().longValue());
    blobStoreStats.close();
  }

  /**
   * Test the static method that converts the quota stats stored in a nested Map to an {@link StatsSnapshot} object.
   * This test verifies both {@link com.github.ambry.store.BlobStoreStats#convertStoreUsageToAccountStatsSnapshot(Map)} and
   * {@link com.github.ambry.store.BlobStoreStats#convertStoreUsageToContainerStatsSnapshot(Map)}
   */
  @Test
  public void testConvertStoreUsageToStatsSnapshot() {
    Random random = new Random();
    Map<Short, Map<Short, Long>> utilizationMap = new HashMap<>();
    Map<String, StatsSnapshot> accountSubMap = new HashMap<>();
    Map<String, StatsSnapshot> accountContainerPairSubMap = new HashMap<>();

    long total = 0;
    for (int i = 0; i < 10; i++) {
      Map<String, StatsSnapshot> containerSubMap = new HashMap<>();
      Map<Short, Long> innerUtilizationMap = new HashMap<>();
      long subTotal = 0;
      for (int j = 0; j < 3; j++) {
        long randValue = random.nextInt(10000);
        subTotal += randValue;
        innerUtilizationMap.put((short) j, randValue);
        containerSubMap.put(Utils.statsContainerKey((short) j), new StatsSnapshot(randValue, null));
        accountContainerPairSubMap.put(Utils.partitionClassStatsAccountContainerKey((short) i, (short) j),
            new StatsSnapshot(randValue, null));
      }
      total += subTotal;
      utilizationMap.put((short) i, innerUtilizationMap);
      accountSubMap.put(Utils.statsAccountKey((short) i), new StatsSnapshot(subTotal, containerSubMap));
    }
    StatsSnapshot expectAccountSnapshot = new StatsSnapshot(total, accountSubMap);
    StatsSnapshot convertedAccountStatsSnapshot =
        BlobStoreStats.convertStoreUsageToAccountStatsSnapshot(utilizationMap);
    assertEquals("Mismatch between the converted Account StatsSnapshot and expected StatsSnapshot",
        expectAccountSnapshot, convertedAccountStatsSnapshot);
    StatsSnapshot convertedContainerStatsSnapshot =
        BlobStoreStats.convertStoreUsageToContainerStatsSnapshot(utilizationMap);
    StatsSnapshot expectContainerSnapshot = new StatsSnapshot(total, accountContainerPairSubMap);
    assertEquals("Mismatch between the converted Container StatsSnapshot and expected StatsSnapshot",
        expectContainerSnapshot, convertedContainerStatsSnapshot);
  }

  /**
   * Test the getStatsSnapshots method by verifying the returned {@link StatsSnapshot} against the original quota {@link Map}.
   */
  @Test
  public void testGetStatsSnapshots() throws StoreException {
    BlobStoreStats blobStoreStats = setupBlobStoreStats(0, 0);
    List<Short> allAccountIds =
        state.allKeys.keySet().stream().map(mockId -> mockId.getAccountId()).distinct().collect(Collectors.toList());
    assertFalse(allAccountIds.isEmpty());
    short maxAccountId = allAccountIds.stream().max(Comparator.naturalOrder()).get();
    List<Short> accountIdToExclude = null;

    while (true) {
      long deleteAndExpirationRefTimeInMs = state.time.milliseconds();
      Map<Short, Map<Short, Long>> utilizationMap =
          blobStoreStats.getValidDataSizeByContainer(deleteAndExpirationRefTimeInMs);
      Optional.ofNullable(accountIdToExclude).orElse(Collections.EMPTY_LIST).
          forEach(id -> utilizationMap.remove(id));

      // Verify account stats snapshot
      Map<StatsReportType, StatsSnapshot> snapshotsByType =
          blobStoreStats.getStatsSnapshots(EnumSet.of(StatsReportType.ACCOUNT_REPORT), deleteAndExpirationRefTimeInMs,
              accountIdToExclude);
      verifyStatsSnapshots(utilizationMap, snapshotsByType, EnumSet.of(StatsReportType.ACCOUNT_REPORT));
      // Verify partition class stats snapshot
      snapshotsByType = blobStoreStats.getStatsSnapshots(EnumSet.of(StatsReportType.PARTITION_CLASS_REPORT),
          deleteAndExpirationRefTimeInMs, accountIdToExclude);
      verifyStatsSnapshots(utilizationMap, snapshotsByType, EnumSet.of(StatsReportType.PARTITION_CLASS_REPORT));
      // Verify all types of stats snapshots
      Map<StatsReportType, StatsSnapshot> allStatsSnapshots =
          blobStoreStats.getStatsSnapshots(EnumSet.allOf(StatsReportType.class), deleteAndExpirationRefTimeInMs,
              accountIdToExclude);
      verifyStatsSnapshots(utilizationMap, allStatsSnapshots, EnumSet.allOf(StatsReportType.class));
      if (accountIdToExclude == null) {
        accountIdToExclude = Arrays.asList(allAccountIds.get(0), (short) (maxAccountId + 1));
      } else {
        break;
      }
    }
  }

  /**
   * Verify the correctness of specified stats snapshots fetched from {@link BlobStoreStats}
   * @param accountContainerUtilizationMap the map of account to each container quota map. The container quota map presents
   *                                 each container name to its valid data size
   * @param snapshotsByType the map of {@link StatsReportType} to {@link StatsSnapshot} to be verified
   * @param typesToVerify the {@link StatsReportType} to be verified
   */
  private void verifyStatsSnapshots(Map<Short, Map<Short, Long>> accountContainerUtilizationMap,
      Map<StatsReportType, StatsSnapshot> snapshotsByType, EnumSet<StatsReportType> typesToVerify) {
    for (StatsReportType type : typesToVerify) {
      switch (type) {
        case ACCOUNT_REPORT:
          Map<String, StatsSnapshot> accountToSnapshot =
              snapshotsByType.get(StatsReportType.ACCOUNT_REPORT).getSubMap();
          assertEquals("Mismatch on number of accounts for " + StatsReportType.ACCOUNT_REPORT,
              accountContainerUtilizationMap.size(), accountToSnapshot.size());
          for (Map.Entry<Short, Map<Short, Long>> accountToContainerEntry : accountContainerUtilizationMap.entrySet()) {
            Map<Short, Long> containerUtilizationMap = accountToContainerEntry.getValue();
            Map<String, StatsSnapshot> containerToSnapshot =
                accountToSnapshot.get(Utils.statsAccountKey(accountToContainerEntry.getKey())).getSubMap();
            assertEquals("Mismatch on number of containers", containerUtilizationMap.size(),
                containerToSnapshot.size());
            for (Map.Entry<Short, Long> containerEntry : containerUtilizationMap.entrySet()) {

              // Ensure container value and name in ACCOUNT_SNAPSHOT match that in UtilizationMap
              assertNotNull("Expected container: " + containerEntry.getKey() + " doesn't exist",
                  containerToSnapshot.get(Utils.statsContainerKey(containerEntry.getKey())));
              assertEquals("Mismatch on value of container in account snapshot", containerEntry.getValue().longValue(),
                  containerToSnapshot.get(Utils.statsContainerKey(containerEntry.getKey())).getValue());
            }
          }
          break;
        case PARTITION_CLASS_REPORT:
          Map<String, StatsSnapshot> acctContPairToSnapshot =
              snapshotsByType.get(StatsReportType.PARTITION_CLASS_REPORT).getSubMap();
          for (Map.Entry<Short, Map<Short, Long>> accountToContainerEntry : accountContainerUtilizationMap.entrySet()) {
            Map<Short, Long> containerUtilizationMap = accountToContainerEntry.getValue();
            for (Map.Entry<Short, Long> containerEntry : containerUtilizationMap.entrySet()) {
              // Ensure account_container value and name in CONTAINER_SNAPSHOT match that in UtilizationMap
              String accountContainerName =
                  Utils.partitionClassStatsAccountContainerKey(accountToContainerEntry.getKey(),
                      containerEntry.getKey());
              assertNotNull("Expected account_container pair: " + accountContainerName + " doesn't exist",
                  acctContPairToSnapshot.get(accountContainerName));
              assertEquals("Mismatch on value of container in container snapshot",
                  containerEntry.getValue().longValue(), acctContPairToSnapshot.get(accountContainerName).getValue());
            }
          }
          break;
        default:
          fail("Unsupported stats report type!");
      }
    }
    if (typesToVerify.equals(EnumSet.allOf(StatsReportType.class))) {
      // Ensure two snapshots have same aggregated value
      assertEquals("Mismatch on total aggregated value for two snapshots",
          snapshotsByType.get(StatsReportType.PARTITION_CLASS_REPORT).getValue(),
          snapshotsByType.get(StatsReportType.ACCOUNT_REPORT).getValue());
    }
  }

  /**
   * Use the given {@link StoreKey} to get its corresponding {@link MockId} and update various states in
   * {@link CuratedLogIndexState} in preparation for the delete.
   * @param storeKey the {@link StoreKey} to be deleted
   * @return the {@link MockId} to be deleted
   */
  private MockId getIdToDelete(StoreKey storeKey) {
    MockId idToDelete = null;
    for (MockId id : state.liveKeys) {
      if (id.getID().equals(storeKey.getID())) {
        idToDelete = id;
        break;
      }
    }
    if (idToDelete != null) {
      state.deletedKeys.add(idToDelete);
      state.liveKeys.remove(idToDelete);
    }
    return idToDelete;
  }

  /**
   * A helper function that deletes the PUT with the given {@link MockId} and lifeVersion,  and inform
   * {@link BlobStoreStats} about it.
   * @param blobStoreStats the {@link BlobStoreStats} instance to handle the new delete
   * @param idToDelete the {@link MockId} to be deleted
   * @param lifeVersion the lifeVersion of the new delete
   * @throws StoreException
   */
  private void newDelete(BlobStoreStats blobStoreStats, MockId idToDelete, short lifeVersion) throws StoreException {
    state.addDeleteEntry(idToDelete, null, lifeVersion);
    IndexValue currentDelete = state.getExpectedValue(idToDelete, false);
    IndexValue originalPut = state.getExpectedValue(idToDelete, true);
    IndexValue previousValue =
        state.getExpectedValue(idToDelete, EnumSet.complementOf(EnumSet.of(PersistentIndex.IndexEntryType.TTL_UPDATE)),
            new FileSpan(originalPut.getOffset(), currentDelete.getOffset()));
    blobStoreStats.handleNewDeleteEntry(idToDelete, currentDelete, originalPut, previousValue);
  }

  /**
   * A helper function that deletes the PUT with the given {@link MockId} and inform {@link BlobStoreStats} about it.
   * @param blobStoreStats the {@link BlobStoreStats} instance to handle the new delete
   * @param idToDelete the {@link MockId} to be deleted
   * @throws StoreException
   */
  private void newDelete(BlobStoreStats blobStoreStats, MockId idToDelete) throws StoreException {
    state.addDeleteEntry(idToDelete);
    IndexValue currentDelete = state.getExpectedValue(idToDelete, false);
    IndexValue originalPut = state.getExpectedValue(idToDelete, true);
    IndexValue previousValue =
        state.getExpectedValue(idToDelete, EnumSet.complementOf(EnumSet.of(PersistentIndex.IndexEntryType.TTL_UPDATE)),
            new FileSpan(originalPut.getOffset(), currentDelete.getOffset()));
    blobStoreStats.handleNewDeleteEntry(idToDelete, currentDelete, originalPut, previousValue);
  }

  /**
   * A helper function that undeletes the blob with the given {@link MockId} and lifeVersion,  and inform
   * {@link BlobStoreStats} about it.
   * @param blobStoreStats the {@link BlobStoreStats} instance to handle the new undelete
   * @param idToUndelete the {@link MockId} to be undeleted
   * @param lifeVersion the lifeVersion of the new undeleted
   * @throws StoreException
   */
  private void newUndelete(BlobStoreStats blobStoreStats, MockId idToUndelete, short lifeVersion)
      throws StoreException {
    state.addUndeleteEntry(idToUndelete, lifeVersion);
    IndexValue currentUndelete = state.getExpectedValue(idToUndelete, false);
    IndexValue originalPut = state.getExpectedValue(idToUndelete, true);
    IndexValue previousValue = state.getExpectedValue(idToUndelete,
        EnumSet.complementOf(EnumSet.of(PersistentIndex.IndexEntryType.TTL_UPDATE)),
        new FileSpan(originalPut.getOffset(), currentUndelete.getOffset()));
    blobStoreStats.handleNewUndeleteEntry(idToUndelete, currentUndelete, originalPut, previousValue);
  }

  /**
   * A helper function that undeletes the blob with the given {@link MockId} and inform {@link BlobStoreStats} about it.
   * @param blobStoreStats the {@link BlobStoreStats} instance to handle the new undelete
   * @param idToUndelete the {@link MockId} to be undeleted
   * @throws StoreException
   */
  private void newUndelete(BlobStoreStats blobStoreStats, MockId idToUndelete) throws StoreException {
    state.addUndeleteEntry(idToUndelete);
    IndexValue currentUndelete = state.getExpectedValue(idToUndelete, false);
    IndexValue originalPut = state.getExpectedValue(idToUndelete, true);
    IndexValue previousValue = state.getExpectedValue(idToUndelete,
        EnumSet.complementOf(EnumSet.of(PersistentIndex.IndexEntryType.TTL_UPDATE)),
        new FileSpan(originalPut.getOffset(), currentUndelete.getOffset()));
    blobStoreStats.handleNewUndeleteEntry(idToUndelete, currentUndelete, originalPut, previousValue);
  }

  /**
   * A helper function that updates ttl of the blob with the given {@link MockId} and inform {@link BlobStoreStats}
   * about it.
   * @param blobStoreStats the {@link BlobStoreStats} instance to handle the new ttl_update
   * @param idToUpdate the {@link MockId} to be updated
   * @throws StoreException
   */
  private void newTtlUpdate(BlobStoreStats blobStoreStats, MockId idToUpdate) throws StoreException {
    // Get original PUT IndexValue before adding TTL_UPDATE so that original PUT IndexValue would have it's original
    // expiration time.
    IndexValue originalPut = state.getExpectedValue(idToUpdate, true);
    state.makePermanent(idToUpdate, false);
    IndexValue ttlUpdateValue =
        state.getExpectedValue(idToUpdate, EnumSet.of(PersistentIndex.IndexEntryType.TTL_UPDATE), null);
    blobStoreStats.handleNewTtlUpdateEntry(ttlUpdateValue, originalPut);
  }

  /**
   * Advance the time to the next nearest second. That is, 1 sec to 2 sec or 1001 ms to 2000ms.
   */
  private void advanceTimeToNextSecond() {
    long currentTimeInMs = state.time.milliseconds();
    state.advanceTime(Time.MsPerSec - currentTimeInMs % Time.MsPerSec);
  }

  /**
   * Verify the correctness of valid data size information per container returned by BlobStoreStats and return the
   * total valid data size of all containers.
   * @param blobStoreStats the {@link BlobStoreStats} to be verified
   * @param referenceTimeInMs the reference time in ms until which deletes and expiration are relevant
   * @return the total valid data size of all containers (from all serviceIds)
   */
  private long verifyAndGetContainerValidSize(BlobStoreStats blobStoreStats, long referenceTimeInMs)
      throws StoreException {
    Map<String, Pair<AtomicLong, AtomicLong>> deleteTombstoneStats = generateDeleteTombstoneStats();
    Map<Short, Map<Short, Long>> actualContainerValidSizeMap =
        blobStoreStats.getValidDataSizeByContainer(referenceTimeInMs);
    Map<Short, Map<Short, Long>> expectedContainerValidSizeMap =
        getValidSizeByContainer(referenceTimeInMs, state.time.milliseconds(), deleteTombstoneStats);
    long totalValidSize = 0L;

    for (Map.Entry<Short, Map<Short, Long>> expectedContainerValidSizeEntry : expectedContainerValidSizeMap.entrySet()) {
      short accountId = expectedContainerValidSizeEntry.getKey();
      assertTrue("Expected accountId: " + accountId + " not found", actualContainerValidSizeMap.containsKey(accountId));
      Map<Short, Long> innerMap = expectedContainerValidSizeEntry.getValue();
      for (Map.Entry<Short, Long> innerEntry : innerMap.entrySet()) {
        short containerId = innerEntry.getKey();
        assertTrue("Expected containerId: " + containerId + " not found in accountId: " + accountId,
            innerMap.containsKey(containerId));
        long expectedContainerValidSize = innerEntry.getValue();
        long actualContainerValidSize = actualContainerValidSizeMap.get(accountId).get(containerId);
        assertEquals("Valid data size mismatch for accountId: " + accountId + " containerId: " + containerId,
            expectedContainerValidSize, actualContainerValidSize);
        totalValidSize += expectedContainerValidSize;
      }
      if (innerMap.size() != actualContainerValidSizeMap.get(accountId).size()) {
        // make sure all the new items have value 0
        for (Map.Entry<Short, Long> actualContainerEntry : actualContainerValidSizeMap.get(accountId).entrySet()) {
          if (!innerMap.containsKey(actualContainerEntry.getKey())) {
            assertEquals(
                "Expecting 0 value for account id " + accountId + " and container " + actualContainerEntry.getKey(), 0,
                actualContainerEntry.getValue().longValue());
          }
        }
      }
      actualContainerValidSizeMap.remove(accountId);
    }
    for (Map.Entry<Short, Map<Short, Long>> actualContainerValidSizeEntry : actualContainerValidSizeMap.entrySet()) {
      if (actualContainerValidSizeEntry.getValue().size() != 0) {
        for (Map.Entry<Short, Long> mapEntry : actualContainerValidSizeEntry.getValue().entrySet()) {
          assertEquals("Additional values found in actual container valid size map for service "
              + actualContainerValidSizeEntry.getKey(), 0, mapEntry.getValue().longValue());
        }
      }
    }
    // verify delete tombstone stats
    verifyDeleteTombstoneStats(blobStoreStats, deleteTombstoneStats);
    return totalValidSize;
  }

  /**
   * Verify the correctness of valid data size information per log segment returned by BlobStoreStats for a given
   * {@link TimeRange} and return the total valid data size of all log segments.
   * @param blobStoreStats the {@link BlobStoreStats} to be verified
   * @param timeRange the {@link TimeRange} to be used for the verification
   * @return the total valid data size of all log segments
   * @throws StoreException
   */
  private long verifyAndGetLogSegmentValidSize(BlobStoreStats blobStoreStats, TimeRange timeRange)
      throws StoreException {
    long expiryReferenceTime = state.time.milliseconds();
    Pair<Long, NavigableMap<LogSegmentName, Long>> actualLogSegmentValidSizeMap =
        blobStoreStats.getValidDataSizeByLogSegment(timeRange, expiryReferenceTime);
    assertTrue("Valid data size collection time should be in the range",
        timeRange.getStartTimeInMs() <= actualLogSegmentValidSizeMap.getFirst()
            && timeRange.getEndTimeInMs() >= actualLogSegmentValidSizeMap.getFirst());

    int expectedNumberOfLogSegments = 0;
    long expectedTotalLogSegmentValidSize = 0L;
    LogSegment logSegment = state.log.getFirstSegment();
    Pair<Set<MockId>, Set<MockId>> expiredDeletes = new Pair<>(new HashSet<>(), new HashSet<>());
    Map<String, Pair<AtomicLong, AtomicLong>> deleteTombstoneStats = generateDeleteTombstoneStats();
    while (logSegment != null) {
      LogSegmentName logSegmentName = logSegment.getName();
      assertTrue("Log segment: " + logSegmentName + " not found in TimeRange " + timeRange,
          actualLogSegmentValidSizeMap.getSecond().containsKey(logSegmentName));

      long expectedLogSegmentValidSize =
          state.getValidDataSizeForLogSegment(logSegment, timeRange.getEndTimeInMs(), expiryReferenceTime, null,
              deleteTombstoneStats, expiredDeletes, true);
      long actualLogSegmentValidSize = actualLogSegmentValidSizeMap.getSecond().get(logSegmentName);
      assertEquals("Valid data size mismatch for log segment: " + logSegmentName + " in TimeRange " + timeRange,
          expectedLogSegmentValidSize, actualLogSegmentValidSize);

      expectedTotalLogSegmentValidSize += expectedLogSegmentValidSize;
      expectedNumberOfLogSegments++;
      logSegment = state.log.getNextSegment(logSegment);
    }

    assertEquals("Mismatch in number of log segments", expectedNumberOfLogSegments,
        actualLogSegmentValidSizeMap.getSecond().size());

    Pair<Long, Long> actualTotalValidSize = blobStoreStats.getValidSize(timeRange);
    assertTrue("Returned collection time should be in the range",
        timeRange.getStartTimeInMs() <= actualTotalValidSize.getFirst()
            && timeRange.getEndTimeInMs() >= actualLogSegmentValidSizeMap.getFirst());
    assertEquals("Total valid data size of all log segments mismatch", expectedTotalLogSegmentValidSize,
        actualTotalValidSize.getSecond().longValue());
    verifyDeleteTombstoneStats(blobStoreStats, deleteTombstoneStats);
    return actualTotalValidSize.getSecond();
  }

  /**
   * Go over the referenceIndex to collect valid data size information per container. The result is used for
   * verification purposes.
   * @param deleteReferenceTimeInMs the reference time in ms until which deletes are relevant
   * @param expiryReferenceTimeInMs the reference time in ms until which expirations are relevant
   * @param deleteTombstoneStats a hashmap that tracks stats related delete tombstones in log segments.
   * @return a nested {@link Map} of serviceId to containerId to valid data size
   */
  private Map<Short, Map<Short, Long>> getValidSizeByContainer(long deleteReferenceTimeInMs,
      long expiryReferenceTimeInMs, Map<String, Pair<AtomicLong, AtomicLong>> deleteTombstoneStats) {
    Map<Short, Map<Short, Long>> containerValidSizeMap = new HashMap<>();
    Pair<Set<MockId>, Set<MockId>> expiredDeletes = new Pair<>(new HashSet<>(), new HashSet<>());
    for (Offset indSegStartOffset : state.referenceIndex.keySet()) {
      List<IndexEntry> validEntries =
          state.getValidIndexEntriesForIndexSegment(indSegStartOffset, deleteReferenceTimeInMs, expiryReferenceTimeInMs,
              null, deleteTombstoneStats, expiredDeletes, true);
      for (IndexEntry indexEntry : validEntries) {
        IndexValue indexValue = indexEntry.getValue();
        if (indexValue.isPut()) {
          StatsUtils.updateNestedMapHelper(containerValidSizeMap, indexValue.getAccountId(),
              indexValue.getContainerId(), indexValue.getSize());
        }
      }
    }
    return containerValidSizeMap;
  }

  private void verifyDeleteTombstoneStats(BlobStoreStats blobStoreStats,
      Map<String, Pair<AtomicLong, AtomicLong>> deleteTombstoneStats) {
    Map<String, Pair<Long, Long>> storeDeleteStats = blobStoreStats.getDeleteTombstoneStats();
    Pair<Long, Long> expiredDeletes = storeDeleteStats.get(EXPIRED_DELETE_TOMBSTONE);
    Pair<Long, Long> permanentDeletes = storeDeleteStats.get(PERMANENT_DELETE_TOMBSTONE);
    assertEquals("Mismatch in permanent delete count",
        deleteTombstoneStats.get(PERMANENT_DELETE_TOMBSTONE).getFirst().get(), (long) permanentDeletes.getFirst());
    assertEquals("Mismatch in permanent delete total size",
        deleteTombstoneStats.get(PERMANENT_DELETE_TOMBSTONE).getSecond().get(), (long) permanentDeletes.getSecond());
    assertEquals("Mismatch in expired delete count",
        deleteTombstoneStats.get(EXPIRED_DELETE_TOMBSTONE).getFirst().get(), (long) expiredDeletes.getFirst());
    assertEquals("Mismatch in expired delete total size",
        deleteTombstoneStats.get(EXPIRED_DELETE_TOMBSTONE).getSecond().get(), (long) expiredDeletes.getSecond());
  }

  /**
   * Mock {@link Throttler} with latches and a counter to track and control various states during a scan.
   */
  private class MockThrottler extends Throttler {
    final AtomicInteger throttleCount = new AtomicInteger(0);
    volatile boolean isThrottlerStarted = false;
    CountDownLatch startedLatch;
    CountDownLatch holdLatch;
    CountDownLatch throttleCountLatch;
    CountDownLatch throttleCountHoldLatch;
    int throttleCountAtInterest;

    MockThrottler(CountDownLatch startedLatch, CountDownLatch holdLatch, double desiredRatePerSec,
        long checkIntervalMs) {
      super(desiredRatePerSec, checkIntervalMs, true, SystemTime.getInstance());
      this.startedLatch = startedLatch;
      this.holdLatch = holdLatch;
    }

    MockThrottler(CountDownLatch startedLatch, CountDownLatch holdLatch) {
      this(startedLatch, holdLatch, null, null, 0);
    }

    MockThrottler(CountDownLatch startedLatch, CountDownLatch holdLatch, CountDownLatch throttleCountLatch,
        CountDownLatch throttleCountHoldLatch, int throttleCountAtInterest) {
      super(0, 0, true, new MockTime());
      this.startedLatch = startedLatch;
      this.holdLatch = holdLatch;
      this.throttleCountLatch = throttleCountLatch;
      this.throttleCountHoldLatch = throttleCountHoldLatch;
      this.throttleCountAtInterest = throttleCountAtInterest;
    }

    @Override
    public void maybeThrottle(double observed) throws InterruptedException {
      throttleCount.incrementAndGet();
      if (!isThrottlerStarted) {
        isThrottlerStarted = true;
        startedLatch.countDown();
        assertTrue("IndexScanner is held for too long", holdLatch.await(5, TimeUnit.SECONDS));
      }
      if (throttleCountLatch != null && throttleCount.get() == throttleCountAtInterest) {
        throttleCountLatch.countDown();
        assertTrue("IndexScanner is held for too long", throttleCountHoldLatch.await(5, TimeUnit.SECONDS));
      }
    }
  }

  /**
   * Mock {@link IndexValue} with a latch to act as a probe to inform us about the state of the newEntryQueue in
   * {@link BlobStoreStats}.
   */
  private class MockIndexValue extends IndexValue {
    private final CountDownLatch latch;

    MockIndexValue(CountDownLatch latch, Offset offset) {
      super(0, offset, Utils.Infinite_Time, Utils.Infinite_Time, Account.UNKNOWN_ACCOUNT_ID,
          Container.UNKNOWN_CONTAINER_ID);
      this.latch = latch;
    }

    @Override
    long getExpiresAtMs() {
      latch.countDown();
      return super.getExpiresAtMs();
    }
  }
}
