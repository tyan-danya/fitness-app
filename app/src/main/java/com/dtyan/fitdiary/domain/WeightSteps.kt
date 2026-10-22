package com.dtyan.fitdiary.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Шаг веса тренажёра по собственным записям: какие веса человек реально ставил.
 * Чистая логика без Android — шкала стека определяется по тому, как часто подходы
 * ложатся на сетку base + k·step.
 */
object WeightSteps {
    /** Кандидаты от крупного к мелкому: берём самый крупный шаг, который объясняет записи. */
    val CANDIDATES = listOf(10.0, 7.5, 5.0, 4.0, 3.0, 2.5, 2.0)

    const val MIN_SETS = 6
    const val MIN_DISTINCT = 3
    const val MIN_COVERAGE = 0.8
    const val RELATIVE_COVERAGE = 0.95
    private const val EPS = 1e-6

    /**
     * Догадка о шкале: [step] — шаг, [base] — смещение шкалы (0 ≤ base < step),
     * [coverage] — доля подходов, попавших на эту шкалу.
     */
    data class StepGuess(val step: Double, val base: Double, val coverage: Double)

    private class Fit(val step: Double, val base: Double, val coverage: Double, val distinctOnGrid: Int)

    /**
     * Шаг по истории «вес → число подходов». null — данных мало (меньше [MIN_SETS] подходов
     * или [MIN_DISTINCT] разных весов) или уверенной шкалы нет (смешанные стеки, фунты).
     */
    fun guess(counts: Map<Double, Int>): StepGuess? {
        val data = clean(counts)
        val total = data.values.sum()
        if (total < MIN_SETS || data.size < MIN_DISTINCT) return null
        val fits = CANDIDATES.map { fit(data, total, it) }
        val best = fits.maxOf { it.coverage }
        return fits
            .filter { it.coverage >= MIN_COVERAGE && it.coverage >= RELATIVE_COVERAGE * best && it.distinctOnGrid >= MIN_DISTINCT }
            .maxByOrNull { it.step }
            ?.let { StepGuess(it.step, it.base, it.coverage) }
    }

    /** Самый частый остаток веса по модулю [step] — смещение шкалы; 0.0, если записей нет. */
    fun base(counts: Map<Double, Int>, step: Double): Double {
        if (!step.isFinite() || step <= 0) return 0.0
        val data = clean(counts)
        if (data.isEmpty()) return 0.0
        return mostCommonResidue(residues(data, step)) ?: 0.0
    }

    /**
     * Следующее значение шкалы base + k·step. Вес на шкале сдвигается на ±step;
     * вес вне шкалы притягивается к ближайшему делению в нужную сторону. Не ниже нуля.
     */
    fun bump(current: Double, step: Double, base: Double, up: Boolean): Double {
        val start = if (current.isFinite()) current.coerceAtLeast(0.0) else 0.0
        if (!step.isFinite() || step <= 0) return round3(start)
        val offset = residue(if (base.isFinite()) base else 0.0, step)
        val k = (start - offset) / step
        val nearest = round(k)
        val target = when {
            abs(k - nearest) * step < EPS -> offset + (nearest + if (up) 1 else -1) * step
            up -> offset + ceil(k) * step
            else -> offset + floor(k) * step
        }
        return round3(target.coerceAtLeast(0.0))
    }

    private fun clean(counts: Map<Double, Int>): Map<Double, Int> =
        counts.filter { (weight, sets) -> weight.isFinite() && weight > 0 && sets > 0 }

    private fun fit(data: Map<Double, Int>, total: Int, step: Double): Fit {
        val byResidue = residues(data, step)
        val base = mostCommonResidue(byResidue) ?: 0.0
        val onGrid = data.filterKeys { residue(it, step) == base }
        return Fit(step, base, onGrid.values.sum().toDouble() / total, onGrid.size)
    }

    private fun residues(data: Map<Double, Int>, step: Double): Map<Double, Int> =
        data.entries.groupingBy { residue(it.key, step) }.fold(0) { acc, entry -> acc + entry.value }

    /** При равенстве — меньший остаток: шкала от нуля привычнее. */
    private fun mostCommonResidue(byResidue: Map<Double, Int>): Double? =
        byResidue.entries.sortedBy { it.key }.maxByOrNull { it.value }?.key

    private fun residue(weight: Double, step: Double): Double {
        val r = ((weight % step) + step) % step
        return if (r < EPS || step - r < EPS) 0.0 else round3(r)
    }

    private fun round3(value: Double): Double {
        val rounded = round(value * 1000.0) / 1000.0
        return if (rounded == 0.0) 0.0 else rounded // без «-0.0»
    }
}

/**
 * Упражнения, где шаг выбран человеком: подбор по записям их больше не трогает, даже если
 * выбрано те же 2,5 кг, что стоят по умолчанию.
 */
interface ManualStepRegistry {
    fun isWeightStepManual(exerciseId: Long): Boolean
    fun markWeightStepManual(exerciseId: Long)
}
