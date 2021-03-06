/**
 *
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
 *
 */
package org.apache.bookkeeper.bookie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.Sets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.conf.TestBKConfiguration;
import org.apache.bookkeeper.util.DiskChecker;
import org.apache.bookkeeper.util.IOUtils;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for EntryLog.
 */
public class EntryLogTest {
    private static final Logger LOG = LoggerFactory.getLogger(EntryLogTest.class);

    final List<File> tempDirs = new ArrayList<File>();

    File createTempDir(String prefix, String suffix) throws IOException {
        File dir = IOUtils.createTempDir(prefix, suffix);
        tempDirs.add(dir);
        return dir;
    }

    @After
    public void tearDown() throws Exception {
        for (File dir : tempDirs) {
            FileUtils.deleteDirectory(dir);
        }
        tempDirs.clear();
    }

    @Test
    public void testCorruptEntryLog() throws Exception {
        File tmpDir = createTempDir("bkTest", ".dir");
        File curDir = Bookie.getCurrentDirectory(tmpDir);
        Bookie.checkDirectoryStructure(curDir);

        int gcWaitTime = 1000;
        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setJournalDirName(tmpDir.toString());

        conf.setGcWaitTime(gcWaitTime);
        conf.setLedgerDirNames(new String[] {tmpDir.toString()});
        Bookie bookie = new Bookie(conf);
        // create some entries
        EntryLogger logger = ((InterleavedLedgerStorage) bookie.ledgerStorage).entryLogger;
        logger.addEntry(1, generateEntry(1, 1).nioBuffer());
        logger.addEntry(3, generateEntry(3, 1).nioBuffer());
        logger.addEntry(2, generateEntry(2, 1).nioBuffer());
        logger.flush();
        // now lets truncate the file to corrupt the last entry, which simulates a partial write
        File f = new File(curDir, "0.log");
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        raf.setLength(raf.length() - 10);
        raf.close();
        // now see which ledgers are in the log
        logger = new EntryLogger(conf, bookie.getLedgerDirsManager());

        EntryLogMetadata meta = logger.getEntryLogMetadata(0L);
        LOG.info("Extracted Meta From Entry Log {}", meta);
        assertTrue(meta.getLedgersMap().containsKey(1L));
        assertFalse(meta.getLedgersMap().containsKey(2L));
        assertTrue(meta.getLedgersMap().containsKey(3L));
    }

    private static ByteBuf generateEntry(long ledger, long entry) {
        byte[] data = generateDataString(ledger, entry).getBytes();
        ByteBuf bb = Unpooled.buffer(8 + 8 + data.length);
        bb.writeLong(ledger);
        bb.writeLong(entry);
        bb.writeBytes(data);
        return bb;
    }

    private static String generateDataString(long ledger, long entry) {
        return ("ledger-" + ledger + "-" + entry);
    }

    @Test
    public void testMissingLogId() throws Exception {
        File tmpDir = createTempDir("entryLogTest", ".dir");
        File curDir = Bookie.getCurrentDirectory(tmpDir);
        Bookie.checkDirectoryStructure(curDir);

        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setJournalDirName(tmpDir.toString());
        conf.setLedgerDirNames(new String[] {tmpDir.toString()});
        Bookie bookie = new Bookie(conf);
        // create some entries
        int numLogs = 3;
        int numEntries = 10;
        long[][] positions = new long[2 * numLogs][];
        for (int i = 0; i < numLogs; i++) {
            positions[i] = new long[numEntries];

            EntryLogger logger = new EntryLogger(conf,
                    bookie.getLedgerDirsManager());
            for (int j = 0; j < numEntries; j++) {
                positions[i][j] = logger.addEntry(i, generateEntry(i, j).nioBuffer());
            }
            logger.flush();
        }
        // delete last log id
        File lastLogId = new File(curDir, "lastId");
        lastLogId.delete();

        // write another entries
        for (int i = numLogs; i < 2 * numLogs; i++) {
            positions[i] = new long[numEntries];

            EntryLogger logger = new EntryLogger(conf,
                    bookie.getLedgerDirsManager());
            for (int j = 0; j < numEntries; j++) {
                positions[i][j] = logger.addEntry(i, generateEntry(i, j).nioBuffer());
            }
            logger.flush();
        }

        EntryLogger newLogger = new EntryLogger(conf,
                bookie.getLedgerDirsManager());
        for (int i = 0; i < (2 * numLogs + 1); i++) {
            File logFile = new File(curDir, Long.toHexString(i) + ".log");
            assertTrue(logFile.exists());
        }
        for (int i = 0; i < 2 * numLogs; i++) {
            for (int j = 0; j < numEntries; j++) {
                String expectedValue = "ledger-" + i + "-" + j;
                ByteBuf value = newLogger.readEntry(i, j, positions[i][j]);
                long ledgerId = value.readLong();
                long entryId = value.readLong();
                byte[] data = new byte[value.readableBytes()];
                value.readBytes(data);
                value.release();
                assertEquals(i, ledgerId);
                assertEquals(j, entryId);
                assertEquals(expectedValue, new String(data));
            }
        }
    }

    @Test
    /**
     * Test that EntryLogger Should fail with FNFE, if entry logger directories does not exist.
     */
    public void testEntryLoggerShouldThrowFNFEIfDirectoriesDoesNotExist()
            throws Exception {
        File tmpDir = createTempDir("bkTest", ".dir");
        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setLedgerDirNames(new String[] { tmpDir.toString() });
        EntryLogger entryLogger = null;
        try {
            entryLogger = new EntryLogger(conf, new LedgerDirsManager(conf, conf.getLedgerDirs(),
                    new DiskChecker(conf.getDiskUsageThreshold(), conf.getDiskUsageWarnThreshold())));
            fail("Expecting FileNotFoundException");
        } catch (FileNotFoundException e) {
            assertEquals("Entry log directory does not exist", e
                    .getLocalizedMessage());
        } finally {
            if (entryLogger != null) {
                entryLogger.shutdown();
            }
        }
    }

    /**
     * Test to verify the DiskFull during addEntry.
     */
    @Test
    public void testAddEntryFailureOnDiskFull() throws Exception {
        File ledgerDir1 = createTempDir("bkTest", ".dir");
        File ledgerDir2 = createTempDir("bkTest", ".dir");
        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setJournalDirName(ledgerDir1.toString());
        conf.setLedgerDirNames(new String[] { ledgerDir1.getAbsolutePath(),
                ledgerDir2.getAbsolutePath() });
        Bookie bookie = new Bookie(conf);
        EntryLogger entryLogger = new EntryLogger(conf,
                bookie.getLedgerDirsManager());
        InterleavedLedgerStorage ledgerStorage = ((InterleavedLedgerStorage) bookie.ledgerStorage);
        ledgerStorage.entryLogger = entryLogger;
        // Create ledgers
        ledgerStorage.setMasterKey(1, "key".getBytes());
        ledgerStorage.setMasterKey(2, "key".getBytes());
        ledgerStorage.setMasterKey(3, "key".getBytes());
        // Add entries
        ledgerStorage.addEntry(generateEntry(1, 1));
        ledgerStorage.addEntry(generateEntry(2, 1));
        // Add entry with disk full failure simulation
        bookie.getLedgerDirsManager().addToFilledDirs(entryLogger.currentDir);
        ledgerStorage.addEntry(generateEntry(3, 1));
        // Verify written entries
        Assert.assertTrue(0 == generateEntry(1, 1).compareTo(ledgerStorage.getEntry(1, 1)));
        Assert.assertTrue(0 == generateEntry(2, 1).compareTo(ledgerStorage.getEntry(2, 1)));
        Assert.assertTrue(0 == generateEntry(3, 1).compareTo(ledgerStorage.getEntry(3, 1)));
    }

    /**
     * Explicitely try to recover using the ledgers map index at the end of the entry log.
     */
    @Test
    public void testRecoverFromLedgersMap() throws Exception {
        File tmpDir = createTempDir("bkTest", ".dir");
        File curDir = Bookie.getCurrentDirectory(tmpDir);
        Bookie.checkDirectoryStructure(curDir);

        int gcWaitTime = 1000;
        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setJournalDirName(tmpDir.toString());

        conf.setGcWaitTime(gcWaitTime);
        conf.setLedgerDirNames(new String[] {tmpDir.toString()});
        Bookie bookie = new Bookie(conf);

        // create some entries
        EntryLogger logger = ((InterleavedLedgerStorage) bookie.ledgerStorage).entryLogger;
        logger.addEntry(1, generateEntry(1, 1).nioBuffer());
        logger.addEntry(3, generateEntry(3, 1).nioBuffer());
        logger.addEntry(2, generateEntry(2, 1).nioBuffer());
        logger.addEntry(1, generateEntry(1, 2).nioBuffer());
        logger.rollLog();
        logger.flushRotatedLogs();

        EntryLogMetadata meta = logger.extractEntryLogMetadataFromIndex(0L);
        LOG.info("Extracted Meta From Entry Log {}", meta);
        assertEquals(60, meta.getLedgersMap().get(1L));
        assertEquals(30, meta.getLedgersMap().get(2L));
        assertEquals(30, meta.getLedgersMap().get(3L));
        assertFalse(meta.getLedgersMap().containsKey(4L));
        assertEquals(120, meta.getTotalSize());
        assertEquals(120, meta.getRemainingSize());
    }

    /**
     * Explicitely try to recover using the ledgers map index at the end of the entry log.
     */
    @Test
    public void testRecoverFromLedgersMapOnV0EntryLog() throws Exception {
        File tmpDir = createTempDir("bkTest", ".dir");
        File curDir = Bookie.getCurrentDirectory(tmpDir);
        Bookie.checkDirectoryStructure(curDir);

        int gcWaitTime = 1000;
        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setGcWaitTime(gcWaitTime);
        conf.setLedgerDirNames(new String[] { tmpDir.toString() });
        conf.setJournalDirName(tmpDir.toString());
        Bookie bookie = new Bookie(conf);

        // create some entries
        EntryLogger logger = ((InterleavedLedgerStorage) bookie.ledgerStorage).entryLogger;
        logger.addEntry(1, generateEntry(1, 1).nioBuffer());
        logger.addEntry(3, generateEntry(3, 1).nioBuffer());
        logger.addEntry(2, generateEntry(2, 1).nioBuffer());
        logger.addEntry(1, generateEntry(1, 2).nioBuffer());
        logger.rollLog();

        // Rewrite the entry log header to be on V0 format
        File f = new File(curDir, "0.log");
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        raf.seek(EntryLogger.HEADER_VERSION_POSITION);
        // Write zeros to indicate V0 + no ledgers map info
        raf.write(new byte[4 + 8]);
        raf.close();

        // now see which ledgers are in the log
        logger = new EntryLogger(conf, bookie.getLedgerDirsManager());

        try {
            logger.extractEntryLogMetadataFromIndex(0L);
            fail("Should not be possible to recover from ledgers map index");
        } catch (IOException e) {
            // Ok
        }

        // Public method should succeed by falling back to scanning the file
        EntryLogMetadata meta = logger.getEntryLogMetadata(0L);
        LOG.info("Extracted Meta From Entry Log {}", meta);
        assertEquals(60, meta.getLedgersMap().get(1L));
        assertEquals(30, meta.getLedgersMap().get(2L));
        assertEquals(30, meta.getLedgersMap().get(3L));
        assertFalse(meta.getLedgersMap().containsKey(4L));
        assertEquals(120, meta.getTotalSize());
        assertEquals(120, meta.getRemainingSize());
    }

    /**
     * Test pre-allocate for entry log in EntryLoggerAllocator.
     * @throws Exception
     */
    @Test
    public void testPreAllocateLog() throws Exception {
        File tmpDir = createTempDir("bkTest", ".dir");
        File curDir = Bookie.getCurrentDirectory(tmpDir);
        Bookie.checkDirectoryStructure(curDir);

        // enable pre-allocation case
        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setLedgerDirNames(new String[] {tmpDir.toString()});
        conf.setEntryLogFilePreAllocationEnabled(true);
        Bookie bookie = new Bookie(conf);
        // create a logger whose initialization phase allocating a new entry log
        EntryLogger logger = ((InterleavedLedgerStorage) bookie.ledgerStorage).entryLogger;
        assertNotNull(logger.getEntryLoggerAllocator().getPreallocationFuture());

        logger.addEntry(1, generateEntry(1, 1).nioBuffer());
        // the Future<BufferedLogChannel> is not null all the time
        assertNotNull(logger.getEntryLoggerAllocator().getPreallocationFuture());

        // disable pre-allocation case
        ServerConfiguration conf2 = TestBKConfiguration.newServerConfiguration();
        conf2.setLedgerDirNames(new String[] {tmpDir.toString()});
        conf2.setEntryLogFilePreAllocationEnabled(false);
        Bookie bookie2 = new Bookie(conf2);
        // create a logger
        EntryLogger logger2 = ((InterleavedLedgerStorage) bookie2.ledgerStorage).entryLogger;
        assertNull(logger2.getEntryLoggerAllocator().getPreallocationFuture());

        logger2.addEntry(2, generateEntry(1, 1).nioBuffer());

        // the Future<BufferedLogChannel> is null all the time
        assertNull(logger2.getEntryLoggerAllocator().getPreallocationFuture());

    }

    /**
     * Test the getEntryLogsSet() method.
     */
    @Test
    public void testGetEntryLogsSet() throws Exception {
        File tmpDir = createTempDir("bkTest", ".dir");
        File curDir = Bookie.getCurrentDirectory(tmpDir);
        Bookie.checkDirectoryStructure(curDir);

        int gcWaitTime = 1000;
        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setGcWaitTime(gcWaitTime);
        conf.setLedgerDirNames(new String[] { tmpDir.toString() });
        Bookie bookie = new Bookie(conf);

        // create some entries
        EntryLogger logger = ((InterleavedLedgerStorage) bookie.ledgerStorage).entryLogger;

        assertEquals(Sets.newHashSet(0L, 1L), logger.getEntryLogsSet());

        logger.rollLog();
        logger.flushRotatedLogs();

        assertEquals(Sets.newHashSet(0L, 1L, 2L), logger.getEntryLogsSet());

        logger.rollLog();
        logger.flushRotatedLogs();

        assertEquals(Sets.newHashSet(0L, 1L, 2L, 3L), logger.getEntryLogsSet());
    }

    static class LedgerStorageWriteTask implements Callable<Boolean> {
        long ledgerId;
        int entryId;
        LedgerStorage ledgerStorage;

        LedgerStorageWriteTask(long ledgerId, int entryId, LedgerStorage ledgerStorage) {
            this.ledgerId = ledgerId;
            this.entryId = entryId;
            this.ledgerStorage = ledgerStorage;
        }

        @Override
        public Boolean call() throws IOException, BookieException {
            try {
                ledgerStorage.addEntry(generateEntry(ledgerId, entryId));
            } catch (IOException e) {
                LOG.error("Got Exception for AddEntry call. LedgerId: " + ledgerId + " entryId: " + entryId, e);
                throw new IOException("Got Exception for AddEntry call. LedgerId: " + ledgerId + " entryId: " + entryId,
                        e);
            }
            return true;
        }
    }

    static class LedgerStorageFlushTask implements Callable<Boolean> {
        LedgerStorage ledgerStorage;

        LedgerStorageFlushTask(LedgerStorage ledgerStorage) {
            this.ledgerStorage = ledgerStorage;
        }

        @Override
        public Boolean call() throws IOException {
            try {
                ledgerStorage.flush();
            } catch (IOException e) {
                LOG.error("Got Exception for flush call", e);
                throw new IOException("Got Exception for Flush call", e);
            }
            return true;
        }
    }

    static class LedgerStorageReadTask implements Callable<Boolean> {
        long ledgerId;
        int entryId;
        LedgerStorage ledgerStorage;

        LedgerStorageReadTask(long ledgerId, int entryId, LedgerStorage ledgerStorage) {
            this.ledgerId = ledgerId;
            this.entryId = entryId;
            this.ledgerStorage = ledgerStorage;
        }

        @Override
        public Boolean call() throws IOException {
            try {
                ByteBuf expectedByteBuf = generateEntry(ledgerId, entryId);
                ByteBuf actualByteBuf = ledgerStorage.getEntry(ledgerId, entryId);
                if (!expectedByteBuf.equals(actualByteBuf)) {
                    LOG.error("Expected Entry: {} Actual Entry: {}", expectedByteBuf.toString(Charset.defaultCharset()),
                            actualByteBuf.toString(Charset.defaultCharset()));
                    throw new IOException("Expected Entry: " + expectedByteBuf.toString(Charset.defaultCharset())
                            + " Actual Entry: " + actualByteBuf.toString(Charset.defaultCharset()));
                }
            } catch (IOException e) {
                LOG.error("Got Exception for GetEntry call. LedgerId: " + ledgerId + " entryId: " + entryId, e);
                throw new IOException("Got Exception for GetEntry call. LedgerId: " + ledgerId + " entryId: " + entryId,
                        e);
            }
            return true;
        }
    }

    /**
     * test concurrent write operations and then concurrent read
     * operations using InterleavedLedgerStorage.
     */
    @Test
    public void testConcurrentWriteAndReadCallsOfInterleavedLedgerStorage() throws Exception {
        File ledgerDir = createTempDir("bkTest", ".dir");
        ServerConfiguration conf = TestBKConfiguration.newServerConfiguration();
        conf.setJournalDirName(ledgerDir.toString());
        conf.setLedgerDirNames(new String[] { ledgerDir.getAbsolutePath()});
        conf.setLedgerStorageClass(InterleavedLedgerStorage.class.getName());
        Bookie bookie = new Bookie(conf);
        InterleavedLedgerStorage ledgerStorage = ((InterleavedLedgerStorage) bookie.ledgerStorage);
        Random rand = new Random(0);

        int numOfLedgers = 70;
        int numEntries = 1500;
        // Create ledgers
        for (int i = 0; i < numOfLedgers; i++) {
            ledgerStorage.setMasterKey(i, "key".getBytes());
        }

        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Callable<Boolean>> writeAndFlushTasks = new ArrayList<Callable<Boolean>>();
        for (int j = 0; j < numEntries; j++) {
            for (int i = 0; i < numOfLedgers; i++) {
                writeAndFlushTasks.add(new LedgerStorageWriteTask(i, j, ledgerStorage));
            }
        }

        /*
         * add some flush tasks to the list of writetasks list.
         */
        for (int i = 0; i < (numOfLedgers * numEntries) / 500; i++) {
            writeAndFlushTasks.add(rand.nextInt(writeAndFlushTasks.size()), new LedgerStorageFlushTask(ledgerStorage));
        }

        // invoke all those write/flush tasks all at once concurrently
        executor.invokeAll(writeAndFlushTasks).forEach((future) -> {
            try {
                future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.error("Write/Flush task failed because of InterruptedException", ie);
                Assert.fail("Write/Flush task interrupted");
            } catch (Exception ex) {
                LOG.error("Write/Flush task failed because of  exception", ex);
                Assert.fail("Write/Flush task failed " + ex.getMessage());
            }
        });

        List<Callable<Boolean>> readAndFlushTasks = new ArrayList<Callable<Boolean>>();
        for (int j = 0; j < numEntries; j++) {
            for (int i = 0; i < numOfLedgers; i++) {
                readAndFlushTasks.add(new LedgerStorageReadTask(i, j, ledgerStorage));
            }
        }

        /*
         * add some flush tasks to the list of readtasks list.
         */
        for (int i = 0; i < (numOfLedgers * numEntries) / 500; i++) {
            readAndFlushTasks.add(rand.nextInt(readAndFlushTasks.size()), new LedgerStorageFlushTask(ledgerStorage));
        }

        // invoke all those read/flush tasks all at once concurrently
        executor.invokeAll(readAndFlushTasks).forEach((future) -> {
            try {
                future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.error("Read/Flush task failed because of InterruptedException", ie);
                Assert.fail("Read/Flush task interrupted");
            } catch (Exception ex) {
                LOG.error("Read/Flush task failed because of  exception", ex);
                Assert.fail("Read/Flush task failed " + ex.getMessage());
            }
        });

        executor.shutdownNow();
    }
}
