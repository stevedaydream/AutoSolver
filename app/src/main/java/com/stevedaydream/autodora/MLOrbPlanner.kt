package com.stevedaydream.autodora

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Step-by-step ML policy for orb path planning using TFLite inference.
 *
 * Input (226 floats per step):
 *   - Board 5×6×7 one-hot  (210)  — one-hot over 7 OrbTypes per cell
 *   - Current row 5-hot    (5)
 *   - Current col 6-hot    (6)
 *   - Previous action 5-hot(5)    — UP/DOWN/LEFT/RIGHT/NONE
 *
 * Output: 5 logits → argmax → action (0=UP 1=DOWN 2=LEFT 3=RIGHT 4=STOP)
 *
 * Place a trained model at  app/src/main/assets/orb_policy.tflite  before use.
 * The companion object MODEL_FILE can be changed to load a different file.
 */
class MLOrbPlanner(private val context: Context,
                   val modelFile: String = "orb_policy_general.tflite") {

    companion object {
        /** All available model files and their display labels */
        val AVAILABLE_MODELS: List<Pair<String, String>> = listOf(
            "通用" to "orb_policy_general.tflite",
            "水"   to "orb_policy_water.tflite",
            "火"   to "orb_policy_fire.tflite",
            "木"   to "orb_policy_wood.tflite",
            "光"   to "orb_policy_light.tflite",
            "暗"   to "orb_policy_dark.tflite",
            "心"   to "orb_policy_heart.tflite",
        )
        const val INPUT_SIZE  = 226   // 5×6×7 + 5 + 6 + 5
        const val NUM_ACTIONS = 5

        const val ACTION_UP    = 0
        const val ACTION_DOWN  = 1
        const val ACTION_LEFT  = 2
        const val ACTION_RIGHT = 3
        const val ACTION_STOP  = 4

        /** Maps OrbType to the 0-based index used in the one-hot encoding */
        private val ORB_IDX = mapOf(
            BoardAnalyzer.OrbType.WATER   to 0,
            BoardAnalyzer.OrbType.FIRE    to 1,
            BoardAnalyzer.OrbType.WOOD    to 2,
            BoardAnalyzer.OrbType.LIGHT   to 3,
            BoardAnalyzer.OrbType.DARK    to 4,
            BoardAnalyzer.OrbType.HEART   to 5,
            BoardAnalyzer.OrbType.UNKNOWN to 6
        )
        private val DIR_R = intArrayOf(-1,  1,  0, 0)  // UP DOWN LEFT RIGHT
        private val DIR_C = intArrayOf( 0,  0, -1, 1)
    }

    private var interpreter: Interpreter? = null

    /** True when the TFLite model was successfully loaded from assets. */
    val isAvailable: Boolean get() = interpreter != null

    init {
        interpreter = tryLoadModel()
    }

    private fun tryLoadModel(): Interpreter? {
        return try {
            val buf = loadModelBuffer()
            Interpreter(buf, Interpreter.Options().apply { setNumThreads(2) }).also {
                Log.d("MLOrbPlanner", "orb_policy.tflite loaded successfully")
            }
        } catch (e: Exception) {
            Log.w("MLOrbPlanner", "Model not available (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    private fun loadModelBuffer(): MappedByteBuffer {
        val afd = context.assets.openFd(modelFile)
        return FileInputStream(afd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    // ─── Encoding ────────────────────────────────────────────────────────────

    /**
     * Encodes the full game state into a 226-element FloatArray for inference.
     * Layout: [board 5×6×7] [row 5-hot] [col 6-hot] [prevAction 5-hot]
     */
    fun encodeBoardState(
        board: Array<Array<BoardAnalyzer.OrbType>>,
        row: Int, col: Int,
        prevAction: Int
    ): FloatArray {
        val input = FloatArray(INPUT_SIZE)
        var idx = 0

        // Board: 5 rows × 6 cols × 7 one-hot
        for (r in 0 until 5) {
            for (c in 0 until 6) {
                val typeIdx = ORB_IDX[board[r][c]] ?: 6
                for (t in 0 until 7) input[idx++] = if (t == typeIdx) 1f else 0f
            }
        }
        // Current row one-hot (5)
        for (r in 0 until 5) input[idx++] = if (r == row) 1f else 0f
        // Current col one-hot (6)
        for (c in 0 until 6) input[idx++] = if (c == col) 1f else 0f
        // Previous action one-hot (5)
        for (a in 0 until NUM_ACTIONS) input[idx++] = if (a == prevAction) 1f else 0f

        return input
    }

    // ─── Inference ───────────────────────────────────────────────────────────

    /**
     * Runs one inference step.
     * Returns the chosen action index (ACTION_UP … ACTION_STOP).
     * Returns ACTION_STOP immediately if the model is not loaded.
     */
    fun predict(
        board: Array<Array<BoardAnalyzer.OrbType>>,
        row: Int, col: Int,
        prevAction: Int = ACTION_STOP
    ): Int {
        val interp = interpreter ?: return ACTION_STOP

        val inputBuf = ByteBuffer.allocateDirect(INPUT_SIZE * 4).order(ByteOrder.nativeOrder())
        for (f in encodeBoardState(board, row, col, prevAction)) inputBuf.putFloat(f)
        inputBuf.rewind()

        val outputBuf = ByteBuffer.allocateDirect(NUM_ACTIONS * 4).order(ByteOrder.nativeOrder())
        interp.run(inputBuf, outputBuf)
        outputBuf.rewind()

        var bestAction = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (a in 0 until NUM_ACTIONS) {
            val v = outputBuf.float
            if (v > bestVal) { bestVal = v; bestAction = a }
        }
        return bestAction
    }

    // ─── Path Planning ───────────────────────────────────────────────────────

    /**
     * Runs the step-by-step policy to produce a full orb path.
     *
     * The path is a list of (row, col) board positions, starting with [startRow, startCol].
     * Each step swaps the held orb with the orb at the new position, mirroring the
     * in-game mechanic (no gravity during the drag; gravity applies only on release).
     *
     * Returns a list with at least one element (the start position).
     * The path ends when the policy outputs ACTION_STOP, the orb goes out of bounds,
     * or maxSteps is reached.
     */
    fun planPath(
        initialBoard: Array<Array<BoardAnalyzer.OrbType>>,
        maxSteps: Int,
        startRow: Int,
        startCol: Int
    ): List<Pair<Int, Int>> {
        // Work on a mutable copy of the board
        val board = Array(5) { r -> Array(6) { c -> initialBoard[r][c] } }
        val path = mutableListOf(Pair(startRow, startCol))

        var row = startRow
        var col = startCol
        var prevAction = ACTION_STOP  // "NONE" at start

        for (step in 0 until maxSteps) {
            val action = predict(board, row, col, prevAction)
            if (action == ACTION_STOP) break

            val nr = row + DIR_R[action]
            val nc = col + DIR_C[action]
            if (nr !in 0 until 5 || nc !in 0 until 6) break  // out of bounds — stop

            // Simulate the orb swap
            val temp = board[row][col]
            board[row][col] = board[nr][nc]
            board[nr][nc] = temp

            row = nr; col = nc
            path.add(Pair(row, col))
            prevAction = action
        }
        return path
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
