# gpus.v2.json — attribution

`gpus.v2.json` is derived from the **RightNow GPU Database**
(https://github.com/RightNow-AI/RightNow-GPU-Database, commit
`dfb3c9a54b092ad78681abb5a044e146398092a2`), licensed under the
Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
RightNow sources its data from the TechPowerUp GPU Database
(https://www.techpowerup.com/gpu-specs/) via dbgpu.

Changes from the upstream data: filtered to consumer desktop cards of any age (NVIDIA,
AMD/ATI and Intel only; workstation, server, mobile and integrated parts left out) that
pass `GpuSpecValidator`, with a spec left null where upstream has none, fields renamed to `Payload.GpuSpec`, clocks rounded to
whole MHz, "N GB" written as "NGB", four card names kept in their `gpus.v1` spelling,
and twelve launch prices carried over from `gpus.v1` (TechSpot "Price at Launch").
