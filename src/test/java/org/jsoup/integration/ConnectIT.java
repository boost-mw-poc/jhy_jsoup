package org.jsoup.integration;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.DataUtil;
import org.jsoup.integration.servlets.EchoServlet;
import org.jsoup.integration.servlets.FileServlet;
import org.jsoup.integration.servlets.SlowRider;
import org.jsoup.internal.SharedConstants;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.StreamParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failsafe integration tests for Connect methods. These take a bit longer to run, so included as Integ, not Unit, tests.
 */
public class ConnectIT {
    @BeforeAll
    public static void setUp() {
        TestServer.start();
        System.setProperty(SharedConstants.UseHttpClient, "false"); // use the default UrlConnection. See HttpClientConnectIT for other version
    }

    // Slow Rider tests.
    @Test
    public void canInterruptBodyStringRead() throws InterruptedException {
        final String[] body = new String[1];
        Thread runner = new Thread(() -> {
            try {
                Connection.Response res = Jsoup.connect(SlowRider.Url)
                    .timeout(15 * 1000)
                    .execute();
                body[0] = res.body();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        });

        runner.start();
        Thread.sleep(1000 * 3);
        runner.interrupt();
        assertTrue(runner.isInterrupted());
        runner.join();

        assertTrue(body[0].length() > 0);
        assertTrue(body[0].contains("<p>Are you still there?"));
    }

    @Test
    public void canInterruptDocumentRead() throws InterruptedException {
        long start = System.currentTimeMillis();
        final String[] body = new String[1];
        Thread runner = new Thread(() -> {
            try {
                Connection.Response res = Jsoup.connect(SlowRider.Url)
                    .timeout(15 * 1000)
                    .execute();
                body[0] = res.parse().text();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        });

        runner.start();
        Thread.sleep(3 * 1000);
        runner.interrupt();
        assertTrue(runner.isInterrupted());
        runner.join();

        long end = System.currentTimeMillis();
        // check we are between 3 and connect timeout seconds (should be just over 3; but allow some slack for slow CI runners)
        assertTrue(end - start > 3 * 1000);
        assertTrue(end - start < 10 * 1000);
    }

    @Test public void canInterruptThenJoinASpawnedThread() throws InterruptedException {
        // https://github.com/jhy/jsoup/issues/1991
        AtomicBoolean ioException = new AtomicBoolean();
        Thread runner = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Document doc  = Jsoup.connect(SlowRider.Url)
                        .timeout(30000)
                        .get();
                }
            } catch (IOException e) {
                ioException.set(true); // don't expect to catch, because the outer sleep will complete before this timeout
            }
        });

        runner.start();
        Thread.sleep(2 * 1000);
        runner.interrupt();
        runner.join();
        assertFalse(ioException.get());
    }

    @Test
    public void totalTimeout() throws IOException {
        int timeout = 3 * 1000;
        long start = System.currentTimeMillis();
        boolean threw = false;
        try {
            Jsoup.connect(SlowRider.Url).timeout(timeout).get();
        } catch (SocketTimeoutException e) {
            long end = System.currentTimeMillis();
            long took = end - start;
            assertTrue(took > timeout, ("Time taken was " + took));
            assertTrue(took < timeout * 1.8, ("Time taken was " + took));
            threw = true;
        }

        assertTrue(threw);
    }

    @Test
    public void slowReadOk() throws IOException {
        // make sure that a slow read that is under the request timeout is still OK
        Document doc = Jsoup.connect(SlowRider.Url)
            .data(SlowRider.MaxTimeParam, "2000") // the request completes in 2 seconds
            .get();

        Element h1 = doc.selectFirst("h1");
        assertEquals("outatime", h1.text());
    }

    @Test void readFullyThrowsOnTimeout() throws IOException {
        // tests that response.readFully excepts on timeout
        boolean caught = false;
        Connection.Response res = Jsoup.connect(SlowRider.Url).timeout(3000).execute();
        try {
            res.readFully();
        } catch (IOException e) {
            caught = true;
        }
        assertTrue(caught);
    }

    @Test void readBodyThrowsOnTimeout() throws IOException {
        // tests that response.readBody excepts on timeout
        boolean caught = false;
        Connection.Response res = Jsoup.connect(SlowRider.Url).timeout(3000).execute();
        try {
            res.readBody();
        } catch (IOException e) {
            caught = true;
        }
        assertTrue(caught);
    }

    @Test void bodyThrowsUncheckedOnTimeout() throws IOException {
        // tests that response.body unchecked excepts on timeout
        boolean caught = false;
        Connection.Response res = Jsoup.connect(SlowRider.Url).timeout(3000).execute();
        try {
            res.body();
        } catch (UncheckedIOException e) {
            caught = true;
        }
        assertTrue(caught);
    }

    @Test
    public void infiniteReadSupported() throws IOException {
        Document doc = Jsoup.connect(SlowRider.Url)
            .timeout(0)
            .data(SlowRider.MaxTimeParam, "2000")
            .get();

        Element h1 = doc.selectFirst("h1");
        assertEquals("outatime", h1.text());
    }

    @Test void streamParserUncheckedExceptionOnTimeoutInStream() throws IOException {
        boolean caught = false;
        try (StreamParser streamParser = Jsoup.connect(SlowRider.Url)
            .data(SlowRider.MaxTimeParam, "10000")
            .data(SlowRider.IntroSizeParam, "8000") // 8K to pass first buffer, or the timeout would occur in execute or streamparser()
            .timeout(4000) // has a 1000 sleep at the start
            .execute()
            .streamParser()) {

            // we should expect to timeout while in stream
            try {
                long count = streamParser.stream().count();
            } catch (Exception e) {
                caught = true;
                UncheckedIOException ioe = (UncheckedIOException) e;
                IOException cause = ioe.getCause();
                //assertInstanceOf(SocketTimeoutException.class, cause); // different JDKs seem to wrap this differently
                assertInstanceOf(IOException.class, cause);

            }
        }
        assertTrue(caught);
    }

    @Test void streamParserCheckedExceptionOnTimeoutInSelect() throws IOException {
        boolean caught = false;
        try (StreamParser streamParser = Jsoup.connect(SlowRider.Url)
            .data(SlowRider.MaxTimeParam, "10000")
            .data(SlowRider.IntroSizeParam, "8000") // 8K to pass first buffer, or the timeout would occur in execute or streamparser()
            .timeout(4000) // has a 1000 sleep at the start
            .execute()
            .streamParser()) {

            // we should expect to timeout while in stream
            try {
                long count = 0;
                while (streamParser.selectNext("p") != null) {
                    count++;
                }
            } catch (IOException e) {
                caught = true;
            }
        }
        assertTrue(caught);
    }

    private static final int LargeHtmlSize = 280735;

    @Test
    public void remainingAfterFirstRead() throws IOException {
        int bufferSize = 5 * 1024;
        int capSize = 100 * 1024;

        String url = FileServlet.urlTo("/htmltests/large.html"); // 280 K

        try (BufferedInputStream stream = Jsoup.connect(url).maxBodySize(capSize)
            .execute().bodyStream()) {

            // simulates parse which does a limited read first
            stream.mark(bufferSize);
            ByteBuffer firstBytes = DataUtil.readToByteBuffer(stream, bufferSize);

            byte[] array = firstBytes.array();
            String firstText = new String(array, StandardCharsets.UTF_8);
            assertTrue(firstText.startsWith("<html><head><title>Large"));
            assertEquals(bufferSize, array.length);

            boolean fullyRead = stream.read() == -1;
            assertFalse(fullyRead);

            // reset and read again
            stream.reset();
            ByteBuffer fullRead = DataUtil.readToByteBuffer(stream, 0);
            byte[] fullArray = fullRead.array();

            // bodyStream is not capped to body size - only for jsoup consumed stream
            assertTrue(fullArray.length > capSize);

            assertEquals(LargeHtmlSize, fullRead.limit());
            String fullText = new String(fullRead.array(), 0, fullRead.limit(), StandardCharsets.UTF_8);
            assertTrue(fullText.startsWith(firstText));
            assertEquals(LargeHtmlSize, fullText.length());
        }
    }

    @Test
    public void noLimitAfterFirstRead() throws IOException {
        int firstMaxRead = 5 * 1024;

        String url = FileServlet.urlTo("/htmltests/large.html"); // 280 K
        try (BufferedInputStream stream = Jsoup.connect(url).execute().bodyStream()) {
            // simulates parse which does a limited read first
            stream.mark(firstMaxRead);
            ByteBuffer firstBytes = DataUtil.readToByteBuffer(stream, firstMaxRead);
            byte[] array = firstBytes.array();
            String firstText = new String(array, StandardCharsets.UTF_8);
            assertTrue(firstText.startsWith("<html><head><title>Large"));
            assertEquals(firstMaxRead, array.length);

            // reset and read fully
            stream.reset();
            ByteBuffer fullRead = DataUtil.readToByteBuffer(stream, 0);
            assertEquals(LargeHtmlSize, fullRead.limit());
            String fullText = new String(fullRead.array(), 0, fullRead.limit(), StandardCharsets.UTF_8);
            assertTrue(fullText.startsWith(firstText));
            assertEquals(LargeHtmlSize, fullText.length());
        }
    }

    @Test public void bodyStreamConstrainedViaReadFully() throws IOException {
        int cap = 5 * 1024;
        String url = FileServlet.urlTo("/htmltests/large.html"); // 280 K
        try (BufferedInputStream stream = Jsoup
            .connect(url)
            .maxBodySize(cap)
            .execute()
            .readFully()
            .bodyStream()) {

            ByteBuffer cappedRead = DataUtil.readToByteBuffer(stream, 0);
            assertEquals(cap, cappedRead.limit());
        }
    }

    @Test public void bodyStreamConstrainedViaBufferUp() throws IOException {
        int cap = 5 * 1024;
        String url = FileServlet.urlTo("/htmltests/large.html"); // 280 K
        try (BufferedInputStream stream = Jsoup
            .connect(url)
            .maxBodySize(cap)
            .execute()
            .bufferUp()
            .bodyStream()) {

            ByteBuffer cappedRead = DataUtil.readToByteBuffer(stream, 0);
            assertEquals(cap, cappedRead.limit());
        }
    }
}
