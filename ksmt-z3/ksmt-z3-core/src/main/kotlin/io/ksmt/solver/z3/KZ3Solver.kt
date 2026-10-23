package io.ksmt.solver.z3

import com.microsoft.z3.*
import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KBoolSort
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import kotlin.time.Duration

open class KZ3Solver(private val ctx: KContext) : KSolver<KZ3SolverConfiguration>, KZ3ConstraintSolver() {
    override val z3Ctx = KZ3Context(ctx)

    private var lazyConfiguration: KZ3SolverLazyConfiguration? = null
    private var solverInitialized: Boolean = false
    private val solver by lazy { createSolver() }
    fun nativeSolver(): Solver = solver

    private var currentScope: UInt = 0u

    private val exprInternalizer by lazy {
        // assert internalizes before it touches solver, so resolve the configuration first
        solver
        createExprInternalizer(z3Ctx)
    }
    private val exprConverter by lazy {
        createExprConverter(z3Ctx)
    }

    open fun createExprInternalizer(z3Ctx: KZ3Context): KZ3ExprInternalizer = KZ3ExprInternalizer(ctx, z3Ctx)

    open fun createExprConverter(z3Ctx: KZ3Context) = KZ3ExprConverter(ctx, z3Ctx)

    private fun createSolver(): Solver {
        solverInitialized = true
        val config = lazyConfiguration
        lazyConfiguration = null

        val resolvedLogic = config?.resolveLogic()
        z3Ctx.uninterpretedValueDescriptor = resolvedLogic?.valueDescriptor
            ?: KZ3UninterpretedValueDescriptor.INT

        val logic = resolvedLogic?.logic

        val solver = if (logic != null) {
            z3Ctx.nativeContext.mkSolver(logic)
        } else {
            z3Ctx.nativeContext.mkSolver()
        }

        config?.params?.let { solver.setParameters(it) }

        return solver
    }

    override fun configure(configurator: KZ3SolverConfiguration.() -> Unit) {
        if (!solverInitialized) {
            val config = lazyConfiguration
                ?: KZ3SolverLazyConfiguration(z3Ctx.nativeContext.mkParams())
                    .also { lazyConfiguration = it }

            config.configurator()
        } else {
            val config = KZ3SolverParamsConfiguration(z3Ctx.nativeContext.mkParams())
            config.configurator()
            solver.setParameters(config.params)
        }
    }

    override fun push() {
        solver.push()
        z3Ctx.pushAssertionLevel()
        currentScope++
    }

    override fun pop(n: UInt) {
        require(n <= currentScope) {
            "Can not pop $n scope levels because current scope level is $currentScope"
        }
        if (n == 0u) return

        solver.pop(n.toInt())
        repeat(n.toInt()) { z3Ctx.popAssertionLevel() }

        currentScope -= n
    }

    override fun assert(expr: KExpr<KBoolSort>) = z3Try {
        ctx.ensureContextMatch(expr)

        val z3Expr = with(exprInternalizer) { expr.internalizeExpr() }
        solver.solverAssert(z3Expr)

        z3Ctx.assertPendingAxioms(solver)
    }

    private val trackedAssertions = Long2ObjectOpenHashMap<KExpr<KBoolSort>>()

    override fun assertAndTrack(expr: KExpr<KBoolSort>) = z3Try {
        ctx.ensureContextMatch(expr)

        val trackExpr = ctx.mkFreshConst("track", ctx.boolSort)
        val z3Expr = with(exprInternalizer) { expr.internalizeExpr() }
        val z3TrackVar = with(exprInternalizer) { trackExpr.internalizeExpr() }

        trackedAssertions.put(z3TrackVar, expr)

        solver.solverAssertAndTrack(z3Expr, z3TrackVar)

        z3Ctx.assertPendingAxioms(solver)
    }

    override fun check(timeout: Duration): KSolverStatus = z3TryCheck {
        solver.updateTimeout(timeout)
        solver.check().processCheckResult()
    }

    override fun checkWithAssumptions(
        assumptions: List<KExpr<KBoolSort>>,
        timeout: Duration
    ): KSolverStatus = z3TryCheck {
        ctx.ensureContextMatch(assumptions)

        val z3Assumptions = with(exprInternalizer) {
            LongArray(assumptions.size) {
                val assumption = assumptions[it]

                /**
                 * Assumptions are trivially unsat and no check-sat is required.
                 * */
                if (assumption == ctx.falseExpr) {
                    lastUnsatCore = listOf(ctx.falseExpr)
                    lastCheckStatus = KSolverStatus.UNSAT
                    return KSolverStatus.UNSAT
                }

                assumption.internalizeExpr()
            }
        }

        solver.updateTimeout(timeout)

        solver.solverCheckAssumptions(z3Assumptions).processCheckResult()
    }

    override fun queryModel(): KZ3Model = KZ3Model(solver.model, ctx, z3Ctx, exprInternalizer)

    override fun unsatCore(): List<KExpr<KBoolSort>> = z3Try {
        require(lastCheckStatus == KSolverStatus.UNSAT) { "Unsat cores are only available after UNSAT checks" }

        val unsatCore = lastUnsatCore ?: with(exprConverter) {
            val solverUnsatCore = solver.solverGetUnsatCore()
            solverUnsatCore.map { trackedAssertions.get(it) ?: it.convertExpr() }
        }
        lastUnsatCore = unsatCore

        unsatCore
    }

    override fun queryReasonUnknown(): String = solver.reasonUnknown

    override fun interrupt() = z3Try {
        solver.interrupt()
    }

    private fun Solver.updateTimeout(timeout: Duration) {
        setParameters(mkTimeoutParams(timeout))
    }
}
