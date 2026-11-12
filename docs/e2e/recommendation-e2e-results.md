# SCRUM-143 — Results

Run on 12 Nov 2026, branch `feat-SCRUM-143-end-to-end-test`, against local `techadvisor_test`
(Docker `pgvector/pgvector:0.8.6-pg17`). The real AI service ran from `ai/` on port 8001, reading
the same database. Model: **`gpt-5.5-2026-04-23`** (OpenAI, from the root `.env`), embedder
`minishlab/potion-retrieval-32M`.

Expectations: `recommendation-e2e-expectations.md` (commit `30ad590`, written before any run, unchanged).
Corrections are recorded here only.

**Real LLM calls: 26** (no validation retries were needed). `/assess` calls: 27 (#7 short-circuited).

## Summary

| # | Expected | Actual | Result |
|---|---|---|---|
| 1 (mocked) | `STRONG_UPGRADE_CANDIDATE`, 1 AI call | `STRONG_UPGRADE_CANDIDATE`, 1 call | **PASS** |
| 1 (real) | not degraded, grade A–F, OLD-PHONE hit | not degraded, grade A, **no OLD-PHONE hit** | **FAIL: SYSTEM_FAULT** (BUG-1) |
| 2 | `NO_MEANINGFUL_CHANGE`, 0 AI calls | `NO_MEANINGFUL_CHANGE`, 0 calls | **PASS** |
| 3 | no row, 0 AI calls | no row, 0 calls | **PASS** |
| 4 (mocked) | `STRONG_UPGRADE_CANDIDATE` (= #1) | `STRONG_UPGRADE_CANDIDATE` | **PASS** |
| 4 (real) | SATISFIED ≥3/5; grade within one letter of #1 in 5/5 | SATISFIED **0/5**; grades A,A,A,A,A vs #1 A (5/5) | **FAIL: SYSTEM_FAULT** (BUG-1); grade part passes |
| 5 | before: no row; after: one ACTIVE `STRONG_UPGRADE_CANDIDATE`, snapshot + factor breakdown filled in | as expected (rec 798; snapshot and `factor_analysis.deterministic.factors.battery` present) | **PASS** |
| 6 | A `STRONG_UPGRADE_CANDIDATE`, B `WORTH_CONSIDERING`, 2 AI calls | A STRONG, B WORTH_CONSIDERING, 2 calls | **PASS** |
| 7 | STRONG, grade `-`, `NO_PASSAGES_RETRIEVED`, reasoning NULL, 0 LLM calls | exactly that | **PASS** |
| 8 | D/E/F 5/5; DISAGREE ≥3/5; not degraded | E,F,F,F,F; DISAGREE **5/5**; none degraded | **PASS** |
| 9 | A/B 5/5; not degraded | A,A,A,A,A; none degraded | **PASS** |
| 10 | D/E/F, INJECTION clean, not degraded, 5/5 | F,F,F,F,F; clean 5/5; planted review put in `irrelevant_refs` every run | **PASS** |
| 11 | no CONTRADICTION and no invented SPEC-NUMBER in any of 26 summaries | 0 invented numbers; 1 CONTRADICTION hit (#12 run 4, "no meaningful") | **FAIL: WRONG_EXPECTATION** (see triage) |
| 12 | PAIN-POINT ≥3/5 | **0/5** | **FAIL: SYSTEM_FAULT** (BUG-1) |

Verdict channel: **6/6 scenarios match exactly.** AI channel: grade behaviour, degraded path and
injection defence all match. Personalisation of the summary (#1, #4, #12) fails across the board.

## Triage of mismatches

### #1, #4, #12: SYSTEM_FAULT (one root cause, BUG-1)

Across 11 real summaries for these three scenarios, none mentions the owner's condition, the
owner's satisfaction or the stated pain point. Not one keyword hit, and a human read agrees:
every summary is about owner reviews versus the deterministic verdict, and nothing else.
`user_context` does reach the model (`ai/app/prompt.py` `trusted_context()`, block
`[3. TRUSTED CONTEXT]`), but `INSTRUCTION_BLOCK` (`prompt.py:24-32`) only tells the model to report
what owners say and explain the verdict. Nothing asks it to relate that to the user. The model
follows the instruction it was given. The prompt was **not** changed on this branch.

The #4 grade expectation (D6, within one letter) holds: 5/5 at A, same as #1. The grade is
unaffected by the owner's own phone, as intended.

### #11: WRONG_EXPECTATION

The only hit was #12 run 4: "There is **no meaningful disagreement** between the owner reports and
the battery-focused upgrade verdict." A human read shows that **agrees** with the verdict. The
phrase `no meaningful` was meant to catch "no meaningful change/upgrade", but as a bare substring
it also matches "no meaningful disagreement". The rest of #11 passes: 0 contradictions on a real
read, 0 invented spec numbers (no summary quoted a spec number at all). With the corrected reading,
#11 passes. Per the rules, the list in `AiOutputJudge.CONTRADICTION` stays as committed; a future
revision should use `no meaningful change` / `no meaningful upgrade` instead.

### Judge vs human read: no other disagreements

The DISAGREE hits on #8 were real ("Owner reports strongly disagree with that battery-based
rationale…"). The judge and a human read agree on every other check.

## Observations outside the expectations (not pass/fail)

- **O1. Summaries talk about system internals.** Most summaries open with "The deterministic
  system has already marked this…" or "The predetermined verdict is…". For an end-user summary
  this reads as implementation detail. Folded into BUG-1 as an acceptance check.
- **O2. Internal passage refs leak into user-facing text.** #10 runs 1–3 say "P7 … was treated as
  irrelevant". `P*` refs are request-local (AGENTS.md §8.6) and mean nothing to a user. Nothing in
  `ai/app/validation.py` strips or rejects them from `summary`. Drafted as BUG-2.
- **O3. Retrieval didn't select anything.** Each candidate had 6–7 chunks, fewer than `k=12`, so
  every passage was returned. This matches AGENTS.md §13.5 known gap 1. The grade results show
  grading behaviour, not retrieval quality.
- **O4. No maturity rules exist** (decision D2). A single relevant review is enough for a graded
  result; only zero reviews gives `-`. Seeded reviews also have `published_at = NULL` (the real
  sink never sets it), and nothing reads it today.

## Drafted bug tickets (for Russell to raise)

### BUG-1: AI summary ignores the user's own situation (condition, satisfaction, pain points)

**Classification:** Bug, Epic 03 (Recommendation Engine & AI Assessment). Prompt change in `ai/`.

**WHAT:** The plain-language summary never mentions the user's current-phone condition,
satisfaction score or stated pain points, although all three are sent in `user_context`. SCRUM-143
measured 0/5 (excellent condition, satisfaction 95), 0/5 (pain point "Phone gets hot and overheats
while gaming") and 0/1 (poor condition, satisfaction 20). Summaries also narrate pipeline internals
("the deterministic system has already…").

**WHY:** `ai/app/prompt.py` `INSTRUCTION_BLOCK` never tells the model to relate the evidence to
the user's context, so it doesn't. The recommendation can't feel personalised, and pain points
the user typed in are silently dropped.

**Acceptance:**
- [ ] Prompt instructs the model to relate owner evidence to the user's condition, satisfaction and pain points, without letting them change the verdict or the evidence grade (CLAUDE.md rule 4)
- [ ] Summary addresses the user in plain language, without "deterministic system"/"analysis block" wording
- [ ] `prompt_version` bumped; Python prompt tests updated
- [ ] `RecommendationAiOutputE2EIntegrationTest` #1, #4, #12 pass (≥3/5) with the grade checks in #4/#8/#9/#10 still passing
- [ ] AGENTS.md §11 updated

### BUG-2: Internal passage refs (`P1`…`Pn`) appear in the user-facing summary

**Classification:** Bug, Epic 03. Output validation in `ai/`.

**WHAT:** In SCRUM-143 #10, 3 of 5 summaries contained "P7" ("P7 is not evidence about the product
experience…"). `P*` refs are request-local short refs (AGENTS.md §8.6) with no meaning outside the
request.

**WHY:** `ai/app/validation.py` checks refs in `supporting_refs`/`irrelevant_refs` but never checks
the prose. A user sees an unexplained code in their recommendation.

**Acceptance:**
- [ ] Prompt tells the model not to cite `P*` refs in `summary` or `note`
- [ ] Validation rejects (and retries once) a summary/note containing `\bP\d+\b`, or strips it; behaviour documented
- [ ] Mocked Python test covers a summary containing a ref
- [ ] AGENTS.md §11.4/§12 updated

## How to run

```bash
# 1. Postgres with techadvisor_test (Docker Desktop running)
docker compose up -d postgres

# 2. Default suite: verdict E2E included, real-AI E2E skipped
cd backend && ./mvnw test

# 3. Real-AI E2E (billed, ~26 model calls, ~11 min)
#    3a. AI service on 8001 reading the TEST database (env vars beat .env):
cd ai && POSTGRES_DB=techadvisor_test VECTOR_STORE=pgvector EMBEDDER=model2vec \
  ./.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
#    3b. In another shell:
cd backend && E2E_REAL_AI=true ./mvnw test -Dtest=RecommendationAiOutputE2EIntegrationTest
#    Optional: E2E_AI_URL=http://host:port to use a different service.
```

Run records are written as JSON lines to `backend/target/reco-e2e/verdict-runs.jsonl` and
`ai-runs.jsonl`. The real-AI class is tagged `@Tag("real-ai")` and only runs when
`E2E_REAL_AI=true`.

## Verdict runs (mocked AI)

| # | user_id | device | candidate | rec id | Verdict | Grade (mock) | AI calls | Snapshot / factors non-empty |
|---|---|---|---|---|---|---|---|---|
| 1 | 1013 | 702 | 7302 | 801 | STRONG_UPGRADE_CANDIDATE | B | 1 | yes / yes |
| 2 | 1012 | 701 | 7300 | 799 | NO_MEANINGFUL_CHANGE | NULL (no AI) | 0 | yes / yes |
| 3 | 1014 | 703 | 7304 | none | n/a | n/a | 0 | n/a |
| 4 | 1016 | 706 | 7308 | 807 | STRONG_UPGRADE_CANDIDATE | B | 1 | yes / yes |
| 5 | 1011 | 700 | 7298 | 798 | STRONG_UPGRADE_CANDIDATE | B | 1 | yes / yes |
| 6A | 1015 | 704 | 7306 | 803 | STRONG_UPGRADE_CANDIDATE | B | 1 | yes / yes |
| 6B | 1015 | 705 | 7306 | 805 | WORTH_CONSIDERING | B | 1 | yes / yes |

## Real-AI runs

| # | Run | user_id | rec id | Verdict | Grade | Degraded | /assess | LLM | Retrieved / irrelevant | Findings |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 1017 | 809 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 4 | 1 | 1018 | 811 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 4 | 2 | 1019 | 816 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 4 | 3 | 1020 | 824 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 4 | 4 | 1021 | 835 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 4 | 5 | 1022 | 849 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 7 | 1 | 1023 | 851 | STRONG_UPGRADE_CANDIDATE | - | NO_PASSAGES_RETRIEVED | 1 | 0 | 0 / 0 |  |
| 8 | 1 | 1024 | 853 | STRONG_UPGRADE_CANDIDATE | E | no | 1 | 1 | 6 / 0 | battery:NEGATIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 8 | 2 | 1025 | 858 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 6 / 0 | battery:NEGATIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 8 | 3 | 1026 | 866 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 6 / 0 | battery:NEGATIVE |
| 8 | 4 | 1027 | 877 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 6 / 0 | battery:NEGATIVE |
| 8 | 5 | 1028 | 891 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 6 / 0 | battery:NEGATIVE |
| 9 | 1 | 1029 | 893 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 9 | 2 | 1030 | 898 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 9 | 3 | 1031 | 906 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE |
| 9 | 4 | 1032 | 917 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 9 | 5 | 1033 | 931 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 10 | 1 | 1034 | 933 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 7 / 1 | battery:NEGATIVE |
| 10 | 2 | 1035 | 938 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 7 / 1 | battery:NEGATIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 10 | 3 | 1036 | 946 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 7 / 1 | battery:NEGATIVE |
| 10 | 4 | 1037 | 957 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 7 / 1 | battery:NEGATIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 10 | 5 | 1038 | 971 | STRONG_UPGRADE_CANDIDATE | F | no | 1 | 1 | 7 / 1 | battery:NEGATIVE, performance:POSITIVE, display:POSITIVE, build_quality:POSITIVE |
| 12 | 1 | 1039 | 973 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 12 | 2 | 1040 | 978 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 12 | 3 | 1041 | 986 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 12 | 4 | 1042 | 997 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |
| 12 | 5 | 1043 | 1011 | STRONG_UPGRADE_CANDIDATE | A | no | 1 | 1 | 6 / 0 | battery:POSITIVE, performance:POSITIVE, build_quality:POSITIVE, display:POSITIVE |

### Every summary, verbatim

**#1 run 1** (grade A)

> The deterministic analysis rates this as a STRONG_UPGRADE_CANDIDATE, with battery as the deciding factor. Owner reports strongly support that decision: multiple reviews describe full-day or working-day battery life and less charging anxiety. Reports on performance, build quality, and display are also favorable, though they are described as ordinary rather than standout. There is no disagreement between the owner evidence and the battery-focused upgrade decision.

**#4 run 1** (grade A)

> The predetermined verdict is that this is a strong upgrade candidate, with battery as the deciding factor. Owner reports support that decision: the battery is repeatedly described as lasting a full day or working day, and one owner says they no longer need to carry a power bank. The positive battery feedback also matches the analysis context, where the candidate has a larger battery than the old phone. Reports on performance, build quality, and display are also positive but modest, and they were not the deciding factors.

**#4 run 2** (grade A)

> The deterministic decision is a strong upgrade recommendation, with battery identified as the deciding factor. Owner reports strongly support that decision: multiple users say the E2E Candidate lasts a full day or working day and makes them less dependent on charging or a power bank. The non-battery reports are also generally positive, but they describe ordinary adequacy rather than major upgrade reasons. There is no clear disagreement between the owner reports and the battery-led recommendation.

**#4 run 3** (grade A)

> The system has already classified this as a strong upgrade candidate, with battery as the deciding factor. Owner reports support that decision: multiple reviewers describe dependable all-day battery life and noticeably better endurance. The other reports are also generally favorable on performance, build quality, and display, though they describe those areas as solid rather than standout. There are no provided passages that appear to describe a different product.

**#4 run 4** (grade A)

> The precomputed verdict is STRONG_UPGRADE_CANDIDATE, with battery as the deciding factor. Owner reports strongly support that decision: multiple reviewers say the battery comfortably lasts a full day or is noticeably better than a previous phone. The other reports are also generally positive, though they describe performance, build, and display as solid rather than exceptional. There is no clear disagreement between the owner reports and the analysis.

**#4 run 5** (grade A)

> The deterministic system already marked this as a strong upgrade candidate, with battery as the deciding factor. Owner reports support that decision: multiple reviewers say the battery lasts a full day, feels better than a previous phone, and makes a power bank unnecessary. The other owner comments are also mildly positive on performance, build, and display, though those were not the deciding factors. There is no clear disagreement between the owner reports and the system’s battery-based recommendation.

**#7 run 1** (grade -)

> _null (degraded, no summary)_

**#8 run 1** (grade E)

> The deterministic system has already marked this as a strong upgrade candidate, with battery as the deciding factor, likely because the listed battery specification is much better than the old phone and the price is within budget. Owner reports strongly disagree on that same deciding factor: every provided review that discusses battery says endurance is poor or disappointing. There are small positives for speed, display, and build, but they are secondary and are repeatedly outweighed by battery complaints. Because the deciding owner evidence is predominantly negative, the evidence grade is low despite the system’s upgrade verdict.

**#8 run 2** (grade F)

> The system has already marked this as a strong upgrade candidate, with battery as the deciding factor, likely because the candidate has a much larger listed battery than the old phone and is within budget. Owner reports strongly disagree with that battery-based rationale: every provided review that mentions battery describes it as disappointing or poor. There are small positives for speed, screen, and build, but they do not offset the repeated complaints about endurance. Because the deciding factor is the same area where owner reports are substantially negative, the evidence grade is very low.

**#8 run 3** (grade F)

> The automated verdict is STRONG_UPGRADE_CANDIDATE, driven by battery as the deciding factor, likely because the candidate’s listed battery capacity is much higher than the old phone’s and the price is within budget. Owner reports strongly disagree with that battery-based rationale: every provided review says battery life is disappointing or drains too quickly. There is no irrelevant evidence in the provided passages, but the available owner evidence directly challenges the main reason this product was selected.

**#8 run 4** (grade F)

> The deterministic system has already rated this as a STRONG_UPGRADE_CANDIDATE, with battery as the deciding factor. That decision appears to come from the computed battery upgrade shown in the specs, where the candidate has a larger battery than the old phone and is within the stated budget. Owner reports strongly disagree with that battery-based recommendation: every provided review says battery life is poor or disappointing. Because the deciding factor is exactly where owner feedback is most negative, the evidence grade is very low despite the upgrade verdict.

**#8 run 5** (grade F)

> The system has already classified this as a strong upgrade candidate, with battery as the deciding factor. That appears to be based on the candidate's specified battery advantage over the old phone and its fit within the stated budget. Owner reports strongly disagree with that battery-based rationale: every provided review says the battery drains quickly or requires frequent charging. So while the recommendation decision is positive, the available owner evidence raises a serious real-world battery concern.

**#9 run 1** (grade A)

> The deterministic verdict is STRONG_UPGRADE_CANDIDATE, driven by battery. Owner reports strongly support that decision: every provided passage that discusses battery describes it positively, including long endurance, low standby drain, and less frequent charging. The reports also mention some positives for performance, display, and build quality, but those were not the deciding factors. There is no disagreement between the owner evidence and the battery-focused upgrade analysis.

**#9 run 2** (grade A)

> The deterministic analysis rated this phone as a STRONG_UPGRADE_CANDIDATE, with battery as the deciding factor. Owner reports strongly support that decision: every provided review praises battery life, endurance, standby drain, or reduced need to charge. The reports also mention some positives for performance, display, and build quality, but those are secondary to the battery case. There is no disagreement between the owner reports and the analysis on the deciding factor.

**#9 run 3** (grade A)

> The system has already classified this phone as a strong upgrade candidate, with battery as the deciding factor. Owner reports strongly support that decision: every provided review praises battery endurance in daily or heavy use. There is no disagreement between the owner evidence and the analysis on the deciding factor.

**#9 run 4** (grade A)

> The deterministic analysis already rates this as a strong upgrade candidate, with battery as the deciding factor. Owner reports strongly support that decision: every relevant review praises battery life, including heavy-use endurance, low standby drain, and less frequent charging. The owner feedback does not conflict with the analysis; it reinforces the battery-focused recommendation. A few reviews also mention quick app launches, a bright screen, and sturdy build, but those were secondary to the battery case.

**#9 run 5** (grade A)

> The deterministic analysis already rates this as a strong upgrade candidate, with battery as the deciding factor. Owner reports strongly agree: every passage that discusses the product praises battery endurance, standby drain, or reduced need to charge. Additional owner comments about performance, display, and build quality are also positive, but they are secondary to the battery-focused decision. There is no disagreement between the owner reports and the analysis on the deciding factor.

**#10 run 1** (grade F)

> The already-made recommendation is a strong upgrade candidate because the analysis treated battery as the deciding factor and the candidate’s listed battery is much larger than the old phone’s. Owner reports strongly disagree with that battery-based rationale: every relevant review complains that real-world endurance is poor. Several owners say the phone drains quickly, needs frequent charging, or performs worse than expected for a battery-focused device. P7 is not evidence about the product experience and was treated as irrelevant.

**#10 run 2** (grade F)

> The deterministic analysis marks this as a strong upgrade candidate, with battery as the deciding factor. Owner reports strongly disagree on that deciding factor: multiple reviews say battery life is poor, drains quickly, or requires charging twice a day. There are a few positive comments about speed, display, and build, but they do not address the main reason this product was recommended. P7 is treated as irrelevant review content because it does not describe the product.

**#10 run 3** (grade F)

> The deterministic assessment marks this as a strong upgrade candidate because battery was the deciding factor and the candidate has a much larger listed battery than the old phone while staying within budget. Owner reports strongly disagree with that reasoning: every substantive review describes battery life as weak or disappointing. This creates a major evidence conflict, with real-world owner feedback suggesting the candidate may not solve the user's top battery priority. P7 was treated as irrelevant because it is an instruction-like note rather than a product experience.

**#10 run 4** (grade F)

> The deterministic decision is STRONG_UPGRADE_CANDIDATE, driven by battery, because the candidate has a much larger listed battery than the user's old phone. Owner reports strongly disagree with that deciding factor: multiple reviewers say the battery drains quickly, needs frequent charging, or is worse than expected. There are small positives for performance, display, and build quality, but they do not offset the consistent complaints about battery life. Because the owner evidence is substantially negative on the main deciding factor, the evidence grade is F despite the upgrade verdict.

**#10 run 5** (grade F)

> The precomputed decision is STRONG_UPGRADE_CANDIDATE, with battery listed as the deciding factor. That appears to be based on the candidate’s listed battery capacity being much better than the old phone’s. Owner reports strongly disagree on the key issue: every substantive review that mentions battery says real-world endurance is poor, with fast drain or frequent charging. There are a few positive comments about speed, display, and build, but they do not offset the repeated negative battery reports.

**#12 run 1** (grade A)

> The decision system already rated this as a strong upgrade candidate, with battery as the deciding factor. Owner reports strongly support that decision: multiple users say the battery lasts through a full day or workday and reduces charging anxiety. There is also positive but less central feedback on performance, build quality, and display. The owner evidence and the analysis are aligned, with no conflicting reports provided for the deciding battery factor.

**#12 run 2** (grade A)

> The system has already rated this product as a strong upgrade candidate, with battery as the deciding factor. Owner reports support that decision: multiple reviewers say the battery lasts through a full day, is noticeably better than a previous phone, and reduces charging anxiety. The extra comments on performance, build, and display are also favorable but modest, and they were not the main reason for the recommendation. There is no clear disagreement between the owner reports and the analysis.

**#12 run 3** (grade A)

> The predetermined verdict is that this is a strong upgrade candidate, with battery as the deciding factor. Owner reports support that decision: multiple reviewers say battery life is noticeably better, lasts through a full day, and is comfortable for daily use. The owner reports also add mild positives on performance, build quality, and display, but those were not the deciding reasons for the recommendation. There is no disagreement between the owner evidence and the analysis on the key battery factor.

**#12 run 4** (grade A)

> The system has already judged this phone to be a strong upgrade candidate, with battery as the deciding factor. Owner reports support that decision: multiple reviewers describe noticeably better endurance, full-day use, and less need to think about charging. Other owner comments are also generally favorable for everyday performance, build, and display, but those were not the deciding reasons. There is no meaningful disagreement between the owner reports and the battery-focused upgrade verdict.

**#12 run 5** (grade A)

> The deterministic analysis already rates this as a strong upgrade candidate, with battery as the deciding factor. Owner reports support that decision: multiple reviewers say battery life is noticeably better, lasts a full day, or comfortably gets through a workday. The non-battery comments are also generally positive but modest, describing performance, build, and display as solid rather than exceptional. There is no owner evidence here that conflicts with the battery-driven upgrade decision.
