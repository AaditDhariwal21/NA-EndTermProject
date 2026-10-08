package bhtp;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * Tests that talk to the server with hand-built bytes, so they check the spec
 * rather than "BCurl and BServe agree with each other". No JUnit, just run main().
 */
public class ProtocolTest {

    static final PrintStream OUT = System.out;
    static int passed, failed;

    static void check(String name, boolean ok) {
        OUT.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (ok) passed++; else failed++;
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("bhtp-test");
        Path root = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(tmp.resolve("secret.txt"), "you should not see this");
        Files.writeString(root.resolve("hello.txt"), "hi\n");
        Files.createDirectory(root.resolve("docs"));
        Files.writeString(root.resolve("docs/index.html"), "<h1>docs</h1>");
        Files.write(root.resolve("empty.txt"), new byte[0]);
        byte[] big = new byte[200_000];
        new Random(42).nextBytes(big);
        Files.write(root.resolve("big.bin"), big);

        BServe server = new BServe(root, 0);
        Thread st = new Thread(() -> {
            try { server.serveForever(); } catch (IOException ignored) { }
        });
        st.setDaemon(true);
        st.start();
        int port = server.port();

        // the server logs to System.out; keep that quiet so the results are readable
        System.setOut(quiet());
        OUT.println("server on port " + port);

        OUT.println("-- server");
        try (Raw c = new Raw(port)) {
            check("server answers with its own preface", Arrays.equals(c.readPreface(), Proto.PREFACE));

            Resp r = c.get(1, Proto.GET, "/hello.txt");
            check("GET /hello.txt -> 200", r.status == 200);
            check("body is 'hi\\n'", r.bodyString().equals("hi\n"));
            check("content-type is text/plain", r.headers.get("content-type").startsWith("text/plain"));
            check("content-length is 3", "3".equals(r.headers.get("content-length")));

            // the important one: unknown frame type must be skipped
            c.send(new Frame(0x7f, 0xff, 2, "some future extension".getBytes()));
            r = c.get(3, Proto.GET, "/hello.txt");
            check("unknown frame type 0x7f is skipped, next request still works", r.status == 200);

            // empty payload of an unknown type too
            c.send(new Frame(0xee, 0, 0, new byte[0]));
            r = c.get(4, Proto.GET, "/hello.txt");
            check("empty unknown frame is skipped", r.status == 200);

            r = c.get(5, Proto.GET, "/nope.html");
            check("missing file -> 404", r.status == 404);

            r = c.get(6, Proto.GET, "/../secret.txt");
            check("/../secret.txt -> 403 (no escaping root)", r.status == 403);
            check("...and the secret isn't in the body", !r.bodyString().contains("should not"));

            r = c.get(7, Proto.GET, "/docs/");
            check("directory serves index.html", r.status == 200 && r.bodyString().equals("<h1>docs</h1>"));

            r = c.get(8, 9, "/hello.txt");
            check("unknown method -> 405", r.status == 405);
            check("405 carries an allow header", "GET, HEAD".equals(r.headers.get("allow")));

            r = c.get(9, Proto.HEAD, "/hello.txt");
            check("HEAD -> 200 with no DATA frames", r.status == 200 && r.dataFrames == 0);
            check("HEAD still reports content-length", "3".equals(r.headers.get("content-length")));

            r = c.get(10, Proto.GET, "/empty.txt");
            check("empty file -> END on the RESPONSE frame", r.status == 200 && r.dataFrames == 0);

            r = c.get(11, Proto.GET, "/big.bin");
            check("200000-byte file arrives intact", Arrays.equals(r.body.toByteArray(), big));
            check("...split over several DATA frames (" + r.dataFrames + ")", r.dataFrames > 1);

            r = c.get(12, Proto.GET, "hello.txt");
            check("path without leading '/' -> 400", r.status == 400);

            // malformed: path_len says 200 but only 5 bytes follow
            c.send(new Frame(Proto.REQUEST, Proto.FLAG_END, 13,
                    new byte[] {1, 0, (byte) 200, '/', 'a', 'b', 'c', 'd'}));
            r = c.readResponse(13);
            check("truncated REQUEST -> 400", r.status == 400);

            // malformed header block: value length runs past the end
            c.send(new Frame(Proto.REQUEST, Proto.FLAG_END, 14,
                    new byte[] {1, 0, 1, '/', 1, 0, 50, 'x'}));
            r = c.readResponse(14);
            check("truncated header block -> 400", r.status == 400);

            c.send(new Frame(Proto.REQUEST, Proto.FLAG_END, 15, new byte[0]));
            r = c.readResponse(15);
            check("empty REQUEST payload -> 400", r.status == 400);

            r = c.get(16, Proto.GET, "/hello.txt");
            check("connection still usable after three 400s", r.status == 200);

            r = c.get(0, Proto.GET, "/hello.txt");
            check("stream id 0 -> 400", r.status == 400);

            c.send(new Frame(Proto.DATA, Proto.FLAG_END, 17, "body?".getBytes()));
            r = c.readResponse(17);
            check("DATA from a client -> 400", r.status == 400);

            // header with an index we don't have in our table (from a "newer" client)
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            p.write(Proto.GET);
            p.write(0);
            p.write(10);
            p.writeBytes("/hello.txt".getBytes());
            p.writeBytes(new byte[] {(byte) 200, 0, 3, 'x', 'y', 'z'});   // unknown index 200
            p.writeBytes(new byte[] {0, 5, 'x', '-', 'f', 'o', 'o', 0, 1, '1'}); // literal x-foo: 1
            c.send(new Frame(Proto.REQUEST, Proto.FLAG_END, 18, p.toByteArray()));
            r = c.readResponse(18);
            check("unknown header index + literal header are accepted", r.status == 200);
        }

        try (Raw c = new Raw(port, "GET / HTTP/1.1\r\n\r\n".getBytes())) {
            check("plain HTTP client (bad preface) gets disconnected", c.in.read() == -1);
        }

        OUT.println("-- client");
        clientTests(port, server);

        // client side of the skip rule: a fake server that sends junk frames first
        try (ServerSocket fake = new ServerSocket(0)) {
            Thread ft = new Thread(() -> {
                try (Socket s = fake.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    in.readNBytes(8);
                    Frame req = Frame.read(in);
                    out.write(Proto.PREFACE);
                    new Frame(0x42, 0, 0, "hello from v2".getBytes()).writeTo(out);
                    ByteArrayOutputStream rp = new ByteArrayOutputStream();
                    rp.write(0);
                    rp.write(200);
                    new Frame(Proto.RESPONSE, 0, req.streamId, rp.toByteArray()).writeTo(out);
                    new Frame(0x99, 0, req.streamId, new byte[100]).writeTo(out);
                    new Frame(Proto.DATA, Proto.FLAG_END, req.streamId, "ok".getBytes()).writeTo(out);
                    out.flush();
                    in.read(); // wait for client to hang up
                } catch (IOException ignored) {
                }
            });
            ft.start();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int code = new BCurl(body, quiet()).run(new String[] {"localhost:" + fake.getLocalPort() + "/x"});
            check("bcurl skips unknown frames from the server", code == 0 && body.toString().equals("ok"));
            ft.join(2000);
        }

        try (ServerSocket fake = new ServerSocket(0)) {
            Thread ft = new Thread(() -> {
                try (Socket s = fake.accept()) {
                    s.getInputStream().readNBytes(20);
                    s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                } catch (IOException ignored) {
                }
            });
            ft.start();
            int code = new BCurl(new ByteArrayOutputStream(), quiet())
                    .run(new String[] {"localhost:" + fake.getLocalPort() + "/x"});
            check("bcurl notices it is talking to a non-BHTP server", code == BCurl.EXIT_PROTOCOL);
            ft.join(2000);
        }

        server.close();
        OUT.println();
        OUT.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void clientTests(int port, BServe server) {
        String base = "localhost:" + port;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int code = new BCurl(body, quiet()).run(new String[] {base + "/hello.txt"});
        check("bcurl GET -> exit 0, body on stdout", code == 0 && body.toString().equals("hi\n"));

        code = new BCurl(new ByteArrayOutputStream(), quiet()).run(new String[] {base + "/missing"});
        check("bcurl 404 -> exit 4", code == BCurl.EXIT_4XX);

        int before = server.connectionsAccepted();
        body.reset();
        code = new BCurl(body, quiet()).run(new String[] {base + "/hello.txt", "/docs/", base + "/hello.txt"});
        int used = server.connectionsAccepted() - before;
        check("three paths -> one connection (used " + used + ")", used == 1);
        check("...and all three bodies arrive in order",
                code == 0 && body.toString().equals("hi\n<h1>docs</h1>hi\n"));

        code = new BCurl(new ByteArrayOutputStream(), quiet())
                .run(new String[] {base + "/hello.txt", "otherhost:1234/x"});
        check("bcurl refuses a URL that would need a second connection", code == BCurl.EXIT_USAGE);

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        new BCurl(new ByteArrayOutputStream(), new PrintStream(log)).run(new String[] {"-v", base + "/hello.txt"});
        String v = log.toString();
        check("-v hexdumps the preface and both directions",
                v.contains("> preface") && v.contains("> REQUEST") && v.contains("< RESPONSE") && v.contains("< DATA"));
    }

    static PrintStream quiet() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    // ------------------------------------------------------------ raw test client

    static class Resp {
        int status = -1;
        Headers headers;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int dataFrames;

        String bodyString() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    /** Speaks the protocol by hand, independent of BCurl. */
    static class Raw implements Closeable {
        final Socket s;
        final InputStream in;
        final OutputStream out;

        Raw(int port) throws IOException {
            this(port, Proto.PREFACE);
        }

        Raw(int port, byte[] preface) throws IOException {
            s = new Socket("localhost", port);
            s.setSoTimeout(5000);
            in = s.getInputStream();
            out = s.getOutputStream();
            out.write(preface);
        }

        byte[] readPreface() throws IOException {
            return in.readNBytes(8);
        }

        void send(Frame f) throws IOException {
            f.writeTo(out);
            out.flush();
        }

        Resp get(int id, int method, String path) throws IOException {
            send(BCurl.buildRequest(id, method, path, "localhost"));
            return readResponse(id);
        }

        Resp readResponse(int id) throws IOException {
            Resp r = new Resp();
            while (true) {
                Frame f = Frame.read(in);
                if (f == null)
                    throw new IOException("server hung up");
                if (f.streamId != id)
                    throw new ProtocolException("wrong stream: " + f.streamId + " != " + id);
                if (f.type == Proto.RESPONSE) {
                    ByteBuffer b = ByteBuffer.wrap(f.payload);
                    r.status = b.getShort() & 0xffff;
                    r.headers = Headers.decode(b);
                } else if (f.type == Proto.DATA) {
                    r.body.writeBytes(f.payload);
                    r.dataFrames++;
                }
                if (f.has(Proto.FLAG_END))
                    return r;
            }
        }

        @Override
        public void close() throws IOException {
            s.close();
        }
    }
}
