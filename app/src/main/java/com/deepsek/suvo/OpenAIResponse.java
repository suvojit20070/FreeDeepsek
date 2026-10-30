package com.deepsek.suvo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** OpenAI-compatible JSON builders + messages/tools -> single prompt conversion. */
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

    private static Object parseArgs(Object args) {
        if (args == null || args == JSONObject.NULL) return new JSONObject();
        if (args instanceof JSONObject || args instanceof JSONArray) return args;
        String s = String.valueOf(args).trim();
        if (s.isEmpty()) return new JSONObject();
        try {
            return new JSONTokener(s).nextValue();
        } catch (JSONException e) {
            return s;
        }
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

    /* ---------------------------------------------------------------- tools */

    /** "tools" ya purana "functions" field -> standard tools array. "tool_choice": "none" = null. */
    public static JSONArray extractTools(JSONObject body) {
        Object choice = body.opt("tool_choice");
        if ("none".equals(choice)) return null;

        JSONArray tools = body.optJSONArray("tools");
        if (tools == null || tools.length() == 0) {
            JSONArray fns = body.optJSONArray("functions");
            if (fns == null || fns.length() == 0) return null;
            tools = new JSONArray();
            for (int i = 0; i < fns.length(); i++) {
                JSONObject f = fns.optJSONObject(i);
                if (f != null) tools.put(obj("type", "function", "function", f));
            }
        }
        return tools.length() == 0 ? null : tools;
    }

    public static Set<String> toolNames(JSONArray tools) {
        Set<String> names = new HashSet<>();
        if (tools == null) return names;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            String n = (fn != null ? fn : t).optString("name", "");
            if (!n.isEmpty()) names.add(n);
        }
        return names;
    }

    private static String toolsSection(JSONArray tools, Object toolChoice) {
        JSONArray compact = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) fn = t;
            String name = fn.optString("name", "");
            if (name.isEmpty()) continue;
            JSONObject c = obj("name", name);
            try {
                String desc = fn.optString("description", "");
                if (!desc.isEmpty()) c.put("description", desc);
                Object params = fn.opt("parameters");
                if (params == null) params = fn.opt("input_schema");
                c.put("parameters", params == null ? obj("type", "object", "properties", new JSONObject()) : params);
            } catch (JSONException ignored) {
            }
            compact.put(c);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Available tools\n")
                .append("You can use the following tools. Definitions (JSON):\n")
                .append("<tools>\n").append(compact.toString()).append("\n</tools>\n\n")
                .append("## How to call tools (the ONLY accepted format)\n")
                .append("To call tools, write one <tool_calls> block. Inside it put a JSON ARRAY. ")
                .append("Each element of the array is ONE call: {\"name\": ..., \"arguments\": {...}}.\n\n")
                .append("Example: one call\n")
                .append("<tool_calls>\n")
                .append("[{\"name\": \"tool_a\", \"arguments\": {\"query\": \"hello\"}}]\n")
                .append("</tool_calls>\n\n")
                .append("Example: several calls at once (parallel). Still ONE block, ONE array:\n")
                .append("<tool_calls>\n")
                .append("[{\"name\": \"tool_a\", \"arguments\": {\"query\": \"hello\"}}, ")
                .append("{\"name\": \"tool_b\", \"arguments\": {\"id\": 42, \"verbose\": true}}]\n")
                .append("</tool_calls>\n\n")
                .append("Example: tool without parameters\n")
                .append("<tool_calls>\n")
                .append("[{\"name\": \"tool_c\", \"arguments\": {}}]\n")
                .append("</tool_calls>\n\n")
                .append("(tool_a / tool_b / tool_c are placeholders. Use the real names from <tools>.)\n\n")
                .append("Rules:\n")
                .append("- Between the tags there must be ONLY a valid JSON array. No comments, no extra text.\n")
                .append("- \"arguments\" is always a JSON object with ALL parameters of that call inside it. ")
                .append("It must match the tool's \"parameters\" schema.\n")
                .append("- Parallel calls: all calls go in the SAME array of ONE <tool_calls> block. ")
                .append("Never write several blocks.\n")
                .append("- The <tool_calls> block must be the LAST thing in your reply. ")
                .append("You may write one short sentence before it, nothing after it.\n")
                .append("- Never invent or guess tool results. After calling, stop; ")
                .append("results arrive as [TOOL RESULT] messages.\n")
                .append("- FORBIDDEN formats: DSML tags (<\uFF5CDSML\uFF5Cfunction_calls>, <\uFF5CDSML\uFF5Cinvoke>, ")
                .append("<\uFF5CDSML\uFF5Cparameter>), <function_calls>, <invoke>, <parameter>, <tool_call>, <arg>, ")
                .append("and markdown code blocks. Only <tool_calls>[...]</tool_calls> works.\n")
                .append("- If you say you will do something that needs a tool, call the tool in the same reply.\n")
                .append("- If no tool is needed, answer normally in plain text without the tags.\n");

        if ("required".equals(toolChoice) || "any".equals(toolChoice)) {
            sb.append("- In this reply you MUST call at least one tool.\n");
        } else if (toolChoice instanceof JSONObject) {
            JSONObject fn = ((JSONObject) toolChoice).optJSONObject("function");
            String forced = fn != null ? fn.optString("name", "") : ((JSONObject) toolChoice).optString("name", "");
            if (!forced.isEmpty()) {
                sb.append("- In this reply you MUST call the tool \"").append(forced).append("\".\n");
            }
        }
        return sb.toString();
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
                    } else if ("tool_result".equals(type) && p.has("content")) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(textOf(p.opt("content")));
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

    /** Assistant ke purane tool_calls ko usi <tool_calls> format mein likho jo model ko sikhaya hai. */
    private static String renderAssistantCalls(JSONObject m, Map<String, String> idToName) {
        JSONArray simple = new JSONArray();
        JSONArray calls = m.optJSONArray("tool_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.optJSONObject(i);
                if (c == null) continue;
                JSONObject fn = c.optJSONObject("function");
                if (fn == null) continue;
                String name = fn.optString("name", "");
                String id = c.optString("id", "");
                if (!id.isEmpty()) idToName.put(id, name);
                simple.put(obj("name", name, "arguments", parseArgs(fn.opt("arguments"))));
            }
        }
        JSONObject legacy = m.optJSONObject("function_call");
        if (legacy != null) {
            simple.put(obj("name", legacy.optString("name", ""), "arguments", parseArgs(legacy.opt("arguments"))));
        }
        if (simple.length() == 0) return "";
        return "<tool_calls>\n" + simple + "\n</tool_calls>";
    }

    /**
     * Har request fresh chat mein jaati hai, isliye system + tools + pura conversation
     * ek prompt mein flatten hota hai.
     */
    public static String buildPrompt(JSONArray messages, JSONArray tools, Object toolChoice) {
        boolean hasTools = tools != null && tools.length() > 0;
        Map<String, String> idToName = new HashMap<>();
        List<String[]> list = new ArrayList<>();
        boolean hasHistory = false;

        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "user").toLowerCase(Locale.ROOT);
            String text = textOf(m.opt("content")).trim();
            String label;

            switch (role) {
                case "system":
                case "developer":
                    label = "SYSTEM";
                    break;
                case "assistant": {
                    hasHistory = true;
                    label = "ASSISTANT";
                    String calls = renderAssistantCalls(m, idToName);
                    if (!calls.isEmpty()) text = text.isEmpty() ? calls : text + "\n" + calls;
                    break;
                }
                case "tool":
                case "function": {
                    hasHistory = true;
                    String id = m.optString("tool_call_id", "");
                    String name = m.optString("name", "");
                    if (name.isEmpty() && idToName.containsKey(id)) name = idToName.get(id);
                    label = "TOOL RESULT" + (name.isEmpty() ? "" : ": " + name);
                    if (text.isEmpty()) text = "(empty result)";
                    break;
                }
                default:
                    label = "USER";
            }
            if (text.isEmpty()) continue;
            list.add(new String[]{label, text});
        }
        if (list.isEmpty()) return "";

        // Simple case: tools nahi, history nahi -> seedha text
        if (!hasTools && !hasHistory) {
            StringBuilder sys = new StringBuilder();
            StringBuilder usr = new StringBuilder();
            for (String[] r : list) {
                StringBuilder target = "SYSTEM".equals(r[0]) ? sys : usr;
                if (target.length() > 0) target.append("\n\n");
                target.append(r[1]);
            }
            if (sys.length() == 0) return usr.toString();
            return "Instructions:\n" + sys + "\n\n---\n\n" + usr;
        }

        StringBuilder sb = new StringBuilder();
        if (hasTools) sb.append(toolsSection(tools, toolChoice)).append('\n');
        sb.append("# Conversation\n")
                .append("Continue the conversation below. Reply ONLY with the next ASSISTANT message, ")
                .append("without any role label or prefix.\n\n");
        for (String[] r : list) {
            sb.append('[').append(r[0]).append("]\n").append(r[1]).append("\n\n");
        }
        if (hasTools) {
            // Lambe conversation mein format bhoolta hai, isliye end mein yaad dilao
            sb.append("(Reminder: to call tools reply with <tool_calls>[{\"name\": \"...\", \"arguments\": {...}}]")
                    .append("</tool_calls> — several calls = several objects in the same array. ")
                    .append("No DSML / <invoke> / <parameter> tags.)\n\n");
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

    /** Non-stream response. toolResult null = tools nahi bheje the. */
    public static JSONObject completion(ChatJob job, ToolCallParser.Result toolResult) {
        String content = job.content();
        String finish = job.finishReason();
        JSONObject message = obj("role", "assistant");
        try {
            if (toolResult != null && toolResult.hasCalls()) {
                message.put("content", toolResult.content.isEmpty() ? JSONObject.NULL : toolResult.content);
                message.put("tool_calls", toolResult.toolCalls);
                finish = "tool_calls";
            } else {
                message.put("content", content);
            }
            String r = job.reasoning();
            if (!r.isEmpty()) message.put("reasoning_content", r);
        } catch (JSONException ignored) {
        }
        JSONArray choices = new JSONArray();
        choices.put(obj("index", 0, "message", message, "finish_reason", finish));
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

    /** Streaming tool call delta: OpenAI format mein har call ka index ke saath. */
    public static JSONObject toolCallDelta(JSONObject call, int index) {
        JSONObject fn = call.optJSONObject("function");
        JSONObject c = obj(
                "index", index,
                "id", call.optString("id"),
                "type", "function",
                "function", obj(
                        "name", fn == null ? "" : fn.optString("name"),
                        "arguments", fn == null ? "{}" : fn.optString("arguments", "{}")));
        JSONArray arr = new JSONArray();
        arr.put(c);
        return obj("tool_calls", arr);
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
