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

Spring Boot (Java 21) with MVC, Thymeleaf and Bootstrap, matching Enrola's stack. An inbound SMS
hits an endpoint that stands in for the Enrola platform. The service loads the conversation from
the database, applies the deterministic pre-checks (opt-out keywords, conversation already
ended), then makes **one** OpenAI Responses API call with a single versioned system prompt, the
Comparato info pack, the lead's data and the full transcript. The model can call two tools,
`get_available_slots` and `book_call`, backed by a mocked Calendly client, and returns a
**structured output** (reply text plus state flags). Code validates that output (length, state
transitions), persists it, and returns it to the platform.

No multi-agent orchestration and no agent framework. One prompt, structured output and tools, as
the CTO suggested. The interesting engineering is in the seams around the model, not in chaining
models together.

## 3. Assumptions

### Platform and integration
- **Enrola's platform owns SMS transport, lead ingestion and scheduling the first message.** The
  agent is a service with two operations: *start a conversation for lead X* and *handle inbound
  message Y for lead X*. Both return the outbound message (if any) and the conversation state.
  The prototype exposes these as REST endpoints so the boundary is explicit.
- **The lead API and Calendly are mocked** behind interfaces (`LeadClient`, `CalendlyClient`) so
  swapping in real clients doesn't touch the agent.
- Calendly returns availability as UTC instants. The mock produces weekday slots every 30 minutes
  during advisor business hours (assumed 9:00–17:00 Melbourne time, since that's Comparato's HQ),
  with some already taken so the "that time isn't free, here are the closest" path gets exercised.
- Booking needs name, phone, email and a start time, all from the lead record. I assume email is
  present. If it isn't, booking still goes ahead with phone only, because the advisor calls by
  phone anyway.

### Conversation design
- **The opening message is a template, not LLM-generated.** It's the one message every lead gets
  (750 a week), it carries the opt-out text, and Comparato will likely want to approve it word
  for word. Variants depend on whether we know the current provider. Costs nothing and can't go
  wrong.
- **The persona is "Anna from Comparato"**, as in the sample transcript. The agent admits it's an
  AI assistant whenever it's asked, and never claims to be human.
- **The agent doesn't give insurance advice.** Comparato operates under the PHII Code of Conduct.
  The agent can answer questions about Comparato itself (from the info pack) and about the call,
  but anything about which policy or cover is right gets deferred to the advisor. That's the
  reason for the call, so it also helps the goal.
- **Times are shown in the lead's local time**, derived from their state (WA is Perth time, NSW
  is Sydney time, and so on). The lead's state is the only time-zone signal we have.
- **Offered slots can go stale.** A lead might pick "12" two days after we offered it.
  `book_call` re-checks availability, and if the slot has gone the agent offers the nearest
  alternatives instead of confirming a booking that doesn't exist.
- **Tone** matches the sample transcript: short, plain, a little blunt, Australian, no
  exclamation marks, no "I hope this finds you well".

### Ending conversations (state the platform can rely on)
| Situation | Reply sent? | State |
|---|---|---|
| Call booked | Yes, a confirmation | `goalMet=true`, status `BOOKED` |
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
  conversation as a backstop. If the cap is hit, the conversation is flagged for a human rather
  than looping.

## 4. In scope / explicitly out of scope

**In scope**
- Agent turn: prompt file, structured output, tool calls, state persistence
- Mock lead API and mock Calendly (availability and booking)
- Deterministic guardrails: opt-out, 320 characters, terminal states, objection count, message cap
- Moderation: OpenAI's moderation endpoint on inbound messages, used to detect abuse
- Minimal web UI: seed leads, play the lead, view structured output, reset
- Unit tests plus a small set of opt-in end-to-end scenarios against the real model

**Out of scope (deliberately)**
- Real SMS, real Calendly, real lead API, which the platform already owns
- Follow-up nudges for leads who never reply (high-value, but it's a platform scheduling concern;
  see §7)
- Rescheduling or cancelling an existing booking (the agent hands this to a human for now)
- Quiet hours / contact-time rules (the platform schedules sends; noted as a risk)
- Multi-customer configuration (see the CTO's "build for this customer first")
- Authentication, deployment, production observability

## 5. Trade-offs I'm aware of

- **Model-driven conversation with code-enforced invariants, not a hand-coded state machine.**
  The designer's five steps are guidance for the prompt, not states in code. Real leads skip steps
  ("just call me Tuesday") and a rigid state machine fights that. The cost is less predictability,
  so the invariants that *must* hold are enforced in code.
- **Full transcript sent on every turn** instead of OpenAI's `previous_response_id` threading.
  We already store the conversation, SMS conversations are short (~10 turns), and owning the
  context means turns can be replayed against a new prompt version for evaluation. Cost grows
  with conversation length, which is fine at this length.
- **H2 file database** instead of Postgres. Pull-and-run with no Docker, and state survives a
  restart. JPA keeps the move to Postgres a configuration change.
- **Synchronous request handling.** Fine for a prototype. At 750 leads a week the volume is tiny
  (peak a few requests a minute), but in production I'd still take inbound messages off a queue so
  a slow OpenAI call or retry never blocks the platform's webhook. Two SMS arriving seconds apart
  should be batched into one turn. The prototype serialises turns per conversation instead.
- **A templated opener** gives up personalisation on the first message in exchange for
  predictability and approval-ability.

## 6. Open questions for the agent designer / Comparato

- Is "Anna" OK as a persona name, given we'll admit to being an AI when asked?
- Should the booking confirmation include anything else (advisor name, calendar link)?
- What should happen when a booked lead wants to reschedule or cancel? (Currently: hand to a
  human.)
- What contact hours are acceptable for replies? (The agent always replies to an inbound message.
  Only the first message is scheduled.)
- Advisor availability: one Calendly event for all advisors, or per-advisor?

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

_To be written once the skeleton exists._ Requires JDK 21. Copy `.env.example` to `.env` and add
your OpenAI API key.

## 9. How I used AI

_To be written as I go._
