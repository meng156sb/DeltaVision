package com.deltavision.app.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object Jsons {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
}
