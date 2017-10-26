package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi
import org.droidmate.device.datatypes.Widget
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

data class CandidateTrace(val widget: Widget, val trace: PlaybackTrace, val api: IApi, var screenshot : URI?): Serializable{
    var confirmed = false
    var blocked = false
    var partiallyBlocked = false
    var similarityRatio = 0.0

    @Throws(IOException::class)
    fun serialize(outPath: Path) {
        logger.info("Serializing candidate trace to $outPath")

        val fileOut = FileOutputStream(outPath.toFile())
        val out = FSTObjectOutput(fileOut)
        out.writeObject(this)
        out.close()
        fileOut.close()
        logger.info("Candidate trace successfully serialized to $outPath")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CandidateTrace::class.java)
        @JvmStatic private val serialVersionUID = 1

        @Suppress("unused")
        @Throws(IOException::class)
        fun deserialize(inPath: Path): List<CandidateTrace> {
            logger.info("Deserializing candidate trace from $inPath")

            val fileIn = FileInputStream(inPath.toFile())
            val reader = FSTObjectInput(fileIn)
            val traceData = (reader.readObject() as List<*>).filterIsInstance<CandidateTrace>()
            reader.close()
            fileIn.close()
            logger.info("Candidate trace successfully deserialized from $inPath")

            return traceData
        }

    }
}