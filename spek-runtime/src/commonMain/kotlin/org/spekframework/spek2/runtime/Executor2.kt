package org.spekframework.spek2.runtime

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.spekframework.spek2.dsl.Skip
import org.spekframework.spek2.dsl.TestBody
import org.spekframework.spek2.lifecycle.CachingMode
import org.spekframework.spek2.lifecycle.MemoizedValue
import org.spekframework.spek2.runtime.execution.ExecutionListener
import org.spekframework.spek2.runtime.execution.ExecutionRequest
import org.spekframework.spek2.runtime.execution.ExecutionResult
import org.spekframework.spek2.runtime.lifecycle.MemoizedValueReader
import org.spekframework.spek2.runtime.scope.GroupScopeImpl
import org.spekframework.spek2.runtime.scope.ScopeDeclaration
import org.spekframework.spek2.runtime.scope.ScopeImpl
import org.spekframework.spek2.runtime.scope.TestScopeImpl


class Executor2 {
    /**
     * beforeGroup and afterGroup contains declarations that needs to be invoked when the group is being executed.
     * beforeEachGroup, afterEachGroup, beforeEachTest and afterEachTests are passed further down to the children
     * of this group.
     */
    private class ScopeDeclarations(val beforeGroup: List<ScopeDeclaration>,
                                    val afterGroup: List<ScopeDeclaration>,
                                    val beforeEachGroup: List<ScopeDeclaration>,
                                    val afterEachGroup: List<ScopeDeclaration>,
                                    val beforeEachTest: List<ScopeDeclaration>,
                                    val afterEachTest: List<ScopeDeclaration>) {
        companion object {
            fun from(scope: GroupScopeImpl): ScopeDeclarations {
                return extractPhases(scope.getDeclarations())
            }

            private fun extractPhases(declarations: List<ScopeDeclaration>): ScopeDeclarations {
                val beforeGroup = mutableListOf<ScopeDeclaration>()
                val afterGroup = mutableListOf<ScopeDeclaration>()
                val beforeEachGroup = mutableListOf<ScopeDeclaration>()
                val afterEachGroup = mutableListOf<ScopeDeclaration>()
                val beforeEachTest = mutableListOf<ScopeDeclaration>()
                val afterEachTest = mutableListOf<ScopeDeclaration>()

                declarations.forEach {
                    when (it) {
                        is ScopeDeclaration.Fixture -> {
                            when (it.type) {
                                ScopeDeclaration.FixtureType.BEFORE_GROUP -> beforeGroup.add(it)
                                ScopeDeclaration.FixtureType.AFTER_GROUP -> afterGroup.add(0, it)
                                ScopeDeclaration.FixtureType.BEFORE_EACH_GROUP -> {
                                    beforeGroup.add(it)
                                    beforeEachGroup.add(it)
                                }
                                ScopeDeclaration.FixtureType.AFTER_EACH_GROUP -> {
                                    afterGroup.add(0, it)
                                    afterEachGroup.add(0, it)
                                }
                                ScopeDeclaration.FixtureType.BEFORE_EACH_TEST -> beforeEachTest.add(it)
                                ScopeDeclaration.FixtureType.AFTER_EACH_TEST -> afterEachTest.add(0, it)
                                else -> throw IllegalArgumentException("Invalid fixture type: ${it.type}")
                            }
                        }
                        is ScopeDeclaration.Memoized<*> -> {
                            when (it.cachingMode) {
                                CachingMode.GROUP, CachingMode.EACH_GROUP -> {
                                    beforeGroup.add(it)
                                    afterGroup.add(0, it)

                                    beforeEachGroup.add(it)
                                    afterEachGroup.add(0, it)
                                }
                                CachingMode.SCOPE -> {
                                    beforeGroup.add(it)
                                    afterGroup.add(0, it)
                                }
                                CachingMode.TEST -> {
                                    beforeEachTest.add(it)
                                    afterEachTest.add(0, it)
                                }
                                else -> throw IllegalArgumentException("Invalid caching mode: ${it.cachingMode}")
                            }
                        }
                    }
                }

                return ScopeDeclarations(
                    beforeGroup, afterGroup, beforeEachGroup, afterEachGroup, beforeEachTest, afterEachTest
                )
            }
        }
    }

    fun execute(request: ExecutionRequest) {
        request.executionListener.executionStart()
        request.roots.forEach { execute(it as GroupScopeImpl, request.executionListener, emptyList(), emptyList(), emptyList(), emptyList()) }
        request.executionListener.executionFinish()
    }

    private fun execute(group: GroupScopeImpl, listener: ExecutionListener,
                        beforeEachGroup: List<ScopeDeclaration>, afterEachGroup: List<ScopeDeclaration>,
                        beforeEachTest: List<ScopeDeclaration>, afterEachTest: List<ScopeDeclaration>) {
        if (group.skip is Skip.Yes) {
            scopeIgnored(group, group.skip.reason, listener)
            return
        }

        scopeExecutionStarted(group, listener)
        val scopeDeclarations = ScopeDeclarations.from(group)
        val result = executeSafely {
            try {
                // run before each groups from parent
                executeBeforeEachGroup(beforeEachGroup)
                // run before group for current scope (including before each group declared in this scope)
                executeBeforeGroup(scopeDeclarations.beforeGroup)

                // accumulate declarations needed by descendants
                val combinedBeforeEachGroup = beforeEachGroup + scopeDeclarations.beforeEachGroup
                val combinedAfterEachGroup = scopeDeclarations.afterEachGroup + afterEachGroup
                val combinedBeforeEachTest = beforeEachTest + scopeDeclarations.beforeEachTest
                val combinedAfterEachTest = scopeDeclarations.afterEachTest + afterEachTest

                if (group.failFast) {
                    // fail fast group should only contain tests
                    val tests = group.getChildren().map { it as TestScopeImpl }

                    var failed = false
                    for (test in tests) {
                        if (failed) {
                            scopeIgnored(test, "Previous test failed", listener)
                            continue
                        }

                        failed = executeTest(test, listener, combinedBeforeEachTest, combinedAfterEachTest) != ExecutionResult.Success
                    }

                } else {
                    group.getChildren().forEach { child ->
                        when (child) {
                            is GroupScopeImpl -> execute(child, listener, combinedBeforeEachGroup, combinedAfterEachGroup, combinedBeforeEachTest, combinedAfterEachTest)
                            is TestScopeImpl -> {
                                executeTest(child, listener, combinedBeforeEachTest, combinedAfterEachTest)
                            }
                        }
                    }
                }
            } finally {
                // run after group for current scope (including after each group declared in this scope)
                executeAfterGroup(scopeDeclarations.afterGroup)
                // run after each groups from parent
                executeAfterEachGroup(afterEachGroup)
            }
        }

        scopeExecutionFinished(group, result, listener)
    }

    private fun executeTest(test: TestScopeImpl, listener: ExecutionListener,
                            beforeEachTest: List<ScopeDeclaration>, afterEachTest: List<ScopeDeclaration>): ExecutionResult? {
        if (test.skip is Skip.Yes) {
            scopeIgnored(test, test.skip.reason, listener)
            return null
        }

        scopeExecutionStarted(test, listener)
        val result = executeSafely {
            try {
                doRunBlocking {
                    // this needs to be here, in K/N the event loop
                    // is started during a runBlocking call. Calling
                    // any builders outside that will throw an exception.
                    val job = GlobalScope.async {
                        executeBeforeEachTest(beforeEachTest)
                        test.body(object: TestBody {
                            override fun <T> memoized(): MemoizedValue<T> {
                                return MemoizedValueReader(test)
                            }

                        })
                    }

                    val exception = withTimeout(test.timeout) {
                        try {
                            job.await()
                            null
                        } catch (e: Throwable) {
                            e
                        }
                    }

                    if (exception != null) {
                        throw exception
                    }
                }

            } finally {
                executeAfterEachTest(afterEachTest)
            }
        }
        scopeExecutionFinished(test, result, listener)
        return result
    }

    private fun executeBeforeEachTest(beforeEachTest: List<ScopeDeclaration>) {
        beforeEachTest.forEach {
            when (it) {
                is ScopeDeclaration.Fixture -> it.cb()
                is ScopeDeclaration.Memoized<*> -> {
                    it.adapter.init()
                }
            }
        }
    }

    private fun executeAfterEachTest(afterEachTest: List<ScopeDeclaration>) {
        afterEachTest.forEach {
            when (it) {
                is ScopeDeclaration.Fixture -> it.cb()
                is ScopeDeclaration.Memoized<*> -> {
                    it.adapter.destroy()
                }
            }
        }
    }


    private fun executeBeforeGroup(beforeGroup: List<ScopeDeclaration>) {
        beforeGroup.forEach {
            when (it) {
                is ScopeDeclaration.Fixture -> it.cb()
                is ScopeDeclaration.Memoized<*> -> {
                    it.adapter.init()
                }
            }
        }
    }

    private fun executeAfterGroup(afterGroup: List<ScopeDeclaration>) {
        afterGroup.forEach {
            when (it) {
                is ScopeDeclaration.Fixture -> it.cb()
                is ScopeDeclaration.Memoized<*> -> {
                    it.adapter.destroy()
                }
            }
        }
    }

    private fun executeBeforeEachGroup(beforeEachGroup: List<ScopeDeclaration>) {
        beforeEachGroup.forEach {
            when (it) {
                is ScopeDeclaration.Fixture -> it.cb()
                is ScopeDeclaration.Memoized<*> -> {
                    // TODO: need a stack here for nested groups
                    it.adapter.init()
                }
            }
        }
    }

    private fun executeAfterEachGroup(afterEachGroup: List<ScopeDeclaration>) {
        afterEachGroup.forEach {
            when (it) {
                is ScopeDeclaration.Fixture -> it.cb()
                is ScopeDeclaration.Memoized<*> -> {
                    it.adapter.destroy()
                }
            }
        }
    }

    private inline fun executeSafely(block: () -> Unit): ExecutionResult = try {
        block()
        ExecutionResult.Success
    } catch (e: Throwable) {
        ExecutionResult.Failure(e)
    }

    private fun scopeExecutionStarted(scope: ScopeImpl, listener: ExecutionListener) =
        when (scope) {
            is GroupScopeImpl -> listener.groupExecutionStart(scope)
            is TestScopeImpl -> listener.testExecutionStart(scope)
        }

    private fun scopeExecutionFinished(scope: ScopeImpl, result: ExecutionResult, listener: ExecutionListener) =
        when (scope) {
            is GroupScopeImpl -> listener.groupExecutionFinish(scope, result)
            is TestScopeImpl -> listener.testExecutionFinish(scope, result)
        }

    private fun scopeIgnored(scope: ScopeImpl, reason: String?, listener: ExecutionListener) =
        when (scope) {
            is GroupScopeImpl -> listener.groupIgnored(scope, reason)
            is TestScopeImpl -> listener.testIgnored(scope, reason)
        }
}