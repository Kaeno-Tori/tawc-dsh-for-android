package me.phie.tawc.ops

/**
 * Coarse stages used by the generic ops layer. Concrete operations
 * (install, uninstall, …) carry their own finer-grained stage enum
 * for internal use; at the [Operation] surface they project down to
 * one of these four values.
 */
enum class OperationStage {
    /** Just-registered, no progress reported yet. */
    IDLE,

    /** Work in flight. Progress bar visible; Cancel button visible. */
    RUNNING,

    /** Successful terminal. Progress bar hidden; status painted success-green. */
    DONE,

    /** Failed or cancelled terminal. Progress bar hidden; status painted danger-red. */
    FAILED;

    val isTerminal: Boolean get() = this == DONE || this == FAILED
}

/**
 * Snapshot for [Operation.progress]. [percent] is `null` when the
 * operation can't report a meaningful 0..100; the panel renders an
 * indeterminate spinner in that case.
 */
data class OperationProgress(
    val stage: OperationStage,
    val message: String,
    val percent: Int? = null,
    /**
     * Ordered step names for this operation, and the index of the one
     * running now. Empty when the op has no meaningful step list, which
     * viewers render as "just a status line + bar".
     *
     * This layer deliberately doesn't know what the steps *are* — it
     * only lays them out — so an operation declares the steps it will
     * actually walk. An install that imports a local pack has nothing to
     * download and nothing to verify, so it doesn't list those; a
     * checklist with steps that never run would be worse than none.
     *
     * [currentStep] is `steps.size` once every step is done, and `-1`
     * when there is no list.
     */
    val steps: List<String> = emptyList(),
    val currentStep: Int = -1,
)
