package saarland.cispa.testify.fesenda

class WidgetApiData(val api: String, val screenShot: String){
    override fun hashCode(): Int {
        return this.api.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if ((other == null) || (other !is WidgetApiData))
            return false

        return this.api == other.api
    }
}