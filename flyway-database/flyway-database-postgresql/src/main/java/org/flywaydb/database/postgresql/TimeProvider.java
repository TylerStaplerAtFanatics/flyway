/*-
 * ========================LICENSE_START=================================
 * flyway-database-postgresql
 * ========================================================================
 * Copyright (C) 2010 - 2025 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.flywaydb.database.postgresql;

import java.time.Instant;

/**
 * Abstraction for time operations to enable testing without wall-clock delays.
 * Production code uses {@link SystemTimeProvider}.
 * Test code can use {@link ControllableTimeProvider} to fast-forward time.
 */
public interface TimeProvider {

    /**
     * Returns the current instant.
     */
    Instant now();

    /**
     * Sleeps for the specified number of milliseconds.
     *
     * @param millis milliseconds to sleep
     * @throws InterruptedException if interrupted while sleeping
     */
    void sleep(long millis) throws InterruptedException;

    /**
     * System time provider that uses actual wall clock time.
     */
    class SystemTimeProvider implements TimeProvider {
        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    }

    /**
     * Controllable time provider for testing.
     * Allows instant time advancement without wall-clock delays.
     */
    class ControllableTimeProvider implements TimeProvider {
        private Instant currentTime;

        public ControllableTimeProvider(Instant startTime) {
            this.currentTime = startTime;
        }

        public ControllableTimeProvider() {
            this(Instant.now());
        }

        @Override
        public synchronized Instant now() {
            return currentTime;
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            // Don't actually sleep - just advance time
            advance(millis);
        }

        /**
         * Advances time by the specified number of milliseconds.
         * This is instant and doesn't block.
         */
        public synchronized void advance(long millis) {
            currentTime = currentTime.plusMillis(millis);
        }

        /**
         * Advances time by the specified number of seconds.
         */
        public void advanceSeconds(long seconds) {
            advance(seconds * 1000);
        }

        /**
         * Sets the current time to a specific instant.
         */
        public synchronized void setTime(Instant time) {
            currentTime = time;
        }

        /**
         * Resets time to the current system time.
         */
        public synchronized void reset() {
            currentTime = Instant.now();
        }
    }
}
