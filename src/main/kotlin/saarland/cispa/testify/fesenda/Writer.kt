/*package saarland.cispa.testify.fesenda

import org.droidmate.exploration.strategy.playback.PlaybackTrace
import org.droidmate.misc.isEquivalentIgnoreLocation
import org.droidmate.misc.uniqueString
import org.slf4j.LoggerFactory
import saarland.cispa.testify.ExperimentConfiguration
import saarland.cispa.testify.Reporter
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

object Writer {
    private val logger = LoggerFactory.getLogger(Writer::class.java)

    @JvmStatic
    fun writeWidgetApisList(expCfg: ExperimentConfiguration, widgetSummaryData: List<ExploredWidget>, reportName: String) {
        logger.info("Writing API widget summary .........")
        val summaryDir = Reporter(expCfg).initialize()

        // Widget - API Summary
        val widgetAPISummaryFile = summaryDir.resolve("$reportName-widget-api-summary.txt")
        Files.write(widgetAPISummaryFile, widgetSummaryData.joinToString(separator = "") { "$it\n" }.toByteArray())
    }

    @JvmStatic
    fun writeReports(candidateTraces: List<CandidateTrace>, traceData: List<PlaybackTrace>) {
        val confirmed = candidateTraces.filter { it.confirmRatio == 1.0 }
        val blocked = candidateTraces.filter { it.blockedRatio == 1.0 && it.unseenRatio == 0.0 }
        val partial = candidateTraces.filter { it.blockedRatio == 1.0 && it.unseenRatio > 0.0 }
        val notBlocked = candidateTraces.filter { it.confirmRatio == 1.0 && it.blockedRatio < 1.0 }
        val notConfirmed = candidateTraces.filter { it.confirmRatio < 1.0 }

        val sb = StringBuilder()
        sb.appendln("Unique traces: ${traceData.size}\tRelevant: ${candidateTraces.size}\tConfirmed: ${confirmed.count()}\tBlocked ${blocked.count()}\tPartially Blocked ${partial.count()}")
        sb.appendln("Blocked Sub-traces:")
        blocked.forEachIndexed { idx, it -> sb.appendln("$idx\t$it") }
        sb.appendln("Partially Blocked Sub-traces:")
        partial.forEachIndexed { idx, it -> sb.appendln("$idx\t$it") }
        sb.appendln("Not Blocked Sub-traces:")
        notBlocked.forEachIndexed { idx, it -> sb.appendln("$idx\t$it") }
        sb.appendln("Not confirmed Sub-traces:")
        notConfirmed.forEachIndexed { idx, it -> sb.appendln("$idx\t$it") }

        sb.toString().split("\n").forEach { logger.info(it) }
        try {
            val expReport = Paths.get("exec_summary").resolve("api_trace_analysis_report.txt")
            Files.write(expReport, sb.toString().toByteArray())
        } catch (e: IOException) {
            logger.error("Failed to write log file!", e)
        }

        candidateTraces.forEachIndexed { idx, trace -> writeDetailedReport(trace, idx) }
    }

    private fun writeDetailedReport(trace: CandidateTrace, traceNr: Int) {

        val sb = StringBuilder()

        sb.appendln("API\t\t${trace.api}")
        sb.appendln("Widget\t\t${trace.widget}")
        sb.appendln("Screenshot\t\t${trace.screenshot}")
        sb.appendln("\n")
        sb.appendln("Confirm ratio\t\t${trace.confirmRatio}")
        sb.appendln("Block ratio\t\t${trace.blockedRatio}")
        sb.appendln("Unseen ratio\t\t${trace.unseenRatio}")
        sb.appendln("\n\n\n")
        sb.appendln("Playback trace")
        sb.append(trace.playbackModel.toString())
        sb.appendln("\n\n\n")
        sb.appendln("Missing widgets")
        val unseenWidgets = trace.seenWidgets.filterNot { c -> trace.seenWidgetsBlock.any { t -> c.uniqueString == t.uniqueString } }
                .filterNot { c -> !(c.text.isEmpty() && c.resourceId.isEmpty()) && trace.seenWidgetsBlock.any { t -> t.isEquivalentIgnoreLocation(c) } }
        unseenWidgets.sortedBy { it.uniqueString }.forEach { widget -> sb.appendln(widget.uniqueString.replace("\n", "\\n")) }
        sb.appendln("\n\n\n")
        sb.appendln("Original widgets")
        trace.seenWidgets.sortedBy { it.uniqueString }.forEach { widget -> sb.appendln(widget.uniqueString.replace("\n", "\\n")) }
        sb.appendln("\n\n\n")
        sb.appendln("Widgets after block")
        trace.seenWidgetsBlock.sortedBy { it.uniqueString }.forEach { widget -> sb.appendln(widget.uniqueString.replace("\n", "\\n")) }

        try {
            val expReport = Paths.get("exec_summary").resolve("detailed_trace_$traceNr.txt")
            Files.write(expReport, sb.toString().toByteArray())
        } catch (e: IOException) {
            logger.error("Failed to write log file!", e)
        }
    }
}*/