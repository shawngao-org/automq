/*
 * Copyright 2025, AutoMQ HK Limited.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.stream.s3.operator;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TokensInheritanceStrategy;

/**
 * A limiter that uses Bucker4j to limit the rate of network traffic.
 */
class TrafficRateLimiter {

    /**
     * The maximum rate of refilling the Bucker4j bucket, which is 1 token per nanosecond.
     */
    static final long MAX_BUCKET_TOKENS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    /**
     * The bucket used to limit the rate of network traffic in kilobytes per second.
     * Maximum rate is 1 token per nanosecond, which is 1 TB/s and can be regarded as unlimited.
     */
    private final Bucket bucket;
    /**
     * The scheduler used to schedule the rate limiting tasks.
     * It should be shutdown outside of this class.
     */
    private final ScheduledExecutorService scheduler;

    private long currentRate;

    /**
     * Create a limiter without limiting (actually a limit of 1 TB/s).
     */
    public TrafficRateLimiter(ScheduledExecutorService scheduler) {
        this(scheduler, Long.MAX_VALUE);
    }

    public TrafficRateLimiter(ScheduledExecutorService scheduler, long bytesPerSecond) {
        this.currentRate = toKbps(bytesPerSecond);
        this.bucket = Bucket.builder()
            .addLimit(limit -> limit
                .capacity(currentRate)
                .refillGreedy(currentRate, Duration.ofSeconds(1))
            ).build();
        this.scheduler = scheduler;
    }

    public long currentRate() {
        return toBps(currentRate);
    }

    /**
     * Update the rate of the limiter.
     * Note: The minimum rate is 1 KB/s and the maximum rate is 1 TB/s, any value outside this range will be
     * clamped to this range.
     * Note: An update will not take effect on the previous {@link this#consume} calls. For example, if the
     * previous rate is 1 MB/s and the new rate is 10 MB/s, the previous {@link this#consume} calls will
     * still be limited to 1 MB/s.
     * Note：this method is not thread-safe.
     */
    public void update(long bytesPerSecond) {
        currentRate = toKbps(bytesPerSecond);
        bucket.replaceConfiguration(BucketConfiguration.builder()
                .addLimit(limit -> limit
                    .capacity(currentRate)
                    .refillGreedy(currentRate, Duration.ofSeconds(1))
                ).build(),
            TokensInheritanceStrategy.PROPORTIONALLY
        );
    }

    /**
     * Consume the specified number of bytes and return a CompletableFuture that will be completed when the
     * tokens are consumed.
     * Note: DO NOT perform any heavy operations in the callback, otherwise it will block the scheduler.
     */
    public CompletableFuture<Void> consume(long bytes) {
        return bucket.asScheduler().consume(toKbps(bytes), scheduler);
    }

    private static long toKbps(long bps) {
        long kbps = bps >> 10;
        kbps = Math.min(kbps, MAX_BUCKET_TOKENS_PER_SECOND);
        kbps = Math.max(kbps, 1L);
        return kbps;
    }

    private static long toBps(long kbps) {
        return kbps << 10;
    }
}
