"""Download the embedding model into the image at build time.

Build-only helper: the Dockerfile runs this and then deletes it. It is a file
rather than an inline `python -c` because the quoting does not survive a
Dockerfile RUN cleanly.

It writes a plain directory rather than populating the HuggingFace cache, for
two reasons:

* `ignore_patterns` can then actually take effect. The repo ships the same
  weights twice - `model.safetensors` and `onnx/model.onnx`, ~124 MB each -
  and model2vec only ever reads the safetensors.
* Loading from a directory skips the hub's snapshot-completeness check.
  Pruning the ONNX out of the *cache* does not work: the next offline load
  fails with IncompleteSnapshotError because a file listed in the repo tree
  is missing.

The dimension is asserted here so a model swap cannot silently disagree with
the vector(N) column in migration V5.
"""

from __future__ import annotations

import os
from pathlib import Path

from huggingface_hub import snapshot_download
from model2vec import StaticModel

#: Formats model2vec never reads.
UNUSED = ["*.onnx", "onnx/*", "*.bin", "*.h5"]


def main() -> None:
    name = os.environ["EMBEDDING_MODEL"]
    want = int(os.environ["EMBEDDING_DIM"])
    target = Path(os.environ["EMBEDDING_MODEL_PATH"])

    snapshot_download(name, local_dir=str(target), ignore_patterns=UNUSED)

    model = StaticModel.from_pretrained(str(target))
    if model.dim != want:
        raise SystemExit(
            f"{name} produces {model.dim} dimensions but EMBEDDING_DIM is "
            f"{want}. Update the ARG and the vector(N) column in V5 together."
        )

    size = sum(f.stat().st_size for f in target.rglob("*") if f.is_file())
    print(f"baked {name} ({model.dim} dims) into {target}: {size / 1e6:.0f} MB")


if __name__ == "__main__":
    main()
