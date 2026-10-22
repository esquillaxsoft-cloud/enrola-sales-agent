# Eval cases

One file per case. Each case starts from the lead's real opener, adds `history` (turns after the
opener), then sends `message` and judges the agent's single reply. `./mvnw test -Peval` runs them.

```json
{
  "id": "asks-if-bot",
  "about": "What it checks, in a sentence",
  "lead": "lead-john | lead-jane | lead-lauren",
  "history": [ { "from": "lead | agent", "body": "..." } ],
  "message": "The lead's next SMS",
  "expect": {
    "reply": "present | absent",
    "flags": { "objection": true },          // unsubscribe and abusive default to false; all three are checked
    "replyMatches": ["(?i)regex"],           // each must match
    "replyNotMatches": ["(?i)regex"],        // none may match
    "endsWithQuestion": true,
    "stageIn": ["SUGGEST_CALL"]
  }
}
```

Every reply is also checked against the SMS rules: at most 320 characters, no `!`.
Adding a case costs one model call per model the first time; after that it's cached.
