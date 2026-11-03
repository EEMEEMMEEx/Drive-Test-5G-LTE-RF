package com.drivetest.rf.ui.i18n

enum class AppLanguage(val code: String) {
    EN("EN"),
    TH("TH")
}

object AppStrings {
    fun tr(lang: AppLanguage, en: String, th: String): String =
        if (lang == AppLanguage.TH) th else en
}
