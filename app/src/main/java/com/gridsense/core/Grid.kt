package com.gridsense.core

/** One survey node: the centre of a grid cell, numbered from the origin corner. */
data class GridNode(val row: Int, val col: Int, val x: Double, val y: Double)

/**
 * Splits the room into [rows] by [cols] cells of [cellM] metres and puts a node at the centre
 * of each. Row 0, column 0 is the cell in the corner you start from; columns run along x and
 * rows along y.
 */
fun gridNodes(rows: Int, cols: Int, cellM: Double): List<GridNode> {
    val nodes = ArrayList<GridNode>(rows * cols)
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            nodes.add(GridNode(row, col, (col + 0.5) * cellM, (row + 0.5) * cellM))
        }
    }
    return nodes
}

/** The rectangle the grid covers, which clips the heatmap. */
fun gridOutline(rows: Int, cols: Int, cellM: Double): List<Pt> = listOf(
    Pt(0.0, 0.0),
    Pt(cols * cellM, 0.0),
    Pt(cols * cellM, rows * cellM),
    Pt(0.0, rows * cellM)
)

/** A spreadsheet-style label such as "B3": the letter is the column, the number the row. */
fun nodeLabel(row: Int, col: Int): String {
    var n = col
    val letters = StringBuilder()
    do {
        letters.insert(0, 'A' + n % 26)
        n = n / 26 - 1
    } while (n >= 0)
    return letters.toString() + (row + 1)
}
