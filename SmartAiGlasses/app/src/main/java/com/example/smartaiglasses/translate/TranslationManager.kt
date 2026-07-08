package com.example.smartaiglasses.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.tasks.await

class TranslationManager {

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translatorCache = HashMap<String, Translator>()

    suspend fun translateToEnglish(text: String): String {

        val lang = languageIdentifier.identifyLanguage(text).await()

        if (lang == "und" || lang == "en") return text

        val translator = translatorCache.getOrPut(lang) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(lang)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
            )
        }

        translator.downloadModelIfNeeded(
            DownloadConditions.Builder().build()
        ).await()

        return translator.translate(text).await()
    }

    fun close() {
        languageIdentifier.close()
        translatorCache.values.forEach { it.close() }
    }
}