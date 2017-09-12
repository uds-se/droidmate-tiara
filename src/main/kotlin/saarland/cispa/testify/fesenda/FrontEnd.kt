package saarland.cispa.testify.fesenda

import org.slf4j.LoggerFactory

object FrontEnd {
    private val logger = LoggerFactory.getLogger(FrontEnd::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        logger.info("Starting experiments")
        try {
            val newArgs = arrayOf("-resetEvery=100", "-actionsLimit=5", "-randomSeed=0",
                    "-device=0", "-apksDir=./apks", "-takeScreenshots")
            Analyzer.run(newArgs)
        } catch (e: Exception) {
            logger.info("Experiments finished with error")
            logger.error(e.message, e)
            System.exit(1)
        }

        System.exit(0)
    }
}
