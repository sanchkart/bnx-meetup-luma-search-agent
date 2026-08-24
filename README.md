# meetup-finder-agent

A small Kotlin agent that discovers **tech meetups in Amsterdam** on
[lu.ma](https://lu.ma) and posts a digest to a **Telegram channel**.

It is built on top of the [Koog](https://github.com/JetBrains/koog) agent
framework: meetup discovery and Telegram posting are exposed as Koog **tools**,
and an LLM-driven `AIAgent` orchestrates them. The LLM runs locally via
[Ollama](https://ollama.com), so no LLM API key is required — only Telegram
needs credentials.

## Layout

```
src/main/kotlin/com/bnx/meetup/
├── Main.kt                     entry point: dry-run / Koog agent / fallback
├── config/AppConfig.kt         application.yml loader with ${ENV:default} placeholders
├── client/LumaClient.kt        lu.ma discovery + per-event detail lookups
├── client/TelegramClient.kt    Telegram Bot API sendMessage wrapper
├── agent/KoogMeetupAgent.kt    Koog AIAgent over a local Ollama model
├── agent/MeetupAgent.kt        deterministic (non-LLM) pipeline
├── tool/MeetupToolSet.kt       Koog @Tool definitions exposed to the LLM
├── domain/Meetup.kt            normalized event model
├── utils/DigestFormatter.kt    HTML digest rendering
└── utils/PostedCache.kt        file-backed dedup of already-posted events
```

## How it works

1. **`LumaClient`** queries Luma's public discovery feed
   (`GET https://api.lu.ma/discover/get-paginated-events`), paginates through it,
   and keeps only events whose city matches (default `Amsterdam`) and whose title
   matches a list of tech keywords (`ai`, `developer`, `kotlin`, `startup`, …).
   Short keywords like `ai`/`dev` are matched on word boundaries so they don't hit
   inside larger words. It drops events that have already started, and for each
   remaining candidate calls the event detail endpoint (`/url?url=<slug>`) to:
   - keep **only events with registration currently open** (skipping sold-out and
     waitlist-only events);
   - build a **one-line summary** from the event description, skipping boilerplate
     (event-week banners, greetings, date/time sentences, "rescheduled" notices)
     and trimming to 160 characters;
   - derive a **ticket price** from `ticket_info`. Events with no concrete amount
     are shown as `Free` rather than a misleading "Paid".

   **Free events are prioritized**: pagination keeps scanning until `maxResults`
   free events are collected (capped at 10 pages and 3× `maxResults` matches), and
   paid events are only used to top up the list. The result is sorted by start time.
2. **`DigestFormatter`** renders the results as the HTML message posted to
   Telegram (title link, local start time, city, price, summary).
3. **`MeetupToolSet`** wraps discovery and posting as Koog `@Tool`s
   (`findTechMeetups`, `postToTelegram`).
4. **`KoogMeetupAgent`** registers those tools and runs a Koog `AIAgent` backed by
   a **local, tool-capable model served by Ollama** (default `mistral-nemo`) that
   finds the meetups and posts the digest autonomously.
5. **`MeetupAgent`** is the deterministic fallback that does the same work without
   an LLM.
6. **`PostedCache`** records the Luma id of every posted meetup in a small text
   file, so re-runs skip them and never post duplicates to the channel.

### Local LLM (Ollama)

1. Install [Ollama](https://ollama.com) and start it (`ollama serve`, default
   `http://localhost:11434`).
2. Pull a tool-capable model once: `ollama pull mistral-nemo` (other good options:
   `qwen2.5`, `llama3.1`). The model **must support tool/function calling**.
3. The agent connects to it automatically using `llm.baseUrl` / `llm.model`.

## Telegram setup

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy its token.
2. Add the bot as an **administrator** of your channel.
3. Use the channel's `@username` (public channel) or numeric id as the chat id.

## Configuration (`application.yml`)

All configuration lives in `src/main/resources/application.yml`. Values support
`${ENV_VAR:default}` placeholders, so secrets can stay out of version control and
be provided via environment variables at runtime:

```yaml
telegram:
  botToken: ${TELEGRAM_BOT_TOKEN:}
  chatId: ${TELEGRAM_CHAT_ID:}

meetup:
  city: ${MEETUP_CITY:Amsterdam}
  maxResults: ${MEETUP_MAX_RESULTS:15}
  cacheFile: ${MEETUP_CACHE_FILE:.meetup-cache/posted.txt}

llm:
  baseUrl: ${OLLAMA_BASE_URL:http://localhost:11434}
  model: ${LLM_MODEL:mistral-nemo}
```

| Key                 | Env override         | Default                    | Description                                                 |
|---------------------|----------------------|----------------------------|-------------------------------------------------------------|
| `telegram.botToken` | `TELEGRAM_BOT_TOKEN` | –                          | Bot token from @BotFather                                    |
| `telegram.chatId`   | `TELEGRAM_CHAT_ID`   | –                          | Channel `@username` or numeric id                            |
| `meetup.city`       | `MEETUP_CITY`        | `Amsterdam`                | City to filter events by                                     |
| `meetup.maxResults` | `MEETUP_MAX_RESULTS` | `15`                       | Max number of meetups to include                             |
| `meetup.cacheFile`  | `MEETUP_CACHE_FILE`  | `.meetup-cache/posted.txt` | File of already-posted event ids (dedup)                     |
| `llm.baseUrl`       | `OLLAMA_BASE_URL`    | `http://localhost:11434`   | Ollama server URL for the local LLM                          |
| `llm.model`         | `LLM_MODEL`          | `mistral-nemo`             | Local Ollama tool-capable model used by the agent            |

Blank values fall back to the defaults above (they are hard-coded in `AppConfig`),
so leaving `llm.model` empty still yields `mistral-nemo` — the deterministic
`MeetupAgent` path is currently only reachable by changing that default in code.

`DRY_RUN=true` (env var) prints the digest instead of posting, and needs neither
Telegram credentials nor Ollama.

> ⚠️ The committed `application.yml` currently has a real bot token and chat id as
> placeholder defaults. Replace them with empty defaults and pass the real values
> through `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID`, and revoke the exposed token
> via @BotFather.

## Run

Preview without posting (no credentials, no Ollama needed):

```bash
DRY_RUN=true mvn -q compile exec:java
```

Run the Koog AI agent (uses the local Ollama model, finds meetups and posts via tools):

```bash
ollama pull mistral-nemo   # once
export TELEGRAM_BOT_TOKEN="123456:abc..."
export TELEGRAM_CHAT_ID="@my_tech_channel"
mvn -q compile exec:java
```

> ⚠️ `exec-maven-plugin` in `pom.xml` is configured with
> `<mainClass>com.ls.meetup.MainKt</mainClass>`, but the entry point is
> `com.bnx.meetup.MainKt`. Fix that value before `exec:java` will run.

## Scheduling

Run it daily via `cron`, e.g. every morning at 08:00:

```cron
0 8 * * * cd /path/to/meetup-finder-agent && TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=@my_tech_channel mvn -q compile exec:java >> agent.log 2>&1
```

## Requirements

- JDK 17+ (required by Koog) and Maven.
- Kotlin 2.3.21, Koog 1.0.0 (see `pom.xml`).
- [Ollama](https://ollama.com) running locally with a tool-capable model pulled
  (only needed for the LLM-driven agent; `DRY_RUN` works without it).

## Tests

```bash
mvn -q test
```

`AppConfigTest` covers placeholder resolution, `MeetupAgentTest` covers Luma
parsing/filtering and digest formatting, and `PostedCacheTest` covers dedup.
