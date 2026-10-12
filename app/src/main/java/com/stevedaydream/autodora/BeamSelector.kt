package com.stevedaydream.autodora

import java.util.PriorityQueue

/** 同分時保留原本展開順序；只維護前 capacity 個候選，不反覆掃描整批節點。 */
internal class BeamSelector(private val capacity: Int, compareAt: ((Int, Int) -> Int)? = null, scoreAt: (Int) -> Int) {
    init {
        require(capacity > 0)
    }

    private val order = Comparator<Int> { a, b ->
        val scoreOrder = compareAt?.invoke(a, b) ?: scoreAt(a).compareTo(scoreAt(b))
        if (scoreOrder != 0) scoreOrder else b.compareTo(a)
    }
    private val heap = PriorityQueue(capacity, order)

    fun select(count: Int): List<Int> {
        require(count >= 0)
        heap.clear()
        for (index in 0 until count) {
            if (heap.size < capacity) {
                heap.add(index)
            } else if (order.compare(index, heap.peek()) > 0) {
                heap.poll()
                heap.add(index)
            }
        }
        return heap.sortedWith(order.reversed())
    }
}
