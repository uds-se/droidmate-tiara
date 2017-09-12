package saarland.cispa.testify.fesenda

import org.droidmate.device.datatypes.Widget
import saarland.cispa.testify.strategies.playback.PlaybackTrace

class CandidateTrace(val widget: Widget, val trace: PlaybackTrace){
    var success = false
}