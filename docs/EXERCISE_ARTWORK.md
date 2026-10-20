# Exercise artwork

Illustrations are bundled in the APK and work offline. They describe a movement and typical equipment; machine designs and body proportions vary. User photos retain priority in the interface.

- Source: [Workout Guide](https://github.com/bryllim/workout-guide), pinned commit `aac599224bb9780305239607ef98540b7e0ce389`.
- Artwork license: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); software licensing is separate.
- Authors: Bryl Lim; inherited Everkinetic artwork by Greg Priday. Per-frame upstream source/changes are retained in the machine-readable manifest.
- Original PNG bytes are copied unchanged. White transparent line art is displayed on a dark surface; no source pixels were recolored or cropped.
- Generated gap illustrations have their own credit and no upstream source/license link. See [creation provenance](EXERCISE_GENERATION.md).
- No personal health records, private plan JSON, or user photos are included.

License text: `app/src/main/assets/licenses/exercise-illustrations.txt`.
Per-file source URL, author, SHA-256 and origin: `app/src/main/assets/licenses/exercise-artwork-provenance.json`.

## Visual review

Each selected frame was visually inspected against its mapped exercise. Neutral images are not labelled as obligatory start/end positions. A single frame can contain more than one pose. Exact hand placement, direction, or machine mismatch was not accepted on the strength of an upstream filename alone.

Rejected upstream mappings: `lat-pulldown` shows a behind-neck variation; `assisted-dip` shows a seated dip press; `reverse-pec-deck` faces away from the machine; `dead-bug` has an unsuitable head/limb depiction; `crunch` and `bench-dip` elevate the feet instead of the guide's feet-on-floor variants. The small `lying-leg-curl` diagram was replaced by a clearer side view with an identifiable roller and machine frame. These upstream files are not bundled. The front pulldown uses the upper position of `wide-grip-lat-pulldown`; other gaps use separately reviewed generated illustrations.

## Mappings

| Guide key | Origin / slug | Frames | Clarification |
| --- | --- | --- | --- |
| `barbell_bench_press` | `bench-press` | 2 |  |
| `dumbbell_bench_press` | `dumbbell-bench-press` | 2 |  |
| `machine_chest_press` | `machine-chest-press` | 1 | Пример рычажного тренажёра; расположение рукояток и регулировок зависит от модели. |
| `pec_fly` | `pec-deck` | 1 | Пример «бабочки» с опорой для предплечий; встречаются варианты с рукоятками. |
| `dips` | `chest-dip` | 1 |  |
| `pullup` | `pull-up` | 1 |  |
| `lat_pulldown` | `wide-grip-lat-pulldown` | 1 | Пример верхнего блока с широкой рукоятью. Тягу выполняют перед собой к верхней части груди. |
| `seated_cable_row` | `seated-row` | 1 | Пример тяги с узкой рукоятью; конструкция опор зависит от тренажёра. |
| `bent_over_row` | `barbell-row` | 2 |  |
| `back_extension` | `back-extension` | 1 | Пример наклонной скамьи для гиперэкстензии. |
| `barbell_squat` | `squat` | 2 |  |
| `leg_press` | `leg-press` | 2 | Пример наклонного жима ногами; настройка сиденья и ограничителей зависит от модели. |
| `leg_extension` | `leg-extension` | 2 |  |
| `machine_calf_raise` | `standing-calf-raise` | 1 | Пример подъёма на носки стоя с плечевыми упорами. |
| `seated_dumbbell_press` | `seated-dumbbell-press` | 1 |  |
| `standing_barbell_press` | `overhead-press` | 1 |  |
| `lateral_raise` | `lateral-raise` | 1 |  |
| `barbell_curl` | `ez-bar-curl` | 1 | На рисунке вариант с EZ-грифом; форма грифа отличается от прямой штанги. |
| `dumbbell_curl` | `bicep-curl` | 1 |  |
| `hammer_curl` | `hammer-curl` | 1 |  |
| `lying_triceps_extension` | `skull-crusher` | 1 | Положение штанги над телом; движение выполняется преимущественно в локтях. |
| `triceps_pushdown` | `tricep-pushdown` | 1 | Пример с прямой рукоятью верхнего блока. |
| `machine_biceps_curl` | `preacher-curl` | 1 | Пример бицепс-тренажёра с опорой под плечи; расположение рукояток зависит от модели. |
| `hanging_leg_raise` | `hanging-leg-raise` | 1 | На рисунке колени немного согнуты; амплитуда и вариант выбираются по контролю движения. |
| `supported_calf_raise` | `calf-raise` | 1 | Лёгкая опора руками для равновесия, без дополнительного веса. |
| `chest_supported_row` | `chest-supported-row` | 1 | Пример тяги Т-грифа с грудной опорой; другие тренажёры могут выглядеть иначе. |
| `glute_bridge` | `glute-bridge` | 1 |  |
| `walking` | `walking` | 1 | Пример ходьбы на дорожке. Рисунок показывает оборудование, а не обязательное положение рук. |
| `plank` | `plank` | 1 |  |
| `pushup_standard` | `push-up` | 1 |  |
| `pushup_wide` | `wide-push-up` | 1 |  |
| `pushup_narrow` | `diamond-push-up` | 1 | Показан узкий вариант с ладонями ромбом; соединять пальцы необязательно. |
| `pushup_knees` | `knee-push-up` | 1 |  |
| `pushup_incline` | `incline-push-up` | 1 |  |
| `pullup_assisted` | `assisted-pull-up` | 1 | Пример тренажёра с противовесом и опорой под колени. |
| `dip_assisted` | `generated` | 1 | Пример брусьев с противовесом и опорой под колени; конструкция зависит от модели. |
| `bench_sit_to_stand` | `generated` | 1 | Два положения одного движения. Высоту устойчивой скамьи подбирают по удобству. |
| `dead_bug` | `generated` | 1 | Два положения: исходное и выпрямление противоположных руки и ноги. |
| `pushup_low` | `generated` | 1 | Ладони смещены ближе к поясу. Это отдельная постановка рук, а не уменьшенная амплитуда. |
| `reverse_pec_fly` | `generated` | 1 | Пример обратной «бабочки»: грудь обращена к опоре, руки разводятся в стороны. |
| `pushup_high` | `generated` | 1 | Показана постановка ладоней впереди плеч; это отдельный усложнённый вариант. |
| `crunch` | `generated` | 1 | Небольшой подъём лопаток: колени согнуты, стопы остаются на полу. |
| `bench_dip_reverse` | `generated` | 1 | Вариант с согнутыми коленями и стопами на полу. Справочная иллюстрация, а не назначение нагрузки. |
| `lying_leg_curl` | `generated` | 1 | Пример тренажёра для сгибания ног лёжа; регулировки и рычаги зависят от модели. |
