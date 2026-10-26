package pl.autobot.app.data

/**
 * Operacje na drzewie bloków potrzebne w trybie krokowym (pływający panel).
 *
 * Położenie bloku w drzewie to „ścieżka”: [i0] dla bloku z najwyższego poziomu, a dla bloku zagnieżdżonego
 * [i0, gałąź, i1, gałąź, i2…] – gdzie „gałąź” to numer kieszeni kontenera
 * (JEŻELI: 0 = TO, 1 = W PRZECIWNYM RAZIE; pozostałe kontenery mają tylko kieszeń 0).
 */

/** Krok „spłaszczonego” algorytmu: każdy blok (także kontener) w kolejności odczytu, z głębokością zagnieżdżenia. */
data class FlatStep(val path: List<Int>, val block: Block, val depth: Int)

fun Block.branchCount(): Int = when (this) {
    is IfPatternBlock -> 2
    is LoopBlock, is ForEachPatternBlock, is AtTimeBlock, is RepeatEveryBlock -> 1
    else -> 0
}

fun Block.branch(i: Int): List<Block> = when (val b = this) {
    is IfPatternBlock -> if (i == 0) b.thenBlocks else b.elseBlocks
    is LoopBlock -> b.body
    is ForEachPatternBlock -> b.body
    is AtTimeBlock -> b.body
    is RepeatEveryBlock -> b.body
    else -> emptyList()
}

fun Block.withBranch(i: Int, list: List<Block>): Block = when (val b = this) {
    is IfPatternBlock -> if (i == 0) b.copy(thenBlocks = list) else b.copy(elseBlocks = list)
    is LoopBlock -> b.copy(body = list)
    is ForEachPatternBlock -> b.copy(body = list)
    is AtTimeBlock -> b.copy(body = list)
    is RepeatEveryBlock -> b.copy(body = list)
    else -> b
}

fun flattenBlocks(
    blocks: List<Block>,
    prefix: List<Int> = emptyList(),
    depth: Int = 0,
    out: MutableList<FlatStep> = ArrayList(),
): List<FlatStep> {
    blocks.forEachIndexed { i, b ->
        val p = prefix + i
        out.add(FlatStep(p, b, depth))
        for (br in 0 until b.branchCount()) flattenBlocks(b.branch(br), p + br, depth + 1, out)
    }
    return out
}

/** Blok o podanej ścieżce albo null, gdy ścieżka nie istnieje. */
fun List<Block>.blockAt(path: List<Int>): Block? {
    if (path.isEmpty()) return null
    var list: List<Block> = this
    var i = 0
    while (true) {
        val b = list.getOrNull(path[i]) ?: return null
        if (i == path.lastIndex) return b
        if (i + 2 > path.lastIndex) return null
        list = b.branch(path[i + 1])
        i += 2
    }
}

/** Zmienia listę-rodzeństwo, w której leży blok o ścieżce [path]; [f] dostaje tę listę i indeks bloku. */
fun List<Block>.editSiblings(path: List<Int>, f: (List<Block>, Int) -> List<Block>): List<Block> {
    if (path.isEmpty()) return this
    if (path.size == 1) return f(this, path[0])
    if (path.size < 3) return this
    val idx = path[0]
    val parent = getOrNull(idx) ?: return this
    val br = path[1]
    val newChild = parent.branch(br).editSiblings(path.drop(2), f)
    return mapIndexed { i, b -> if (i == idx) parent.withBranch(br, newChild) else b }
}

fun List<Block>.replaceAt(path: List<Int>, nb: Block): List<Block> = editSiblings(path) { l, i ->
    if (i in l.indices) l.toMutableList().also { it[i] = nb } else l
}

fun List<Block>.removeAtPath(path: List<Int>): List<Block> = editSiblings(path) { l, i ->
    if (i in l.indices) l.filterIndexed { idx, _ -> idx != i } else l
}

/**
 * Wstawia [nb] jako następny krok po bloku o ścieżce [path]. Jeśli ten blok jest kontenerem,
 * nowy blok trafia na początek jego pierwszej kieszeni (bo to właśnie jest następny krok w odczycie).
 */
fun List<Block>.insertAfterStep(path: List<Int>, nb: Block): List<Block> {
    val cur = blockAt(path)
    if (cur != null && cur.branchCount() > 0) {
        return replaceAt(path, cur.withBranch(0, listOf(nb) + cur.branch(0)))
    }
    return editSiblings(path) { l, i ->
        val m = l.toMutableList()
        m.add((i + 1).coerceAtMost(m.size), nb)
        m
    }
}
