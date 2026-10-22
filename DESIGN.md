# Design and approach

How the Comparato SMS agent prototype is designed, what I assumed, what I left out, and why.
To run it, see the [README](README.md).

> I wrote this before writing any code (it started as the README) and updated it as decisions
> changed. Each change, including the ones I reversed, is in the commit history.

**Where it ended up:** the agent texts a lead from a templated opener, works through the
designer's five steps with `gpt-6.1-sol`, and books, moves or cancels a call in a mock Calendly
through tools. The rules that must hold (opt-out, objections, abuse, length, no reply loops) are
enforced in code before or after the model, never left to the prompt. Every turn is recorded with
its tools and cost, and shown next to the message in the UI. Delivery plan rows 1-16 are done
(§2); the eval suite (row 17) is designed (§8) but not built.

**Reading guide:** §2 for the architecture and how it was built, §3 for assumptions, §5 for
trade-offs and the load test, §6 for how I'd measure quality and run a second customer, §9 for how
I used AI.

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
hits an endpoint that stands in for the Enrola platform. A turn (`TurnService`) then runs in three
steps, so no database connection is held while the model thinks:
1. **Store and check, in a short transaction:** save the lead's SMS, then apply the hard rules in
   code: conversation already ended, opt-out keyword, message cap.
2. **Ask the model, with no transaction open:** OpenAI's free moderation endpoint first (abuse ends
   the conversation with no model call), then the Responses API with the conversation's versioned
   system prompt, the Comparato info pack, the lead's details and the full transcript
   (`store: false`). The model can call `get_available_slots`, `book_call`, `reschedule_call` and
   `cancel_call`, each run in its own short transaction against a mocked Calendly. It returns a
   **structured output**: reply, stage, and the objection / unsubscribe / abusive flags. Code
   checks the reply: 320 characters (one retry) and plain punctuation.
3. **Apply and store, in a short transaction:** `ConversationPolicy` turns the flags into state
   (objections counted in code, a fixed goodbye on the second), a booking made this turn is
   confirmed from a template, and the whole turn (output, tool calls, tokens, time) is recorded on
   the lead's message. The reply and the conversation state go back to the platform.

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

Rows 1-16 all shipped as pull requests #1-#20. The extras: #3 added Linux CI, #5 and #15 design
notes, #14 the load test, and #20 a prompt fix found by the scenarios. Row 17 wasn't built.

| # | Commit | What's in it | How to check it |
|---|---|---|---|
| 1 | **Scaffold** | Spring Boot 4.1, Java 21, Maven wrapper, Thymeleaf, Bootstrap (WebJar, so it runs offline), H2, `.env` loading | `./mvnw spring-boot:run` starts, and the context-load test passes |
| 2 | **UI landing page** | Shared layout and a leads page with an empty state | The page renders |
| 3 | **Persistence design** | `Lead`, `Conversation` (status, agent version, locale, objection count, booking), `Message`; Flyway migration so the schema is reviewable SQL | Repository tests: save and reload a conversation with messages |
| 4 | **Mock lead API and seeding** | `LeadClient` interface and stub with the brief's sample leads (John, Jane, Lauren); "Seed lead" button; phone numbers normalised to international format and indexed; one open conversation per phone number | Seeded leads appear on the landing page; unit tests: phone formats normalise alike, a duplicate number reuses the open conversation |
| 5 | **Conversation controller and page** | Transcript view, "send as lead" form, reset. A stub `AgentReplier` returns a canned reply | You can hold a (dumb) conversation in the browser |
| 6 | **Localised template loader** | Locale-fallback asset loader, `{{named}}` renderer, opener templates; starting a conversation sends the templated opener | Unit tests: fallback, missing variable fails, apostrophes survive |
| 7 | **Platform API** | `POST /api/leads/{id}/conversation` and `POST /api/conversations/{id}/inbound`, returning the reply and state; the UI calls the same service | **Walking skeleton done.** An HTTP test covers both endpoints |
| 8 | **Deterministic guardrails** | Opt-out keywords, opt-out applied to the phone number, no reply after a terminal state, message cap | Unit tests per rule; the stub agent is never called on `STOP`; an opted-out number gets no conversation under a new lead id |
| 9 | **Mock Calendly and slot logic** | Availability generator, 7-day query limit, book/cancel, reschedule as book-then-cancel, state → time zone, injected `Clock` | Unit tests: slot selection, time zones, stale slot, reschedule order |
| 10 | **Agent output contract and state transitions** | The structured output record and JSON schema; code that applies it to the conversation (goal met, unsubscribed, objection count, ended) | Unit tests with hand-written model outputs, no LLM |
| 11 | **OpenAI Responses API, no tools** | Client, `system-prompt_en_AU.md` v1, knowledge pack, model from config; replaces the stub | A real conversation in the UI, short of booking |
| 12 | **Tool calling** | Tool loop for availability, book, reschedule and cancel; code-rendered booking confirmation | A real conversation books a call |
| 13 | **Output validation and moderation** | 320-character check with one retry; moderation on inbound messages ends abusive conversations | Unit tests on the validator; a manual abuse check |
| 14 | **Structured output in the UI** | State panel next to each message: flags, status, tool calls, agent version | Visible on the conversation page |
| 15 | **End-to-end scenarios** | Opt-in tests against the real model: books a call, objects twice, prompt injection, asks if it's an AI; code-based assertions on the structured output | `./mvnw test -Pscenarios` |
| 16 | **Docs finish** | README (how to run) and this document: measuring quality at scale, what breaks at 750 leads a week, how I used AI | Read them |
| 17 | **Eval suite (if time permits)** | Quality vs cost per model and prompt version, designed so evals stay cheap (§8) | `./mvnw test -Peval` writes a model-by-model report |

Milestones: **after 7** the platform contract is real with a stub agent. **After 12** the agent
can book a call. Everything after that hardens and proves it.

## 3. Assumptions

### Platform and integration
- **Enrola's platform owns SMS transport, lead ingestion and scheduling the first message.** The
  agent is a service with two operations: *start a conversation for lead X* and *handle inbound
  message Y for lead X*. Both return the outbound message (if any) and the conversation state.
  The prototype exposes these as REST endpoints so the boundary is explicit (see the
  [README's API section](README.md#platform-api)).
- **Inbound SMS are addressed by conversation id**, which the platform gets back when it starts a
  lead's conversation. A duplicate lead for the same number is pointed at the existing
  conversation, so the platform never has two conversations to choose between for one phone.
- **Webhooks get retried.** The platform sends its own id for each inbound SMS; a repeat id
  returns the original reply instead of running the agent again and texting the lead twice.
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
- **The same person can arrive as two leads.** Someone fills in Comparato's form twice, or
  Comparato resends them: two lead ids, one phone number. Merging duplicate lead records is
  lead management, which belongs to Enrola's platform. The agent still protects the person:
  - Phone numbers are stored in one standard international format (`+61457099876`), so
    `0457 099 876` and `+61 457 099 876` match.
  - **One open conversation per phone number.** If a conversation for that number hasn't ended,
    a new lead doesn't start another one; the existing conversation is returned instead. This
    avoids two "Anna"s texting one person, and leaves inbound SMS unambiguous to route.
  - **Opt-out applies to the phone number, not the conversation** (see below).
  - When an earlier conversation has finished: never recontact after an opt-out, and don't start
    a new one while a call is booked. After a decline, a new form submission counts as fresh
    interest and starts a new conversation (open question, §7).

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
  (e.g. `Thursday 29 October 2026 at 12:00 pm`), which iOS and Android recognise and offer to add
  to the calendar. Because the model doesn't write it, the format can't drift. There are no links: the
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
| Second objection | Yes, a fixed goodbye from a template ("I'll leave it there...") | status `DECLINED` |
| Abusive lead (caught by moderation, or the model's flag) | No | status `ENDED`, reason `ABUSIVE` |

- **Opt-out keywords are handled in code before the model is called.** It's a legal requirement
  (Spam Act), so it must be deterministic and must not cost a token.
- **An opt-out covers the phone number.** If a number has ever opted out, no conversation starts
  for it and nothing is sent, whichever lead id it arrives under.
- **Opting out is one-way in the prototype.** Messages after STOP are stored but never answered,
  even "START" or "actually, I'd like that call". Re-subscribing is designed but deliberately not
  built (§8).
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
- Agent-led rescheduling and cancellation after a booking
- Deterministic guardrails: opt-out, 320 characters, terminal states, objection count, message cap
- Duplicate-lead protection by phone number: one open conversation per number, opt-out per number
- Moderation: OpenAI's moderation endpoint on inbound messages, used to detect abuse
- Minimal web UI: seed leads, play the lead, view structured output, reset
- Unit tests plus a small set of opt-in end-to-end scenarios against the real model

**Out of scope (deliberately)**
- Real SMS, real Calendly, real lead API, which the platform already owns
- Follow-up nudges for leads who never reply (high-value, but it's a platform scheduling concern;
  see §8)
- Multi-customer configuration (see the CTO's "build for this customer first")
- Merging duplicate lead records (lead management belongs to the platform)
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
- **Synchronous requests, but no transaction held across the model call.** The platform's
  webhook waits for the turn (2-15 s). Splitting the turn (§2) keeps the database free while it
  waits. If a newer SMS arrives mid-turn, the older reply is dropped and the newer turn answers
  both (opt-out and abuse flags still apply), and two turns can't both commit (optimistic lock).
  In production I'd still take inbound messages off a queue, so a slow or failing OpenAI call never
  ties up the platform's webhook.
- **Plain HTTP to OpenAI, not the SDK.** One `RestClient` call per round, so the exact request
  (strict schema, `store: false`, tools, encrypted reasoning) is visible in our code. The cost is
  keeping up with the API ourselves.
- **Tool results aren't kept between turns, only the SMS text.** When a lead picks "the first
  one", the model looks the times up again before booking. That re-checks the slot is still free,
  but costs a round: booking turns take about 15 s. Keeping the last offered slots on the
  conversation would cut that (§8).
- **A templated opener** gives up personalisation on the first message in exchange for
  predictability and approval-ability.
- **Replying at any hour, not only 8am–8pm.** I first planned an 8am–8pm reply window, with
  overnight replies deferred to the morning, because an instant 2am reply hints that it's a bot.
  I dropped it: someone who messages at 10pm is engaged right then, and an answer the next
  morning risks losing them. It also removed a scheduler and a deferred-message state from the
  prototype. Worth revisiting if late-night replies turn out to hurt trust or opt-out rates.

### Load test: is 750 leads a week a problem?
`./mvnw test -Pload` runs the full app over real HTTP, with a fake model that waits 1-2 s per
reply (like a real call, but spending no tokens) and a lead API that invents leads. Results on my
Windows laptop (in-memory H2):

| Scenario | Requests | Errors | p50 / p95 / p99 | Wall time |
|---|---|---|---|---|
| Cold start: first 50 requests at once | 50 | 0 | 1360 / 1385 / 1386 ms | n/a |
| A week's leads (750) started at once, 50 in flight | 750 | 0 | 35 / 61 / 81 ms | 0.6 s |
| A day's leads (150) all talking at once, 5-10 SMS each, replying instantly | 1,148 | 0 | 1580 / 2181 / 2446 ms | 17.1 s |

Every SMS got exactly one reply, and none were lost or duplicated.

**What it shows**
- **Volume isn't the problem.** A whole week of leads starts in under a second. A real day is
  about 1,000 turns spread over hours, roughly one every half a minute. The test pushed 67 turns a
  second.
- **Splitting the turn is what makes that possible.** A turn takes the model's time plus about
  50-200 ms. The database pool has only 10 connections, but no connection is held during the model
  call. If the call ran inside the transaction, 10 connections at about 1.5 s each would cap
  throughput near 7 turns a second, and 150 conversations at once would queue for tens of seconds.
- **Cold start costs about 1.4 s for the first burst.** That matters only straight after a deploy.

**What would break first, in order**
1. **OpenAI rate limits and outages,** not our code. A failed turn already returns `503` and
   resumes on retry; production would add backoff, and alerting on failure rate.
2. **Tomcat's 200 request threads,** each blocked for a 1-2 s model call. That's a ceiling of
   roughly 100-130 turns a second per instance, far above Comparato's volume. Virtual threads
   (`spring.threads.virtual.enabled`), or taking turns off a queue, would lift it.
3. **H2 and one instance:** fine for the prototype, but production needs Postgres and two or more
   instances. The optimistic lock already makes concurrent turns on one conversation safe.
4. **The in-memory Calendly mock and its bookings,** which are lost on restart. Real Calendly has
   its own rate limits to check.

**Cost, from the real `gpt-6.1-sol` run:** about 2,000 input tokens a turn, ~95% served from the
prompt cache, and about 85 output tokens. At the listed $2 / $10 per million (cached input is
cheaper still), that's well under a cent a turn. Even if every lead replied 5-10 times, 750 leads
a week is single-digit dollars a week. (Prices from third-party listings; check OpenAI's.)

**What it doesn't measure:** real model latency variance, real network, Postgres, or many
instances. It's a sanity check of our own code under Comparato's volume, not a capacity plan.

## 6. Beyond the prototype

### Measuring conversation quality at scale
At 750 leads a week nobody reads every conversation, so quality has to come from numbers on the
records already kept (each turn's output, tools, tokens and the conversation's end state), plus a
little human reading. **Outcomes per lead contacted:** booking rate, opt-out rate, decline rate,
messages and hours to a booking, and cost per booking. **Hygiene on every live turn,** checked by
the same code as the scenario tests: retries for length, moderation ends, message-cap ends, tool
errors, and replies before a booking that don't ask anything. **A weekly read** of about 20
conversations, weighted to declines, opt-outs and health-event mentions. **For a new prompt
version:** each conversation pins its version, so once `v2` passes the scenarios (and the eval
suite), half of new leads can get it and the two be compared on the same outcomes. **The metric
I'd stop trusting first is the booking rate.** A pushier agent raises it while no-shows, opt-outs
and complaints rise with it, and it says nothing about whether the call happened. It only means
something next to Comparato's show rate for those calls and the opt-out rate.

### A second customer on the same engine
The seams are mostly there, and the Comparato-specific parts are known:
- **Agent bundle per customer.** Prompts and templates already live under
  `agents/<customer>/<version>`. The customer (and its current bundle) moves from config
  (`AgentProperties`) onto the lead and the conversation.
- **Lead fields.** `currentProvider` and `currentPremium` are Comparato's custom fields, wired into
  `Lead`, the choice of opener and `PromptBuilder`. They'd become a per-customer map of custom
  fields that the bundle's templates and prompt refer to by name.
- **Calendar.** A `CalendlyClient` per customer (their account and event type), with their
  advisors' hours and time zone instead of Melbourne's.
- **Country.** Phone parsing (default region `AU`), `AuState` time zones and the Spam Act opt-out
  words are Australian.
- **Suppression list.** Consent is given to a customer, so `opt_out` needs a customer id: a STOP to
  Comparato shouldn't unsubscribe the person from someone else.
- **Rules stay as code.** The objection count, message cap and moderation categories are shared;
  each customer gets its own numbers in config.

## 7. Questions for the agent designer / Comparato

**Answered**
- *Persona?* "Anna from Comparato".
- *Booking confirmation contents?* The name of the advisor who'll call, and the time in a format
  the phone turns into a calendar event. No links, because the advisor calls the lead.
- *Advisor name?* It's on the Calendly invite, so it's available from the booking.
- *Reschedule or cancel after booking?* The agent handles it end to end, with no human involved.
- *Reply hours?* Any time the lead messages (see §5).
- *Advisor availability?* One Calendly event type pooling all advisors (round robin).
- *AI disclosure?* As the brief says: if the lead asks, the agent admits it's an AI.
- *Does opting out cancel a booked call?* No. STOP stops SMS only; the call the lead booked
  stands, and the platform sees `goalMet: true, unsubscribed: true`.

**Still open**
- **A number that comes back after declining:** contact them again straight away, or wait some
  number of days? Assumed: a new form submission is fresh interest, so contact them again.
- **A number that opted out submits the form again:** is that fresh consent to contact them?
  Assumed no (blocked) until Comparato's compliance people confirm, since a wrong yes is a breach.
- **Who owns STOP/START at the SMS provider:** if Enrola's platform already handles them, it should
  tell the agent rather than the agent parsing keywords itself.
- The designer said more example transcripts are coming, and those will likely raise more.

## 8. With more time

### Planned last, if time permits: a cost-aware eval suite (delivery plan row 17, not built)
The CTO wants to move towards evals but worries their cost will outpace their value. So the
suite is designed for cents per run, and **you only pay again for what changed**. It's mainly a
demonstration of measuring quality against cost across OpenAI models and prompt versions, not a
tuning harness.

- **Mostly one-turn cases.** A case is a fixed transcript so far plus the lead's next SMS: one model
  call per case, repeatable, testing one behaviour. A few multi-turn cases script every lead reply,
  so no second model is needed to play the lead.
- **Checks done in code, not by another model.** One assertion per behaviour, on the structured
  output: `objection`/`unsubscribe`/`abusive` set when they should be, reply ≤ 320 characters, no
  `!`, admits being an AI when asked, ends with a question, never names a fund. An optional cheap
  model-as-judge for exactly one fuzzy thing (tone), off by default. No single "9/10" score.
- **A results cache** keyed by model + prompt-bundle hash + case hash. Re-running with nothing
  changed costs $0; editing the prompt re-runs only what that prompt affects.
- **A hard budget per run** (e.g. $0.50), tallied from the token usage in each response; the run
  stops when it's reached.
- **On demand, never in CI** (`-Peval`), like the load test.
- **The same code path as production:** the real `PromptBuilder` and OpenAI client, so it judges
  what ships. This needs the client to return token usage and latency, not just log them.
- **About 10 starting cases, as JSON files a designer can add to:** bot question, prompt
  injection, off-topic joke, first vs second objection, soft opt-out, abuse, a health event, a
  question about fees, a lead with no provider, and the brief's Lauren transcript. Booking cases
  follow the booking tools.
- **Report** (`target/eval-report.md`): per model, pass rate, p50 latency, tokens in (and how much
  was cached) and out, cost per case, and **cost per passing case**, plus which assertions failed.
  Prices live in a config table, since they change. Default comparison: `gpt-6.1-sol` (current),
  `gpt-6-luna` (cheap), `gpt-6-astra` (top tier).
- **How it's used:** a new prompt version or model is promoted only if it passes every safety
  assertion the current one passes, with the cost difference visible next to it.

### Potential feature: re-subscribing after an opt-out (designed, not built)
A lead who texts STOP and later changes their mind is stuck today. The Spam Act allows consent to
be given again, so revival should be possible, but only on clear, deliberate signals:
1. **An opt-in keyword** (`START`, `UNSTOP`, `resubscribe`; a per-locale asset like the opt-out
   list) takes the number off the suppression list, sets the conversation back to `ACTIVE`, and
   sends a fixed confirmation ("You're back on. Happy to pick up where we left off - still keen on
   that call?"). The opt-out record is kept, with a `resubscribed_at` timestamp, so both events
   stay on file.
2. **Anything else after STOP** gets one fixed, non-marketing reply, once ("You've opted out of
   messages from Comparato. Reply START if you'd like to hear from us again."), then silence.
3. **A new form submission** from an opted-out number stays blocked until Comparato confirms it
   counts as consent (§7).

Roughly: a keyword asset, two templates, one column, one state change and tests. Left out because
it isn't needed to get Comparato live, and the compliance questions in §7 should be answered first.

### Other next steps
- **Faster booking turns:** keep the last offered slots on the conversation, so "the first one"
  books in one round instead of re-fetching (§5).
- **Virtual threads and a queue for turns;** backoff on OpenAI rate limits; alerts on turn
  failures.
- **Postgres** in production and in CI (Testcontainers), instead of H2 in PostgreSQL mode.

### What I'd delete
- **The stub agent's objection phrases.** They existed only to try the objection flow before the
  model was wired in.
- **Reset clearing the opt-out.** A testing aid that must never ship: one line in
  `ConversationStarter.restart`.
- **`TurnViews`' handling of the old turn-record format,** once the prototype database is thrown
  away.

- **Prompt evaluation**: replay a library of real transcripts against a new prompt version and
  compare specific, checkable outcomes (booking rate, opt-out rate, messages to booking,
  guardrail violations) before promoting it.
- **Per-customer configuration**: prompt, info pack, lead custom fields, opener templates and
  booking provider as data, turning this into the product the CTO described.
- **Follow-up nudges** for unresponsive leads, and human handoff for edge cases.
- **Queue-based processing**, retries with idempotency on booking, and per-conversation cost
  tracking.

## 9. How I used AI

> _Draft: to review, correct and put in my own words before submitting. The two cost figures need
> filling in._

**What I used:** Claude Code (in the Claude desktop app) as a pair for the whole build. OpenAI only
for what the product does: the agent's replies and moderation.

**How:** I kept the decisions and had the AI do most of the typing. The brief became this document
first, then a 16-row delivery plan, and each row went in as its own pull request (20 in all) that I
read, ran locally and merged before the next one started. The product calls were mine, and several
show as reversals in the history:
- an 8am-8pm reply window, added and then dropped (§5)
- AI disclosure: briefly considered not admitting it, then kept as the brief says
- the Comparato info pack: the AI paraphrased it, and I had it replaced verbatim
- openers aimed at gold hospital and extras, after rereading the designer's note on who the leads
  are
- STOP doesn't cancel a booked call; re-subscribing deferred; the eval suite scoped to "if time
  permits"
- UI details from using it myself: Enter to send, a typing indicator, status colours

**What it got wrong, and what caught it:**
- a paraphrased info pack: caught reviewing the prompt
- two docs commits pushed after their PR had merged: spotted later and cherry-picked
- template files that would read differently on Windows and Linux: caught from git's line-ending
  warning
- a test database silently swapped for a different H2 mode: caught by a failing test
- a session id appearing in redirect URLs: caught in the browser
- a test that read the clock twice and failed only on Linux: caught by CI

That's why the tests, the Linux CI, the browser checks and the scenarios exist.

**What it cost:** OpenAI for the product during development: about [fill in] (a scenario run is
about 4 cents; the load test uses none). Claude Code: [fill in from my usage page]. Worth it: the
plan, PR and review loop covered far more of the brief than I'd have managed by hand in the time,
and the decisions stayed mine.
