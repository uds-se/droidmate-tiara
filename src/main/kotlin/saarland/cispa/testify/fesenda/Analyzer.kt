package saarland.cispa.testify.fesenda

import org.droidmate.ExplorationAPI
import org.droidmate.command.ExploreCommand
import org.droidmate.configuration.ConfigProperties.Exploration.apksDir
import org.droidmate.configuration.ConfigurationBuilder
import org.droidmate.device.apis.IApi
import org.droidmate.device.logcat.ApiLogcatMessage
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.SelectorFunction
import org.droidmate.exploration.StrategySelector
import org.droidmate.exploration.strategy.ResourceManager
import org.droidmate.explorationModel.interaction.Widget
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

    suspend fun run(args: Array<String>){

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

    private suspend fun runFeSenDA(args: Array<String>, originalOutDir: Path, data: ExplorationContext){
		// Get API/Widget pairs
		val apisPerWidget = data.getApisPerWidget()

		for (widgetId in apisPerWidget.keys) {
			val apis = apisPerWidget[widgetId] ?: throw Exception("Something wrong happened...")

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

        val exploration = this.explorationTrace.getActions()

        exploration.indices.forEach { index ->
            val apis = exploration[index].deviceLogs
				.map { ApiLogcatMessage.from(it.messagePayload) }
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

	private suspend fun ExplorationContext.uniqueObservedWidgets(): Set<Widget>{
		return mutableSetOf<Widget>().apply {
			getModel().getWidgets()
				// TODO we would like a mechanism to identify which widget config was the (default)
					.groupBy { it.uid }
					.forEach { add(it.value.first()) }
		} }


	private suspend fun ExplorationContext.compareTo(other: ExplorationContext): Result{
		val thisActions = this.explorationTrace.getActions()
		val otherActions = other.explorationTrace.getActions()

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
		val thisActionableWidgets = thisObservedWidgets.filter { it.canInteractWith }
		val otherActionableWidgets = otherObservedWidgets.filter { it.canInteractWith }

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

	private suspend fun confirm(args: Array<String>,
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

    private suspend fun exploreWithEnforcement(args: Array<String>,
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
}