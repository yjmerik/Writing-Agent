# Collaboration Quality Study Protocol

## Data and eligibility

Two genres (`mystery`, `wuxia`), two conditions (`collaboration`, `solo`), 20 matched chapters per genre, at least three distinct human readers per chapter/condition. Generated content must come from the real study; scenario/replay and synthetic test data are not literary evidence. The manifest must explicitly declare `evaluatorType: HUMAN blind readers`; this declaration does not authenticate participants or establish blinding by itself.

CSV columns: `genre,condition,chapter,readerId,engagement,coherence,character,hook,overall,authorMinutes,severeContinuityErrors`. Scores must be finite and within 1–10; author time must be finite and nonnegative; serious continuity errors must be human-confirmed nonnegative integers. Missing values, duplicate readers within a cell, unknown strata and out-of-range chapters invalidate the data. Repeated readers' time and error counts for the same chapter/condition must agree and are counted once, not per reader. The old CSV template lacks the new errors column; add it only when genuine chapter-level counts are available, never default it to zero.

## Acceptance thresholds

- Overall mean paired chapter quality difference (collaboration minus solo) must be **at least −0.2**. Compute each chapter's reader mean first; chapters have equal weight.
- Overall **median** author review time must fall **at least 20%**. A zero solo median leaves the reduction undefined and is insufficient data.
- Total serious continuity errors must not increase, both within each genre and overall.

Gate using unrounded decimal values. Report each genre and overall independently; genre quality/time are diagnostic, while the original overall quality/time thresholds remain the acceptance gates.

## Uncertainty

Use a genre-stratified paired-chapter bootstrap (seed `20260917`, `10000` replicates). Resample 20 chapter pairs with replacement within each genre, keeping both conditions and all chapter-level measurements together. Compute overall statistics from the 40 resampled pairs. Report 95% percentile intervals (linear interpolation at ranks `(n−1)×0.025` and `(n−1)×0.975`) and valid replicate counts for quality difference, time reduction and severe-error count difference. Zero-control-median bootstrap replicates are excluded only from time intervals. Intervals describe uncertainty; their bounds are not additional acceptance thresholds.

## Result states and current evidence

`INCOMPLETE`: missing/invalid data or undefined baseline time. `FAILED`: valid complete data but one or more thresholds fail. `COMPLETE`: valid complete data and every threshold passes. `--require-complete` exits zero only for `COMPLETE`; informational mode reports the same status without a failure exit code. All failure reasons are retained.

The existing human ratings CSV lacks `severeContinuityErrors`. Its historical manifest's `COMPLETE` label is retained for traceability, but does not establish current acceptance. Strict reevaluation must report `INCOMPLETE` until real error counts are supplied. Do not fabricate counts, change ratings to pass, or treat passing synthetic algorithm tests as a completed literary experiment.
