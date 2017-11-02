package saarland.cispa.testify.fesenda

import org.droidmate.android_sdk.AdbWrapper
import org.droidmate.apis.IApi
import org.droidmate.apis.IApiLogcatMessage
import org.droidmate.configuration.Configuration
import org.droidmate.configuration.ConfigurationBuilder
import org.droidmate.device.datatypes.Widget
import org.droidmate.exploration.actions.WidgetExplorationAction
import org.droidmate.misc.SysCmdExecutor
import org.droidmate.report.uniqueString
import org.slf4j.LoggerFactory
import saarland.cispa.testify.*
import saarland.cispa.testify.strategies.playback.MemoryPlayback
import saarland.cispa.testify.strategies.playback.PlaybackTrace
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths

object Analyzer{
    private val logger = LoggerFactory.getLogger(Analyzer::class.java)

    private val nrAttemptsConfirm = 1//3
    private val sensitiveApiList = ResourceManager.getResourceAsStringList("sensitiveApiList.txt")

    fun run(args: Array<String>){
        val additionalStrategies = getAdditionalExtraStrategies()
        val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())
        val expCfg = ExperimentConfiguration(cfg, ArrayList())
        val memoryData = start(args, additionalStrategies, true, cfg, expCfg)

        memoryData.forEach { processMemory(it, args, expCfg) }
    }

    private fun start(args: Array<String>, 
                      additionalStrategies: List<ISelectableExplorationStrategy>,
                      moveOutputToAppDir: Boolean,
                      cfg: Configuration = ConfigurationBuilder().build(args, FileSystems.getDefault()),
                      expCfg: ExperimentConfiguration = ExperimentConfiguration(cfg, ArrayList())): List<Memory> {
        val wrapper = DroidmateWrapper(expCfg.workDir)

        // Select appropriate strategies
        // To quickly disable a strategy uncomment and change
        val selectedStrategies = ApkExplorer.defaultStrategies
        //.filterNot { p -> p == StrategyTypes.FitnessProportionate }

        val apkExploration = ApkExplorer.build(selectedStrategies, expCfg, wrapper, additionalStrategies)

        apkExploration.explore(moveOutputToAppDir)

        // Serialize memory
        MemoryProcessor.serializeResults(apkExploration.memoryData, expCfg)

        additionalStrategies
                .filterIsInstance(MemoryPlayback::class.java)
                .forEach { strategy ->
                    logger.info("Playback similarity: ${strategy.getExplorationRatio()}")
                    Reporter.writePlaybackResults(strategy.traces)
                }

        // Generate results
        MemoryProcessor.processFromMemory(apkExploration.memoryData, expCfg)

        return apkExploration.memoryData
    }

    private fun getAdditionalExtraStrategies(): List<ISelectableExplorationStrategy>{
        val strategies : MutableList<ISelectableExplorationStrategy> = ArrayList()
        //strategies.add(LoginWithFacebook.build())
        //strategies.add(LoginWithGoogle.build())

        return strategies
    }

    private fun processMemory(memory: Memory, args: Array<String>, expCfg: ExperimentConfiguration){
        val apk = memory.getApk()
        val appPkg = apk.packageName

        val apiWidgetSummary = getAPIsPerWidget(memory)
        Writer.writeAPIWidgetSummary(apiWidgetSummary, appPkg)

        val traceData = buildPlaybackTraces(memory)
        val candidateTraces = traceData.createCandidateTraces(apiWidgetSummary)
        candidateTraces.serialize("filtered", appPkg, expCfg)

        confirmCandidateTraces(candidateTraces, args, appPkg)
        candidateTraces.serialize("confirmed", appPkg, expCfg)

        evaluateConfirmedTraces(candidateTraces, args, appPkg)
        candidateTraces.serialize("evaluated", appPkg, expCfg)

        val confirmed = candidateTraces.filter { it.confirmRatio == 1.0 }
        val blocked = candidateTraces.filter { it.blockedRatio == 1.0 && it.seenRatio == 1.0 }
        val partial = candidateTraces.filter { it.blockedRatio == 1.0 && it.seenRatio < 1.0  }
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

    private fun List<CandidateTrace>.serialize(suffix: String, appPackageName: String,
                                expCfg: ExperimentConfiguration){
        this.forEachIndexed { index, trace ->
            val outPath = expCfg.dataDir.resolve("${appPackageName}_${suffix}_$index.trace")
            try {
                logger.info("Serializing trace to ${outPath.fileName}")
                trace.serialize(outPath)
                logger.info("Trace serialized")
            }
            catch(e: IOException){
                logger.error("Filed to serialize trace to ${outPath.fileName}: ${e.message}", e)
            }
        }
    }

    private fun getWidgetsSeen(memoryRecords: List<IMemoryRecord>): List<Widget>{
        val seenWidgets : MutableList<Widget> = ArrayList()

        memoryRecords.forEach { record ->
            if (record.type in arrayListOf(ExplorationType.Explore, ExplorationType.Playback)) {

                record.state.widgets.forEach { w ->
                    if (!seenWidgets.any { q -> q.uniqueString == w.uniqueString })
                        seenWidgets.add(w)
                }
            }
        }

        return seenWidgets
    }

    /**
     * Run the confirmed traces with blocked API and see how similar they are to the normal
     */
    private fun evaluateConfirmedTraces(candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces with enforcement")
        candidateTraces
                .filter { it.confirmRatio == 1.0 }
                .forEachIndexed { index, candidate ->
            candidate.trace.reset()
            val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())

            if (cfg.deviceSerialNumber.isEmpty()) {
                val deviceSN = AdbWrapper(cfg, SysCmdExecutor()).androidDevicesDescriptors[cfg.deviceIndex].deviceSerialNumber
                cfg.deviceSerialNumber = deviceSN
            }
            val playbackStrategy = PlaybackWithEnforcement.build(appPackageName, arrayListOf(candidate.trace),
                    candidate.widget, candidate.api, cfg)
            val memoryData = start(args.filterNot { it == "-takeScreenshots" }.toTypedArray(),
                    playbackStrategy, false, cfg)

            memoryData.forEach { memory ->
                val apk = memory.getApk()
                val appPkg = apk.packageName
                val baseName = "${appPkg}_trace$index"

                val apiWidgetSummary = getAPIsPerWidget(memory)
                Writer.writeAPIWidgetSummary(apiWidgetSummary, baseName)

                if (apiWidgetSummary.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                    val executedPlayback = playbackStrategy.first() as MemoryPlayback
                    val similarityRatio = executedPlayback.getExplorationRatio(candidate.widget)

                    candidate.blockedRatio = similarityRatio
                    val seenWidgetsTrace = getWidgetsSeen(memory.getRecords())
                    candidate.seenWidgetsBlock.addAll(seenWidgetsTrace)

                    val unseenWidgets = candidate.seenWidgets.filterNot { c -> seenWidgetsTrace.any { t -> c.uniqueString == t.uniqueString } }
                    val seenWidgetsRatio = unseenWidgets.size / candidate.seenWidgets.size.toDouble()
                    candidate.seenRatio = seenWidgetsRatio
                }
            }
        }
    }

    /**
     * Run each trace multiple times to see if they can be constantly reproduced
     */
    private fun confirmCandidateTraces(candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces")
        candidateTraces.subList(2,3).forEach { candidate ->
            // Repeat 3x to remove "flaky traces"
            var similarityRatio = 0.0
            var successCount = 0
            (0 until nrAttemptsConfirm).forEach { p ->
                logger.info("Exploring trace $p for (Api=${candidate.api.uniqueString} Widget=${candidate.widget.uniqueString})")
                candidate.trace.reset()
                val playbackStrategy = MemoryPlayback.build(appPackageName, arrayListOf(candidate.trace))
                val memoryData = start(args.filterNot { it == "-takeScreenshots" }.toTypedArray(),
                        playbackStrategy, false)

                memoryData.forEachIndexed { index, memory ->
                    val apk = memory.getApk()
                    val appPkg = apk.packageName
                    val baseName = "${appPkg}_trace$index"

                    val apiWidgetSummary = getAPIsPerWidget(memory)
                    Writer.writeAPIWidgetSummary(apiWidgetSummary, baseName)

                    if (apiWidgetSummary.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                        successCount++

                        similarityRatio += (playbackStrategy.first() as MemoryPlayback).getExplorationRatio(candidate.widget)

                        val seenWidgetsTrace = getWidgetsSeen(memory.getRecords())
                        val unseenWidgets = seenWidgetsTrace.filterNot { c -> candidate.seenWidgets.any { t -> c.uniqueString == t.uniqueString } }
                        candidate.seenWidgets.addAll(unseenWidgets)
                    }
                }
            }

            candidate.trace.reset()
            if (successCount == nrAttemptsConfirm) {
                candidate.confirmRatio = similarityRatio / nrAttemptsConfirm

            }
        }
    }

    /**
     * Filter all traces which contain a relevant API. Creates a trace for each API and widget to
     * allow to be independently evaluated
      */
    private fun MutableList<PlaybackTrace>.createCandidateTraces(apiWidgetSummary: List<ExploredWidget>): List<CandidateTrace>{
        val candidateTraces : MutableList<CandidateTrace> = ArrayList()

        apiWidgetSummary.forEach { widgetSummary ->
            // All traces have reset, so use the first time it started the app
            val filteredTraces = if (widgetSummary.widgetText == "<RESET>")
                arrayListOf(this.first())
            else
                this.filter { trace -> trace.contains(widgetSummary.widget) }

            filteredTraces.forEach { newTrace ->
                widgetSummary.foundApis.forEach { data ->
                    candidateTraces.add(CandidateTrace(widgetSummary.widget, newTrace, data.api, data.screenshot?.toUri()))
                }
            }
        }

        return candidateTraces
    }

    private fun buildPlaybackTraces(memory: Memory): MutableList<PlaybackTrace>{
        val memoryRecords = memory.getRecords()
        val traces : MutableList<PlaybackTrace> = ArrayList()
        val packageName = memory.getApk().packageName

        // Create traces from memory records
        // Each trace starts with a reset
        // Last trace ends with terminate exploration
        for (i in 0 until memoryRecords.size) {
            val memoryRecord = memoryRecords[i]

            if (memoryRecord.type == ExplorationType.Reset)
                traces.add(PlaybackTrace())

            traces.last().add(memoryRecord.action, memoryRecord.state, packageName)
        }

        return traces
    }

    private fun getAPIsPerWidget(memory: Memory): List<ExploredWidget>{
        val widgetSummaryData: MutableList<ExploredWidget> = ArrayList()
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

    private fun getWidgetSummary(record: IMemoryRecord, previousNonPermissionDialogAction: IMemoryRecord?): ExploredWidget? {
        if (record.action is WidgetExplorationAction) {
            val candidateLogs = record.actionResult!!.deviceLogs.apiLogsOrEmpty

            // Remove all APIs which are contained in the API list
            val sensitiveLogs = candidateLogs.filter { candidate ->
                sensitiveApiList.any { monitored -> candidate.matches(monitored) }
            }

            if (sensitiveLogs.isNotEmpty()) {
                val text: String
                val widget : Widget

                val explRecord = record.action as WidgetExplorationAction
                // When it's runtime permission, the action action happened before therefore must look for it
                // moreover, if no previous action was found it happened on a reset
                if (explRecord.isEndorseRuntimePermission) {
                    if (previousNonPermissionDialogAction != null) {
                        val tmpWidget = Reporter.getActionWidget(previousNonPermissionDialogAction)
                        text = Reporter.getWidgetText(tmpWidget)
                        widget = tmpWidget ?: explRecord.widget
                    }
                    else {
                        text = "<RESET>"
                        widget = explRecord.widget
                    }
                } else {
                    val tmpWidget = Reporter.getActionWidget(record)
                    text = Reporter.getWidgetText(tmpWidget)
                    widget = explRecord.widget
                }

                val widgetSummary = if (text == "<RESET>")
                    ExploredWidget(text)
                else
                    ExploredWidget(text, widget)

                val screenshot = Paths.get(record.actionResult?.screenshot).fileName

                sensitiveLogs.forEach { log ->
                    if (!widgetSummary.foundApis.any { it.api.uniqueString == (log as IApi).uniqueString })
                        widgetSummary.addFoundAPI(log as IApi, screenshot)
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