package saarland.cispa.testify.fesenda

import org.droidmate.apis.IApi

data class WidgetApiData(val api: IApi, val screenShot: String){
    override fun equals(other: Any?): Boolean {
        if ((other == null) || (other !is WidgetApiData))
            return false

        return this.api.uniqueString == other.api.uniqueString
    }

    override fun hashCode(): Int {
        var result = api.hashCode()
        result = 31 * result + screenShot.hashCode()
        return result
    }
}