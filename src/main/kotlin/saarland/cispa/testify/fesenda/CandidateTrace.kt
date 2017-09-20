package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi
import org.droidmate.device.datatypes.Widget
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.io.Serializable

data class CandidateTrace(val widget: Widget, val trace: PlaybackTrace, val api: IApi): Serializable{
    var confirmed = false
    var blocked = false
    var similarityRatio = 0.0

    companion object {
        @JvmStatic private val serialVersionUID = 1
    }
}