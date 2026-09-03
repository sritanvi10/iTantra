package com.isro.itantra.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Utility to extract bundled model files from Android `assets` to internal storage (`filesDir`).
 * Native C++ engines (Whisper, Piper) require POSIX file paths (`/data/user/0/...`) to load weights.
 */
object ModelAssetsExtractor {

    /**
     * Extracts a single asset file to internal storage and returns its absolute path.
     */
    fun extractAssetToStorage(context: Context, fileName: String): String {
        val targetFile = File(context.filesDir, fileName)
        if (!targetFile.exists()) {
            try {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {}
        }
        return targetFile.absolutePath
    }

    /**
     * Recursively extracts all assets from `assets/models/` to the app's `models` directory.
     */
    fun copyAllAssetsToInternalStorage(context: Context, modelsTargetDir: File) {
        if (!modelsTargetDir.exists()) modelsTargetDir.mkdirs()
        try {
            copyAssetFolder(context, "models", modelsTargetDir)
        } catch (_: Exception) {}
    }

    private fun copyAssetFolder(context: Context, assetFolder: String, targetDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetFolder) ?: return
        if (!targetDir.exists()) targetDir.mkdirs()

        for (filename in files) {
            val assetSubPath = "$assetFolder/$filename"
            val children = assetManager.list(assetSubPath)
            if (children != null && children.isNotEmpty()) {
                copyAssetFolder(context, assetSubPath, File(targetDir, filename))
            } else {
                val targetFile = File(targetDir, filename)
                if (!targetFile.exists()) {
                    try {
                        assetManager.open(assetSubPath).use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
