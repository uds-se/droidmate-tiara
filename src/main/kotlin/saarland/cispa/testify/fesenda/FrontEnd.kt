package saarland.cispa.testify.fesenda

class FrontEnd {
	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
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
