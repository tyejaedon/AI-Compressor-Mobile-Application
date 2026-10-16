# Model Params Input

Use `--params-file` to load model/training parameters from JSON instead of passing many CLI flags.

Example file:

- `documentation/model_params_example.json`

The file can be:

1. Sectioned by modality/trainer (`image`, `image_lossy`, `audio`, `video`), or
2. A flat JSON object (for a single trainer).

CLI flags still win over file values if both are provided.

## Examples

```zsh
python src/training/train_autoencoder_image_local.py --params-file documentation/model_params_example.json --data-dir data/ImageData/archive --real-only --no-export-tflite
python src/training/train_autoencoder_image_lossy_local.py --params-file documentation/model_params_example.json --data-dir data/ImageData/archive --real-only --no-export-tflite
python src/training/train_autoencoder_audio_local.py --params-file documentation/model_params_example.json --data-dir data/AudioData/ESC-50-master/audio --no-export-tflite
python src/training/train_autoencoder_video_local.py --params-file documentation/model_params_example.json --data-dir "data/VIDEO DATA" --no-export-tflite
python src/training/train_upscaler_image_local.py --params-file documentation/model_params_example.json --data-dir data/ImageData/archive --real-only --no-export-tflite
```

## Image trainer data cache (Milestone 4)

`train_autoencoder_image_local.py` now supports an optional on-disk decoded-tensor cache for real-image runs:

| Flag | Default | Purpose |
|---|---|---|
| `--cache-dir <path>` | off | Writes/reads TFRecord-backed image tensors for each real-data split (`train`, `validation`, `test`) so repeated runs can skip re-decoding unchanged source images. |

Notes:

- The cache is **opt-in** and leaves prior behavior unchanged when omitted.
- Cache entries are keyed by split membership plus the requested `block_size`, and the key hashes each source file's path, size, and `mtime`, so changing the underlying image set automatically invalidates the old cache.
- Run reports surface the cache status under `split_info.cache.*` (`built`, `hit`, or `disabled`) along with the TFRecord/manifest paths.

## Image lossy trainer: real vs proxy compression metrics (Milestone 1)

`evaluation_report.md` from `train_autoencoder_image_lossy_local.py` distinguishes two families of size metrics:

- **JPEG/WebP baseline proxy** (`lossy_size_stats`, `matched_psnr_baseline_benchmark`): re-encodes the
  model's *reconstructed pixels* as JPEG/WebP and compares byte sizes. This has always existed and is a
  proxy for "how compressible does the output look", not the model's own bitstream.
- **Real latent bitrate** (`latent_rate_stats`, new): the empirical Shannon entropy of the model's own
  quantized latent tensor, reported as `latent_bits_per_pixel` and `estimated_bitstream_bytes_per_image`.
  This is what the model would actually cost to transmit if paired with an entropy coder.
- **Prototype entropy coder** (`entropy_coding`, opt-in via `--enable-entropy-coding`): actually range-codes
  a small sample of test-set latents and reports the real compressed byte count, for comparison against the
  entropy estimate above (they should be very close).

New `image_lossy` flags (all default to preserving prior behavior):

| Flag | Default | Purpose |
|---|---|---|
| `--rate-loss-mode {magnitude,entropy}` | `magnitude` | Rate loss formulation used during training; `entropy` uses a differentiable soft-histogram entropy estimate (in bits, typical scale ~0-5+) instead of mean \|latent\| (typical scale ~0-1). **When switching to `entropy`, reduce `--rate-lambda` by roughly 10-100x** vs. what you'd use for `magnitude` (e.g. keep the default `0.002`, or go lower), or the rate term can overwhelm reconstruction loss and stall PSNR entirely. |
| `--enable-entropy-coding` | off | Run the prototype range coder over a handful of test-set latents after training. |
| `--entropy-coding-samples` | `8` | How many test images to actually range-encode when the above is enabled. |
| `--latent-bit-depth {4,6,8,10}` | `8` | Quantization bit-depth for the latent bottleneck (Milestone 2). |
| `--quant-noise-anneal` | off | Fade additive quantization noise from full strength to zero over training (QAT-style annealing, Milestone 2). |

## Learned image upscaler (Milestone 3)

`train_upscaler_image_local.py` trains a small residual CNN + sub-pixel (depth-to-space) upsampler on the
same bicubic-downscale-plus-noise degrade recipe used by `train_autoencoder_image_local.py`, but produces a
genuinely lower-resolution input (rather than a same-size denoising target). Its own
`upscaler_evaluation_report.md` reports learned-vs-bicubic PSNR/SSIM directly.

`upscale_reconstructed_images.py --upscaler-mode {bicubic,learned}` (default `bicubic`, unchanged) can then
use a trained upscaler model in place of plain BICUBIC resizing via `--learned-upscaler-model <path>`,
with `--upscaler-tile-size`/`--upscaler-tile-overlap` bounding memory use on large images via overlapping,
blended tiles. If no learned model path is given (or it fails to load), it falls back to BICUBIC with a
warning.

