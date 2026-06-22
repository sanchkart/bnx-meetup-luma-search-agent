# meetup-agent

A small Kotlin agent that discovers **tech meetups in Amsterdam** on
[lu.ma](https://lu.ma) and posts a digest to a **Telegram channel**.

It is built on top of the [Koog](https://github.com/JetBrains/koog) agent
framework: the meetup discovery and Telegram posting are exposed as Koog **tools**,
and an LLM-driven `AIAgent` orchestrates them.

## How it works

1. **`LumaClient`** queries Luma's public discovery feed
   (`GET https://api.lu.ma/discover/get-paginated-events`), paginates through it,
   and keeps only events whose city matches (default `Amsterdam`) and whose title
   matches a list of tech keywords (`ai`, `developer`, `kotlin`, `startup`, …).
   It also drops events that have already started and, by querying each event's
   detail endpoint, keeps **only events with registration currently open** (skipping
   sold-out and waitlist-only events). The same detail lookup is used to build a
   **really short, one-line summary** of each event, shown under it in the digest.
2. **`MeetupToolSet`** wraps the above plus Telegram posting as Koog `@Tool`s.
3. **`KoogMeetupAgent`** registers those tools and runs a Koog `AIAgent` backed by
   a **local, tool-capable model served by Ollama** (default `qwen2.5`) that finds the meetups and posts the
   digest autonomously.
4. **`MeetupAgent`** is the deterministic fallback used when no LLM model is set;
   it also builds the HTML digest (`formatDigest`).
5. **`PostedCache`** records the id of every meetup already posted (in a small
   text file) so re-runs skip them and never post duplicates to the channel.

No API key is required for Luma **or** the LLM — the model runs locally via Ollama.
Only Telegram needs credentials.

### Local LLM (Ollama)

1. Install [Ollama](https://ollama.com) and start it (`ollama serve`, default
   `http://localhost:11434`).
2. Pull a tool-capable model once: `ollama pull qwen2.5` (other good options:
   `llama3.1`, `mistral-nemo`). The model **must support tool/function calling**.
3. The agent connects to it automatically using `llm.baseUrl` / `llm.model`.

## Setup

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
  model: ${LLM_MODEL:qwen2.5}
```

| Key                  | Env override         | Default     | Description                                       |
|----------------------|----------------------|-------------|---------------------------------------------------|
| `telegram.botToken`  | `TELEGRAM_BOT_TOKEN` | –           | Bot token from @BotFather                         |
| `telegram.chatId`    | `TELEGRAM_CHAT_ID`   | –           | Channel `@username` or numeric id                 |
| `meetup.city`        | `MEETUP_CITY`        | `Amsterdam` | City to filter events by                          |
| `meetup.maxResults`  | `MEETUP_MAX_RESULTS` | `15`        | Max number of meetups to include                  |
| `meetup.cacheFile`   | `MEETUP_CACHE_FILE`  | `.meetup-cache/posted.txt` | File of already-posted event ids (dedup) |
| `llm.baseUrl`        | `OLLAMA_BASE_URL`    | `http://localhost:11434` | Ollama server URL for the local LLM  |
| `llm.model`          | `LLM_MODEL`          | `qwen2.5`   | Local Ollama tool-capable model used by the agent; empty ⇒ fallback flow |

`DRY_RUN=true` (env var) prints the digest instead of posting.

## Run

Preview without posting (no credentials needed):

```bash
DRY_RUN=true mvn -q compile exec:java
```

Run the Koog AI agent (uses the local Ollama model, finds meetups and posts via tools):

```bash
ollama pull qwen2.5   # once
export TELEGRAM_BOT_TOKEN="123456:abc..."
export TELEGRAM_CHAT_ID="@my_tech_channel"
mvn -q compile exec:java
```

Run the deterministic fallback (clear `llm.model` in `application.yml`, just Telegram):

```bash
export TELEGRAM_BOT_TOKEN="123456:abc..."
export TELEGRAM_CHAT_ID="@my_tech_channel"
mvn -q compile exec:java
```

## Scheduling

Run it daily via `cron`, e.g. every morning at 08:00:

```cron
0 8 * * * cd /path/to/meetup-agent && TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=@my_tech_channel mvn -q compile exec:java >> agent.log 2>&1
```

## Requirements

- JDK 17+ (required by Koog).
- [Ollama](https://ollama.com) running locally with a tool-capable model pulled
  (e.g. `qwen2.5`)
  (only needed for the LLM-driven agent; the fallback and `DRY_RUN` work without it).

## Tests

```bash
mvn -q test
```
