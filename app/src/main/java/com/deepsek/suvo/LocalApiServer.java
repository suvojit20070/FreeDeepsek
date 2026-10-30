package com.deepsek.suvo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Chhota HTTP/1.1 server (no external library).
 *   GET  /health, /
 *   GET  /v1/models
 *   POST /v1/chat/completions   (stream true = SSE, chunked transfer)
 */
public final class LocalApiServer {

    private static final int MAX_HEADER = 64 * 1024;
    private static final int MAX_BODY = 20 * 1024 * 1024;
    private static final long JOB_WAIT_MS = 35 * 60_000L;

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fds-http-conn");
        t.setDaemon(true);
        return t;
    });

    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile String apiKey = "";

    private static final class Req {
        String method;
        String path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    public synchronized void start(int port, boolean allowLan, String key) throws IOException {
        stop();
        apiKey = key == null ? "" : key.trim();
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        InetAddress addr = InetAddress.getByName(allowLan ? "0.0.0.0" : "127.0.0.1");
        s.bind(new InetSocketAddress(addr, port), 50);
        server = s;
        running = true;
        Thread t = new Thread(() -> acceptLoop(s), "fds-http");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void acceptLoop(ServerSocket s) {
        while (running && !s.isClosed()) {
            try {
                Socket c = s.accept();
                pool.execute(() -> handle(c));
            } catch (IOException e) {
                if (running && !s.isClosed()) AppState.log("Accept failed: " + e.getMessage());
            }
        }
    }

    /* ---------------------------------------------------------------- connection */

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(30_000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16 * 1024);
            Req r = readRequest(in);
            if (r == null) return;
            s.setSoTimeout(0);
            route(r, out);
            out.flush();
        } catch (Throwable t) {
            AppState.log("HTTP error: " + t.getMessage());
        }
    }

    private Req readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        while (true) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > MAX_HEADER) throw new IOException("Header too large");
            last4 = (last4 << 8) | (b & 0xFF);
            if (last4 == 0x0D0A0D0A || (last4 & 0xFFFF) == 0x0A0A) break;
        }

        String[] lines = new String(head.toByteArray(), StandardCharsets.UTF_8).split("\r?\n");
        String[] rl = lines[0].trim().split(" ");
        if (rl.length < 2) throw new IOException("Bad request line");

        Req r = new Req();
        r.method = rl[0].toUpperCase(Locale.ROOT);
        String target = rl[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(c + 1).trim());
            }
        }

        String te = r.headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            r.body = readChunked(in);
        } else {
            int len = 0;
            try {
                len = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            if (len > MAX_BODY) throw new IOException("Body too large");
            r.body = readFully(in, Math.max(0, len));
        }
        return r;
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("Unexpected end of body");
            off += n;
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') bo.write(b);
            if (bo.size() > 8192) throw new IOException("Line too long");
        }
        return bo.toString("UTF-8");
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in).trim();
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            if (line.isEmpty()) continue;
            int size = Integer.parseInt(line, 16);
            if (size == 0) {
                while (!readLine(in).isEmpty()) {
                    // trailers skip
                }
                break;
            }
            if (body.size() + size > MAX_BODY) throw new IOException("Body too large");
            body.write(readFully(in, size));
            readLine(in);
        }
        return body.toByteArray();
    }

    /* ---------------------------------------------------------------- routing */

    private void route(Req r, OutputStream out) throws IOException {
        if ("OPTIONS".equals(r.method)) {
            writeHead(out, 204, null, 0, false);
            return;
        }
        String p = r.path;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);

        if ("GET".equals(r.method) && ("/".equals(p) || "/health".equals(p))) {
            JSONObject o = new JSONObject();
            try {
                o.put("status", "ok");
                o.put("app", "Free Deepsek");
                o.put("busy", RequestQueue.get().isBusy());
                o.put("waiting", RequestQueue.get().waiting());
                o.put("served", AppState.served);
            } catch (JSONException ignored) {
            }
            sendJson(out, 200, o);
            return;
        }

        if (!authorized(r)) {
            sendJson(out, 401, OpenAIResponse.error("Invalid API key", "invalid_request_error"));
            return;
        }

        if ("GET".equals(r.method) && ("/v1/models".equals(p) || "/models".equals(p))) {
            sendJson(out, 200, OpenAIResponse.models());
            return;
        }
        if ("POST".equals(r.method) && ("/v1/chat/completions".equals(p) || "/chat/completions".equals(p))) {
            chat(r, out);
            return;
        }
        sendJson(out, 404, OpenAIResponse.error("Not found: " + r.method + " " + r.path, "invalid_request_error"));
    }

    private boolean authorized(Req r) {
        String key = apiKey;
        if (key.isEmpty()) return true;
        String auth = r.headers.get("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) && key.equals(auth.substring(7).trim())) {
            return true;
        }
        return key.equals(r.headers.get("x-api-key"));
    }

    private void chat(Req r, OutputStream out) throws IOException {
        JSONObject body;
        try {
            body = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            sendJson(out, 400, OpenAIResponse.error("Invalid JSON body", "invalid_request_error"));
            return;
        }
        JSONArray msgs = body.optJSONArray("messages");
        if (msgs == null || msgs.length() == 0) {
            sendJson(out, 400, OpenAIResponse.error("'messages' is required", "invalid_request_error"));
            return;
        }
        JSONArray tools = OpenAIResponse.extractTools(body);
        Set<String> toolNames = OpenAIResponse.toolNames(tools);
        String prompt = OpenAIResponse.buildPrompt(msgs, tools, body.opt("tool_choice"));
        if (prompt.trim().isEmpty()) {
            sendJson(out, 400, OpenAIResponse.error("Messages have no text content", "invalid_request_error"));
            return;
        }

        String model = body.optString("model", "");
        if (model.trim().isEmpty()) model = "deepseek-chat";
        boolean think = OpenAIResponse.wantsThinking(model, body);
        boolean search = OpenAIResponse.wantsSearch(model, body);
        boolean stream = body.optBoolean("stream", false);
        JSONObject so = body.optJSONObject("stream_options");
        boolean includeUsage = so != null && so.optBoolean("include_usage", false);

        JSONArray files = OpenAIResponse.extractFiles(msgs);
        String followUp = OpenAIResponse.buildFollowUp(msgs, tools);
        String lastFp = OpenAIResponse.lastAssistantFingerprint(msgs);
        ChatJob job = new ChatJob(prompt, model, think, search, stream, files, followUp, lastFp);
        AppState.log("API request · " + model + (stream ? " · stream" : "")
                + (tools != null ? " · " + tools.length() + " tools" : "")
                + (files.length() > 0 ? " · " + files.length() + " files" : ""));
        RequestQueue.get().submit(job);

        if (stream) streamResponse(job, out, includeUsage, tools != null ? toolNames : null);
        else blockingResponse(job, out, tools != null ? toolNames : null);
    }

    /** toolNames null = request mein tools nahi the. */
    private void blockingResponse(ChatJob job, OutputStream out, Set<String> toolNames) throws IOException {
        boolean done;
        try {
            done = job.awaitClosed(JOB_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = false;
        }
        if (!done) {
            job.cancelled = true;
            sendJson(out, 504, OpenAIResponse.error("Timed out waiting for DeepSeek", "timeout"));
            return;
        }
        if (job.errorMessage() != null) {
            sendJson(out, 502, OpenAIResponse.error(job.errorMessage(), "upstream_error"));
            return;
        }
        ToolCallParser.Result tr = toolNames != null ? ToolCallParser.parse(job.content(), toolNames) : null;
        if (tr != null && tr.hasCalls()) AppState.log("Tool calls: " + tr.toolCalls.length());
        sendJson(out, 200, OpenAIResponse.completion(job, tr));
    }

    private void streamResponse(ChatJob job, OutputStream out, boolean includeUsage, Set<String> toolNames)
            throws IOException {
        writeHead(out, 200, "text/event-stream; charset=utf-8", -1, true);
        Chunked w = new Chunked(out);
        boolean roleSent = false;
        ToolCallParser.StreamFilter filter = toolNames != null ? new ToolCallParser.StreamFilter() : null;
        long deadline = System.currentTimeMillis() + JOB_WAIT_MS;
        try {
            while (true) {
                ChatJob.Event ev;
                try {
                    ev = job.events.poll(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    job.cancelled = true;
                    break;
                }
                if (ev == null) {
                    w.write(": keep-alive\n\n"); // page load ke time client timeout na kare
                    if (System.currentTimeMillis() > deadline) {
                        job.cancelled = true;
                        w.write(sse(OpenAIResponse.error("Timed out waiting for DeepSeek", "timeout")));
                        break;
                    }
                    continue;
                }
                if (!roleSent && (ev.type == ChatJob.EV_START || ev.type == ChatJob.EV_CHUNK || ev.type == ChatJob.EV_DONE)) {
                    w.write(sse(OpenAIResponse.chunk(job, OpenAIResponse.delta("role", "assistant"), null)));
                    roleSent = true;
                }
                if (ev.type == ChatJob.EV_CHUNK) {
                    if (ev.think) {
                        w.write(sse(OpenAIResponse.chunk(job, OpenAIResponse.delta("reasoning_content", ev.text), null)));
                    } else {
                        String text = filter != null ? filter.feed(ev.text) : ev.text;
                        if (!text.isEmpty()) {
                            w.write(sse(OpenAIResponse.chunk(job, OpenAIResponse.delta("content", text), null)));
                        }
                    }
                } else if (ev.type == ChatJob.EV_DONE) {
                    String finish = ev.text;
                    if (filter != null) {
                        String tail = filter.flush();
                        if (!tail.isEmpty()) {
                            w.write(sse(OpenAIResponse.chunk(job, OpenAIResponse.delta("content", tail), null)));
                        }
                        if (filter.isCapturing()) {
                            String full = job.content();
                            ToolCallParser.Result tr = ToolCallParser.parse(full, toolNames);
                            if (tr.hasCalls()) {
                                AppState.log("Tool calls: " + tr.toolCalls.length());
                                for (int i = 0; i < tr.toolCalls.length(); i++) {
                                    w.write(sse(OpenAIResponse.chunk(job,
                                            OpenAIResponse.toolCallDelta(tr.toolCalls.optJSONObject(i), i), null)));
                                }
                                finish = "tool_calls";
                            } else {
                                // Marker mila par parse nahi hua: roka hua text bhej do
                                int from = Math.min(filter.emittedChars(), full.length());
                                String rest = full.substring(from);
                                if (!rest.isEmpty()) {
                                    w.write(sse(OpenAIResponse.chunk(job, OpenAIResponse.delta("content", rest), null)));
                                }
                            }
                        }
                    }
                    w.write(sse(OpenAIResponse.chunk(job, new JSONObject(), finish)));
                    if (includeUsage) w.write(sse(OpenAIResponse.usageChunk(job)));
                    break;
                } else if (ev.type == ChatJob.EV_ERROR) {
                    w.write(sse(OpenAIResponse.error(ev.text, "upstream_error")));
                    break;
                }
            }
            w.write("data: [DONE]\n\n");
            w.finish();
        } catch (IOException e) {
            job.cancelled = true; // client chala gaya
            throw e;
        }
    }

    /* ---------------------------------------------------------------- writing */

    private static String sse(JSONObject o) {
        return "data: " + o.toString() + "\n\n";
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Status";
        }
    }

    private static void writeHead(OutputStream out, int code, String contentType, long length, boolean chunked)
            throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
        if (chunked) h.append("Transfer-Encoding: chunked\r\n");
        else h.append("Content-Length: ").append(Math.max(0, length)).append("\r\n");
        h.append("Cache-Control: no-cache\r\n");
        h.append("Connection: close\r\n");
        h.append("X-Accel-Buffering: no\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        h.append("Server: FreeDeepsek\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sendJson(OutputStream out, int code, JSONObject json) throws IOException {
        byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
        writeHead(out, code, "application/json; charset=utf-8", data.length, false);
        out.write(data);
        out.flush();
    }

    private static final class Chunked {
        private final OutputStream out;

        Chunked(OutputStream out) {
            this.out = out;
        }

        void write(String s) throws IOException {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            if (data.length == 0) return;
            out.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void finish() throws IOException {
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }
}
