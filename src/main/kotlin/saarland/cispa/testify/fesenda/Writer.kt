package saarland.cispa.testify.fesenda

import org.slf4j.LoggerFactory
import saarland.cispa.testify.Reporter
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

object Writer{
    private val logger = LoggerFactory.getLogger(Writer::class.java)

    @JvmStatic
    fun writeWidgetApisList(widgetSummaryData: List<ExploredWidget>, reportName: String) {
        logger.info("Writing API widget summary .........")
        val summaryDir = Reporter.initialize()

        // Widget - API Summary
        val widgetAPISummaryFile = summaryDir.resolve("$reportName-widget-api-summary.txt")
        Files.write(widgetAPISummaryFile, widgetSummaryData.joinToString(separator = "") { "$it\n" }.toByteArray())
    }

    @JvmStatic
    fun writeReports(candidateTraces: List<CandidateTrace>, traceData: List<PlaybackTrace>){
        val confirmed = candidateTraces.filter { it.confirmRatio == 1.0 }
        val blocked = candidateTraces.filter { it.blockedRatio == 1.0 && it.unseenRatio == 0.0 }
        val partial = candidateTraces.filter { it.blockedRatio == 1.0 && it.unseenRatio > 0.0 }
        val notBlocked = candidateTraces.filter { it.confirmRatio == 1.0 && it.blockedRatio < 1.0 }
        val notConfirmed = candidateTraces.filter { it.confirmRatio < 1.0 }

        val sb = StringBuilder()
        sb.appendln("Unique traces: ${traceData.size}\tRelevant: ${candidateTraces.size}\tConfirmed: ${confirmed.count()}\tBlocked ${blocked.count()}\tPartially Blocked ${partial.count()}")
        sb.appendln("Blocked Sub-traces:")
        blocked.forEach { sb.appendln(it.toString()) }
        sb.appendln("Partially Blocked Sub-traces:")
        partial.forEach { sb.appendln(it.toString()) }
        sb.appendln("Not Blocked Sub-traces:")
        notBlocked.forEach { sb.appendln(it.toString()) }
        sb.appendln("Not confirmed Sub-traces:")
        notConfirmed.forEach { sb.appendln(it.toString()) }

        sb.toString().split("\n").forEach { logger.info(it) }
        try{
            val expReport = Paths.get("exec_summary").resolve("api_trace_analysis_report.txt")
            Files.write(expReport, sb.toString().toByteArray())
        }
        catch(e: IOException){
            logger.error("Failed to write log file!", e)
        }
    }

}