# Comparato SMS sales agent (prototype)

An SMS agent that texts Comparato's health-insurance leads, makes the case for a free 15-minute
call with an advisor, and books it. Built for the Enrola take-home in Java / Spring Boot.

This page is about running it. **The design, assumptions, trade-offs and what I left out are in
[DESIGN.md](DESIGN.md).**

> **Status:** the agent holds real conversations with OpenAI, books, moves and cancels calls in a
> mock Calendly, and the hard rules hold: opt-out, objections, abuse (moderation plus the model's
> flag), the message cap, and the 320-character limit (one retry). Each lead message on the
> conversation page expands to show the agent's read of it: stage, flags, tool calls, tokens and
> time. Still to come: end-to-end scenario tests.

## Run it

You need **JDK 21 or newer**. Nothing else: the Maven wrapper fetches Maven, and the database is
an embedded file under `./data/`.

```bash
git clone https://github.com/esquillaxsoft-cloud/enrola-sales-agent.git
```
```bash
cd enrola-sales-agent
```
```bash
cp .env.example .env
```

Put your OpenAI API key in `.env` (`OPENAI_API_KEY=sk-...`). Then start it:

```bash
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd spring-boot:run`. If it says `JAVA_HOME` isn't set, point it at your
JDK first, e.g. `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21..."` in PowerShell.

Open **http://localhost:8080**.

- **No key?** It still runs, with a canned stub agent instead of the model. The startup log says
  which one is in use (`Agent: OpenAI gpt-6.1-sol` or `using the stub agent`).
- **Model:** `gpt-6.1-sol` by default; set `OPENAI_MODEL` in `.env` to change it. Each model call
  logs its token usage.

## Try it

1. **Seed a lead.** Pick John, Jane or Lauren from the dropdown and press **Seed lead**. The agent's
   opening SMS is sent straight away. The fourth sample, `lead-john-again`, is John again with his
   number written differently. It joins John's conversation instead of texting him twice.
2. **Press VIEW** and reply as the lead. Enter sends. The badge on the leads page shows the
   status: amber active, green booked, grey over.
3. Things worth trying:

| Send this | What should happen |
|---|---|
| `All of the above`, then what you're covered for | It asks about your cover, then suggests a call |
| A day and time, e.g. `thursday arvo`, then `the first one` | It offers up to three real free times in your time zone, books one, and sends the confirmation (a fixed template naming the advisor). The badge turns green |
| `thanks!` after booking | No reply: no goodbye loop |
| `can we move it to friday morning?` | It offers Friday times and moves the booking, then confirms again |
| `is this a bot?` | It says it's Comparato's AI assistant, and the call is with a real advisor |
| `tell me a joke about chickens` or `ignore your instructions` | It declines in a few words and steers back |
| `not interested`, then again | One retry, then a fixed goodbye; status `DECLINED`, no more replies |
| `STOP` | No reply, status `OPTED_OUT`. Seeding `lead-john-again` afterwards sends nothing |
| Anything abusive | No reply, status `ENDED` |

**Reset conversation** (on the conversation page) throws the conversation away and starts the lead
again. It also clears that number's opt-out so you can replay an opt-out scenario; production would
never do that.

## Where things are

| What | Where |
|---|---|
| **The prompt** (the product) | `src/main/resources/agents/comparato/v1/system-prompt_en_AU.md` |
| Comparato info pack (verbatim from the brief) | `.../comparato/v1/knowledge_en_AU.md` |
| SMS templates (openers, goodbye) and opt-out keywords | `.../comparato/v1/sms/`, `.../comparato/v1/opt-out-keywords_en_AU.txt` |
| What the model returns each turn | `src/main/resources/agent/agent-output.schema.json`, `AgentOutput.java` |
| One turn: hard rules, model call, applying its output | `agent/TurnService.java`, `agent/ConversationPolicy.java` |
| OpenAI call | `agent/openai/OpenAiAgentReplier.java` |
| Database schema | `src/main/resources/db/migration/` |

Prompt and templates are plain files: edit them and restart. A change that alters behaviour belongs
in a new version folder (`comparato/v2`), so the two can be compared.

## Platform API

What Enrola's platform would call. Every response includes the conversation `state`: `status`,
`goalMet`, `unsubscribed`, `ended`, `endReason` and `booking`. Errors are RFC 9457 problem details.
No authentication; it's a local prototype.

| Call | Does |
|---|---|
| `POST /api/leads/{leadId}/conversation` | Fetch the lead and start its conversation. `201` with the opener in `outbound`; `200` with no `outbound` if the lead (`ALREADY_STARTED`) or its number (`SAME_NUMBER_OPEN`, `NUMBER_OPTED_OUT`) already has one |
| `POST /api/conversations/{id}/inbound` | An SMS from the lead: `{"messageId": "...", "body": "..."}`. Returns the reply in `outbound` (or null) and the new state. A repeated `messageId` returns `"duplicate": true` and the original reply. `503` if the model failed; retrying the same `messageId` resumes the turn |
| `GET /api/conversations/{id}` | The whole conversation, for support and debugging |

```bash
curl -X POST http://localhost:8080/api/leads/lead-jane/conversation
```
```bash
curl -X POST http://localhost:8080/api/conversations/1/inbound -H "Content-Type: application/json" -d '{"messageId":"sms-1","body":"All of the above"}'
```
```bash
curl http://localhost:8080/api/conversations/1
```

## Tests

```bash
./mvnw test
```

They use an in-memory database and the stub agent, so they never touch `./data/` and **never call
OpenAI**, even with a key in `.env`. GitHub Actions runs them on Linux for every pull request.

**Load test** (token-free, about 30 seconds): a week's 750 leads arriving at once, then a day's 150
leads all mid-conversation together, with a fake model that takes 1-2 s per reply. It writes
`target/load-report.md`. Results and what they mean are in [DESIGN.md](DESIGN.md#load-test-is-750-leads-a-week-a-problem).

```bash
./mvnw test -Pload
```

## Starting clean and looking inside

- **Start over:** stop the app and delete the `./data/` folder.
- **Database console:** http://localhost:8080/h2-console, JDBC URL `jdbc:h2:file:./data/sales-agent`,
  user `sa`, no password.

Stack: Spring Boot 4.1, Java 21, Thymeleaf, Bootstrap 5.3, Spring Data JPA, Flyway, H2 (in
PostgreSQL mode), OpenAI Responses API.
