package com.lyon.rhythmictouch.config

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ProfileExporter {

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")

    fun singleJsonBytes(profile: VibrationProfile): ByteArray? {
        if (profile.isDefault) return null
        return profile.toJson().toByteArray(Charsets.UTF_8)
    }

    fun batchZipBytes(profiles: List<VibrationProfile>): ByteArray? {
        val customs = profiles.filter { !it.isDefault }
        if (customs.isEmpty()) return null
        return try {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zip ->
                val used = mutableSetOf<String>()
                for (p in customs) {
                    val base = sanitize(p.name)
                    var entryName = "$base.json"
                    var n = 2
                    while (entryName in used) {
                        entryName = "$base($n).json"
                        n++
                    }
                    used.add(entryName)
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(p.toJson().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            bos.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    fun writeToUri(context: Context, uri: Uri, bytes: ByteArray): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                true
            } ?: false
        } catch (t: Throwable) {
            false
        }
    }

    fun suggestedBatchFileName(): String =
        "RhythmicTouch_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"

    fun importFromBytes(bytes: ByteArray): List<VibrationProfile> {
        val jsonText = String(bytes, Charsets.UTF_8)
        VibrationProfile.fromJson(jsonText)?.let { p ->
            if (!p.isDefault) return listOf(p)
        }
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val profiles = mutableListOf<VibrationProfile>()
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".json")) {
                        val json = zip.readBytes().toString(Charsets.UTF_8)
                        VibrationProfile.fromJson(json)?.let { p ->
                            if (!p.isDefault) profiles.add(p)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                profiles
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
