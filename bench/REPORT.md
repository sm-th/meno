# Model bench (request-level, comparative)

_Updated 2026-09-07T08:08:21.300060Z._  Judge: `anthropic/claude-opus-4-8` · probes: 20 · models: 5. Bare `omp -p --no-tools` completions; per-probe comparative scoring; Δ limit % = worst usage-window delta for the model's provider during generation.

## Conclusion

- **Overall best:** `opencode-go/deepseek-v4-flash` (8.10/10).
- **Best per request type:** extract → `opencode-go/deepseek-v4-flash`, synthesize → `opencode-go/deepseek-v4-flash`, connection → `anthropic/claude-sonnet-4-5`.
- **Recommendation:** split by class for cost — extract: `opencode-go/deepseek-v4-flash`; synthesize: `opencode-go/deepseek-v4-flash`; connection: `anthropic/claude-sonnet-4-5`. Single-model fallback: `opencode-go/deepseek-v4-flash`.

## Ranking

| model | avg /10 | Δ limit % | best-of-probe |
|---|---|---|---|
| `opencode-go/deepseek-v4-flash` | 8.10 | 0 (opencode-go) | 7/20 |
| `opencode-go/glm-5.2` | 7.90 | 0 (opencode-go) | 7/20 |
| `opencode-go/deepseek-v4-pro` | 7.75 | 6 (opencode-go) | 4/20 |
| `opencode-go/kimi-k2.6` | 7.05 | 1 (opencode-go) | 1/20 |
| `anthropic/claude-sonnet-4-5` | 6.80 | 0 (anthropic) | 1/20 |

## By request type (avg /10 — choose a model per class)

| model | extract | synthesize | connection |
|---|---|---|---|
| `opencode-go/deepseek-v4-flash` | 8.3 | 8.1 | 7.8 |
| `opencode-go/glm-5.2` | 8.2 | 7.8 | 7.8 |
| `opencode-go/deepseek-v4-pro` | 7.8 | 7.8 | 7.7 |
| `opencode-go/kimi-k2.6` | 6.2 | 7.0 | 8.0 |
| `anthropic/claude-sonnet-4-5` | 5.8 | 6.6 | 8.0 |

## Per probe (score /10, ★ = judged best)

| probe | type | anthropic/claude-sonnet-4-5 | opencode-go/deepseek-v4-flash | opencode-go/deepseek-v4-pro | opencode-go/glm-5.2 | opencode-go/kimi-k2.6 |
|---|---|---|---|---|---|---|
| extract-andy-ephemeral | extract | 5 | 9 ★ | 7 | 8 | 6 |
| extract-coinage-trap | extract | 6 | 9 | 9 ★ | 8 | 7 |
| extract-andy-microsandbox | extract | 6 | 8 | 8 | 9 ★ | 5 |
| extract-distributed-note | extract | 5 | 8 | 9 ★ | 7 | 8 |
| extract-mundane-restraint | extract | 7 | 9 ★ | 6 | 8 | 3 |
| extract-security-note | extract | 6 | 7 | 8 | 9 ★ | 8 |
| synth-idempotency | synthesize | 7 | 9 ★ | 8 | 7 | 6 |
| synth-cap-bad-source | synthesize | 8 | 9 ★ | 7 | 9 | 8 |
| synth-linearizability | synthesize | 6 | 7 | 8 | 9 ★ | 8 |
| synth-consistent-hashing | synthesize | 7 | 8 | 9 ★ | 6 | 8 |
| synth-merkle-tree | synthesize | 5 | 9 ★ | 6 | 8 | 6 |
| synth-two-phase-commit | synthesize | 5 | 8 | 8 | 9 ★ | 8 |
| synth-vector-clock | synthesize | 7 | 8 | 9 ★ | 5 | 6 |
| synth-bloom-no-sources | synthesize | 8 | 7 | 7 | 9 ★ | 6 |
| connect-correct | connection | 8 | 9 ★ | 8 | 7 | 8 |
| connect-misinterpretation | connection | 7 | 9 ★ | 8 | 7 | 7 |
| connect-consistent-hashing-correct | connection | 8 | 7 | 8 | 7 | 9 ★ |
| connect-eventual-misinterp | connection | 7 | 6 | 8 | 9 ★ | 8 |
| connect-idempotency-conflation | connection | 8 | 8 | 7 | 9 ★ | 7 |
| connect-backpressure-correct | connection | 10 ★ | 8 | 7 | 8 | 9 |

## Judge notes

- **extract-andy-ephemeral** best=`opencode-go/deepseek-v4-flash` — D covers all required concepts, ties each to its phrase, correctly flags self-escalation phrasing and 'ephemeral'/'tick' as author's, cleanest. C nearly equal but slightly noisier. E worst-ish and B weakest: E omits the capability/permission (self-grant) concept; B redundant, never flags coinage.
- **extract-coinage-trap** best=`opencode-go/deepseek-v4-pro` — B and E both name least privilege, fault isolation/blast radius, reproducible builds, manifest, each tied to a phrase, and flag 'context capsule' as coined. B's evidence-column mapping is cleanest. A and C omit explicit least privilege; C weakest on phrase-tying.
- **extract-andy-microsandbox** best=`opencode-go/glm-5.2` — B explicitly frames the container-vs-VM isolation trade-off (namespaces vs KVM), ties each concept to phrases, and cleanly marks microsandboxing as coinage. E and C merely list primitives without tying phrases or distinguishing the container-vs-VM point.
- **extract-distributed-note** best=`opencode-go/deepseek-v4-pro` — C hits every rubric concept, ties each to a specific phrase in a clean table, invents nothing. E fails worst: over-splits into granular non-concepts (ack, timeout, caching) and injects CAP theorem not in note. D and B add quorum/durability speculation.
- **extract-mundane-restraint** best=`opencode-go/deepseek-v4-flash` — E most restrained: names only Git/README, flags them as incidental, explicitly says few-to-none. C close. B worst: lists 'project folder' and 'main branch' as separate canonical concepts, over-extracts, never acknowledges little theory.
- **extract-security-note** best=`opencode-go/glm-5.2` — C ties each phrase to a distinct canonical concept, keeps DiD/least-privilege/zero-trust separate, and handles canonical-vs-coinage cleanly. D is thin, minimal justification. B dilutes with 'assumption of breach' as a fourth principle, muddying the core three.
- **synth-idempotency** best=`opencode-go/deepseek-v4-flash` — B wins: precise definition, correct HTTP examples, atomic, and full verbatim source quotes in references maximizing grounding. D weakest: terse bare-label citations, POST claim citation placement ambiguous, minimal referencing rigor.
- **synth-cap-bad-source** best=`opencode-go/deepseek-v4-flash` — C defines CAP directly as the C-vs-A-during-partition impossibility, adds proof history and CP/AP examples, never restating 'two of three.' B nearly equal with strong citations. A weakest: its Definition still asserts 'at most two of three' before correcting, partly propagating the error.
- **synth-linearizability** best=`opencode-go/glm-5.2` — B: complete definition, real-time ordering, fully grounded citations, concise with clear source labels. C weakest: invents 'distributed computing and concurrent systems' beyond sources. D verbose with mild inference. A/E clean but thinner citation labeling.
- **synth-consistent-hashing** best=`opencode-go/deepseek-v4-pro` — A covers all four rubric points verbatim from sources with clean citations and zero invented facts. C/E strong but add minor glosses (K/n defs, 'distributed scheme'). D and B invent 'naive modular'/'traditional hashing' not in sources, weakening the no-invented-facts criterion.
- **synth-merkle-tree** best=`opencode-go/deepseek-v4-flash` — E cleanly atomizes each claim to its exact source with no additions. B strong but adds 'hash tree'/'Integrity' framing. A/C bundle multiple facts under one cite (less atomic). D invents 'cryptographic'/'cryptographically', unsupported by sources.
- **synth-two-phase-commit** best=`opencode-go/glm-5.2` — A: full flow, blocking weakness, dual citations, honest coverage note flagging absent details—strictly source-bound. B/D/E: correct, faithful, minimal. C weakest: invents phase names and editorializes ('significant availability tradeoff'), exceeding sources against no-invented-facts rule.
- **synth-vector-clock** best=`opencode-go/deepseek-v4-pro` — D: card-style label, verbatim source-faithful, cited, no invention. A nearly identical but plain paragraph. C adds 'distributed systems' (unsourced) yet clean. B similar invention. E worst: most embellishment ('data structure', 'distributed system', 'causally unordered') beyond sources.
- **synth-bloom-no-sources** best=`opencode-go/glm-5.2` — B: full mechanism, correct formulas, deletion caveat, and explicit no-page-number honesty. A cites exact pages 422-426 (against rubric); C adds specific Mitzenmacher reference and is thin; D miscalculates ~6.9 bits/element for 1% (should be ~9.6).
- **connect-correct** best=`opencode-go/deepseek-v4-flash` — A confirms correctly and uniquely nails the timeout-ambiguity behind the claim's 'could' without padding. C weakest: correct but verbose, redundant 'refinement' repeats the same point. B/D/E solid, concise, but less precise on the ambiguity insight.
- **connect-misinterpretation** best=`opencode-go/deepseek-v4-flash` — E is most precise: cites conditional Gilbert-Lynch proof, correctly nuances that 'CA' only describes healthy-network behavior without endorsing it as steady-state, maps to CP. C is tight and correct. Others solid but less rigorous; B's absolutism slightly loose.
- **connect-consistent-hashing-correct** best=`opencode-go/kimi-k2.6` — E: tight, accurate ring mechanics, correct K/n and modulo contrast, no false flag. A/C strong and concise. B verbose. D correct but caveat-heavy and slightly wordy; all confirm correctly.
- **connect-eventual-misinterp** best=`opencode-go/glm-5.2` — B flags category error, corrects convergence-only-if-updates-stop/no bound/conflicting reads, precise, avoids equating eventual with linearizability. C worst: verbose, tangential vendor detail, ignores 'short' brief. D/E slightly less complete; A strong but terser than B.
- **connect-idempotency-conflation** best=`opencode-go/glm-5.2` — D flags conflation both directions—unique-per-request defeats dedup, and thread-safety/atomicity enable idempotency not vice versa—most precise. C similarly rich. B clean. A/E correct but simpler, A's 'orthogonal' slightly loose.
- **connect-backpressure-correct** best=`anthropic/claude-sonnet-4-5` — B: tightest correct confirmation, clean TCP zero-window chain, no false flag. D weakest: RSVP example is a bandwidth-reservation protocol, an off/imprecise tangent. All correctly confirm without spurious corrections; scored by conciseness and precision.