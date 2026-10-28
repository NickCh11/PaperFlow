package com.paperflow.translation;

import java.io.*;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real loopback TLS and files: no external service or production trust changes. */
public class OfflineRuntimeTransferTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private SSLSocketFactory previous;
    private SSLContext tls;
    private static SSLContext localTls;
    private final List<Server> servers = new ArrayList<>();

    @BeforeClass public static void createAnEphemeralLocalTlsIdentity() throws Exception {
        java.nio.file.Path certificate = Files.createTempFile("paperflow-loopback-tls-", ".p12");
        KeyStore store = KeyStore.getInstance("PKCS12");
        char[] password = "paperflow-test-only".toCharArray();
        try {
            Files.delete(certificate); // keytool requires a missing destination, not an empty store.
            String executable = new File(System.getProperty("java.home"), "bin/keytool" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).getPath();
            Process keytool = new ProcessBuilder(executable, "-genkeypair", "-alias", "local-test-server", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", certificate.toString(), "-storepass", new String(password), "-keypass", new String(password), "-noprompt").redirectErrorStream(true).start();
            try {
                assertTrue("Local TLS identity generation timed out", keytool.waitFor(15, TimeUnit.SECONDS));
                assertEquals(new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8), 0, keytool.exitValue());
            } finally { keytool.destroyForcibly(); }
            try (InputStream input = Files.newInputStream(certificate)) { store.load(input, password); }
        } finally {
            Files.deleteIfExists(certificate);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        localTls = SSLContext.getInstance("TLS"); localTls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    }

    @Before public void trustOnlyTheLocalFixtureDuringThisTest() {
        tls = localTls;
        previous = HttpsURLConnection.getDefaultSSLSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
    }

    @After public void restoreTrustAndCloseEveryConnection() throws Exception {
        HttpsURLConnection.setDefaultSSLSocketFactory(previous);
        for (Server server : servers) server.close();
        Thread.interrupted();
    }

    private Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = OfflineRuntime.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw new AssertionError(error.getCause());
        }
    }

    private void transfer(String url, File file, boolean gzip, long size, BooleanSupplier cancel, List<Long> progress) throws Exception {
        Class<?> callback = Class.forName("com.paperflow.translation.OfflineRuntime$ByteProgress");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> { progress.add((Long) args[0]); return null; });
        invoke("transfer", new Class<?>[]{String.class, File.class, boolean.class, long.class, BooleanSupplier.class, callback}, url, file, gzip, size, cancel, listener);
    }

    private Server server(byte[] body, int failures, boolean block) throws Exception {
        Server server = new Server(body, failures, block); servers.add(server); return server;
    }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test public void plainAndGzipDownloadsPreserveBytesAndReportNetworkProgress() throws Exception {
        byte[] payload = bytes("offline language fixture Ελληνικά 日本語\n".repeat(2000));
        for (boolean gzip : new boolean[]{false, true}) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (gzip) try (GZIPOutputStream output = new GZIPOutputStream(encoded)) { output.write(payload); }
            byte[] network = gzip ? encoded.toByteArray() : payload;
            Server source = server(network, 0, false); File target = temporary.newFile(); List<Long> progress = new ArrayList<>();
            transfer(source.url(), target, gzip, network.length, () -> false, progress);
            assertArrayEquals(payload, Files.readAllBytes(target.toPath()));
            assertEquals(1, source.attempts.get()); assertFalse(progress.isEmpty());
            assertTrue(progress.get(progress.size() - 1) <= network.length);
            assertTrue(source.headers.toString(), source.headers.toString().toLowerCase(Locale.ROOT).contains("accept-encoding: identity"));
        }
    }

    @Test public void transientServerFailuresRetryAndThenCommitTheSuccessfulPayload() throws Exception {
        byte[] payload = bytes("complete third attempt"); Server source = server(payload, 2, false); File target = temporary.newFile();
        transfer(source.url(), target, false, payload.length, () -> false, new ArrayList<>());
        assertEquals(3, source.attempts.get()); assertArrayEquals(payload, Files.readAllBytes(target.toPath()));
    }

    @Test public void exhaustedHttpFailuresRemovePartialFiles() throws Exception {
        Server source = server(bytes("unavailable"), 3, false); File target = temporary.newFile(); Files.write(target.toPath(), bytes("partial old attempt"));
        IOException error = assertThrows(IOException.class, () -> transfer(source.url(), target, false, 0, () -> false, new ArrayList<>()));
        assertTrue(error.getMessage(), error.getMessage().contains("HTTP 503"));
        assertEquals(3, source.attempts.get()); assertFalse(target.exists());
    }

    @Test public void emptyTruncatedAndInvalidGzipDownloadsNeverRemainInstalled() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Server source = server(kind == 0 ? new byte[0] : bytes("not-a-complete-package"), 0, false);
            File target = temporary.newFile(); final boolean gzip = kind == 2; final long expected = kind == 1 ? 999 : 0;
            IOException error = assertThrows(IOException.class, () -> transfer(source.url(), target, gzip, expected, () -> false, new ArrayList<>()));
            if (kind < 2) assertTrue(error.getMessage(), error.getMessage().contains(kind == 0 ? "empty" : "size mismatch"));
            assertFalse(target.exists()); assertEquals(3, source.attempts.get());
        }
    }

    @Test public void insecureSourcesAndAlreadyCancelledDownloadsDoNotOpenASocket() throws Exception {
        File target = temporary.newFile();
        IOException insecure = assertThrows(IOException.class, () -> transfer("http://127.0.0.1:1/model", target, false, 0, () -> false, new ArrayList<>()));
        assertTrue(insecure.getMessage().contains("HTTPS")); assertFalse(target.exists());
        Server source = server(bytes("unused"), 0, false);
        assertThrows(InterruptedIOException.class, () -> transfer(source.url(), target, false, 0, () -> true, new ArrayList<>()));
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, () -> transfer(source.url(), target, false, 0, () -> false, new ArrayList<>())); }
        finally { Thread.interrupted(); }
        assertEquals(0, source.attempts.get());
    }

    @Test public void cancellingABlockedDownloadDisconnectsItAndRemovesThePartialFile() throws Exception {
        Server source = server(bytes("partial"), 0, true); File target = temporary.newFile(); AtomicBoolean cancel = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> {
                try { transfer(source.url(), target, false, 100000, cancel::get, new ArrayList<>()); throw new AssertionError("Cancelled transfer succeeded"); }
                catch (InterruptedIOException expected) { return; }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(source.sent.await(5, TimeUnit.SECONDS)); cancel.set(true);
            task.get(5, TimeUnit.SECONDS); assertFalse(target.exists()); assertEquals(1, source.attempts.get());
        } finally { source.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void storageHelpersRejectTraversalAndFilesWhereDirectoriesAreRequired() throws Exception {
        File root = temporary.newFolder();
        assertEquals(new File(root, "nested/model"), invoke("resolve", new Class<?>[]{File.class, String.class}, root, "nested/model"));
        for (String path : new String[]{"../outside", ".", "nested/../../outside"}) assertThrows(IOException.class, () -> invoke("resolve", new Class<?>[]{File.class, String.class}, root, path));
        File directory = new File(root, "new/nested");
        invoke("ensureDirectory", new Class<?>[]{File.class}, directory); invoke("ensureDirectory", new Class<?>[]{File.class}, directory);
        File file = new File(directory, "model"); Files.write(file.toPath(), bytes("model"));
        assertThrows(IOException.class, () -> invoke("ensureDirectory", new Class<?>[]{File.class}, file));
        invoke("deleteTree", new Class<?>[]{File.class}, root); assertFalse(root.exists());
        invoke("deleteTree", new Class<?>[]{File.class}, root);
    }

    @Test public void streamCopyHonorsCancellationAndCountsBothReadForms() throws Exception {
        Class<?> callback = Class.forName("com.paperflow.translation.OfflineRuntime$ByteProgress"); List<Long> progress = new ArrayList<>();
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> { progress.add((Long) args[0]); return null; });
        byte[] payload = bytes("exact copied payload"); ByteArrayOutputStream output = new ByteArrayOutputStream();
        invoke("copy", new Class<?>[]{InputStream.class, OutputStream.class, BooleanSupplier.class, callback}, new ByteArrayInputStream(payload), output, (BooleanSupplier) () -> false, listener);
        assertArrayEquals(payload, output.toByteArray()); assertEquals(Collections.singletonList((long) payload.length), progress);
        output.reset();
        assertThrows(InterruptedIOException.class, () -> invoke("copy", new Class<?>[]{InputStream.class, OutputStream.class, BooleanSupplier.class, callback}, new ByteArrayInputStream(payload), output, (BooleanSupplier) () -> true, null));
        assertEquals(0, output.size());
        Class<?> counter = Class.forName("com.paperflow.translation.OfflineRuntime$CountingInputStream");
        Constructor<?> constructor = counter.getDeclaredConstructor(InputStream.class, callback); constructor.setAccessible(true);
        try (InputStream input = (InputStream) constructor.newInstance(new ByteArrayInputStream(new byte[]{7, 8, 9}), listener)) {
            assertEquals(7, input.read()); assertEquals(2, input.read(new byte[8], 0, 8)); assertEquals(-1, input.read()); assertEquals(-1, input.read(new byte[8], 0, 8));
            Field count = counter.getDeclaredField("count"); count.setAccessible(true); assertEquals(3, count.getLong(input));
        }
    }

    private final class Server implements AutoCloseable {
        final SSLServerSocket socket;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch sent = new CountDownLatch(1);
        final StringBuilder headers = new StringBuilder();
        volatile Socket active;
        final Future<?> task;
        Server(byte[] body, int failures, boolean block) throws Exception {
            socket = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            task = worker.submit(() -> {
                try {
                    while (!socket.isClosed()) try (Socket client = socket.accept()) {
                        active = client; client.setSoTimeout(10000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String line; while ((line = reader.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
                        int attempt = attempts.incrementAndGet(); int status = attempt <= failures ? 503 : 200;
                        OutputStream output = client.getOutputStream();
                        output.write(bytes("HTTP/1.1 " + status + " Fixture\r\nContent-Length: " + (block ? 100000 : body.length) + "\r\nConnection: close\r\n\r\n")); output.write(body); output.flush(); sent.countDown();
                        if (block) while (client.getInputStream().read() != -1) { }
                    }
                } catch (IOException error) { if (!socket.isClosed()) throw new UncheckedIOException(error); }
            });
        }
        String url() { return "https://127.0.0.1:" + socket.getLocalPort() + "/package"; }
        @Override public void close() throws Exception {
            socket.close(); if (active != null) active.close(); worker.shutdownNow();
            assertTrue("TLS fixture worker leaked", worker.awaitTermination(5, TimeUnit.SECONDS));
            try { task.get(); } catch (ExecutionException error) { throw new AssertionError(error.getCause()); }
        }
    }
}
