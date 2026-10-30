package com.deepsek.suvo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek ke text output se OpenAI-style tool_calls nikalta hai.
 *
 * Main format (prompt mein yahi sikhaya jata hai):
 *   <tool_calls>
 *   [{"name": "get_weather", "arguments": {"city": "Kolkata"}}]
 *   </tool_calls>
 *
 * Lenient fallbacks (model kabhi apna format bana de):
 *   DeepSeek native DSML:
 *     <|DSML|function_calls>
 *     <|DSML|invoke name="x">
 *     <|DSML|parameter name="k" string="true">v</|DSML|parameter>
 *     </|DSML|invoke>
 *     </|DSML|function_calls>
 *     (invoke ke andar seedha JSON ho, ya tags toote hon, tab bhi)
 *   <tool_call>{"name": "...", "arguments": {...}}</tool_call>
 *   <tool_call>list_tools<arg name="x">value</arg></tool_call>
 *   ```json {"name": "...", "arguments": {...}} ```   (sirf known tool name ho tab)
 */
public final class ToolCallParser {

    public static final class Result {
        public final String content;
        public final JSONArray toolCalls;

        Result(String content, JSONArray toolCalls) {
            this.content = content;
            this.toolCalls = toolCalls;
        }

        public boolean hasCalls() {
            return toolCalls.length() > 0;
        }
    }

    private static final Pattern BLOCK = Pattern.compile(
            "<tool_calls>(.*?)(?:</tool_calls>|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE = Pattern.compile(
            "<tool_call>(.*?)(?:</tool_call>|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ARG = Pattern.compile(
            "<arg\\s+name\\s*=\\s*[\"']?([^\"'>]*)[\"']?\\s*>(.*?)</arg>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile(
            "^```[a-zA-Z_]*\\s*(.*?)\\s*```$", Pattern.DOTALL);

    /** DSML tag: <|DSML|invoke name="x">, </||DSML||parameter>, < | DSML | calls> ... (| pehle | mein normalize) */
    private static final Pattern DSML_TAG = Pattern.compile(
            "<\\s*(/?)\\s*\\|[\\s|]*DSML[\\s|]*([a-zA-Z_]+)([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_NAME = Pattern.compile("name\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern ATTR_STRING = Pattern.compile("string\\s*=\\s*[\"']true[\"']", Pattern.CASE_INSENSITIVE);

    private ToolCallParser() {
    }

    public static Result parse(String text, Set<String> knownNames) {
        if (text == null) text = "";

        // 1. <tool_calls>[...]</tool_calls>
        JSONArray calls = new JSONArray();
        StringBuilder rest = new StringBuilder();
        Matcher m = BLOCK.matcher(text);
        int last = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            rest.append(text, last, m.start());
            last = m.end();
            addFromJson(m.group(1), calls);
        }
        if (found) {
            rest.append(text.substring(last));
            return calls.length() > 0 ? new Result(rest.toString().trim(), calls) : new Result(text, calls);
        }

        // 2. DeepSeek DSML format
        Result dsml = parseDsml(text);
        if (dsml != null) return dsml;

        // 3. <tool_call>...</tool_call> (JSON ya <arg> format)
        rest.setLength(0);
        m = SINGLE.matcher(text);
        last = 0;
        while (m.find()) {
            found = true;
            rest.append(text, last, m.start());
            last = m.end();
            String inner = m.group(1).trim();
            if (inner.startsWith("{") || inner.startsWith("[") || inner.startsWith("```")) {
                addFromJson(inner, calls);
            } else {
                addLegacy(inner, calls);
            }
        }
        if (found) {
            rest.append(text.substring(last));
            return calls.length() > 0 ? new Result(rest.toString().trim(), calls) : new Result(text, calls);
        }

        // 4. Poora reply sirf JSON / ```json block ho aur tool name known ho
        String t = text.trim();
        Matcher f = FENCE.matcher(t);
        String json = f.matches() ? f.group(1).trim() : t;
        if ((json.startsWith("{") || json.startsWith("[")) && knownNames != null && !knownNames.isEmpty()) {
            JSONArray tmp = new JSONArray();
            addFromJson(json, tmp);
            if (tmp.length() > 0 && allKnown(tmp, knownNames)) return new Result("", tmp);
        }

        return new Result(text, calls);
    }

    /* ---------------------------------------------------------------- DSML format */

    /** DSML tags na milen ya koi invoke na ho toh null. */
    private static Result parseDsml(String original) {
        // Fullwidth | (U+FF5C) -> |  (ek char -> ek char, indexes same rehte hain)
        String text = original.replace('\uFF5C', '|');
        Matcher m = DSML_TAG.matcher(text);

        JSONArray calls = new JSONArray();
        int firstTag = -1, lastTag = -1;

        String callName = null;
        JSONObject callArgs = null;
        int bodyStart = 0;
        String paramName = null;
        boolean paramIsString = false;
        int paramStart = 0;

        while (m.find()) {
            if (firstTag < 0) firstTag = m.start();
            lastTag = m.end();
            boolean closing = "/".equals(m.group(1));
            String tag = m.group(2).toLowerCase(Locale.ROOT);
            String attrs = m.group(3);

            if ("invoke".equals(tag)) {
                if (!closing) {
                    if (callName != null) finishDsmlCall(text, callName, callArgs, bodyStart, m.start(), calls);
                    Matcher n = ATTR_NAME.matcher(attrs);
                    callName = n.find() ? n.group(1).trim() : "";
                    callArgs = new JSONObject();
                    bodyStart = m.end();
                    paramName = null;
                } else if (callName != null) {
                    finishDsmlCall(text, callName, callArgs, bodyStart, m.start(), calls);
                    callName = null;
                    callArgs = null;
                }
            } else if ("parameter".equals(tag)) {
                if (!closing) {
                    Matcher n = ATTR_NAME.matcher(attrs);
                    paramName = n.find() ? n.group(1).trim() : "";
                    paramIsString = ATTR_STRING.matcher(attrs).find();
                    paramStart = m.end();
                } else if (paramName != null && callArgs != null) {
                    String raw = text.substring(paramStart, m.start()).trim();
                    if (!paramName.isEmpty()) {
                        try {
                            callArgs.put(paramName, paramIsString ? raw : scalar(raw));
                        } catch (JSONException ignored) {
                        }
                    }
                    paramName = null;
                }
                // Akela closing </parameter> (bina opening ke) ignore
            }
            // function_calls / calls / tool_calls container tags sirf position ke liye
        }

        if (firstTag < 0) return null;
        if (callName != null) finishDsmlCall(text, callName, callArgs, bodyStart, text.length(), calls);
        if (calls.length() == 0) return null;

        String content = (original.substring(0, firstTag) + original.substring(lastTag)).trim();
        return new Result(content, calls);
    }

    private static void finishDsmlCall(String text, String name, JSONObject args, int bodyStart, int bodyEnd,
                                       JSONArray out) {
        if (name == null || name.isEmpty()) return;
        if (args == null) args = new JSONObject();
        if (args.length() == 0) {
            // invoke ke andar parameters nahi, seedha JSON likha hai
            String raw = DSML_TAG.matcher(text.substring(bodyStart, Math.max(bodyStart, bodyEnd))).replaceAll("").trim();
            if (!raw.isEmpty()) {
                out.put(makeCall(name, argsToString(raw)));
                return;
            }
        }
        out.put(makeCall(name, args.toString()));
    }

    /* ---------------------------------------------------------------- JSON format */

    private static String stripFence(String s) {
        Matcher f = FENCE.matcher(s.trim());
        return f.matches() ? f.group(1).trim() : s.trim();
    }

    private static Object lenientJson(String s) {
        s = stripFence(s);
        try {
            return new JSONTokener(s).nextValue();
        } catch (JSONException e) {
            // Aage-peeche faltu text ho toh pehle [ / { se aakhri ] / } tak try karo
            int a = indexOfAny(s, '[', '{');
            int b = Math.max(s.lastIndexOf(']'), s.lastIndexOf('}'));
            if (a >= 0 && b > a) {
                try {
                    return new JSONTokener(s.substring(a, b + 1)).nextValue();
                } catch (JSONException ignored) {
                }
            }
            return null;
        }
    }

    private static int indexOfAny(String s, char x, char y) {
        int i = s.indexOf(x), j = s.indexOf(y);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    private static void addFromJson(String s, JSONArray out) {
        Object o = lenientJson(s);
        if (o instanceof JSONArray) {
            JSONArray arr = (JSONArray) o;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null) addOne(item, out);
            }
        } else if (o instanceof JSONObject) {
            JSONObject obj = (JSONObject) o;
            JSONArray inner = obj.optJSONArray("tool_calls");
            if (inner != null) {
                for (int i = 0; i < inner.length(); i++) {
                    JSONObject item = inner.optJSONObject(i);
                    if (item != null) addOne(item, out);
                }
            } else {
                addOne(obj, out);
            }
        }
    }

    private static void addOne(JSONObject o, JSONArray out) {
        JSONObject fn = o.optJSONObject("function");
        String name;
        Object args;
        if (fn != null) {
            name = fn.optString("name", "");
            args = fn.opt("arguments");
        } else {
            name = o.optString("name", o.optString("tool", o.optString("tool_name", "")));
            args = o.opt("arguments");
            if (args == null) args = o.opt("parameters");
            if (args == null) args = o.opt("args");
            if (args == null) args = o.opt("input");
        }
        name = name.trim();
        if (name.isEmpty()) return;
        out.put(makeCall(name, argsToString(args)));
    }

    /* ---------------------------------------------------------------- <arg> format */

    private static void addLegacy(String inner, JSONArray out) {
        int lt = inner.indexOf('<');
        String name = (lt >= 0 ? inner.substring(0, lt) : inner).trim();
        int nl = name.indexOf('\n');
        if (nl >= 0) name = name.substring(0, nl).trim();
        if (name.isEmpty()) return;

        JSONObject args = new JSONObject();
        Matcher a = ARG.matcher(inner);
        while (a.find()) {
            String key = a.group(1).trim();
            if (key.isEmpty()) continue;
            String raw = a.group(2).trim();
            try {
                args.put(key, scalar(raw));
            } catch (JSONException ignored) {
            }
        }
        out.put(makeCall(name, args.toString()));
    }

    private static Object scalar(String raw) {
        if (raw.isEmpty()) return "";
        char c = raw.charAt(0);
        if (c == '{' || c == '[' || c == '"' || c == '-' || Character.isDigit(c)
                || "true".equals(raw) || "false".equals(raw) || "null".equals(raw)) {
            try {
                Object v = new JSONTokener(raw).nextValue();
                if (v != null) return v;
            } catch (JSONException ignored) {
            }
        }
        return raw;
    }

    /* ---------------------------------------------------------------- helpers */

    private static String argsToString(Object args) {
        if (args == null || args == JSONObject.NULL) return "{}";
        if (args instanceof JSONObject || args instanceof JSONArray) return args.toString();
        String s = String.valueOf(args).trim();
        if (s.isEmpty()) return "{}";
        Object parsed = lenientJson(s);
        if (parsed instanceof JSONObject) return parsed.toString();
        JSONObject wrap = new JSONObject();
        try {
            wrap.put("input", s);
        } catch (JSONException ignored) {
        }
        return wrap.toString();
    }

    private static JSONObject makeCall(String name, String argumentsJson) {
        JSONObject fn = new JSONObject();
        JSONObject call = new JSONObject();
        try {
            fn.put("name", name);
            fn.put("arguments", argumentsJson);
            call.put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            call.put("type", "function");
            call.put("function", fn);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return call;
    }

    private static boolean allKnown(JSONArray calls, Set<String> names) {
        for (int i = 0; i < calls.length(); i++) {
            JSONObject fn = calls.optJSONObject(i).optJSONObject("function");
            if (fn == null || !names.contains(fn.optString("name"))) return false;
        }
        return true;
    }

    /* ---------------------------------------------------------------- streaming */

    /**
     * Stream ke time text aage bhejta hai, lekin tool-call marker dikhte hi rok deta hai,
     * taaki tool block client ko content ki tarah leak na ho. Chunk ke end mein agar
     * marker adhoora ho ("<too", "<"), utna hissa next chunk tak hold karta hai.
     * "<|" / "<|" = DeepSeek ke special/DSML tags.
     */
    public static final class StreamFilter {
        private static final String[] MARKERS = {"<tool_call", "<function_call", "<|", "<\uFF5C"};

        private final StringBuilder pending = new StringBuilder();
        private boolean capturing = false;
        private int emitted = 0;

        public String feed(String s) {
            if (capturing || s == null || s.isEmpty()) return "";
            pending.append(s);
            String lower = pending.toString().toLowerCase(Locale.ROOT);

            int idx = -1;
            for (String mk : MARKERS) {
                int i = lower.indexOf(mk);
                if (i >= 0 && (idx < 0 || i < idx)) idx = i;
            }
            if (idx >= 0) {
                capturing = true;
                String out = pending.substring(0, idx);
                pending.setLength(0);
                emitted += out.length();
                return out;
            }

            int keep = partialSuffix(lower);
            String out = pending.substring(0, pending.length() - keep);
            pending.delete(0, pending.length() - keep);
            emitted += out.length();
            return out;
        }

        /** Stream end par bacha hua (non-tool) text. */
        public String flush() {
            if (capturing) return "";
            String out = pending.toString();
            pending.setLength(0);
            emitted += out.length();
            return out;
        }

        public boolean isCapturing() {
            return capturing;
        }

        /** Content ke kitne chars client ko already bhej diye. */
        public int emittedChars() {
            return emitted;
        }

        private static int partialSuffix(String lower) {
            int max = 0;
            for (String mk : MARKERS) max = Math.max(max, mk.length() - 1);
            for (int k = Math.min(max, lower.length()); k > 0; k--) {
                String suffix = lower.substring(lower.length() - k);
                for (String mk : MARKERS) {
                    if (mk.startsWith(suffix)) return k;
                }
            }
            return 0;
        }
    }
}
