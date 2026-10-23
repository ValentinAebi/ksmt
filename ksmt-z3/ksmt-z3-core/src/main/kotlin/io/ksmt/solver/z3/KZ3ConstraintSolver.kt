package io.ksmt.solver.z3

import com.microsoft.z3.Params
import com.microsoft.z3.Status
import com.microsoft.z3.Z3Exception
import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolverException
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.model.KNativeSolverModel
import io.ksmt.sort.KBoolSort
import io.ksmt.utils.library.NativeLibraryLoaderUtils
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.*
import kotlin.time.Duration
import kotlin.time.DurationUnit

abstract class KZ3ConstraintSolver : AutoCloseable {

    protected abstract val z3Ctx: KZ3Context

    @Suppress("LeakingThis")
    private val contextCleanupActionHandler = registerContextForCleanup(this, z3Ctx)

    protected var lastCheckStatus = KSolverStatus.UNKNOWN
    protected var lastReasonOfUnknown: String? = null
    protected var lastModel: KModel? = null
    protected var lastUnsatCore: List<KExpr<KBoolSort>>? = null

    protected abstract fun queryModel(): KZ3Model
    protected abstract fun queryReasonUnknown(): String

    fun model(): KModel = z3Try {
        require(lastCheckStatus == KSolverStatus.SAT) {
            "Model are only available after SAT checks, current solver status: $lastCheckStatus"
        }
        lastModel?.let { return it }

        val z3Model = queryModel()
        return KNativeSolverModel(z3Model).also {
            lastModel = it
        }
    }

    fun reasonOfUnknown(): String = z3Try {
        require(lastCheckStatus == KSolverStatus.UNKNOWN) { "Unknown reason is only available after UNKNOWN checks" }
        lastReasonOfUnknown ?: queryReasonUnknown()
    }

    protected fun Status?.processCheckResult() = when (this) {
        Status.SATISFIABLE -> KSolverStatus.SAT
        Status.UNSATISFIABLE -> KSolverStatus.UNSAT
        Status.UNKNOWN -> KSolverStatus.UNKNOWN
        null -> KSolverStatus.UNKNOWN
    }.also { lastCheckStatus = it }

    protected fun invalidateSolverState() {
        lastReasonOfUnknown = null
        lastCheckStatus = KSolverStatus.UNKNOWN
        lastModel = null
        lastUnsatCore = null
    }

    protected inline fun z3TryCheck(body: () -> KSolverStatus): KSolverStatus = try {
        invalidateSolverState()
        body()
    } catch (ex: Z3Exception) {
        lastReasonOfUnknown = ex.message
        KSolverStatus.UNKNOWN.also { lastCheckStatus = it }
    }

    protected inline fun <reified T> z3Try(body: () -> T): T = try {
        body()
    } catch (ex: Z3Exception) {
        throw KSolverException(ex)
    }

    override fun close() {
        unregisterContextCleanup(contextCleanupActionHandler)
        z3Ctx.close()
    }

    protected fun mkTimeoutParams(timeout: Duration): Params? {
        val z3Timeout = if (timeout == Duration.INFINITE) {
            UInt.MAX_VALUE.toInt()
        } else {
            timeout.toInt(DurationUnit.MILLISECONDS)
        }
        val params = z3Ctx.nativeContext.mkParams().apply {
            add("timeout", z3Timeout)
        }
        return params
    }

    companion object {
        init {
            System.setProperty("z3.skipLibraryLoad", "true")
            NativeLibraryLoaderUtils.load<KZ3NativeLibraryLoader>()
        }

        private val cleanupHandlers = ReferenceQueue<KZ3ConstraintSolver>()
        private val contextForCleanup = IdentityHashMap<PhantomReference<KZ3ConstraintSolver>, KZ3Context>()

        /** Ensure Z3 native context is closed and all native memory is released.
         * */
        private fun registerContextForCleanup(solver: KZ3ConstraintSolver, context: KZ3Context): PhantomReference<KZ3ConstraintSolver> {
            cleanupStaleContexts()
            val cleanupHandler = PhantomReference(solver, cleanupHandlers)
            contextForCleanup[cleanupHandler] = context

            return cleanupHandler
        }

        private fun unregisterContextCleanup(handler: PhantomReference<KZ3ConstraintSolver>) {
            contextForCleanup.remove(handler)
            handler.clear()
            cleanupStaleContexts()
        }

        private fun cleanupStaleContexts() {
            while (true) {
                val handler = cleanupHandlers.poll() ?: break
                contextForCleanup.remove(handler)?.close()
            }
        }
    }

}
