package bhtp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * bserve - static file server for BHTP/1.
 *
 *   usage: bserve <root-dir> <port>
 *
 * One thread per connection. Each connection handles requests one after
 * another until the client hangs up or goes quiet for IDLE_TIMEOUT_MS.
 */
public class BServe {
    static final int IDLE_TIMEOUT_MS = 30_000;
    static final String SERVER_NAME = "bserve/1.0";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("json", "application/json"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("pdf", "application/pdf"));

    /** Thrown while handling a request when we've decided to answer with an error status. */
    private static class Reject extends Exception {
        final int status;

        Reject(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    private final Path root;
    private final ServerSocket listener;
    private final AtomicInteger connections = new AtomicInteger();

    public BServe(Path root, int port) throws IOException {
        this.root = root.toRealPath();
        this.listener = new ServerSocket(port);
    }

    public int port() {
        return listener.getLocalPort();
    }

    public int connectionsAccepted() {
        return connections.get();
    }

    public void close() throws IOException {
        listener.close();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: bserve <root-dir> <port>");
            System.exit(1);
        }
        Path root = Paths.get(args[0]);
        if (!Files.isDirectory(root)) {
            System.err.println("bserve: " + args[0] + " is not a directory");
            System.exit(1);
        }
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("bserve: bad port '" + args[1] + "'");
            System.exit(1);
            return;
        }

        BServe server = new BServe(root, port);
        System.out.println("bserve: serving " + server.root + " on port " + server.port());
        server.serveForever();
    }

    public void serveForever() throws IOException {
        while (!listener.isClosed()) {
            Socket sock;
            try {
                sock = listener.accept();
            } catch (IOException e) {
                if (listener.isClosed())
                    return;
                throw e;
            }
            int n = connections.incrementAndGet();
            Thread t = new Thread(() -> handleConnection(sock, n), "conn-" + n);
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------------------------------------------------------------- connection

    private void handleConnection(Socket sock, int connNo) {
        String who = "conn#" + connNo + " " + sock.getRemoteSocketAddress();
        log(who, "connected");
        int served = 0;
        try (sock) {
            sock.setSoTimeout(IDLE_TIMEOUT_MS);
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());

            byte[] preface = in.readNBytes(Proto.PREFACE.length);
            if (!Arrays.equals(preface, Proto.PREFACE)) {
                // not a BHTP/1 client (or a future version) - nothing we can say it would understand
                log(who, "bad preface [" + Hex.inline(preface) + "], closing");
                return;
            }
            out.write(Proto.PREFACE);
            out.flush();

            Frame f;
            while ((f = Frame.read(in)) != null) {
                switch (f.type) {
                    case Proto.REQUEST -> {
                        handleRequest(who, f, out);
                        served++;
                    }
                    case Proto.RESPONSE, Proto.DATA -> {
                        // known types, but v1 clients never send them (requests have no body)
                        sendError(out, f.streamId, 400, false,
                                Proto.typeName(f.type) + " frame is not valid from a client");
                        log(who, "#" + f.streamId + " unexpected " + Proto.typeName(f.type) + " -> 400");
                    }
                    default ->
                        // the rule from the spec: unknown type -> skip it. Frame.read already
                        // consumed the payload, so dropping the frame is all there is to do.
                        log(who, String.format("skipped unknown frame type 0x%02x (%d bytes)",
                                f.type, f.payload.length));
                }
                out.flush();
            }
            log(who, "closed by client after " + served + " request(s)");
        } catch (SocketTimeoutException e) {
            log(who, "idle for " + IDLE_TIMEOUT_MS / 1000 + "s, closing after " + served + " request(s)");
        } catch (IOException e) {
            log(who, "connection error: " + e.getMessage());
        }
    }

    private void handleRequest(String who, Frame f, OutputStream out) throws IOException {
        int id = f.streamId;
        String what = "REQUEST";
        boolean head = false;
        try {
            if (id == 0)
                throw new Reject(400, "stream id 0 is reserved");

            // payload: [method:1][path_len:2][path][header block...]
            ByteBuffer buf = ByteBuffer.wrap(f.payload);
            int method;
            String path;
            try {
                method = buf.get() & 0xff;
                int plen = buf.getShort() & 0xffff;
                byte[] p = new byte[plen];
                buf.get(p);
                path = new String(p, StandardCharsets.UTF_8);
                Headers.decode(buf); // we don't use any request headers yet, but they must parse
            } catch (BufferUnderflowException e) {
                throw new Reject(400, "request frame is truncated");
            } catch (ProtocolException e) {
                throw new Reject(400, e.getMessage());
            }

            what = Proto.methodName(method) + " " + path;
            head = method == Proto.HEAD;
            if (method != Proto.GET && method != Proto.HEAD)
                throw new Reject(405, "only GET and HEAD are supported");

            Path file = mapToFile(path);
            long sent = sendFile(out, id, file, head);
            log(who, "#" + id + " " + what + " -> 200 (" + sent + " body bytes)");

        } catch (Reject r) {
            sendError(out, id, r.status, head, r.getMessage());
            log(who, "#" + id + " " + what + " -> " + r.status + " (" + r.getMessage() + ")");
        }
    }

    /** Turns a request path into a file under root, or throws the right error. */
    private Path mapToFile(String path) throws Reject, IOException {
        int q = path.indexOf('?');
        if (q >= 0)
            path = path.substring(0, q); // no query strings for a static server

        if (!path.startsWith("/"))
            throw new Reject(400, "path must start with '/'");
        if (path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0)
            throw new Reject(400, "illegal character in path");

        Path file;
        try {
            file = root.resolve(path.substring(1)).normalize();
        } catch (InvalidPathException e) {
            throw new Reject(400, "invalid path");
        }
        // after normalize(), "/../../etc/passwd" ends up outside root
        if (!file.startsWith(root))
            throw new Reject(403, "path escapes the document root");

        if (Files.isDirectory(file))
            file = file.resolve("index.html");
        if (!Files.isRegularFile(file))
            throw new Reject(404, "no such file: " + path);
        // a symlink inside root could still point outside it
        if (!file.toRealPath().startsWith(root))
            throw new Reject(403, "path escapes the document root");
        if (!Files.isReadable(file))
            throw new Reject(403, "file is not readable");
        return file;
    }

    /** Sends RESPONSE + DATA frames for a file. Returns the number of body bytes sent. */
    private long sendFile(OutputStream out, int id, Path file, boolean headOnly) throws IOException {
        long size = Files.size(file);
        Instant mtime = Files.getLastModifiedTime(file).toInstant();

        Headers h = new Headers()
                .add("content-type", mimeType(file))
                .add("content-length", Long.toString(size))
                .add("last-modified", HTTP_DATE.format(mtime))
                .add("etag", "\"" + Long.toHexString(size) + "-" + Long.toHexString(mtime.toEpochMilli()) + "\"");
        addCommon(h);

        // no body to follow -> END goes on the RESPONSE frame itself
        boolean noBody = headOnly || size == 0;
        writeResponse(out, id, 200, h, noBody);
        if (noBody)
            return 0;

        long sent = 0;
        byte[] chunk = new byte[Proto.DATA_CHUNK];
        try (InputStream fin = Files.newInputStream(file)) {
            while (sent < size) {
                int want = (int) Math.min(chunk.length, size - sent);
                int n = fin.readNBytes(chunk, 0, want);
                if (n == 0)
                    break; // file got shorter while we were sending it
                sent += n;
                int flags = sent == size ? Proto.FLAG_END : 0;
                new Frame(Proto.DATA, flags, id, Arrays.copyOf(chunk, n)).writeTo(out);
            }
        }
        if (sent < size) // still have to close the message off somehow
            new Frame(Proto.DATA, Proto.FLAG_END, id, new byte[0]).writeTo(out);
        return sent;
    }

    private void sendError(OutputStream out, int id, int status, boolean headOnly, String msg)
            throws IOException {
        byte[] body = (status + " " + Proto.reason(status) + ": " + msg + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Headers h = new Headers()
                .add("content-type", "text/plain; charset=utf-8")
                .add("content-length", Integer.toString(body.length));
        if (status == 405)
            h.add("allow", "GET, HEAD");
        addCommon(h);

        writeResponse(out, id, status, h, headOnly);
        if (!headOnly)
            new Frame(Proto.DATA, Proto.FLAG_END, id, body).writeTo(out);
    }

    private static void writeResponse(OutputStream out, int id, int status, Headers h, boolean end)
            throws IOException {
        // payload: [status:2][header block...]
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(status >>> 8);
        p.write(status & 0xff);
        h.encode(p);
        new Frame(Proto.RESPONSE, end ? Proto.FLAG_END : 0, id, p.toByteArray()).writeTo(out);
    }

    private static void addCommon(Headers h) {
        h.add("date", HTTP_DATE.format(Instant.now()));
        h.add("server", SERVER_NAME);
    }

    private static String mimeType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    private static void log(String who, String msg) {
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + who + " " + msg);
    }
}
