package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi
import org.droidmate.device.datatypes.Widget
import org.droidmate.report.uniqueString
import org.nustaq.serialization.FSTObjectInput
import org.nustaq.serialization.FSTObjectOutput
import org.slf4j.LoggerFactory
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.Serializable
import java.net.URI
import java.nio.file.Path

data class CandidateTrace(val widget: Widget, val playbackTrace: PlaybackTrace, val api: IApi, var screenshot : URI?): Serializable{
    var confirmRatio = 0.0
    var blockedRatio = 0.0
    var unseenRatio = 0.0
    val seenWidgets : MutableList<Widget> = ArrayList()
    val seenWidgetsBlock : MutableList<Widget> = ArrayList()

    @Throws(IOException::class)
    fun serialize(outPath: Path) {
        logger.info("Serializing candidate playbackTrace to $outPath")

        val fileOut = FileOutputStream(outPath.toFile())
        val out = FSTObjectOutput(fileOut)
        out.writeObject(this)
        out.close()
        fileOut.close()
        logger.info("Candidate playbackTrace successfully serialized to $outPath")
    }

    fun getExploredRatio(): Double{
        return this.playbackTrace.getExploredRatio(this.widget)
    }

    override fun toString(): String {
        return "C${this.confirmRatio}\tB${this.blockedRatio}\tU${this.unseenRatio}\t${this.widget.uniqueString}\t${this.api.uniqueString}\t${this.screenshot}"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CandidateTrace::class.java)
        @JvmStatic private val serialVersionUID = 1

        @Suppress("unused")
        @Throws(IOException::class)
        fun deserialize(inPath: Path): List<CandidateTrace> {
            logger.info("Deserializing candidate playbackTrace from $inPath")

            val fileIn = FileInputStream(inPath.toFile())
            val reader = FSTObjectInput(fileIn)
            val traceData = (reader.readObject() as List<*>).filterIsInstance<CandidateTrace>()
            reader.close()
            fileIn.close()
            logger.info("Candidate playbackTrace successfully deserialized from $inPath")

            return traceData
        }

    }
}