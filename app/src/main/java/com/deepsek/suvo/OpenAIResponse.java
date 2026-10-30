package com.deepsek.suvo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** OpenAI-compatible JSON builders + messages -> single prompt conversion. */
public final class OpenAIResponse {

    public static final String[] MODELS = {
            "deepseek-chat",
            "deepseek-reasoner",
            "deepseek-chat-search",
            "deepseek-reasoner-search"
    };

    private OpenAIResponse() {
    }

    /* ---------------------------------------------------------------- helpers */

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        int ascii = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < 128) ascii++;
            else other++;
        }
        return Math.max(1, ascii / 4 + other);
    }

    /* ---------------------------------------------------------------- model flags */

    public static boolean wantsThinking(String model, JSONObject body) {
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("reason") || m.contains("r1") || m.contains("think")) return true;
        Object t = body.opt("thinking");
        if (t instanceof Boolean) return (Boolean) t;
        if (t instanceof JSONObject) return "enabled".equals(((JSONObject) t).optString("type"));
        return body.optBoolean("deepthink", false);
    }

    public static boolean wantsSearch(String model, JSONObject body) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.contains("search") || body.optBoolean("web_search", false) || body.optBoolean("search", false);
    }

    /* ---------------------------------------------------------------- prompt */

    public static String textOf(Object content) {
        if (content == null || content == JSONObject.NULL) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object part = arr.opt(i);
                if (part instanceof String) {
                    sb.append(part);
                } else if (part instanceof JSONObject) {
                    JSONObject p = (JSONObject) part;
                    String type = p.optString("type", "text");
                    if (p.has("text")) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(p.optString("text"));
                    } else if (type.contains("image")) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append("[image omitted]");
                    }
                }
            }
            return sb.toString();
        }
        if (content instanceof JSONObject) return ((JSONObject) content).optString("text", content.toString());
        return String.valueOf(content);
    }

    /**
     * Har request fresh chat mein jaati hai, isliye pura conversation ek prompt mein
     * flatten hota hai. Sirf ek user message ho toh as-is bheja jata hai.
     */
    public static String buildPrompt(JSONArray messages) {
        List<String[]> list = new ArrayList<>();
        boolean hasHistory = false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "user").toLowerCase(Locale.ROOT);
            String text = textOf(m.opt("content")).trim();

            if ("assistant".equals(role)) {
                JSONArray calls = m.optJSONArray("tool_calls");
                if (calls != null && calls.length() > 0) {
                    text = (text.isEmpty() ? "" : text + "\n") + "(called tools: " + calls + ")";
                }
                hasHistory = true;
            } else if ("tool".equals(role) || "function".equals(role)) {
                hasHistory = true;
                String name = m.optString("name", m.optString("tool_call_id", ""));
                role = "tool" + (name.isEmpty() ? "" : " " + name);
            }
            if (text.isEmpty()) continue;
            list.add(new String[]{role, text});
        }
        if (list.isEmpty()) return "";

        if (!hasHistory) {
            // system + user(s): seedha jod do
            StringBuilder sys = new StringBuilder();
            StringBuilder usr = new StringBuilder();
            for (String[] r : list) {
                StringBuilder target = ("system".equals(r[0]) || "developer".equals(r[0])) ? sys : usr;
                if (target.length() > 0) target.append("\n\n");
                target.append(r[1]);
            }
            if (sys.length() == 0) return usr.toString();
            return "Instructions:\n" + sys + "\n\n---\n\n" + usr;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Continue the conversation below. Reply ONLY with the next assistant message, ")
                .append("without any role label or prefix.\n\n");
        for (String[] r : list) {
            sb.append('[').append(r[0].toUpperCase(Locale.ROOT)).append("]\n").append(r[1]).append("\n\n");
        }
        sb.append("[ASSISTANT]\n");
        return sb.toString();
    }

    /* ---------------------------------------------------------------- responses */

    public static JSONObject models() {
        JSONArray data = new JSONArray();
        long now = System.currentTimeMillis() / 1000L;
        for (String id : MODELS) {
            data.put(obj("id", id, "object", "model", "created", now, "owned_by", "deepseek-web"));
        }
        return obj("object", "list", "data", data);
    }

    private static JSONObject usage(ChatJob job) {
        int p = estimateTokens(job.prompt);
        int c = estimateTokens(job.content()) + estimateTokens(job.reasoning());
        return obj("prompt_tokens", p, "completion_tokens", c, "total_tokens", p + c);
    }

    public static JSONObject completion(ChatJob job) {
        JSONObject message = obj("role", "assistant", "content", job.content());
        String r = job.reasoning();
        if (!r.isEmpty()) {
            try {
                message.put("reasoning_content", r);
            } catch (JSONException ignored) {
            }
        }
        JSONArray choices = new JSONArray();
        choices.put(obj("index", 0, "message", message, "finish_reason", job.finishReason()));
        return obj(
                "id", "chatcmpl-" + job.id,
                "object", "chat.completion",
                "created", job.created,
                "model", job.model,
                "choices", choices,
                "usage", usage(job));
    }

    public static JSONObject chunk(ChatJob job, JSONObject delta, String finishReason) {
        JSONArray choices = new JSONArray();
        choices.put(obj("index", 0, "delta", delta,
                "finish_reason", finishReason == null ? JSONObject.NULL : finishReason));
        return obj(
                "id", "chatcmpl-" + job.id,
                "object", "chat.completion.chunk",
                "created", job.created,
                "model", job.model,
                "choices", choices);
    }

    public static JSONObject usageChunk(ChatJob job) {
        return obj(
                "id", "chatcmpl-" + job.id,
                "object", "chat.completion.chunk",
                "created", job.created,
                "model", job.model,
                "choices", new JSONArray(),
                "usage", usage(job));
    }

    public static JSONObject delta(String key, String value) {
        return obj(key, value);
    }

    public static JSONObject error(String message, String type) {
        return obj("error", obj("message", message, "type", type, "code", JSONObject.NULL));
    }
}
