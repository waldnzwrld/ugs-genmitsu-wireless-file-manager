package com.willwinder.ugs.nbp.genmitsusd;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.communicator.AbstractCommunicator;
import com.willwinder.universalgcodesender.communicator.ICommunicator;
import com.willwinder.universalgcodesender.communicator.ICommunicatorListener;
import com.willwinder.universalgcodesender.connection.Connection;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.types.GcodeCommand;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * Client for the Genmitsu Wi-Fi module's JSON protocol (TCP 10086), recovered from the
 * vendor app (flutter_genmitsu_cnc). The module accepts a single client, so this rides on
 * UGS's own TCP connection instead of opening a second one:
 * <ul>
 *   <li>commands are written raw to UGS's connection (they never get a GRBL "ok", so they
 *   must bypass the command queue);</li>
 *   <li>replies are one JSON object per line, picked up via {@link ICommunicatorListener}
 *   (UGS's GRBL handler ignores them);</li>
 *   <li>UGS's status polling is suspended for each exchange so no "?" lands inside an
 *   upload's file bytes.</li>
 * </ul>
 * Calls block and must not be made on the EDT.
 */
public final class GenmitsuModule implements ICommunicatorListener {
    public record SdFile(String fname, long bytes) {}

    private static final Gson GSON = new Gson();
    private static final long REPLY_TIMEOUT_MS = 8000;
    private static final int CHUNK_CHARS = 8192;

    private final BackendAPI backend;
    private final Object exchangeLock = new Object();
    private final List<Predicate<JsonObject>> waiters = new CopyOnWriteArrayList<>();
    private ICommunicator attachedTo;

    public GenmitsuModule(BackendAPI backend) {
        this.backend = backend;
    }

    public List<SdFile> list() throws IOException {
        JsonObject r = exclusive(conn -> request(conn, command("ar"), REPLY_TIMEOUT_MS));
        if (r.toString().contains("no_sd")) {
            throw new IOException("No SD card in the controller");
        }
        List<SdFile> files = new ArrayList<>();
        if (r.has("file_list")) {
            for (JsonElement e : r.getAsJsonArray("file_list")) {
                JsonObject f = e.getAsJsonObject();
                files.add(new SdFile(f.get("fname").getAsString(), f.get("bytes").getAsLong()));
            }
        }
        return files;
    }

    public void remove(String fname) throws IOException {
        JsonObject cmd = command("cf");
        cmd.addProperty("fname", fname);
        requireSuccess(exclusive(conn -> request(conn, cmd, REPLY_TIMEOUT_MS)), "delete");
    }

    /** be (begin, with fname), rp (pause), rc (resume), ed (end/stop). */
    public void control(String cmd, String fname) throws IOException {
        JsonObject payload = command(cmd);
        if (fname != null) {
            payload.addProperty("fname", fname);
        }
        requireSuccess(exclusive(conn -> request(conn, payload, REPLY_TIMEOUT_MS)), cmd);
    }

    /** The module only answers po while a job runs; null means no job. */
    public JsonObject progress() throws IOException {
        return exclusive(conn -> {
            try {
                return request(conn, command("po"), 2000);
            } catch (TimeoutException e) {
                return null;
            }
        });
    }

    /**
     * ft(file record) → module acks → raw file bytes → module reports {"cmd":"ft","prog":written}
     * until everything is on the card → fe.
     *
     * @param progress receives bytes sent, then bytes the module reports written
     */
    public void upload(String fname, byte[] data, LongConsumer progress) throws IOException {
        String text = decodeStrictUtf8(data);
        // UGS's TCPConnection writes String.getBytes() (platform charset).
        if (!Charset.defaultCharset().equals(StandardCharsets.UTF_8) && !isAscii(data)) {
            throw new IOException("UGS is not running with a UTF-8 default charset; only plain ASCII files can be uploaded");
        }
        JsonObject entity = FileEntity.of(fname, data, text);
        entity.addProperty("cmd", "ft");
        long total = data.length;

        JsonObject end = exclusive(conn -> {
            requireSuccess(request(conn, entity, REPLY_TIMEOUT_MS), "upload");

            CompletableFuture<JsonObject> written = expect(r -> {
                if (!"ft".equals(cmdOf(r)) || !r.has("prog")) {
                    return false;
                }
                long prog = r.get("prog").getAsLong();
                progress.accept(prog);
                return prog >= total;
            });
            long sent = 0;
            for (int i = 0; i < text.length(); ) {
                int j = Math.min(i + CHUNK_CHARS, text.length());
                if (j < text.length() && Character.isHighSurrogate(text.charAt(j - 1))) {
                    j--; // keep surrogate pairs together so each chunk encodes to the original bytes
                }
                String chunk = text.substring(i, j);
                send(conn, chunk);
                sent += chunk.getBytes(StandardCharsets.UTF_8).length;
                progress.accept(sent);
                i = j;
            }
            await(written, 60_000 + total / 5); // generous: SD write speed varies

            return request(conn, command("fe"), REPLY_TIMEOUT_MS);
        });
        requireSuccess(end, "SD write");
    }

    // --- exchange plumbing ---------------------------------------------------------------

    private interface Exchange<T> {
        T run(Connection conn) throws Exception;
    }

    private <T> T exclusive(Exchange<T> exchange) throws IOException {
        synchronized (exchangeLock) {
            if (!backend.isConnected()) {
                throw new IOException("UGS is not connected to the machine");
            }
            IController controller = backend.getController();
            ICommunicator comm = controller.getCommunicator();
            if (backend.isSendingFile() || comm.areActiveCommands()) {
                throw new IOException("UGS is busy sending commands; wait until it is idle");
            }
            if (attachedTo != comm) {
                comm.addListener(this);
                attachedTo = comm;
            }
            Connection conn = connectionOf(comm);

            // Stop the status poll timer (it fires on the EDT, so once this returns no poll is mid-write).
            boolean polling = controller.getStatusUpdatesEnabled();
            onEdt(() -> controller.setStatusUpdatesEnabled(false));
            try {
                return exchange.run(conn);
            } catch (IOException e) {
                throw e;
            } catch (TimeoutException e) {
                throw new IOException("The module did not reply", e);
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            } finally {
                if (polling) {
                    onEdt(() -> controller.setStatusUpdatesEnabled(true));
                }
            }
        }
    }

    private JsonObject request(Connection conn, JsonObject payload, long timeoutMs) throws Exception {
        String cmd = cmdOf(payload);
        CompletableFuture<JsonObject> reply = expect(r -> cmd.equals(cmdOf(r)));
        send(conn, GSON.toJson(payload)); // JSON commands are sent without a line terminator
        return await(reply, timeoutMs);
    }

    private CompletableFuture<JsonObject> expect(Predicate<JsonObject> match) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        Predicate<JsonObject> waiter = new Predicate<>() {
            @Override
            public boolean test(JsonObject r) {
                if (!match.test(r)) {
                    return false;
                }
                waiters.remove(this);
                f.complete(r);
                return true;
            }
        };
        waiters.add(waiter);
        f.whenComplete((r, e) -> waiters.remove(waiter));
        return f;
    }

    private static JsonObject await(CompletableFuture<JsonObject> f, long timeoutMs) throws Exception {
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw e;
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void send(Connection conn, String s) throws Exception {
        conn.sendStringToComm(s);
    }

    // UGS keeps the Connection protected inside the communicator; raw bulk writes need it.
    private static Connection connectionOf(ICommunicator comm) throws IOException {
        try {
            Field f = AbstractCommunicator.class.getDeclaredField("connection");
            f.setAccessible(true);
            Connection conn = (Connection) f.get(comm);
            if (conn == null || !conn.isOpen()) {
                throw new IOException("UGS has no open connection");
            }
            if (!conn.getClass().getSimpleName().equals("TCPConnection")) {
                throw new IOException("Connect UGS to the module with the TCP driver (host:10086)");
            }
            return conn;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IOException("This UGS version doesn't expose its connection as expected", e);
        }
    }

    private static void onEdt(Runnable r) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception e) {
            throw new IOException("Couldn't toggle UGS status polling", e);
        }
    }

    // --- ICommunicatorListener (called on UGS's connection reader thread) ----------------

    @Override
    public void rawResponseListener(String response) {
        String line = response.trim();
        if (!line.startsWith("{") || !line.endsWith("}") || waiters.isEmpty()) {
            return;
        }
        try {
            JsonObject r = GSON.fromJson(line, JsonObject.class);
            for (Predicate<JsonObject> w : waiters) {
                w.test(r);
            }
        } catch (JsonParseException ignored) {
            // not module JSON
        }
    }

    @Override
    public void commandSent(GcodeCommand command) {
    }

    @Override
    public void commandSkipped(GcodeCommand command) {
    }

    @Override
    public void communicatorPausedOnError() {
    }

    @Override
    public void onConnectionClosed() {
    }

    // --- helpers -------------------------------------------------------------------------

    private static JsonObject command(String cmd) {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", cmd);
        return o;
    }

    private static String cmdOf(JsonObject o) {
        JsonElement c = o.get("cmd");
        return c != null && c.isJsonPrimitive() ? c.getAsString() : "";
    }

    private static void requireSuccess(JsonObject r, String what) throws IOException {
        for (var e : r.entrySet()) {
            if (e.getValue().isJsonPrimitive() && "failure".equals(e.getValue().getAsString())) {
                throw new IOException("The module refused " + what + ": " + r);
            }
        }
    }

    private static String decodeStrictUtf8(byte[] data) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("File is not valid UTF-8 text; it can't be sent byte-exact over UGS's connection", e);
        }
    }

    private static boolean isAscii(byte[] data) {
        for (byte b : data) {
            if (b < 0) {
                return false;
            }
        }
        return true;
    }
}
