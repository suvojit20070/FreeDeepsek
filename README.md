# Free Deepsek

`com.deepsek.suvo` — logged-in DeepSeek web session ko phone par ek local **OpenAI-compatible API** bana deta hai.

```
Client ──HTTP──▶ LocalApiServer (127.0.0.1:8080)
                    │ ChatJob
                    ▼
               RequestQueue (ek-ek karke)
                    │
                    ▼
            DeepSeekAutomation ──evaluateJavascript──▶ DeepSeek WebView
                    ▲                                     │ fetch/XHR hook (SSE copy)
                    └──────── DeepSeekJsBridge ◀──────────┘ onStart / onChunk / onEnd
```

## Build (GitHub Actions)

1. Ye folder GitHub repo mein push karo.
2. **Actions → Build Free Deepsek APK** apne aap chalega (ya *Run workflow*).
3. Run khatam hone par **Artifacts** se `FreeDeepsek-debug` / `FreeDeepsek-release` APK download karo.

Gradle wrapper ki zaroorat nahi, workflow Gradle 8.9 khud setup karta hai.

## Pehli baar setup

1. App kholo → WebView mein DeepSeek par **login** karo (email/phone. Google login WebView mein block ho sakta hai).
2. **Allow overlay** → permission do. Iske bina background mein WebView throttle hoga.
3. **Battery unrestricted** → allow karo.
4. Ab app band kar sakte ho, notification wali service chalti rahegi.

## API

Base URL: `http://127.0.0.1:8080/v1` (API key: Settings mein set ki ho toh wahi, warna kuch bhi)

| Model | DeepThink | Search |
|---|---|---|
| `deepseek-chat` | off | off |
| `deepseek-reasoner` | on | off |
| `deepseek-chat-search` | off | on |
| `deepseek-reasoner-search` | on | on |

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","stream":true,"messages":[{"role":"user","content":"Hi"}]}'
```

- Har request = **naya chat** (home page reload). Pura `messages` array ek prompt mein flatten hota hai.
- Thinking `reasoning_content` mein aata hai (stream aur non-stream dono mein).
- `stream_options.include_usage` support hai. Tokens approx hain.
- Endpoints: `GET /health`, `GET /v1/models`, `POST /v1/chat/completions`.

## Pichhle plan mein jo galat tha, aur kya fix kiya

| Pichhla plan | Problem | Fix |
|---|---|---|
| WebView Activity context se, "service mein retain" | Activity leak, aur detached WebView throttle ho jata hai | `MutableContextWrapper(appContext)` + 1x1 px overlay window mein park |
| `MutationObserver` se DOM response | UI badalte hi toot jata hai, rAF band hone par update nahi aate | Page ki apni completion `fetch`/`XHR` stream ko clone karke parse |
| `textarea.value = x` | React state update nahi hoti | Native value setter + `input` event |
| Inject on page load | Fetch hook late lag sakta tha | `addDocumentStartJavaScript` (fallback: pageStarted/Finished) |
| Selectors pe poora bharosa | Send fail par "stop" dab sakta hai | Enter → agar text abhi bhi box mein hai tabhi Send click |
| FGS type unspecified | Android 14+ crash | `specialUse` type + property |

## Limitations

- WebView automation DeepSeek ke ToS ke against ho sakta hai, aur account flag ho sakta hai. Apne risk par use karo.
- DeepThink/Search toggles text se dhoondhe jaate hain. UI badla toh log mein `missing` aayega, baaki kaam chalta rahega.
- Images aur tool-calling forward nahi hote (text-only).
