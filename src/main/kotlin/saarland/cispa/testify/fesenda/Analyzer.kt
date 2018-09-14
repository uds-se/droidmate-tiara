package saarland.cispa.testify.fesenda

import kotlinx.coroutines.experimental.runBlocking
import org.droidmate.ExplorationAPI
import org.droidmate.apis.IApi
import org.droidmate.command.ExploreCommand
import org.droidmate.configuration.ConfigProperties.Exploration.apksDir
import org.droidmate.configuration.ConfigurationBuilder
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.SelectorFunction
import org.droidmate.exploration.StrategySelector
import org.droidmate.exploration.statemodel.Widget
import org.droidmate.exploration.strategy.ResourceManager
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

object Analyzer{
	enum class Status{
		None,
		InformationLoss,
		PossibleFunctionalityLoss,
		FunctionalityLoss
	}

	data class Result(val status: Status,
					  val reproducibleRatio: Double,
					  val originalActionable : Int = 0,
					  val currentActionable: Int = 0,
					  val actionableRatio: Double = 0.0,
					  val originalObserved : Int = 0,
					  val currentObserved: Int = 0,
					  val observedRatio: Double = 0.0,
					  val newRatio: Double = 0.0)

    private val nullUID = UUID.fromString("4cf44f4b-e036-4705-9da7-e39beea34a16")

    private const val nrAttemptsConfirm = 3
    private val sensitiveApiList by lazy { ResourceManager.getResourceAsStringList("sensitiveApiList.txt") }
	private val rootOutDir: Path = Paths.get("out").toAbsolutePath()

    fun run(args: Array<String>){

		println("Starting FeSenDA")
		Files.createDirectories(rootOutDir)

		val outDir = rootOutDir.resolve("original")

		val outArgs = args.toMutableList()
				.also {
					it.add("--Output-outputDir=$outDir")
				}.toTypedArray()
		val explCfg = ExplorationAPI.config(outArgs)

		// Inline the apk
		val inlineCfgArgs = arrayOf("--ExecutionMode-inline=true",
				"--ExecutionMode-explore=false",
				"--Exploration-apksDir=${explCfg[apksDir]}")
		ExplorationAPI.inline(inlineCfgArgs)

		// Explore the inlined apk
		val explorationData = ExplorationAPI.explore(explCfg)

		// Run FeSenDa
        explorationData.forEach { exploredApp ->
            runFeSenDA(args, outDir, exploredApp)
        }
    }

    private fun runFeSenDA(args: Array<String>, originalOutDir: Path, data: ExplorationContext){
		// Get API/Widget pairs
		val apisPerWidget = data.getApisPerWidget()

		apisPerWidget.forEach { widgetId, apis ->
			apis.forEach { api ->
				println("Identified widget: $widgetId with API $api")

				val difference = confirm(args, originalOutDir, widgetId, api, data)

				// If can confirm the execution
				if (difference.status == Status.None) {
					println("Trace successfully reproduced Applying RWE.")
					// Try to explore with enforcement
					val rweStatus = exploreWithEnforcement(args, originalOutDir, widgetId, api, data)
					println("RWE status: [$rweStatus]")
				}
				else{
					println("Trace could not be reproduced.")
					println("Status: [$difference]")
				}
			}
		}
    }

    private fun ExplorationContext.getApisPerWidget(): Map<UUID, List<IApi>>{
        val exploredWidgets: MutableMap<UUID, MutableList<IApi>> = mutableMapOf()

        val exploration = this.actionTrace.getActions()

        exploration.indices.forEach { index ->
            val apis = exploration[index].deviceLogs.apiLogs
					.filter { candidate -> sensitiveApiList.any { monitored -> candidate.matches(monitored) } }

            apis.distinctBy { it.uniqueString }.forEach { api ->
                val widgetId = exploration[index].targetWidget?.uid ?: nullUID

                exploredWidgets.getOrPut(widgetId) { mutableListOf() }.let {apiList ->
					if (!apiList.any { it.uniqueString == api.uniqueString })
						apiList.add(api)
				}
            }
        }

        return exploredWidgets
    }

	private fun IApi.matches(monitored: String): Boolean{
		var uri = ""
		if (this.objectClass.contains("ContentResolver") && this.paramTypes.indexOf("android.net.Uri") != -1)
			uri = "\t${this.parseUri()}"

		val params = this.paramTypes.joinToString(separator = ",")

		val thisValue = "${this.objectClass}->${this.methodName}($params)$uri"

		return thisValue.contains(monitored)
	}

	private fun IApi.toDirName(): String {
		return uniqueString
				.filter { it.isLetterOrDigit() }
				.take(255)
	}

	private fun ExplorationContext.uniqueObservedWidgets(): Set<Widget>{
		return mutableSetOf<Widget>().apply {
			runBlocking {
				getModel().getWidgets()
						.groupBy { it.uid } // TODO we would like a mechanism to identify which widget config was the (default)
						.forEach { add(it.value.first()) }
			}
		} }


	private fun ExplorationContext.compareTo(other: ExplorationContext): Result{
		val thisActions = this.actionTrace.getActions()
		val otherActions = other.actionTrace.getActions()

		// Measure functionality loss (Critical)
		val reproducibilityRatio = if (otherActions.isNotEmpty())
			thisActions.size / otherActions.size.toDouble()
		else
			0.0

		if (reproducibilityRatio < 1.0)
			return Result(Status.FunctionalityLoss, reproducibilityRatio)

		/*thisActions.forEachIndexed{ idx, thisAction ->
			val otherAction = otherActions[idx]

			if (otherAction.toString() != thisAction.toString())
				return Status.FunctionalityLoss
		}*/

		// Observed
		val thisObservedWidgets = this.uniqueObservedWidgets()
		val otherObservedWidgets = other.uniqueObservedWidgets()

		// Actionable
		val thisActionableWidgets = thisObservedWidgets.filter { it.canBeActedUpon }
		val otherActionableWidgets = otherObservedWidgets.filter { it.canBeActedUpon }

		// Measure possible functionality loss (Severe)
		val foundActionable = thisActionableWidgets
				.map { thisWidget -> otherActionableWidgets.any { otherWidget -> thisWidget.uid == otherWidget.uid } }

		val actionableRatio = foundActionable.filter { it }.size / foundActionable.size.toDouble()

		if (actionableRatio < 1.0)
			return Result(Status.PossibleFunctionalityLoss, reproducibilityRatio,
					thisActionableWidgets.size, otherActionableWidgets.size, actionableRatio)


		// Measure information loss (Minor)
		val foundObserved = thisObservedWidgets
				.map { thisWidget -> otherObservedWidgets.any { otherWidget -> thisWidget.uid == otherWidget.uid } }

		val observedRatio = foundObserved.filter { it }.size / foundObserved.size.toDouble()


		// Measure information loss
		val foundObservedWidgets = thisObservedWidgets
				.map { thisWidget -> otherObservedWidgets.any { otherWidget -> thisWidget.uid == otherWidget.uid } }

		val newWidgets = otherObservedWidgets
				.map { otherWidget -> !thisObservedWidgets.any { thisWidget -> thisWidget.uid == otherWidget.uid } }

		val newRatio = newWidgets.filter { it }.size / foundObservedWidgets.size.toDouble()

		if (observedRatio < 1.0)
			return Result(Status.InformationLoss, reproducibilityRatio,
					thisActionableWidgets.size, otherActionableWidgets.size, actionableRatio,
					thisObservedWidgets.size, otherObservedWidgets.size, observedRatio,
					newRatio)

		// No loss
		return Result(Status.None, reproducibilityRatio)
	}

	private fun confirm(args: Array<String>,
						originalOutDir: Path,
						widgetId: UUID,
						api: IApi,
						originalExploration: ExplorationContext): Result{
		for (x in 0 until nrAttemptsConfirm){
			val outDir = rootOutDir.resolve(widgetId.toString()).resolve(api.toDirName()).resolve("confirm1")

			val playbackArgs = args.toMutableList()
					.also {
						it.add("--Output-outputDir=$outDir")
						it.add("--Selectors-playbackModelDir=${originalOutDir.resolve("model")}")
						it.add("--Strategies-playback=true")
					}

			val cfg = ExplorationAPI.config(playbackArgs.toTypedArray())
			val explorationData = ExplorationAPI.explore(cfg)

			// Should have a single character
			val newTestContext = explorationData.single()
			val explDifference = originalExploration.compareTo(newTestContext)

			if (explDifference.status != Status.None)
				return explDifference
		}

		return Result(Status.None, 1.0)
	}

    private fun exploreWithEnforcement(args: Array<String>,
									   originalOutDir: Path,
									   widgetId: UUID,
									   api: IApi,
									   originalExploration: ExplorationContext): Result{

		val outDir = rootOutDir.resolve(widgetId.toString()).resolve(api.toDirName()).resolve("rwe")
		val modelDir = originalOutDir.resolve("model")

		val enforcementArgs = args.toMutableList()
				.also {
					it.add("--Output-outputDir=$outDir")
					//it.add("--Selectors-playbackModelDir=$modelDir")
					//it.add("--Strategies-playback=true")
				}

		val cfg = ConfigurationBuilder().build(enforcementArgs.toTypedArray(), FileSystems.getDefault())
		val strategies = ExploreCommand.getDefaultStrategies(cfg).toMutableList().apply {
			add(ReplayWithEnforcement(widgetId, api, cfg, modelDir))
		}
		val selectors = ExploreCommand.getDefaultSelectors(cfg)
				// Remove random
				.dropLast(1)
				.toMutableList()

		val rwe: SelectorFunction = { _, pool, _ ->
			StrategySelector.logger.debug("RWE. Returning 'RWE'")
			pool.getFirstInstanceOf(ReplayWithEnforcement::class.java)
		}

		selectors.add(StrategySelector(selectors.size + 1, "RWE", rwe))

		val explorationData = ExplorationAPI.explore(cfg, strategies, selectors)

		return explorationData.single().compareTo(originalExploration)
	}

    /*fun run(args: Array<String>){
        val additionalStrategies = getAdditionalExtraStrategies()
        val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())
        val expCfg = ExperimentConfiguration(cfg, ArrayList())
        val memoryData = start(args, additionalStrategies, true, cfg, expCfg)

        memoryData.forEach { processMemory(it, args, expCfg) }
    }

    private fun start(args: Array<String>,
                      additionalStrategies: List<ISelectableExplorationStrategy>,
                      moveOutputToAppDir: Boolean,
                      cfg: ConfigurationWrapper = ConfigurationBuilder().build(args, FileSystems.getDefault()),
                      expCfg: ExperimentConfiguration = ExperimentConfiguration(cfg, ArrayList())): List<Memory> {
        val wrapper = DroidmateWrapper(expCfg.workDir)
        val selectedStrategies = ApkExplorer.defaultStrategies
        val apkExploration = ApkExplorer.build(selectedStrategies, expCfg, wrapper, additionalStrategies)

        apkExploration.explore(moveOutputToAppDir)

        // Serialize memory
        MemoryProcessor.serializeResults(apkExploration.memoryData, expCfg)

        additionalStrategies
                .filterIsInstance(MemoryPlayback::class.java)
                .forEach { strategy ->
                    //logger.info("Playback similarity: ${strategy.getExplorationRatio()}")
                    Reporter(expCfg).writePlaybackResults(strategy.traces)
                }

        // Generate results
        MemoryProcessor.processFromMemory(apkExploration.memoryData, expCfg)

        return apkExploration.memoryData
    }

    private fun getAdditionalExtraStrategies(): List<ISelectableExplorationStrategy>{
        //val strategies : MutableList<ISelectableExplorationStrategy> = ArrayList()
        //strategies.add(LoginWithFacebook.build())
        //strategies.add(LoginWithGoogle.build())
        //return strategies
        return ArrayList()
    }

    private fun processMemory(memory: Memory, args: Array<String>, expCfg: ExperimentConfiguration) {
        val apk = memory.getApk()
        val appPkg = apk.packageName

        val widgetsApiList = memory.getApisPerWidget()
        Writer.writeWidgetApisList(expCfg, widgetsApiList, appPkg)

        val traceData = memory.buildPlaybackTraces()
        val candidateTraces = traceData.createCandidateTraces(widgetsApiList)
        candidateTraces.serialize("filtered", appPkg, expCfg)

        confirmCandidateTraces(expCfg, candidateTraces, args, appPkg)
        candidateTraces.serialize("confirmed", appPkg, expCfg)

        evaluateConfirmedTraces(expCfg, candidateTraces, args, appPkg)
        candidateTraces.serialize("evaluated", appPkg, expCfg)

        Writer.writeReports(candidateTraces, traceData)
    }

    private fun List<CandidateTrace>.serialize(suffix: String, appPackageName: String,
                                expCfg: ExperimentConfiguration){
        this.forEachIndexed { index, trace ->
            val outPath = expCfg.dataDir.resolve("${appPackageName}_${suffix}_$index.playbackModel")
            try {
                logger.info("Serializing playbackModel to ${outPath.fileName}")
                trace.serialize(outPath)
                logger.info("Trace serialized")
            }
            catch(e: IOException){
                logger.error("Filed to serialize playbackModel to ${outPath.fileName}: ${e.message}", e)
            }
        }
    }

    private fun getWidgetsSeen(memoryRecords: List<IMemoryRecord>): List<IWidget>{
        val seenWidgets : MutableList<IWidget> = ArrayList()

        memoryRecords.forEach { record ->
            if (record.type in arrayListOf(ExplorationType.Explore, ExplorationType.Playback)) {

                record.widgetContext.guiState.widgets.forEach { w ->
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
    private fun evaluateConfirmedTraces(expCfg: ExperimentConfiguration, candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces with enforcement")
        candidateTraces
                .filter { it.confirmRatio == 1.0 }
                .forEachIndexed { index, candidate ->
            candidate.playbackModel.reset()
            val cfg = ConfigurationBuilder().build(args, FileSystems.getDefault())

            if (cfg.deviceSerialNumber.isEmpty()) {
                val deviceSN = AdbWrapper(cfg, SysCmdExecutor()).getAndroidDevicesDescriptors()[cfg.deviceIndex].deviceSerialNumber
                cfg.deviceSerialNumber = deviceSN
            }
            val playbackStrategy = PlaybackWithEnforcement(appPackageName, arrayListOf(candidate.playbackModel),
                    candidate.widget, candidate.api, cfg)
            val memoryData = start(args.filterNot { it == "-takeScreenshots" }.toTypedArray(),
                    playbackStrategy, false, cfg)

            memoryData.forEach { memory ->
                val apk = memory.getApk()
                val appPkg = apk.packageName
                val baseName = "${appPkg}_trace$index"

                val widgetApiList = memory.getApisPerWidget()
                Writer.writeWidgetApisList(expCfg, widgetApiList, baseName)

                if (widgetApiList.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                    val similarityRatio = candidate.getExploredRatio()

                    candidate.blockedRatio = similarityRatio
                    val seenWidgetsTrace = getWidgetsSeen(memory.getRecords())
                    candidate.seenWidgetsBlock.addAll(seenWidgetsTrace)

                    // Remove all widgets seen
                    // If they have resId or text, remove those which were not found as well
                    val unseenWidgets = candidate.seenWidgets.filterNot { c -> seenWidgetsTrace.any { t -> c.uniqueString == t.uniqueString } }
                            .filterNot { c -> !(c.text.isEmpty() && c.resourceId.isEmpty()) && seenWidgetsTrace.any { t -> t.isEquivalentIgnoreLocation(c) } }
                    val unseenWidgetsRatio = unseenWidgets.size / candidate.seenWidgets.size.toDouble()
                    candidate.unseenRatio = unseenWidgetsRatio
                }
            }
        }
    }

    /**
     * Run each playbackModel multiple times to see if they can be constantly reproduced
     */
    private fun confirmCandidateTraces(expCfg: ExperimentConfiguration, candidateTraces: List<CandidateTrace>, args: Array<String>, appPackageName: String){
        logger.debug("Exploring traces")
        candidateTraces.forEach { candidate ->
            // Repeat 3x to remove "flaky traces"
            var similarityRatio = 0.0
            var successCount = 0
            (0 until nrAttemptsConfirm).forEach { p ->
                logger.info("Exploring playbackModel $p for (Api=${candidate.api.uniqueString} Widget=${candidate.widget.uniqueString})")
                candidate.playbackModel.reset()
                val playbackStrategy = MemoryPlayback.build(appPackageName, arrayListOf(candidate.playbackModel))
                val memoryData = start(args.filterNot { it == "-takeScreenshots" }.toTypedArray(),
                        playbackStrategy, false)

                memoryData.forEachIndexed { index, memory ->
                    val apk = memory.getApk()
                    val appPkg = apk.packageName
                    val baseName = "${appPkg}_trace$index"

                    val apiWidgetSummary = memory.getApisPerWidget()
                    Writer.writeWidgetApisList(expCfg, apiWidgetSummary, baseName)

                    if (apiWidgetSummary.any { it.widget.uniqueString == candidate.widget.uniqueString }) {
                        successCount++

                        //similarityRatio += (playbackStrategy.first() as MemoryPlayback).getExplorationRatio(candidate.widget)
                        similarityRatio += candidate.getExploredRatio()

                        val seenWidgetsTrace = getWidgetsSeen(memory.getRecords())
                        val unseenWidgets = seenWidgetsTrace.filterNot { c -> candidate.seenWidgets.any { t -> c.uniqueString == t.uniqueString } }
                        candidate.seenWidgets.addAll(unseenWidgets)
                    }
                }
            }

            candidate.playbackModel.reset()
            if (successCount == nrAttemptsConfirm) {
                candidate.confirmRatio = similarityRatio / nrAttemptsConfirm

            }
        }
    }

    /**
     * Filter all traces which contain a relevant API. Creates a playbackModel for each API and widget to
     * allow to be independently evaluated
      */
    private fun MutableList<PlaybackTrace>.createCandidateTraces(exploredWidgetList: List<ExploredWidget>): List<CandidateTrace>{
        val candidateTraces : MutableList<CandidateTrace> = ArrayList()

        exploredWidgetList.forEach { exploredWidget ->
            // All traces have reset, so use the first time it started the app
            val tracesWithWidget = if (exploredWidget.widget == ExploredWidget.dummyWidget)
                arrayListOf(this.first())
            else
                this.filter { trace -> trace.contains(exploredWidget.widget) }

            exploredWidget.foundApis.forEach { data ->
                tracesWithWidget.forEach { playbackTrace ->
                    val newTrace = CandidateTrace(exploredWidget.widget, playbackTrace, data.api, data.screenshot?.toUri())
                    candidateTraces.add(newTrace)
                }
            }
        }

        return candidateTraces
    }

    private fun Memory.buildPlaybackTraces(): MutableList<PlaybackTrace>{
        val memoryRecords = this.getRecords()
        val traces : MutableList<PlaybackTrace> = ArrayList()
        val packageName = this.getApk().packageName

        // Create traces from memory records
        // Each playbackModel starts with a reset
        // Last playbackModel ends with terminate exploration
        for (i in 0 until memoryRecords.size) {
            val memoryRecord = memoryRecords[i]

            if (memoryRecord.type == ExplorationType.Reset)
                traces.add(PlaybackTrace())

            val widgetContext = this.getWidgetContext(memoryRecord.widgetContext.guiState, packageName)
            traces.last().add(memoryRecord.action, widgetContext)
        }

        return traces
    }

    private fun Memory.getApisPerWidget(): List<ExploredWidget>{
        val exploredWidgetsList: MutableList<ExploredWidget> = ArrayList()
        val history = this.getRecords()
        history.indices.forEach { index ->
            val newWidgetSummary = getExploredWidget(history, index)

            if (newWidgetSummary != null){
                val currWidgetSummary = exploredWidgetsList.firstOrNull { it == newWidgetSummary }

                // New widget, insert
                if (currWidgetSummary == null)
                    exploredWidgetsList.add(newWidgetSummary)
                // Widget already exists, merge
                else
                    currWidgetSummary.merge(newWidgetSummary)
            }
        }

        return exploredWidgetsList
    }

    private fun IApiLogcatMessage.matches(monitored: String): Boolean{
        var uri = ""
        if (this.objectClass.contains("ContentResolver") && this.paramTypes.indexOf("android.net.Uri") != -1)
            uri = "\t${this.parseUri()}"

        val params = this.paramTypes.joinToString(separator = ",")

        val thisValue = "${this.objectClass}->${this.methodName}($params)$uri"

        return thisValue.contains(monitored)
    }

    private fun getExploredWidget(history: List<IMemoryRecord>, index: Int): ExploredWidget? {
        val record = history[index]

        val candidateLogs = record.actionResult!!.deviceLogs.apiLogs
        // Remove all non monitored APIs
        val sensitiveLogs = candidateLogs.filter { candidate -> sensitiveApiList.any { monitored -> candidate.matches(monitored) } }

        // Nothing to monitor
        if (sensitiveLogs.isEmpty())
            return null

        if (record.action is WidgetExplorationAction){

            val explRecord = record.action as WidgetExplorationAction
            val lastAppWidget = if (explRecord.isEndorseRuntimePermission()) {
                val previousNonPermissionDialogAction = getPreviousNonPermissionDialogAction(history, index)

                if (previousNonPermissionDialogAction.action is ResetAppExplorationAction)
                    ExploredWidget.dummyWidget
                else
                    (previousNonPermissionDialogAction.action as WidgetExplorationAction).widget
            }
            else
                explRecord.widget

            // Was an exploration action, record.type guarantees it
            val exploredWidget = ExploredWidget(lastAppWidget)

            val screenshot = record.actionResult.screenshot
            val screenshotPath = if (!screenshot.toString().startsWith("test://"))
                Paths.get(screenshot).fileName
            else
                Paths.get(".").fileName

            sensitiveLogs.forEach { log ->
                if (!exploredWidget.foundApis.any { it.api.uniqueString == (log as IApi).uniqueString })
                    exploredWidget.addFoundAPI(log as IApi, screenshotPath)
            }

            return exploredWidget
        }

        // Not an exploration action
        return null
    }

    private fun getPreviousNonPermissionDialogAction(memoryRecord: List<IMemoryRecord>, index: Int): IMemoryRecord {
        // First reset
        if (index == 0)
            return memoryRecord[index]

        val previousAction = memoryRecord[index - 1]

        return if (previousAction.action is WidgetExplorationAction) {
            if (previousAction.action.isEndorseRuntimePermission())
                getPreviousNonPermissionDialogAction(memoryRecord, index - 1)
            else
                previousAction
        } else if (previousAction.type == ExplorationType.Reset)
            return previousAction
        else
            getPreviousNonPermissionDialogAction(memoryRecord, index - 1)
    }*/
}