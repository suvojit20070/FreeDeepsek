package com.deepsek.suvo;

import android.webkit.JavascriptInterface;

/** JS (deepseek_inject.js) se Java callbacks. Ye JavaBridge thread par chalte hain. */
public final class DeepSeekJsBridge {

    @JavascriptInterface
    public void onStart(String id) {
        ChatJob job = RequestQueue.get().find(id);
        if (job != null) {
            boolean first = !job.isStarted();
            job.onStreamStart();
            AppState.log(first ? "Response stream started" : "Continue stream started");
        }
    }

    @JavascriptInterface
    public void onChunk(String id, String kind, String text) {
        ChatJob job = RequestQueue.get().find(id);
        if (job != null) job.addChunk("think".equals(kind), text);
    }

    @JavascriptInterface
    public void onEnd(String id, String reason, String error) {
        ChatJob job = RequestQueue.get().find(id);
        if (job != null) {
            job.streamEnded(reason, error);
            AppState.log("Stream end: " + reason + (error == null || error.isEmpty() ? "" : " · " + error));
        }
    }

    @JavascriptInterface
    public void log(String msg) {
        AppState.log("[js] " + msg);
    }
}
