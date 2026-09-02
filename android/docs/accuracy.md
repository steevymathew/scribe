# Accuracy and speed, measured

Produced by `native/asr/build-host/scribe_bench` over `fixtures/audio/` — 13 clips,
141.5 s, 325 reference words: twelve LibriSpeech dev-clean utterances plus whisper.cpp's
`jfk.wav`, which is real archive audio rather than a studio read and is the hardest clip in
the set. References are the corpora's own, not transcripts we wrote.

Rebuild the corpus with `python3 tools/fetch-fixtures.py`, then:

```bash
cmake -S native/asr -B native/asr/build-host -DCMAKE_BUILD_TYPE=Release \
      -DSCRIBE_BUILD_BENCH=ON -DSCRIBE_BUILD_JNI=OFF
cmake --build native/asr/build-host --target scribe_bench -j "$(nproc)"
./native/asr/build-host/scribe_bench --model models/ggml-base.en-q5_1.bin \
      --threads 6 fixtures/audio/*.wav
```

## Results

Measured 2026-09-01 on the build host — an aarch64 workstation, 6 decode threads, greedy
sampling, the exact decode parameters `libscribewhisper.so` uses.

| Tier | Model | Size | WER | Errors | RTF (build host) |
|---|---|---|---|---|---|
| Feather | `tiny.en` q5_1 | 31 MB | **10.15 %** | 33 / 325 | 0.025 |
| Everyday | `base.en` q5_1 | 57 MB | **9.54 %** | 31 / 325 | 0.051 |
| Sharp | `small.en` q5_1 | 181 MB | **7.08 %** | 23 / 325 | 0.184 |

## What these numbers do and do not say

**The word error rates transfer to the phone. The timings do not.**

The model, the quantisation and the decode parameters are byte-for-byte the same on the
device, so a clip that base.en gets 9.54 % wrong here it gets 9.54 % wrong on a Fold 7.
The real-time factors are a property of *this* CPU. A workstation core is not a Snapdragon
core, and nothing here has run on a phone. **On-device latency is `OWNER-VERIFY`** and must
be measured on the device before any claim is made about it.

## What the numbers changed

- **Feather is a real tier, not a token one.** tiny.en is only 0.6 points worse than
  base.en on this corpus at half the cost, so a phone that struggles with Everyday is not
  being handed something broken — it gets a model that is genuinely close.
- **Sharp earns its 181 MB.** 7.08 % against 9.54 % is a 26 % relative reduction in
  errors, which is the difference between re-reading every message and trusting it. It
  costs 3.6× the compute, which is why it is a download and a choice rather than the
  default.
- **The gap is smaller than the file sizes suggest.** Six times the bytes buys a quarter
  fewer errors. That is the argument for bundling base.en rather than shipping a 200 MB
  APK: the everyday experience is most of the way there, and the upgrade is there for
  people who want it.

## Where the errors actually are

Reading the per-clip output rather than the summary: the mistakes are proper nouns and rare
words — "Linnell" as "Lynille", "Leighton" as "Layton", "Ruskin" surviving but
"Michelangelo … furnishing upholsterer" mangled. Ordinary sentences come back clean;
`1272-128104-0002.wav` (12.5 s, 33 words) and `jfk.wav` are both 0 %.

That is exactly the failure mode the custom dictionary exists for. A name Scribe has been
told about is passed to Whisper as an initial prompt, so it is more likely to be *heard*
correctly rather than only corrected afterwards — which is why the dictionary feeds
recognition and not just the text pipeline.

It is also the argument for the high-accuracy key: the words Sharp fixes are precisely the
ones a person would otherwise have to go back and retype.
