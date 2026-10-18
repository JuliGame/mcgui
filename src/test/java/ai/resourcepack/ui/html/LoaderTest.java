package ai.resourcepack.ui.html;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private String base;

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/small", ex -> {
            requests.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/huge", ex -> {
            requests.incrementAndGet();
            // Chunked, so no Content-Length tells the client in advance.
            ex.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[1 << 20];
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 20; i++) out.write(chunk);
            } catch (IOException e) {
                // The client hung up, as it should.
            }
        });
        server.createContext("/gone", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void privateAddressesNeedTheSetting() {
        assertNull(new Page.Loader(true, null, false, null).fetch(URI.create(base + "/small"), null));
        assertNull(new Page.Loader(true, null, false, null).fetch(URI.create("http://localhost:" + server.getAddress().getPort() + "/small"), null));
        assertEquals(0, requests.get());
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), new Page.Loader(true, null, true, null).fetch(URI.create(base + "/small"), null));
    }

    /** An IPv6 address as DNS would hand it back: Inet6Address.getByAddress does not unwrap an IPv4 inside, as InetAddress.getByAddress would. */
    private static InetAddress v6(int... bytes) throws IOException {
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) bytes[i];
        return Inet6Address.getByAddress(null, b, -1);
    }

    @Test
    void ipv4InsideIpv6IsJudgedAsIpv4() throws IOException {
        // ::ffff:127.0.0.1, ::ffff:169.254.169.254, ::10.0.0.1, 64:ff9b::192.168.1.1
        assertFalse(Page.Loader.publicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 127, 0, 0, 1)));
        assertFalse(Page.Loader.publicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 169, 254, 169, 254)));
        assertFalse(Page.Loader.publicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)));
        assertFalse(Page.Loader.publicAddress(v6(0, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 192, 168, 1, 1)));
        // The same wrappings round public addresses stay public, as does an ordinary global IPv6 address.
        assertTrue(Page.Loader.publicAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 8, 8, 8, 8)));
        assertTrue(Page.Loader.publicAddress(v6(0, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)));
        assertTrue(Page.Loader.publicAddress(v6(0x26, 0x06, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x68, 0x10, 0x84, 0xe5)));
        assertFalse(Page.Loader.publicAddress(v6(0xFD, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)));
    }

    @Test
    void bodiesOverTheCapAreDropped() {
        assertNull(new Page.Loader(true, null, true, null).fetch(URI.create(base + "/huge"), null));
    }

    @Test
    void aFailedUrlIsNotAskedForAgainAtOnce() {
        Page.Loader loader = new Page.Loader(true, null, true, null);
        assertNull(loader.fetch(URI.create(base + "/gone"), null));
        assertNull(loader.fetch(URI.create(base + "/gone"), null));
        assertEquals(1, requests.get());
    }

    @Test
    void filesOnlyFromTheRoot(@TempDir Path dir) throws IOException {
        Path pages = Files.createDirectories(dir.resolve("pages"));
        Files.writeString(pages.resolve("a.css"), "a{}");
        Files.writeString(dir.resolve("secret.txt"), "no");
        Page.Loader loader = new Page.Loader(false, null, false, pages);
        assertArrayEquals("a{}".getBytes(StandardCharsets.UTF_8), loader.fetch(pages.resolve("a.css").toUri(), null));
        assertNull(loader.fetch(pages.toUri().resolve("../secret.txt"), null));
        assertNull(loader.fetch(dir.resolve("secret.txt").toUri(), null));
    }
}
