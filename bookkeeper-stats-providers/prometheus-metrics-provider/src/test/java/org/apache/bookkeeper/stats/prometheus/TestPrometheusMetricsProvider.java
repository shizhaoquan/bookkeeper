/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.bookkeeper.stats.prometheus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.apache.bookkeeper.stats.Counter;
import org.apache.bookkeeper.stats.OpStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.junit.Test;

/**
 * Unit test of {@link PrometheusMetricsProvider}.
 */
public class TestPrometheusMetricsProvider {

    @Test
    public void testCache() {
        PrometheusMetricsProvider provider = new PrometheusMetricsProvider();

        StatsLogger statsLogger =  provider.getStatsLogger("test");

        OpStatsLogger opStatsLogger1 = statsLogger.getOpStatsLogger("optest");
        OpStatsLogger opStatsLogger2 = statsLogger.getOpStatsLogger("optest");
        assertSame(opStatsLogger1, opStatsLogger2);

        Counter counter1 = statsLogger.getCounter("countertest");
        Counter counter2 = statsLogger.getCounter("countertest");
        assertSame(counter1, counter2);

        StatsLogger scope1 = statsLogger.scope("scopetest");
        StatsLogger scope2 = statsLogger.scope("scopetest");
        assertSame(scope1, scope2);
    }

    @Test
    public void testCounter() {
        LongAdderCounter counter = new LongAdderCounter();
        long value = counter.get();
        assertEquals(0L, value);
        counter.inc();
        assertEquals(1L, counter.get().longValue());
        counter.dec();
        assertEquals(0L, counter.get().longValue());
        counter.add(3);
        assertEquals(3L, counter.get().longValue());
    }

    @Test
    public void testTwoCounters() throws Exception {
        PrometheusMetricsProvider provider = new PrometheusMetricsProvider();
        StatsLogger statsLogger =  provider.getStatsLogger("test");

        Counter counter1 = statsLogger.getCounter("counter");
        Counter counter2 = statsLogger.getCounter("counter");
        assertEquals(counter1, counter2);
        assertSame(counter1, counter2);

        assertEquals(1, provider.counters.size());
    }

}
