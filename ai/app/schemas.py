"""Service contract. Section 6 of the LLM layer specification.

Spring Boot owns every number in the request; nothing here is recomputed.
"""

from datetime import date
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.factors import (
    BRAND_FLEXIBILITIES,
    CONDITIONS,
    FACTORS,
    GRADE_INSUFFICIENT,
    GRADES,
    STANCES,
    UPGRADE_URGENCIES,
    VERDICTS,
)

Factor = Literal[FACTORS]  # type: ignore[valid-type]
Stance = Literal[STANCES]  # type: ignore[valid-type]
Grade = Literal[GRADES]  # type: ignore[valid-type]
ReturnedGrade = Literal[GRADES + (GRADE_INSUFFICIENT,)]  # type: ignore[valid-type]
Verdict = Literal[VERDICTS]  # type: ignore[valid-type]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


# --- Request -------------------------------------------------------------


class OwnedDevice(Strict):
    name: str
    device_age_months: int
    condition: Literal[CONDITIONS]  # type: ignore[valid-type]
    satisfaction_score: int = Field(ge=0, le=100)
    use_cases: list[str] = []


class Preferences(Strict):
    budget: float | None = None
    currency: str | None = None
    upgrade_urgency: Literal[UPGRADE_URGENCIES]  # type: ignore[valid-type]
    brand_flexibility: Literal[BRAND_FLEXIBILITIES]  # type: ignore[valid-type]
    priorities: dict[Factor, int] = {}
    pain_points: str | None = None
    notes: str | None = None


class UserContext(Strict):
    owned_device: OwnedDevice
    preferences: Preferences


class Candidate(Strict):
    product_id: int
    name: str
    release_date: date
    age_days: int


class SpecDelta(Strict):
    current: float | str | None = None
    candidate: float | str | None = None
    delta_pct: float | None = None


class Price(Strict):
    current: float | None = None
    currency: str | None = None
    vs_budget: float | None = None
    change_pct: float | None = None


class TriggerEvent(Strict):
    event_type: str
    title: str
    old_value: dict[str, Any] | None = None
    new_value: dict[str, Any] | None = None


class Computed(Strict):
    """Finished figures. `benchmark_uplift_pct` already has
    `benchmark_results.higher_is_better` applied on the Java side."""

    spec_deltas: dict[str, SpecDelta] = {}
    benchmark_uplift_pct: float | None = None
    price: Price | None = None
    trigger_event: TriggerEvent | None = None


class Analysis(Strict):
    verdict: Verdict
    relevance_score: float
    preference_score: float
    deciding_factors: list[Factor] = []


class RetrievalOptions(Strict):
    k: int | None = None


class AssessRequest(Strict):
    request_id: str
    user_context: UserContext
    candidate: Candidate
    computed: Computed = Computed()
    analysis: Analysis
    retrieval: RetrievalOptions = RetrievalOptions()


# --- Response ------------------------------------------------------------


class EvidenceFinding(Strict):
    factor: Factor
    stance: Stance
    supporting_refs: list[str]
    #: `supporting_refs` resolved back to `review_chunks.id`. Spring persists
    #: these, never the refs, which are meaningless outside one request.
    supporting_chunk_ids: list[int] = []
    note: str


class ResponseMeta(Strict):
    ai_model: str
    prompt_version: str
    retrieved_chunk_ids: list[int]
    retry_count: int
    #: Retrieval parameters, for `input_snapshot`. Two recommendations sharing
    #: a prompt_version must also share these or reproducibility is lost.
    retrieval: dict[str, Any] = {}
    #: True when no usable grade was produced and '-' is being returned.
    degraded: bool = False
    degraded_reason: str | None = None


class AssessResponse(Strict):
    request_id: str
    evidence_grade: ReturnedGrade
    evidence_findings: list[EvidenceFinding] = []
    irrelevant_refs: list[str] = []
    irrelevant_chunk_ids: list[int] = []
    summary: str | None = None
    meta: ResponseMeta
    #: Ready-formed `system_log` row for Spring to persist. Section 10.
    system_log: dict[str, Any] | None = None
