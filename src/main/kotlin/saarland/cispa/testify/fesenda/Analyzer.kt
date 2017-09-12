package saarland.cispa.testify.fesenda

import org.droidmate.exploration.actions.WidgetExplorationAction
import org.droidmate.report.uniqueString
import org.slf4j.LoggerFactory
import saarland.cispa.testify.*
import saarland.cispa.testify.strategies.playback.MemoryPlayback
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.nio.file.Paths

object Analyzer{
    private val logger = LoggerFactory.getLogger(Analyzer::class.java)

    private val sensitiveApiList = ResourceManager.getResourceAsStringList("sensitiveApiList.txt")

    fun run(args: Array<String>){
        val memoryData = saarland.cispa.testify.Main.start(args, ArrayList())

        memoryData.forEach { memory ->
            val apk = memory.getApk()
            val appPkg = apk.packageName

            val apiWidgetSummary = getAPIWidgetSummary(memory)
            Writer.writeAPIWidgetSummary(apiWidgetSummary, appPkg)

            val traceData = buildTraces(memory)
            val candidateTraces = filterTraces(traceData, apiWidgetSummary)
            val confirmedTraces = exploreCandidateTraces(candidateTraces, args, appPkg)

            logger.info(confirmedTraces.size.toString())
        }
    }

    private fun exploreCandidateTraces(candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String): List<CandidateTrace>{
        candidateTraces.forEach { candidate ->
            val playbackStrategy = MemoryPlayback.build(appPackageName, arrayListOf(candidate.trace))
            val memoryData = saarland.cispa.testify.Main.start(args, playbackStrategy)

            memoryData.forEachIndexed { index, memory ->
                val apk = memory.getApk()
                val appPkg = apk.packageName
                val baseName = "${appPkg}_trace$index"

                val apiWidgetSummary = getAPIWidgetSummary(memory)
                Writer.writeAPIWidgetSummary(apiWidgetSummary, baseName)

                if (apiWidgetSummary.any { p -> p.widget.uniqueString == candidate.widget.uniqueString }){
                    candidate.trace.reset()
                    candidate.success = true
                }
            }
        }

        return candidateTraces.filter { it.success }
    }

    private fun filterTraces(traces: MutableList<PlaybackTrace>, apiWidgetSummary: List<WidgetSummary>): List<CandidateTrace>{
        val candidateTraces : MutableList<CandidateTrace> = ArrayList()

        apiWidgetSummary.forEach { widget ->
            // All traces have reset, so use the first time it started the app
            val filteredTraces = if (widget.widgetText == "<RESET>")
                arrayListOf(traces.first())
            else
                traces.filter { trace -> trace.contains(widget.widget) }

            filteredTraces.forEach { newTrace ->
                candidateTraces.add(CandidateTrace(widget.widget, newTrace))
            }
        }

        return candidateTraces
    }

    private fun buildTraces(memory: Memory): MutableList<PlaybackTrace>{
        val memoryRecords = memory.getRecords()
        val traces : MutableList<PlaybackTrace> = ArrayList()

        // Create traces from memory records
        // Each trace starts with a reset
        // Last trace ends with terminate exploration
        for (i in 0 until memoryRecords.size) {
            val memoryRecord = memoryRecords[i]

            if (memoryRecord.type == ExplorationType.Reset)
                traces.add(PlaybackTrace())

            traces.last().add(memoryRecord.action)
        }

        return traces
    }

    private fun getAPIWidgetSummary(memory: Memory): List<WidgetSummary>{
        val widgetSummaryData: MutableList<WidgetSummary> = ArrayList()
        val history = memory.getRecords()
        history.forEachIndexed { index, record ->
            val previousNonPermissionDialogAction = getPreviousNonPermissionDialogAction(history, index)

            val newWidgetSummary = getWidgetSummary(record, previousNonPermissionDialogAction)

            if (newWidgetSummary != null){
                val currWidgetSummary = widgetSummaryData.firstOrNull { it == newWidgetSummary }

                if (currWidgetSummary == null)
                    widgetSummaryData.add(newWidgetSummary)
                else
                    currWidgetSummary.merge(newWidgetSummary)
            }
        }

        return widgetSummaryData
    }

    private fun getWidgetSummary(record: IMemoryRecord, previousNonPermissionDialogAction: IMemoryRecord?): WidgetSummary? {
        if (record.action is WidgetExplorationAction) {
            val droidmateLogs = record.actionResult!!.deviceLogs.apiLogsOrEmpty

            val candidateLogs = droidmateLogs.map { p ->
                var uri = ""
                if (p.objectClass.contains("ContentResolver") && p.paramTypes.indexOf("android.net.Uri") != -1)
                    uri = "\t${p.parseUri()}"

                "${p.objectClass}->${p.methodName}$uri"
            }

            // Remove all APIs which are contained in the API list
            val logs = candidateLogs.filter { candidate -> sensitiveApiList.any { monitored -> candidate.contains(monitored) } }

            if (logs.isNotEmpty()) {
                val text: String

                val explRecord = record.action as WidgetExplorationAction
                // When it's runtime permission, the action action happened before therefore must look for it
                // moreover, if no previous action was found it happened on a reset
                text = if (explRecord.isEndorseRuntimePermission) {
                    if (previousNonPermissionDialogAction != null)
                        Reporter.getActionText(previousNonPermissionDialogAction)
                    else
                        "<RESET>"
                } else
                    Reporter.getActionText(record)
                val widget = explRecord.widget

                val widgetSummary : WidgetSummary
                widgetSummary = if (text == "<RESET>")
                    WidgetSummary(text)
                else
                    WidgetSummary(text, widget)

                logs.forEach { log ->
                    var screenshot = ""
                    if (record.actionResult!!.screenshot != null)
                        screenshot = Paths.get(record.actionResult!!.screenshot).fileName.toString()
                    widgetSummary.addApiData(log, screenshot)
                }

                return widgetSummary
            }
        }

        return null
    }

    private fun getPreviousNonPermissionDialogAction(memoryRecord: List<IMemoryRecord>, index: Int): IMemoryRecord? {
        if (index <= 0)
            return null

        val previousAction = memoryRecord[index - 1]

        return if (previousAction.action is WidgetExplorationAction) {
            if (previousAction.action.isEndorseRuntimePermission)
                getPreviousNonPermissionDialogAction(memoryRecord, index - 1)
            else
                previousAction
        } else
            getPreviousNonPermissionDialogAction(memoryRecord, index - 1)
    }
}