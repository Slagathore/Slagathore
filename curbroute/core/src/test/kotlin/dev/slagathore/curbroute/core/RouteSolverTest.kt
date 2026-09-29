package dev.slagathore.curbroute.core

import dev.slagathore.curbroute.core.solve.RouteSolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteSolverTest {
    private fun randomAsymmetric(n: Int, seed: Int): Array<DoubleArray> {
        val rnd = Random(seed)
        // Points on a plane + a random "wrong-side" penalty per direction, a bit like curb-side costs.
        val xs = DoubleArray(n) { rnd.nextDouble(0.0, 1000.0) }
        val ys = DoubleArray(n) { rnd.nextDouble(0.0, 1000.0) }
        return Array(n) { i ->
            DoubleArray(n) { j ->
                if (i == j) 0.0 else Math.hypot(xs[i] - xs[j], ys[i] - ys[j]) + rnd.nextDouble(0.0, 400.0)
            }
        }
    }

    private fun assertValidRoute(order: IntArray, n: Int, start: Int, end: Int?) {
        assertEquals(n, order.size)
        assertEquals((0 until n).toSet(), order.toSet(), "every node exactly once")
        assertEquals(start, order.first())
        end?.let { assertEquals(it, order.last()) }
    }

    @Test
    fun `heuristic finds the proven optimum on small problems`() {
        val exact = RouteSolver(exactUpTo = 12)
        val heuristic = RouteSolver(exactUpTo = 0)
        for (seed in 1..40) {
            val cost = randomAsymmetric(12, seed)
            for (end in listOf(null, 11)) {
                val best = RouteSolver.routeCost(exact.solve(cost, 0, end), cost)
                val found = heuristic.solve(cost, 0, end)
                assertValidRoute(found, 12, 0, end)
                assertEquals(best, RouteSolver.routeCost(found, cost), 1e-6, "seed=$seed end=$end")
            }
        }
    }

    @Test
    fun `follows a one-way loop instead of fighting it`() {
        // 15 houses around a block where going clockwise is cheap (100) and anything else is expensive,
        // like houses that are only curb-side when you drive the loop one way.
        val n = 15
        val cost = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else if (j == (i + 1) % n) 100.0 else 900.0 } }
        val order = RouteSolver().solve(cost, start = 0)
        assertContentEquals((0 until n).toList().toIntArray(), order)
    }

    @Test
    fun `sixty stops stay valid and fast`() {
        val cost = randomAsymmetric(61, 99)
        val t0 = System.nanoTime()
        val order = RouteSolver().solve(cost, start = 0, end = 60)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertValidRoute(order, 61, 0, 60)
        println("61-node solve took $ms ms")
        assertTrue(ms < 5_000, "took $ms ms")
        // and it should beat plain nearest-neighbour-in-index-order by a lot
        val naive = RouteSolver.routeCost((0..60).toList().toIntArray(), cost)
        assertTrue(RouteSolver.routeCost(order, cost) < naive * 0.6)
    }

    @Test
    fun `tiny inputs`() {
        assertContentEquals(intArrayOf(0), RouteSolver().solve(arrayOf(doubleArrayOf(0.0))))
        val two = arrayOf(doubleArrayOf(0.0, 5.0), doubleArrayOf(5.0, 0.0))
        assertContentEquals(intArrayOf(0, 1), RouteSolver().solve(two))
        assertContentEquals(intArrayOf(1, 0), RouteSolver().solve(two, start = 1, end = 0))
    }
}
