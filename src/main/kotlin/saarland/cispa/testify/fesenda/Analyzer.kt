package saarland.cispa.testify.fesenda

import org.droidmate.android_sdk.AdbWrapper
import org.droidmate.apis.IApi
import org.droidmate.apis.IApiLogcatMessage
import org.droidmate.configuration.ConfigurationBuilder
import org.droidmate.exploration.actions.WidgetExplorationAction
import org.droidmate.misc.SysCmdExecutor
import org.droidmate.report.uniqueString
import org.slf4j.LoggerFactory
import saarland.cispa.testify.*
import saarland.cispa.testify.strategies.playback.MemoryPlayback
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.nio.file.FileSystems
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

            exploreCandidateTraces(candidateTraces, args, appPkg)
            evaluateConfirmedTraces(candidateTraces, args, appPkg)

            val confirmed = candidateTraces.filter { it.confirmed }.count()
            val blocked = candidateTraces.filter { it.blocked }.count()
            logger.info("${traceData.size}\t${candidateTraces.size}\t$confirmed\t$blocked")
        }
    }

    private fun evaluateConfirmedTraces(candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces with enforcement")
        candidateTraces.forEach { candidate ->
            candidate.trace.reset()
            val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())

            if (cfg.deviceSerialNumber.isEmpty()) {
                val deviceSN = AdbWrapper(cfg, SysCmdExecutor()).androidDevicesDescriptors[cfg.deviceIndex].deviceSerialNumber
                cfg.deviceSerialNumber = deviceSN
            }
            val playbackStrategy = PlaybackWithEnforcement.build(appPackageName, arrayListOf(candidate.trace),
                    candidate.widget, candidate.api, cfg)
            val memoryData = saarland.cispa.testify.Main.start(args, playbackStrategy, cfg)

            memoryData.forEachIndexed { index, memory ->
                val apk = memory.getApk()
                val appPkg = apk.packageName
                val baseName = "${appPkg}_trace$index"

                val apiWidgetSummary = getAPIWidgetSummary(memory)
                Writer.writeAPIWidgetSummary(apiWidgetSummary, baseName)

                if (apiWidgetSummary.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                    val similarityRatio = (playbackStrategy.first() as MemoryPlayback).getExplorationRatio(candidate.widget)

                    if (similarityRatio == candidate.similarityRatio)
                        candidate.blocked = true
                }
            }
        }
    }

    private fun exploreCandidateTraces(candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces")
        candidateTraces.forEach { candidate ->
            // Repeat 3x to remove "flaky traces"
            var similarityRatio = 0.0
            var successCount = 0
            (0 until 3).forEach { p ->
                logger.info("Exploring trace $p for (Api=${candidate.api.uniqueString} Widget=${candidate.widget.uniqueString})")
                candidate.trace.reset()
                val playbackStrategy = MemoryPlayback.build(appPackageName, arrayListOf(candidate.trace))
                val memoryData = saarland.cispa.testify.Main.start(args, playbackStrategy)

                memoryData.forEachIndexed { index, memory ->
                    val apk = memory.getApk()
                    val appPkg = apk.packageName
                    val baseName = "${appPkg}_trace$index"

                    val apiWidgetSummary = getAPIWidgetSummary(memory)
                    Writer.writeAPIWidgetSummary(apiWidgetSummary, baseName)

                    if (apiWidgetSummary.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                        successCount++

                        similarityRatio += (playbackStrategy.first() as MemoryPlayback).getExplorationRatio(candidate.widget)
                    }
                }
            }

            candidate.trace.reset()
            if (successCount == 3) {
                candidate.confirmed = true
                candidate.similarityRatio = similarityRatio / 3
            }
        }
    }

    private fun filterTraces(traces: MutableList<PlaybackTrace>, apiWidgetSummary: List<WidgetSummary>): List<CandidateTrace>{
        val candidateTraces : MutableList<CandidateTrace> = ArrayList()

        apiWidgetSummary.forEach { widgetSummary ->
            // All traces have reset, so use the first time it started the app
            val filteredTraces = if (widgetSummary.widgetText == "<RESET>")
                arrayListOf(traces.first())
            else
                traces.filter { trace -> trace.contains(widgetSummary.widget) }

            filteredTraces.forEach { newTrace ->
                widgetSummary.apiData.forEach { (api) ->
                    candidateTraces.add(CandidateTrace(widgetSummary.widget, newTrace, api))
                }
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

    private fun IApiLogcatMessage.matches(monitored: String): Boolean{
        var uri = ""
        if (this.objectClass.contains("ContentResolver") && this.paramTypes.indexOf("android.net.Uri") != -1)
            uri = "\t${this.parseUri()}"

        val params = this.paramTypes.joinToString(separator = ",")

        val thisValue = "${this.objectClass}->${this.methodName}($params)$uri"

        return thisValue.contains(monitored)
    }

    private fun getWidgetSummary(record: IMemoryRecord, previousNonPermissionDialogAction: IMemoryRecord?): WidgetSummary? {
        if (record.action is WidgetExplorationAction) {
            val candidateLogs = record.actionResult!!.deviceLogs.apiLogsOrEmpty

            // Remove all APIs which are contained in the API list
            val logs = candidateLogs.filter { candidate ->
                sensitiveApiList.any { monitored -> candidate.matches(monitored) }
            }

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
                    widgetSummary.addApiData(log as IApi, screenshot)
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