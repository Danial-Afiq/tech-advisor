# Preference-weighted scoring (SCRUM-39)

The deterministic Java scorer reads the priorities saved for each owned device.
It still produces one `upgrade_score` and one verdict; the AI service receives
that verdict and cannot change it.

For each measurable factor, the scorer averages its capped, signed specification
improvements, then calculates:

```text
weight = priority / sum of measured-factor priorities
contribution = improvement * weight
upgrade_score = round(clamp(sum of contributions, 0, 1))
```

Regressions reduce the total. Price still uses headroom against the device's
budget through the `value` factor, and candidate pruning still excludes devices
over budget. Missing specifications and factors without comparable numeric data
do not enter the weight denominator. The existing minimum specification coverage
gate remains independent of user priorities.

## Priority input policy

| Input | Scoring behavior |
| --- | --- |
| Missing/null/empty priorities | Equal weights using `default-priority`. |
| Valid integer | Clamp to `priority-min` / `priority-max`, then normalize. |
| Priority zero | No contribution, unless every measurable priority is zero, triggering equal weights. |
| Negative or excessive integer | Clamp to the configured bounds, including large legacy integers without overflow. |
| Omitted factor in a selected profile | Use `missing-priority`. |
| Null, text, fractional or other invalid value | Use `missing-priority`. |
| Unknown factor name | Ignore it. |
| Only unmeasurable factors selected | Equal weights over measured factors. |
| Malformed JSON or a non-object JSON value stored in the database | Treat as an empty profile; log the unreadable object. |
| No comparable factors | Score zero, `sufficient_data: false`, `NO_MEANINGFUL_CHANGE`. |

This policy applies when reading preferences for scoring. The device API's
existing validation still rejects malformed JSON, unknown keys and non-integer
priority values on new writes.

All scoring defaults are defined in `backend/src/main/resources/application.properties`.
The priority settings are `recommendation.scoring.priority-min`, `priority-max`,
`default-priority` and `missing-priority`. `.env.example` lists their environment
overrides. Band thresholds, coverage and precision remain in the same config
block. Invalid scale/default combinations fail at startup. The version is now
`v4`; change it whenever scoring semantics or calibration changes.

## Audit history

`factor_analysis.deterministic.factors` records each measured factor's effective
`priority`, normalized `weight`, unweighted `improvement`, weighted `contribution`,
impact label and contributing spec names. Impact labels describe the improvement;
deciding factors are selected by the size of their weighted contributions, with
factor names breaking ties deterministically.

Both persistence paths record the effective priorities in
`input_snapshot.scoring_priorities` and the version in `input_snapshot.scoring_version`.
These are copied from the classification result, so later preference changes
cannot change the priorities attached to a historical score. The AI path also
keeps its existing request preferences snapshot. No migration or assessment API
contract change is needed.

## Acceptance fixtures

`backend/src/test/resources/scoring/priority-fixtures.json` contains fixed owned
and candidate specifications, priorities, budget, price, expected score and verdict.
The shipped configuration produces:

| Candidate | Battery/performance priorities | Score | Verdict |
| --- | --- | --- | --- |
| RAM doubles; battery unchanged | 5 / 1 | 0.17 | `NO_MEANINGFUL_CHANGE` |
| Battery improves by 40%; RAM unchanged | 5 / 1 | 0.67 | `WORTH_CONSIDERING` |
| Same battery candidate | 1 / 5 | 0.13 | `NO_MEANINGFUL_CHANGE` |

`PriorityScoringTest` asserts these exact outputs and their ordering, as well as
input fallback, clamping, config, price, determinism and contribution arithmetic.
PostgreSQL tests cover reading device priorities, saving the breakdown and keeping
historical priorities after edits. AI persistence is tested with a local HTTP stub;
no live model calls are required.
