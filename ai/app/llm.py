"""The single reasoning call, behind a vendor-neutral seam.

One call per recommendation in the happy path, at most two when the first
response fails structural validation (section 8, MAX_RETRIES).

Two adapters implement `Llm`:

* `AnthropicLlm` speaks to the Claude API natively, so the Anthropic-specific
  levers this grading path leans on are not thrown away: `output_config.effort`
  for depth, server-side refusal fallbacks, and prompt caching on the system
  block.
* `OpenAICompatibleLlm` speaks the OpenAI chat-completions wire format, which
  is the de-facto standard. One adapter therefore covers OpenRouter (the
  default non-Anthropic provider here), OpenAI itself, Google's compatibility
  endpoint, Groq, Together, DeepSeek, xAI, vLLM and Ollama. The base URL is the
  only thing that changes between them.

Structured output is tiered, because "any model from any vendor" includes
models that cannot enforce a schema server-side:

    json_schema  ->  json_object  ->  a plain instruction in the prompt

In `auto` mode the adapter starts at the strongest tier and steps down only
when the provider rejects the request *because of the response format*, then
remembers what worked. Whatever still slips through is caught by `validate()`
and the existing retry/degrade path, so a weaker model produces a degraded
result rather than a fabricated grade.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, Protocol

import anthropic
import openai

from app.config import Settings

log = logging.getLogger(__name__)

FALLBACK_BETA = "server-side-fallback-2026-07-01"

# Ordered strongest to weakest. `auto` walks down this list.
SCHEMA_TIERS = ("json_schema", "json_object", "prompt")

JSON_INSTRUCTION = (
    "\n\nReturn ONLY a single JSON object conforming exactly to this JSON "
    "schema. No markdown fences, no prose before or after it:\n\n%s"
)

# OpenAI's reasoning models reject `max_tokens` and demand
# `max_completion_tokens`; most other vendors only know `max_tokens`. Which one
# a model wants is not discoverable up front, so the adapter tries and adapts.
TOKEN_PARAMS = ("max_tokens", "max_completion_tokens")

# Substrings that mark a 400 as "this provider/model will not do that response
# format", as opposed to a genuine problem with the request. Checked only
# AFTER the token-parameter case, whose message also says "Unsupported
# parameter" and would otherwise be mistaken for a format rejection.
_FORMAT_REJECTIONS = (
    "response_format",
    "json_schema",
    "structured output",
    "structured_output",
    "does not support",
    "not supported",
    "unsupported",
)


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
    """Native Claude API.

    Notes on the request shape, which is Claude Opus 5 specific:

    * No `temperature`. Sampling parameters are rejected with a 400 on this
      model; depth is controlled with effort instead.
    * Thinking is on by default and is billed inside `max_tokens`, which is why
      `max_tokens` is generous relative to how small the JSON output is.
    * `output_config.format` constrains the response to the schema, so no
      prefill and no "reply with only JSON" pleading is needed.
    * `fallbacks="default"` re-runs a request server-side if a safety
      classifier declines it. Review corpora occasionally contain text that
      trips one, and a declined request would otherwise cost the user a grade.
    """

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


class OpenAICompatibleLlm:
    """Anything speaking OpenAI chat-completions: OpenRouter, OpenAI, Gemini's
    compatibility endpoint, Groq, Together, DeepSeek, vLLM, Ollama.

    `base_url` is what selects the vendor; the wire format is identical.
    """

    def __init__(
        self,
        settings: Settings,
        client: Any | None = None,
        base_url: str | None = None,
        api_key: str | None = None,
    ) -> None:
        self.settings = settings
        self.base_url = base_url

        configured = settings.llm_schema_mode
        self._auto = configured == "auto"
        if not self._auto and configured not in SCHEMA_TIERS:
            raise LlmError(
                "LLM_SCHEMA_MODE must be auto or one of %s (got %r)."
                % (", ".join(SCHEMA_TIERS), configured)
            )
        # Starting tier. Under `auto` this is provisional and may step down.
        self._mode = SCHEMA_TIERS[0] if self._auto else configured
        # Provisional too: swapped once if the model demands the other name.
        self._token_param = TOKEN_PARAMS[0]

        self.client = client or openai.AsyncOpenAI(
            # The SDK refuses to construct without something here; a wrong key
            # should surface as a 401 from the provider, not a TypeError here.
            api_key=api_key or "missing",
            base_url=base_url or None,
            timeout=settings.llm_timeout_seconds,
        )

    def _payload(
        self,
        system: str,
        messages: list[dict[str, Any]],
        schema: dict,
        mode: str,
        token_param: str,
    ) -> dict[str, Any]:
        system_text = system
        # json_object mode requires the literal word "json" somewhere in the
        # prompt on OpenAI, and weaker models need the shape spelled out.
        if mode in ("json_object", "prompt"):
            system_text = system + JSON_INSTRUCTION % json.dumps(schema, indent=2)

        payload: dict[str, Any] = {
            "model": self.settings.llm_model,
            token_param: self.settings.llm_max_tokens,
            "messages": [{"role": "system", "content": system_text}, *messages],
        }
        if mode == "json_schema":
            payload["response_format"] = {
                "type": "json_schema",
                "json_schema": {
                    "name": "assessment",
                    "strict": True,
                    "schema": schema,
                },
            }
        elif mode == "json_object":
            payload["response_format"] = {"type": "json_object"}

        # Reasoning depth. Opt-in, because many models behind a gateway reject
        # the parameter outright.
        if self.settings.llm_send_effort and self.settings.llm_effort:
            payload["reasoning_effort"] = self.settings.llm_effort
        return payload

    async def complete(
        self, system: str, messages: list[dict[str, Any]], schema: dict
    ) -> LlmResult:
        mode = self._mode
        token_param = self._token_param
        swapped_token_param = False
        while True:
            try:
                response = await self.client.chat.completions.create(
                    **self._payload(system, messages, schema, mode, token_param)
                )
            except openai.BadRequestError as exc:
                # Order matters: the token-parameter complaint also contains
                # "Unsupported parameter", so it must be ruled out before the
                # format check, or the wrong thing gets downgraded. And once an
                # error is identified as a token-name problem it is never
                # re-read as a format problem - otherwise a model that rejects
                # both spellings also silently loses schema enforcement.
                if _is_token_param_rejection(exc):
                    if swapped_token_param:
                        raise LlmError(
                            "Model rejected both %s and %s: %s"
                            % (*TOKEN_PARAMS, exc)
                        ) from exc
                    swapped_token_param = True
                    token_param = TOKEN_PARAMS[1 - TOKEN_PARAMS.index(token_param)]
                    log.warning("model wants %s, retrying with it", token_param)
                    continue
                nxt = self._step_down(mode)
                if self._auto and nxt and _is_format_rejection(exc):
                    log.warning(
                        "provider rejected %s output, falling back to %s: %s",
                        mode,
                        nxt,
                        exc,
                    )
                    mode = nxt
                    continue
                raise LlmError(
                    "Model call failed (%s): %s" % (exc.status_code, exc)
                ) from exc
            except openai.APIStatusError as exc:
                raise LlmError(
                    "Model call failed (%s): %s" % (exc.status_code, exc)
                ) from exc
            except openai.APIConnectionError as exc:
                raise LlmError("Could not reach the model API: %s" % exc) from exc
            break

        # Remember what the provider actually accepted, so the next request
        # does not pay for the same rejection again.
        self._mode = mode
        self._token_param = token_param

        choices = getattr(response, "choices", None)
        if not choices:
            # Gateways sometimes report an upstream failure as HTTP 200 with no
            # choices and an `error` member rather than as an HTTP error.
            raise LlmError(
                "Provider returned no choices: %s"
                % (getattr(response, "error", None) or response)
            )
        choice = choices[0]

        refusal = getattr(choice.message, "refusal", None)
        if refusal:
            raise LlmError("Model declined the request: %s" % refusal)

        if getattr(choice, "finish_reason", None) == "length":
            raise LlmError(
                "Model output hit the token ceiling and is truncated; "
                "raise LLM_MAX_TOKENS."
            )

        text = choice.message.content or ""
        if not text.strip():
            raise LlmError("Model returned no text content.")

        return LlmResult(
            text=text, model=getattr(response, "model", self.settings.llm_model)
        )

    @staticmethod
    def _step_down(mode: str) -> str | None:
        index = SCHEMA_TIERS.index(mode)
        return SCHEMA_TIERS[index + 1] if index + 1 < len(SCHEMA_TIERS) else None


def _is_token_param_rejection(exc: Exception) -> bool:
    """A 400 saying this model wants the other spelling of the token ceiling."""
    blob = str(exc).lower()
    return "max_completion_tokens" in blob or (
        "max_tokens" in blob and "unsupported" in blob
    )


def _is_format_rejection(exc: Exception) -> bool:
    blob = str(exc).lower()
    return any(marker in blob for marker in _FORMAT_REJECTIONS)


@dataclass(frozen=True)
class Provider:
    """How to reach one vendor. `base_url` None means the SDK default."""

    name: str
    key_field: str
    base_url: str | None = None
    openai_compatible: bool = True


PROVIDERS: dict[str, Provider] = {
    "anthropic": Provider(
        "anthropic", key_field="anthropic_api_key", openai_compatible=False
    ),
    "openrouter": Provider(
        "openrouter",
        key_field="openrouter_api_key",
        base_url="https://openrouter.ai/api/v1",
    ),
    "openai": Provider("openai", key_field="openai_api_key"),
    # Anything else speaking the same wire format: set LLM_BASE_URL and
    # LLM_API_KEY. Ollama, vLLM, Groq, Together, DeepSeek, a company gateway.
    "custom": Provider("custom", key_field="llm_api_key"),
}


def build_llm(settings: Settings) -> Llm:
    """Select the adapter named by LLM_PROVIDER."""
    provider = PROVIDERS.get(settings.llm_provider)
    if provider is None:
        raise LlmError(
            "LLM_PROVIDER must be one of %s (got %r)."
            % (", ".join(sorted(PROVIDERS)), settings.llm_provider)
        )

    if not provider.openai_compatible:
        return AnthropicLlm(settings)

    base_url = settings.llm_base_url or provider.base_url
    if provider.name == "custom" and not base_url:
        raise LlmError("LLM_PROVIDER=custom requires LLM_BASE_URL to be set.")

    return OpenAICompatibleLlm(
        settings,
        base_url=base_url,
        api_key=getattr(settings, provider.key_field, "") or None,
    )
