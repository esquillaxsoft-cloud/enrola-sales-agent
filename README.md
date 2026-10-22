# Comparato SMS Sales Agent: Prototype

A prototype conversational agent that texts Comparato's health-insurance leads, makes the case
for a 15-minute call with an advisor, and books that call in Calendly.

> **Status:** in progress. I wrote this README before writing any code. It records the
> assumptions and scope I'm working to, and I'll update it as decisions change. Each change is
> visible in the commit history.

---

## 1. What I'm building first, and why

The goal for the next fortnight is **one customer live**, not a general product. So the first
deliverable is the smallest thing that can safely hold a real conversation with a real lead:

1. **An agent turn**: given a stored conversation and a new inbound SMS, produce the next reply
   plus a structured conversation state (`goalMet`, `unsubscribed`, ...). This is the core of the
   product and the thing the agent designer needs to iterate on.
2. **Conversation state that survives the process.** This is async SMS, not a chat window. A lead
   may reply two days later, after a restart or deploy.
3. **Hard rules enforced in code, not just in the prompt**: opt-out, 320 characters, no reply
   after a conversation ends, one retry on objections. These are the ways an SMS agent embarrasses
   a customer, so they can't depend on a model behaving.
4. **A minimal UI** so the designer can seed a lead, play the lead, see the structured output
   next to each message, and reset.
5. **Evidence it works**: unit tests on the deterministic parts and a handful of end-to-end
   scenarios.

## 2. Architecture in one paragraph

Spring Boot 4.1 (Java 21) with MVC, Thymeleaf and Bootstrap, matching Enrola's stack. An inbound SMS
hits an endpoint that stands in for the Enrola platform. The service loads the conversation from
the database, applies the deterministic pre-checks (opt-out keywords, conversation already
ended), then makes **one** OpenAI Responses API call with a single versioned system prompt, the
Comparato info pack, the lead's data and the full transcript. The model can call
`get_available_slots`, `book_call`, `reschedule_call` and `cancel_call`, backed by a mocked
Calendly client, and returns a
**structured output** (reply text plus state flags). Code validates that output (length, state
transitions), persists it, and returns it to the platform.

No multi-agent orchestration and no agent framework. One prompt, structured output and tools, as
the CTO suggested. The interesting engineering is in the seams around the model, not in chaining
models together.

### Delivery plan
Each row is a small commit (occasionally two) that builds, runs and can be reviewed on its own.
The order is deliberate:
- **A walking skeleton comes first.** The whole loop (lead → conversation → reply → stored
  state → UI) works end to end with a stub in place of the model, so the seams exist before any
  prompt work.
- **Guardrails and state transitions come before the LLM.** They're tested with fake model
  output, so they don't depend on a prompt behaving.
- **The model goes in in two steps,** plain structured output first and tool calling second,
  so each diff stays readable.

| # | Commit | What's in it | How to check it |
|---|---|---|---|
| 1 | **Scaffold** | Spring Boot 4.1, Java 21, Maven wrapper, Thymeleaf, Bootstrap (WebJar, so it runs offline), H2, `.env` loading | `./mvnw spring-boot:run` starts, and the context-load test passes |
| 2 | **UI landing page** | Shared layout and a leads page with an empty state | The page renders |
| 3 | **Persistence design** | `Lead`, `Conversation` (status, agent version, locale, objection count, booking), `Message`; Flyway migration so the schema is reviewable SQL | Repository tests: save and reload a conversation with messages |
| 4 | **Mock lead API and seeding** | `LeadClient` interface and stub with the brief's sample leads (John, Jane, Lauren); "Seed lead" button | Seeded leads appear on the landing page |
| 5 | **Conversation controller and page** | Transcript view, "send as lead" form, reset. A stub `AgentReplier` returns a canned reply | You can hold a (dumb) conversation in the browser |
| 6 | **Localised template loader** | Locale-fallback asset loader, `{{named}}` renderer, opener templates; starting a conversation sends the templated opener | Unit tests: fallback, missing variable fails, apostrophes survive |
| 7 | **Platform API** | `POST /api/leads/{id}/conversation` and `POST /api/conversations/{id}/inbound`, returning the reply and state; the UI calls the same service | **Walking skeleton done.** An HTTP test covers both endpoints |
| 8 | **Deterministic guardrails** | Opt-out keywords, no reply after a terminal state, message cap | Unit tests per rule; the stub agent is never called on `STOP` |
| 9 | **Mock Calendly and slot logic** | Availability generator, 7-day query limit, book/cancel, reschedule as book-then-cancel, state → time zone, injected `Clock` | Unit tests: slot selection, time zones, stale slot, reschedule order |
| 10 | **Agent output contract and state transitions** | The structured output record and JSON schema; code that applies it to the conversation (goal met, unsubscribed, objection count, ended) | Unit tests with hand-written model outputs, no LLM |
| 11 | **OpenAI Responses API, no tools** | Client, `system-prompt_en_AU.md` v1, knowledge pack, model from config; replaces the stub | A real conversation in the UI, short of booking |
| 12 | **Tool calling** | Tool loop for availability, book, reschedule and cancel; code-rendered booking confirmation | A real conversation books a call |
| 13 | **Output validation and moderation** | 320-character check with one retry; moderation on inbound messages ends abusive conversations | Unit tests on the validator; a manual abuse check |
| 14 | **Structured output in the UI** | State panel next to each message: flags, status, tool calls, agent version | Visible on the conversation page |
| 15 | **End-to-end scenarios** | Opt-in tests against the real model: books a call, objects twice, prompt injection, asks if it's an AI; code-based assertions on the structured output | `./mvnw test -Pscenarios` |
| 16 | **README finish** | How to run, measuring quality at scale, what breaks at 750 leads a week, how I used AI | Read it |

Milestones: **after 7** the platform contract is real with a stub agent. **After 12** the agent
can book a call. Everything after that hardens and proves it.

## 3. Assumptions

### Platform and integration
- **Enrola's platform owns SMS transport, lead ingestion and scheduling the first message.** The
  agent is a service with two operations: *start a conversation for lead X* and *handle inbound
  message Y for lead X*. Both return the outbound message (if any) and the conversation state.
  The prototype exposes these as REST endpoints so the boundary is explicit.
- **The lead API and Calendly are mocked** behind interfaces (`LeadClient`, `CalendlyClient`) so
  swapping in real clients doesn't touch the agent. The mocks assume the real APIs can do what
  the agent needs (read availability, book, cancel, return the advisor's name). Confirming that
  against Comparato's actual Calendly account is a go-live task, not a prototype one.
- **Comparato has one Calendly event type that pools all advisors**, i.e. a Calendly *round
  robin*. Availability is the union of every advisor's free time, and Calendly assigns an advisor
  when the call is booked. So the agent can't name the advisor when offering times, only in the
  confirmation, and a reschedule may land with a different advisor.
- Calendly returns availability as UTC instants. The mock produces weekday slots every 30 minutes
  during advisor business hours (assumed 9:00–17:00 Melbourne time, since that's Comparato's HQ),
  with some already taken so the "that time isn't free, here are the closest" path gets exercised.
  The mock limits each availability query to 7 days, matching real Calendly, so the tool is used
  the way it will be in production.
- The CTO's booking API returns only an `id`. The assigned advisor's name is on the Calendly
  invite, so I assume the booking response can include it. The mock returns `advisorName`.
- Rescheduling needs a **cancel** operation, which isn't in the CTO's API list. Real Calendly
  supports cancelling a scheduled event through its API, so the mock adds `cancel(id)`.
- Booking needs name, phone, email and a start time, all from the lead record. I assume email is
  present. If it isn't, booking still goes ahead with phone only, because the advisor calls by
  phone anyway.

### Conversation design
- **The opening message is a template, not LLM-generated.** It's the one message every lead gets
  (750 a week), it carries the opt-out text, and Comparato will likely want to approve it word
  for word. Variants depend on whether we know the current provider. Costs nothing and can't go
  wrong.
- **The persona is "Anna from Comparato"**, as in the sample transcript. If the lead asks,
  the agent admits it's an AI, as the brief requires. It says so in one line and goes back to the
  booking: the call is with a real advisor, which is the point. It doesn't volunteer it unprompted,
  and it never claims to be human.
- **The agent doesn't give insurance advice.** Comparato operates under the PHII Code of Conduct.
  The agent can answer questions about Comparato itself (from the info pack) and about the call,
  but anything about which policy or cover is right gets deferred to the advisor. That's the
  reason for the call, so it also helps the goal.
- **Times are shown in the lead's local time**, derived from their state (WA is Perth time, NSW
  is Sydney time, and so on). The lead's state is the only time-zone signal we have.
- **The booking confirmation is rendered by code from a template, not written by the model.** It
  names the advisor who'll call and states the time in a fixed format, in the lead's local time
  (e.g. `Tue 28 Jul 2026, 9:30 am`), which iOS and Android recognise and offer to add to the
  calendar. Because the model doesn't write it, the format can't drift. There are no links: the
  advisor calls the lead's phone.
- **Offered slots can go stale.** A lead might pick "12" two days after we offered it.
  `book_call` re-checks availability, and if the slot has gone the agent offers the nearest
  alternatives instead of confirming a booking that doesn't exist.
- **Booked isn't the end.** A booked lead can reschedule by text, and the agent handles it end
  to end with no human involved: check availability, offer times, move the booking, send a new
  confirmation. The `reschedule_call` tool **books the new slot before cancelling the old one**,
  in code, so a failure part-way never leaves the lead without a call. The conversation stores
  the current booking id and time, so a reschedule request two days later has everything it
  needs.
- **An outright cancellation** ("can't do it, cancel") cancels the booking, sets `goalMet` back to
  `false`, and the agent makes one attempt to rebook under the same objection rule. Platform
  caveat: a "goal met" event may already have been sent, so the platform needs to handle the flag
  going back to `false`.
- **Tone** matches the sample transcript: short, plain, a little blunt, Australian, no
  exclamation marks, no "I hope this finds you well".
- **The agent replies whenever the lead messages, at any hour.** A lead who replies at 10pm is
  engaged at 10pm, and making them wait until morning risks losing them. Only the unsolicited
  opener is time-restricted, and the platform schedules that.

### Prompts and message templates: ready for translation, English only
Everything the agent says or is told lives in **files, not Java strings**, laid out so a second
language could be added later without code changes. Only `en_AU` will exist for this prototype;
translation is *staged*, not delivered.

```
src/main/resources/agents/comparato/v1/       <- one versioned bundle per customer agent
  system-prompt_en_AU.md                      <- LLM system prompt
  knowledge_en_AU.md                          <- Comparato info pack
  sms/opener-known-provider_en_AU.txt         <- user-facing SMS templates
  sms/opener-no-provider_en_AU.txt
  sms/booking-confirmed_en_AU.txt
  opt-out-keywords_en_AU.txt                  <- STOP / unsubscribe words are language-specific too
```

- **One loader for everything**, using the JDK's standard locale fallback (`en_AU` → `en` →
  unsuffixed). A missing locale falls back to English instead of failing.
- **Named placeholders** (`{{givenName}}`, `{{currentProvider}}`), not `MessageFormat`'s `{0}`.
  Translators need to see what a placeholder means. `MessageFormat` also makes a single
  apostrophe an escape character, a classic bug with copy like *"it's Anna"* and
  *"Reply 'stop'"*. Rendering fails loudly on an unknown or missing variable.
- **Locale is stored on the conversation** (default `en_AU`), along with the agent version, so a
  conversation never changes language or prompt version halfway through, and every message can be
  traced to the exact assets that produced it.
- The **system prompt tells the model which language to reply in**, rather than relying on a
  translated prompt alone. The info pack is customer-supplied content, so it's localised as a
  file.
- **Bundled versioning.** Templates are versioned with the prompt (`v1`), because changing the
  opener changes the agent's behaviour as much as changing the prompt does. A new version means a
  new folder, so v1 and v2 can be compared side by side.

Not done: no translations, no language detection, no locale on the lead API, and the internal UI
stays English. One known catch for later: SMS with non-GSM characters (most non-Latin scripts)
are billed per 70 characters rather than 160, so the 320-character limit has a different cost
per language.

### Ending conversations (state the platform can rely on)
| Situation | Reply sent? | State |
|---|---|---|
| Call booked | Yes, a confirmation | `goalMet=true`, status `BOOKED` |
| Call rescheduled | Yes, a new confirmation | `goalMet=true`, status `BOOKED` |
| Booking cancelled by lead | Yes, one rebook attempt | `goalMet=false`, status `ACTIVE` |
| `STOP` / `unsubscribe` / `opt out` keyword | No; the platform handles any compliance confirmation | `unsubscribed=true`, status `OPTED_OUT` |
| "Please stop texting me" (no keyword) | No | Model flags `unsubscribed=true`, status `OPTED_OUT` |
| Second objection | Yes, a polite close ("I'll leave it there...") | status `DECLINED` |
| Abusive lead | No | status `ENDED_ABUSIVE` |

- **Opt-out keywords are handled in code before the model is called.** It's a legal requirement
  (Spam Act), so it must be deterministic and must not cost a token.
- **Objections are counted in code.** The model flags whether this message was an objection, and
  code keeps the count. "Try once more, then give up" doesn't rely on the model remembering.
- **Goodbye loops are prevented in two layers.** After `BOOKED`, the agent only replies if the
  lead says something that needs a reply (a question, or a reschedule request). The model returns
  `reply: null` for "thanks!"-type messages. Code also caps the number of agent messages per
  conversation as a backstop. If the cap is hit, the agent stops replying and the conversation
  ends with status `ENDED` and reason `MESSAGE_CAP`.

## 4. In scope / explicitly out of scope

**In scope**
- Agent turn: prompt file, structured output, tool calls, state persistence
- Mock lead API and mock Calendly (availability, booking, cancelling)
- Agent-led rescheduling and cancellation after a booking- Deterministic guardrails: opt-out, 320 characters, terminal states, objection count, message cap
- Moderation: OpenAI's moderation endpoint on inbound messages, used to detect abuse
- Minimal web UI: seed leads, play the lead, view structured output, reset
- Unit tests plus a small set of opt-in end-to-end scenarios against the real model

**Out of scope (deliberately)**
- Real SMS, real Calendly, real lead API, which the platform already owns
- Follow-up nudges for leads who never reply (high-value, but it's a platform scheduling concern;
  see §7)
- Multi-customer configuration (see the CTO's "build for this customer first")
- Translations. Assets are laid out per locale (see §3), but only `en_AU` ships
- Authentication, deployment, production observability

## 5. Trade-offs I'm aware of

- **Model-driven conversation with code-enforced invariants, not a hand-coded state machine.**
  The designer's five steps are guidance for the prompt, not states in code. Real leads skip steps
  ("just call me Tuesday") and a rigid state machine fights that. The cost is less predictability,
  so the invariants that *must* hold are enforced in code.
- **Full transcript sent on every turn, with `store=false`**, instead of OpenAI's
  `previous_response_id` threading. Our database is the only copy of the conversation:
  - **Privacy.** Threading requires OpenAI to store every response. These are conversations
    about people's health and insurance, which counts as sensitive information under the
    Australian Privacy Act. Keeping one copy that we control is easier to justify, and it means a
    customer that needs OpenAI's zero-data-retention option can still use the product.
  - **Conversations outlive OpenAI's storage.** Stored responses expire (30 days by default), but
    a lead can text to reschedule weeks after booking.
  - **Not every message comes from the model.** The opener, opt-out handling and the booking
    confirmation are produced by code, so OpenAI's thread would have gaps we'd keep patching.
  - **Evaluation runs the same code as production.** Testing a new prompt version means replaying
    stored transcripts through the same context-building code that live turns use.
  - **It costs the same.** Threading doesn't make earlier turns free: they're still billed as
    input tokens. Prompt caching works either way, helped by the system prompt and info pack being
    the same at the start of every call.
  - **What we give up:** a larger request each turn (negligible for SMS), and, with a reasoning
    model, the model's reasoning isn't carried between turns automatically. If that turns out to
    matter, the API can return the reasoning in encrypted form for us to store and send back.
- **H2 file database** instead of Postgres. Pull-and-run with no Docker, and state survives a
  restart. JPA keeps the move to Postgres a configuration change.
- **Synchronous request handling.** Fine for a prototype. At 750 leads a week the volume is tiny
  (peak a few requests a minute), but in production I'd still take inbound messages off a queue so
  a slow OpenAI call or retry never blocks the platform's webhook. Two SMS arriving seconds apart
  should be batched into one turn. The prototype serialises turns per conversation instead.
- **A templated opener** gives up personalisation on the first message in exchange for
  predictability and approval-ability.
- **Replying at any hour, not only 8am–8pm.** I first planned an 8am–8pm reply window, with
  overnight replies deferred to the morning, because an instant 2am reply hints that it's a bot.
  I dropped it: someone who messages at 10pm is engaged right then, and an answer the next
  morning risks losing them. It also removed a scheduler and a deferred-message state from the
  prototype. Worth revisiting if late-night replies turn out to hurt trust or opt-out rates.

## 6. Questions for the agent designer / Comparato

**Answered**
- *Persona?* "Anna from Comparato".
- *Booking confirmation contents?* The name of the advisor who'll call, and the time in a format
  the phone turns into a calendar event. No links, because the advisor calls the lead.
- *Advisor name?* It's on the Calendly invite, so it's available from the booking.
- *Reschedule or cancel after booking?* The agent handles it end to end, with no human involved.
- *Reply hours?* Any time the lead messages (see §5).
- *Advisor availability?* One Calendly event type pooling all advisors (round robin).
- *AI disclosure?* As the brief says: if the lead asks, the agent admits it's an AI.

**Still open**
- None right now. The designer said more example transcripts are coming, and those will likely
  raise new ones.

## 7. With more time

- **Prompt evaluation**: replay a library of real transcripts against a new prompt version and
  compare specific, checkable outcomes (booking rate, opt-out rate, messages to booking,
  guardrail violations) before promoting it.
- **Per-customer configuration**: prompt, info pack, lead custom fields, opener templates and
  booking provider as data, turning this into the product the CTO described.
- **Follow-up nudges** for unresponsive leads, and human handoff for edge cases.
- **Queue-based processing**, retries with idempotency on booking, and per-conversation cost
  tracking.

## 8. Running it

Requires **JDK 21 or newer**. Nothing else: the Maven wrapper downloads Maven, and the database
is an embedded H2 file under `./data/`.

1. Copy `.env.example` to `.env` and add your OpenAI API key. (Real environment variables
   override `.env`.)
2. Run `./mvnw spring-boot:run` (`mvnw.cmd spring-boot:run` on Windows).
3. Open http://localhost:8080. The H2 console is at http://localhost:8080/h2-console
   (JDBC URL `jdbc:h2:file:./data/sales-agent`, user `sa`, no password).

Tests: `./mvnw test`. They use an in-memory database, so they never touch `./data/`. To start
from a clean slate, stop the app and delete `./data/`.

Stack: Spring Boot 4.1, Java 21, Thymeleaf, Bootstrap 5.3 (WebJar), Spring Data JPA, Flyway,
H2 in PostgreSQL compatibility mode.

## 9. How I used AI

_To be written as I go._
