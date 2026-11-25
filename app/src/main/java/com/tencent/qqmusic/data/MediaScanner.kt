package com.tencent.qqmusic.data

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

data class TrackItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: Uri,
    val dataPath: String,
    val albumArtUri: Uri?
)

data class UserPlaylist(
    val id: String,
    val name: String,
    val trackIds: List<Long>
)

object MediaScanner {
    private const val PREFS_NAME = "glass_player_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"
    private const val MIN_DURATION_MS = 45_000L

    private val blockedPathKeywords = listOf(
        "/record", "/recording", "/recordings", "/call", "/voice",
        "/whatsapp/media/whatsapp voice notes", "/telegram", "/notifications",
        "/ringtones", "/alarms", "/android/data"
    )

    private val preferredMusicKeywords = listOf(
        "/music", "/download", "/downloads", "/snaptube", "/vidmate"
    )

    fun scanMusicTracks(context: Context): List<TrackItem> {
        val result = mutableListOf<TrackItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.IS_MUSIC
        )

        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_DURATION_MS.toString())
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val albumArtBase = Uri.parse("content://media/external/audio/albumart")

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val isMusicCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val duration = cursor.getLong(durationCol)
                val rawPath = cursor.getString(dataCol) ?: ""
                val lowerPath = rawPath.lowercase()
                val isMusic = cursor.getInt(isMusicCol) != 0

                val isBlocked = blockedPathKeywords.any { lowerPath.contains(it) }
                val isPreferred = preferredMusicKeywords.any { lowerPath.contains(it) }

                if (isBlocked || (!isMusic && !isPreferred)) {
                    continue
                }

                val title = (cursor.getString(titleCol) ?: "Unknown Track").trim()
                val artistRaw = (cursor.getString(artistCol) ?: "").trim()
                val artist = if (artistRaw.isEmpty() || artistRaw.equals("<unknown>", ignoreCase = true)) {
                    "Unknown Artist"
                } else {
                    artistRaw
                }
                val albumRaw = (cursor.getString(albumCol) ?: "").trim()
                val album = if (albumRaw.isEmpty() || albumRaw.equals("<unknown>", ignoreCase = true)) {
                    "Single / Library"
                } else {
                    albumRaw
                }

                val albumId = cursor.getLong(albumIdCol)
                val artUri = if (albumId > 0) ContentUris.withAppendedId(albumArtBase, albumId) else null
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                result.add(
                    TrackItem(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        contentUri = contentUri,
                        dataPath = rawPath,
                        albumArtUri = artUri
                    )
                )
            }
        }
        return result
    }

    fun loadPlaylists(context: Context): List<UserPlaylist> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        val list = mutableListOf<UserPlaylist>()
        try {
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                val idsArr = obj.optJSONArray("trackIds") ?: JSONArray()
                val ids = mutableListOf<Long>()
                for (j in 0 until idsArr.length()) {
                    ids.add(idsArr.getLong(j))
                }
                if (id.isNotBlank() && name.isNotBlank()) {
                    list.add(UserPlaylist(id = id, name = name, trackIds = ids))
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun savePlaylists(context: Context, playlists: List<UserPlaylist>) {
        val arr = JSONArray()
        playlists.forEach { pl ->
            val obj = JSONObject()
            obj.put("id", pl.id)
            obj.put("name", pl.name)
            val idsArr = JSONArray()
            pl.trackIds.forEach { idsArr.put(it) }
            obj.put("trackIds", idsArr)
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }
}
