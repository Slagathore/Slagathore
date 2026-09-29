package dev.slagathore.curbroute.core.solve

import kotlin.random.Random

/**
 * Finds a short visiting order: start at [start], visit every other node exactly once, and optionally
 * finish at a fixed [end] (otherwise you finish wherever the last stop is).
 *
 * This is the "asymmetric traveling salesman" problem. Asymmetric because with curb-side approach,
 * A->B and B->A can be very different (one might need a lap around the block).
 *
 * Strategy:
 *  - up to [exactUpTo] stops: Held-Karp dynamic programming. Mathematically optimal, instant at this size.
 *  - bigger: nearest-neighbour start, then "iterated local search":
 *      local search = keep applying small improving edits until none help:
 *        * Or-opt: pluck out a run of consecutive stops and re-insert it somewhere better (optionally reversed)
 *        * 2-opt: reverse a stretch of the route
 *      kick = "double bridge": cut the route in 4 pieces and swap the middle two. That shakes it out of a
 *             rut that small edits can't escape, then local search runs again. Keep the best ever seen.
 *      restart = if many kicks in a row find nothing better, reshuffle and start fresh (keeping the best).
 *    On 20-60 stops this lands on or within a hair of optimal in well under a second on a phone.
 *
 * Deterministic: the same input + seed always gives the same route, which makes bugs reproducible.
 */
class RouteSolver(
    private val kicks: Int = 400,
    private val seed: Long = 7L,
    /** Held-Karp memory is 2^k * k doubles: 15 stops = ~4 MB, which is fine even on an old phone. */
    private val exactUpTo: Int = 15,
    /** Longest run of stops Or-opt will try to relocate. */
    private val maxSegment: Int = 12,
    /** Kicks without improvement before a fresh random restart. */
    private val restartAfter: Int = 60,
) {
    /** Returns node indexes in visiting order, starting with [start] (and ending with [end] if given). */
    fun solve(cost: Array<DoubleArray>, start: Int = 0, end: Int? = null): IntArray {
        val n = cost.size
        require(n > 0 && cost.all { it.size == n }) { "cost must be a non-empty square matrix" }
        require(start in 0 until n) { "bad start" }
        require(end == null || (end in 0 until n && end != start)) { "bad end" }

        val free = (0 until n).filter { it != start && it != end }
        if (free.size <= exactUpTo) return exact(cost, start, end, free)

        val lo = 1 // position 0 is the fixed start
        val hi = if (end != null) n - 2 else n - 1 // last movable position
        var best = nearestNeighbour(cost, start, end, free)
        localSearch(best, cost, lo, hi)
        var bestCost = routeCost(best, cost)

        val rnd = Random(seed)
        var current = best.copyOf()
        var currentCost = bestCost
        var stale = 0
        repeat(kicks) {
            val restart = stale >= restartAfter
            val candidate = current.copyOf()
            if (restart) {
                shuffleMovable(candidate, lo, hi, rnd)
                stale = 0
            } else {
                doubleBridge(candidate, lo, hi, rnd)
            }
            localSearch(candidate, cost, lo, hi)
            val c = routeCost(candidate, cost)
            if (restart || c < currentCost - EPS) { // walk from the new spot
                current = candidate
                currentCost = c
            }
            if (c < bestCost - EPS) {
                best = candidate.copyOf()
                bestCost = c
                stale = 0
            } else {
                stale++
            }
        }
        return best
    }

    // ---------------------------------------------------------------------------------------------
    // Exact: Held-Karp. dp[mask][j] = cheapest way to leave start, visit exactly the stops in `mask`,
    // and be standing at stop j. 2^12 * 12 * 12 = ~590k steps at the size limit.

    private fun exact(cost: Array<DoubleArray>, start: Int, end: Int?, free: List<Int>): IntArray {
        val k = free.size
        if (k == 0) return listOfNotNull(start, end).toIntArray()
        val full = (1 shl k) - 1
        val dp = Array(1 shl k) { DoubleArray(k) { Double.MAX_VALUE } }
        val parent = Array(1 shl k) { IntArray(k) { -1 } }
        for (j in 0 until k) dp[1 shl j][j] = cost[start][free[j]]
        for (mask in 1..full) {
            for (j in 0 until k) {
                val here = dp[mask][j]
                if (here == Double.MAX_VALUE || (mask and (1 shl j)) == 0) continue
                for (nxt in 0 until k) {
                    if ((mask and (1 shl nxt)) != 0) continue
                    val m2 = mask or (1 shl nxt)
                    val c = here + cost[free[j]][free[nxt]]
                    if (c < dp[m2][nxt]) {
                        dp[m2][nxt] = c
                        parent[m2][nxt] = j
                    }
                }
            }
        }
        var last = 0
        var bestCost = Double.MAX_VALUE
        for (j in 0 until k) {
            val c = dp[full][j] + (if (end != null) cost[free[j]][end] else 0.0)
            if (c < bestCost) { bestCost = c; last = j }
        }
        // Walk the parent pointers backwards to rebuild the order.
        val order = ArrayList<Int>(k)
        var mask = full
        var j = last
        while (j != -1) {
            order += free[j]
            val p = parent[mask][j]
            mask = mask xor (1 shl j)
            j = p
        }
        order.reverse()
        return (listOf(start) + order + listOfNotNull(end)).toIntArray()
    }

    // ---------------------------------------------------------------------------------------------
    // Heuristic pieces

    private fun nearestNeighbour(cost: Array<DoubleArray>, start: Int, end: Int?, free: List<Int>): IntArray {
        val left = free.toMutableList()
        val order = mutableListOf(start)
        while (left.isNotEmpty()) {
            val here = order.last()
            val next = left.minBy { cost[here][it] }
            order += next
            left -= next
        }
        end?.let { order += it }
        return order.toIntArray()
    }

    /** Apply improving moves until none are left (a "local optimum"). */
    private fun localSearch(r: IntArray, cost: Array<DoubleArray>, lo: Int, hi: Int) {
        while (orOpt(r, cost, lo, hi) || twoOpt(r, cost, lo, hi)) {
            // each call applied one improving move; loop until neither finds one
        }
    }

    /** Cost of edge a->b, where b == null means "the route just ends here" (free). */
    private fun edge(cost: Array<DoubleArray>, a: Int, b: Int?): Double = if (b == null) 0.0 else cost[a][b]

    /**
     * Or-opt: move the run of stops r[i .. i+len-1] to sit between positions p and p+1, either as-is or
     * reversed. Only the edges at the cut and insert points change (plus the run's own edges when reversed),
     * so each try is O(1) with the running totals below.
     */
    private fun orOpt(r: IntArray, cost: Array<DoubleArray>, lo: Int, hi: Int): Boolean {
        val n = r.size
        val (fwd, bwd) = prefixCosts(r, cost)
        val maxLen = minOf(maxSegment, hi - lo) // moving the whole movable part is pointless
        for (len in 1..maxLen) {
            for (i in lo..hi - len + 1) {
                val segEnd = i + len - 1
                val prev = r[i - 1]
                val first = r[i]
                val last = r[segEnd]
                val next: Int? = if (segEnd + 1 < n) r[segEnd + 1] else null
                // what we save by cutting the run out and closing the gap
                val saved = cost[prev][first] + edge(cost, last, next) - edge(cost, prev, next)
                val reversalDelta = (bwd[segEnd] - bwd[i]) - (fwd[segEnd] - fwd[i]) // run's own edges, flipped
                for (p in lo - 1..hi) {
                    if (p in i - 1..segEnd) continue // inserting where it already is
                    val a = r[p]
                    val b: Int? = if (p + 1 < n) r[p + 1] else null
                    val gap = edge(cost, a, b)
                    if (cost[a][first] + edge(cost, last, b) - gap - saved < -EPS) {
                        moveSegment(r, i, len, p, reversed = false)
                        return true
                    }
                    if (len >= 2 && cost[a][last] + edge(cost, first, b) - gap + reversalDelta - saved < -EPS) {
                        moveSegment(r, i, len, p, reversed = true)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun moveSegment(r: IntArray, i: Int, len: Int, p: Int, reversed: Boolean) {
        val segment = r.copyOfRange(i, i + len).toList().let { if (reversed) it.reversed() else it }
        val rest = r.toMutableList().apply { subList(i, i + len).clear() }
        val insertAt = (if (p < i) p else p - len) + 1
        rest.addAll(insertAt, segment)
        rest.forEachIndexed { k, v -> r[k] = v }
    }

    /** fwd[k] = cost of r[0]->...->r[k]; bwd[k] = cost of the same edges driven backwards. */
    private fun prefixCosts(r: IntArray, cost: Array<DoubleArray>): Pair<DoubleArray, DoubleArray> {
        val fwd = DoubleArray(r.size)
        val bwd = DoubleArray(r.size)
        for (k in 1 until r.size) {
            fwd[k] = fwd[k - 1] + cost[r[k - 1]][r[k]]
            bwd[k] = bwd[k - 1] + cost[r[k]][r[k - 1]]
        }
        return fwd to bwd
    }

    /**
     * 2-opt: reverse r[i..k]. In an asymmetric problem the reversed stretch's own cost changes too,
     * so we keep running totals of forward and backward edge costs to price it in O(1).
     */
    private fun twoOpt(r: IntArray, cost: Array<DoubleArray>, lo: Int, hi: Int): Boolean {
        val n = r.size
        val (fwd, bwd) = prefixCosts(r, cost)
        for (i in lo until hi) {
            for (k in i + 1..hi) {
                val a = r[i - 1]
                val b = r[i]
                val e = r[k]
                val f: Int? = if (k + 1 < n) r[k + 1] else null
                val before = cost[a][b] + edge(cost, e, f) + (fwd[k] - fwd[i])
                val after = cost[a][e] + edge(cost, b, f) + (bwd[k] - bwd[i])
                if (after - before < -EPS) {
                    r.reverse(i, k + 1)
                    return true
                }
            }
        }
        return false
    }

    private fun shuffleMovable(r: IntArray, lo: Int, hi: Int, rnd: Random) {
        val movable = r.copyOfRange(lo, hi + 1).toMutableList()
        movable.shuffle(rnd)
        movable.toIntArray().copyInto(r, lo)
    }

    /** Cut the movable part into A|B|C|D and reorder to A|C|B|D. */
    private fun doubleBridge(r: IntArray, lo: Int, hi: Int, rnd: Random) {
        val m = hi - lo + 1
        if (m < 8) return
        val cuts = generateSequence { 1 + rnd.nextInt(m - 1) }.distinct().take(3).sorted().toList()
        val movable = r.copyOfRange(lo, hi + 1)
        val a = movable.copyOfRange(0, cuts[0])
        val b = movable.copyOfRange(cuts[0], cuts[1])
        val c = movable.copyOfRange(cuts[1], cuts[2])
        val d = movable.copyOfRange(cuts[2], m)
        (a + c + b + d).copyInto(r, lo)
    }

    companion object {
        private const val EPS = 1e-9

        fun routeCost(order: IntArray, cost: Array<DoubleArray>): Double {
            var total = 0.0
            for (k in 0 until order.lastIndex) total += cost[order[k]][order[k + 1]]
            return total
        }
    }
}
