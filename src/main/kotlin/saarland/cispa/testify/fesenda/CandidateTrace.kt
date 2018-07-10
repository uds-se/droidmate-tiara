/*package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi
import org.droidmate.exploration.statemodel.Model
import org.droidmate.exploration.statemodel.Widget
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.net.URI

data class CandidateTrace(val traceIdx: Int, val actionIdx: Int, val playbackModel: Model, val api: IApi, var screenshot : URI?): Serializable{
    var confirmRatio = 0.0
    var blockedRatio = 0.0
    var unseenRatio = 0.0
    val seenWidgets : MutableList<Widget> = mutableListOf()
    val seenWidgetsBlock : MutableList<Widget> = mutableListOf()

    /*@Throws(IOException::class)
    fun serialize(outPath: Path) {
        logger.info("Serializing candidate playbackModel to $outPath")

        val fileOut = FileOutputStream(outPath.toFile())
        val out = FSTObjectOutput(fileOut)
        out.writeObject(this)
        out.close()
        fileOut.close()
        logger.info("Candidate playbackModel successfully serialized to $outPath")
    }*/

    fun getExploredRatio(): Double{
        val paths = this.playbackModel.getExploredRatio(this.widget)
    }

    override fun toString(): String {
        return "C${this.confirmRatio}\tB${this.blockedRatio}\tU${this.unseenRatio}\t${this.widget}\t${this.api.uniqueString}\t${this.screenshot}"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CandidateTrace::class.java)
        @JvmStatic private val serialVersionUID = 1

        /*@Suppress("unused")
        @Throws(IOException::class)
        fun deserialize(inPath: Path): List<CandidateTrace> {
            logger.info("Deserializing candidate playbackModel from $inPath")

            val fileIn = FileInputStream(inPath.toFile())
            val reader = FSTObjectInput(fileIn)
            val traceData = (reader.readObject() as List<*>).filterIsInstance<CandidateTrace>()
            reader.close()
            fileIn.close()
            logger.info("Candidate playbackModel successfully deserialized from $inPath")

            return traceData
        }*/
    }
}*/