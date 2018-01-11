package core

import kotlin.math.max
import kotlin.math.min

/**
 * This class is for storing a session of editing the recording
 * @see Recording
 * @see Analyser
 * @author Kacper Lubisz
 * @property recording  The recording that concerns this session
 * @property analyser The analyser for this current session
 * @property updateCallbacks A list of lambdas used for adding onUpdate listeners to anything that needs it
 * @property cursor The position of the cursor for editing (null means the end)
 * @property swap A nullable index of the current section being held to be swapped
 */
class Session(val recording: Recording) {

    private val updateCallbacks: MutableList<() -> Unit> = mutableListOf()
    var width: Int = 0
        set(value) {
            field = value
            runCallbacks()
        }

    var noteWidth: Double = 0.0
    var lastX: Int = 0
        set(value) {
            field = value
            runCallbacks()
        }

    private var cursorField: Int? = null
    private var noteCursorField: Double? = null

    var cursor: Int?
        set(value) {
            val toBecome = if (value == null || value >= recording.timeSteps.size) null else max(value, 0)
            if (toBecome != cursorField) {
                cursorField = toBecome

                val notes = recording.sections.flatMap {
                    it.noteClusters.mapIndexed { index, note ->
                        return@mapIndexed PlayedCluster(note.relTimeStepStart + it.timeStepStart, it.clusterStart + index, it)
                    }
                }
                val after = notes.mapIndexed { index, it -> index to it }.find { it.second.recordingStart > correctedCursor }?.first
                noteCursorField = when {
                    toBecome == null -> null
                    notes.isEmpty() -> 0.0
                    after == null -> notes.size.toDouble() + 0.5 * (correctedCursor - notes[notes.size - 1].recordingStart) / (recording.timeSteps.size - notes[notes.size - 1].recordingStart) - 0.5
                    after == 0 -> 0.5 * correctedCursor / notes[0].recordingStart
                    else -> {
                        val next = notes[after]
                        val previous = notes[after - 1]
                        val between = when {
                            next.section == previous.section -> // no cut
                                (correctedCursor - previous.recordingStart) / (next.recordingStart - previous.recordingStart).toDouble()
                            correctedCursor <= next.section.timeStepStart -> // left side of cut
                                0.5 * (correctedCursor - previous.recordingStart) / (next.section.timeStepStart - previous.recordingStart).toDouble()
                            else -> // right side of cut
                                0.5 + 0.5 * (correctedCursor - next.section.timeStepStart) / (next.recordingStart - next.section.timeStepStart).toDouble()
                        }
                        after.toDouble() + between - 0.5
                    }
                }

                updateLocations()
                runCallbacks()
            }

        }
        get() = cursorField

    var noteCursor: Double?
        set(value) {
            val toBecome = if (value == null || value >= recording.notes.size) null else max(value, 0.0)
            if (toBecome != noteCursorField) {
                noteCursorField = toBecome

                val notes = recording.sections.flatMap {
                    it.noteClusters.mapIndexed { index, note ->
                        return@mapIndexed PlayedCluster(note.relTimeStepStart + it.timeStepStart, it.clusterStart + index, it)
                    }
                }
                cursorField = (when {
                    toBecome == null -> null
                    toBecome > notes.size + 0.5 -> null
                    notes.isEmpty() -> recording.timeSteps.size * toBecome
                    toBecome < 0.5 -> toBecome * 2 * notes[0].recordingStart
                    toBecome > notes.size - 0.5 ->
                        notes[notes.size - 1].recordingStart + (0.5 + toBecome - notes.size) * 2 * +(recording.timeSteps.size - notes[notes.size - 1].recordingStart)
                    else -> {
                        val before = (toBecome - 0.5).toInt()
                        val inter = toBecome - 0.5 - before

                        val next = notes[(toBecome + 0.5).toInt()]
                        val previous = notes[(toBecome - 0.5).toInt()]
                        when {
                            next.section == previous.section ->
                                previous.recordingStart * (1 - inter) + next.recordingStart * inter
                            inter < 0.5 ->
                                previous.recordingStart * (1 - inter * 2) + next.section.timeStepStart * inter * 2
                            else ->
                                next.section.timeStepStart * (2 - inter * 2) + next.recordingStart * (inter * 2 - 1)
                        }

                    }
                })?.toInt()

                updateLocations()
                runCallbacks()
            }
        }
        get() = noteCursorField

    val correctedCursor: Int
        get() = cursor ?: (recording.timeSteps.size - 1)
    val correctedNoteCursor: Double
        get() = noteCursor ?: (recording.notes.size.toDouble())
    var onScreenCursor: Int = 0
    var onScreenNoteCursor: Double = 0.0
    var from: Int = 0
    var to: Int = 0
    var noteFrom: Double = 0.0
    var noteTo: Double = 0.0

    val visibleStepRange
        get() = from..to
    val visibleNoteRange
        get() = noteFrom..noteTo

    var swap: Int? = null
        set(value) {
            field = value
            runCallbacks()
        }
    var swapWith: Int = 0
    var swapWithSection: Boolean = false

    val isEditSafe: Boolean
        get() = soundGatheringController.isPaused && playbackController.isPaused

    val isRecording
        get() = !soundGatheringController.isPaused

    private val soundGatheringController = SoundGatheringController(this)
    private val soundProcessingController = SoundProcessingController(this)
    private val playbackController = PlaybackController(this) {
        runCallbacks()
    }

    private fun updateLocations() {
        onScreenCursor = min(max(width - (recording.timeSteps.size - correctedCursor), width / 2), correctedCursor)
        from = max(correctedCursor - onScreenCursor, 0)
        to = min(correctedCursor + (width - onScreenCursor), recording.timeSteps.size)

        onScreenNoteCursor = min(max(noteWidth - (recording.notes.size - correctedNoteCursor), noteWidth / 2), correctedNoteCursor)
        noteFrom = max(correctedNoteCursor - onScreenNoteCursor, 0.0)
        noteTo = min(correctedNoteCursor + (noteWidth - onScreenNoteCursor), recording.notes.size.toDouble())

    }

    fun record(): Boolean {
        return if (isEditSafe) {
            try {

                if (!soundGatheringController.isAlive)
                    soundGatheringController.start()

                cursor = null
                soundGatheringController.isPaused = false

                recording.startSection()
                runCallbacks()

                true

            } catch (e: Exception) {

                println("Couldn't open microphone line")
                false

            }
        } else {
            false
        }
    }

    fun pauseRecording(): Boolean {
        return if (!soundGatheringController.isPaused) {
            soundGatheringController.isPaused = true
            runCallbacks()
            true
        } else false
    }

    fun playback(): Boolean {
        return if (isEditSafe) {
            playbackController.isPaused = false
            runCallbacks()
            true
        } else {
            false
        }
    }

    fun pausePlayback(): Boolean {
        return if (!playbackController.isPaused) {
            playbackController.isPaused = true
            runCallbacks()
            true
        } else {
            false
        }
    }

    fun addSamples(samples: FloatArray) {
        synchronized(recording) {
            recording.addSamples(samples)
        }
    }

    fun addTimeStep(step: TimeStep) {
        synchronized(recording) {
            recording.addTimeStep(step)
        }
        updateLocations()
        runCallbacks()
    }

    /**
     * Cuts the recording at the cursor location and invokes update listeners
     * @param cursor The time at which the cut should happen
     */
    fun makeCut(cursor: Int) {
        synchronized(recording) {
            recording.cut(cursor)
            runCallbacks()
        }
    }

    /**
     * Adds an onUpdate listener which is called every time one of the properties of session changes
     * @param callback The lambda that will be invoked when an update happens
     */
    fun addOnUpdateListener(callback: () -> Unit) {

        updateCallbacks.add(callback)

    }

    /**
     * Invokes all the onUpdateListeners
     */
    private fun runCallbacks() {
        updateCallbacks.forEach { it.invoke() }
    }

    fun updateSwapWith() {

        val location = lastX + from
        val sectionIndex = recording.sectionAt(location)

        if (sectionIndex != null) {

            val section = recording.sections[sectionIndex]
            val distanceFromEdge = min(10, (section.timeSteps.size * .2).toInt())

            when {
                location - section.timeStepStart < distanceFromEdge -> { // left edge

                    swapWith = sectionIndex
                    swapWithSection = false

                }
                section.timeStepEnd - location < distanceFromEdge -> { // right edge

                    swapWith = sectionIndex + 1
                    swapWithSection = false

                }
                else -> { // section

                    swapWith = sectionIndex
                    swapWithSection = true

                }
            }

        } else {

            swapWith = recording.sections.size
            swapWithSection = false

        }

    }

    fun executeSwap() {

        val swap = swap

        if (swap != null) {
            if (swapWithSection)
                recording.swapSections(swap, swapWith)
            else
                recording.reInsertSection(swap, swapWith)

            this.swap = null
        }

    }

    private data class PlayedCluster(val recordingStart: Int, val index: Int, val section: Section)

}