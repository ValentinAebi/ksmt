package io.ksmt.solver.z3

import com.microsoft.z3.*
import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KArithSort
import io.ksmt.sort.KBoolSort
import kotlin.time.Duration

open class KZ3Optimize(private val ctx: KContext) : KZ3ConstraintSolver() {
    override val z3Ctx = KZ3Context(ctx)

    private val optimize by lazy { createOptimize() }

    private val exprInternalizer by lazy {
        optimize
        createExprInternalizer(z3Ctx)
    }

    // TODO configure method

    fun assert(expr: KExpr<KBoolSort>) = z3Try {
        ctx.ensureContextMatch(expr)

        val z3Expr = with(exprInternalizer) { expr.internalizeExpr() }
        optimize.optimizeAssert(z3Expr)
    }

    fun <T : KArithSort> maximize(expr: KExpr<T>) = z3Try {
        ctx.ensureContextMatch(expr)

        val z3Expr = with(exprInternalizer) { expr.internalizeExpr() }
        optimize.optimizeMaximize(z3Expr)
    }

    fun <T : KArithSort> minimize(expr: KExpr<T>) = z3Try {
        ctx.ensureContextMatch(expr)

        val z3Expr = with(exprInternalizer) { expr.internalizeExpr() }
        optimize.optimizeMinimize(z3Expr)
    }

    fun push() {
        optimize.optimizePush()
        z3Ctx.pushAssertionLevel()
    }

    fun pop() {
        z3Ctx.popAssertionLevel()
        optimize.optimizePop()
    }

    fun check(timeout: Duration = Duration.INFINITE): KSolverStatus = z3TryCheck {
        optimize.setParameters(mkTimeoutParams(timeout))
        optimize.Check().processCheckResult()
    }

    // TODO unsat core

    override fun queryModel(): KZ3Model = KZ3Model(optimize.model, ctx, z3Ctx, exprInternalizer)

    override fun queryReasonUnknown(): String = optimize.reasonUnknown

    open fun createExprInternalizer(z3Ctx: KZ3Context): KZ3ExprInternalizer = KZ3ExprInternalizer(ctx, z3Ctx)

    private fun createOptimize(): Optimize = z3Ctx.nativeContext.mkOptimize()

}
