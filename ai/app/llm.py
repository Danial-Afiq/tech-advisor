"""The single reasoning call.

One call per recommendation in the happy path, at most two when the first
response fails structural validation (section 8, MAX_RETRIES).

Notes on the request shape, which is Claude Opus 5 specific:

* No `temperature`. Sampling parameters are rejected with a 400 on this model;
  determinism is not available through them. Depth is controlled with effort.
* Thinking is on by default and is billed inside `max_tokens`, which is why
  `max_tokens` is generous relative to how small the JSON output is.
* `output_config.format` constrains the response to the schema, so no prefill
  and no "reply with only JSON" pleading is needed.
* `fallbacks="default"` re-runs a request server-side if a safety classifier
  declines it. Review corpora occasionally contain text that trips one, and a
  declined request would otherwise cost the user their grade.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

import anthropic

from app.config import Settings

FALLBACK_BETA = "server-side-fallback-2026-07-01"


class LlmError(Exception):
    """The call could not be completed. Distinct from a response that came
    back but failed validation."""


@dataclass(frozen=True)
class LlmResult:
    text: str
    model: str


class Llm(Protocol):
    async def complete(
        self, system: str, messages: list[dict[str, Any]], schema: dict
    ) -> LlmResult: ...


class AnthropicLlm:
    def __init__(self, settings: Settings, client: Any | None = None) -> None:
        self.settings = settings
        self.client = client or anthropic.AsyncAnthropic(
            # `or None` hands resolution back to the SDK when the root `.env`
            # leaves this blank: a real ANTHROPIC_API_KEY in the environment,
            # then an `ant auth login` profile. Passing "" would defeat both.
            api_key=settings.anthropic_api_key or None,
            timeout=settings.llm_timeout_seconds,
        )

    async def complete(
        self, system: str, messages: list[dict[str, Any]], schema: dict
    ) -> LlmResult:
        request: dict[str, Any] = {
            "model": self.settings.llm_model,
            "max_tokens": self.settings.llm_max_tokens,
            "system": [
                {
                    "type": "text",
                    "text": system,
                    "cache_control": {"type": "ephemeral"},
                }
            ],
            "messages": messages,
            "output_config": {
                "effort": self.settings.llm_effort,
                "format": {"type": "json_schema", "schema": schema},
            },
        }
        if self.settings.llm_fallbacks_enabled:
            request["betas"] = [FALLBACK_BETA]
            request["fallbacks"] = "default"

        try:
            response = await self.client.beta.messages.create(**request)
        except anthropic.APIStatusError as exc:
            raise LlmError("Model call failed (%s): %s" % (exc.status_code, exc)) from exc
        except anthropic.APIConnectionError as exc:
            raise LlmError("Could not reach the model API: %s" % exc) from exc

        # Always check stop_reason before reading content.
        if getattr(response, "stop_reason", None) == "refusal":
            details = getattr(response, "stop_details", None)
            category = getattr(details, "category", None)
            raise LlmError("Model declined the request (category=%s)." % category)

        text = "".join(
            block.text for block in response.content if getattr(block, "type", "") == "text"
        )
        if not text.strip():
            raise LlmError("Model returned no text content.")

        return LlmResult(text=text, model=getattr(response, "model", self.settings.llm_model))
