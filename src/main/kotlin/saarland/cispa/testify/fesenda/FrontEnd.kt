package saarland.cispa.testify.fesenda

import kotlinx.coroutines.runBlocking

class FrontEnd {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runBlocking {
                println("Starting experiments")
                try {
                    Analyzer.run(args)
                } catch (e: Exception) {
                    println("Experiments finished with error")
                    println(e.message)
                    e.printStackTrace()
                    System.exit(1)
                }

                System.exit(0)
            }
        }
    }
}