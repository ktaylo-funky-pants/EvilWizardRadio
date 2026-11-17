
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.example.evilwizardradio

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import android.Manifest
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.evilwizardradio.ui.theme.EvilWizardRadioTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.content.ComponentName
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

data class SongMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: android.graphics.Bitmap?,
    val lyrics: String? = null
)

@kotlinx.serialization.Serializable
data class PlaylistEntry(
    val songUri: String,
    val songName: String,
    val fileName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val eqBandLevels: List<Int> = emptyList(),
    val fileModified: Long = 0L,
    val tagModified: Long = 0L,
    val stinger: Boolean? = null,
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f,
    val reverbPreset: Int = 0,
    val dateAdded: Long = 0L,
    val genre: String = "",
    val followSongEq: Boolean? = null,
    val followSongSound: Boolean? = null,
    val matchTempo: Boolean? = null,
    val stingerMax: Int? = null,
    val stingerChance: Int? = null,
    val stingerFromMs: Long? = null,
    val stingerToMs: Long? = null,
    val stingerScatter: Boolean? = null,
    val stingerMark: Boolean? = null
)

@kotlinx.serialization.Serializable
data class SyncState(
    val stamp: String,
    val availableUris: Set<String>
)

@kotlinx.serialization.Serializable
data class Playlist(
    val name: String,
    val entries: MutableList<PlaylistEntry> = mutableListOf(),
    val eqBandLevels: List<Int> = emptyList(),
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val stinger: Boolean = false,
    val followSongEq: Boolean = true,
    val followSongSound: Boolean = true,
    val matchTempo: Boolean = true,
    val stingerMax: Int = 1,
    val stingerChance: Int = 100,
    val stingerFromMs: Long = 2_000L,
    val stingerToMs: Long = 10_000L,
    val stingerScatter: Boolean = false
)

object PlaylistRepository {
    private const val FILE_NAME = "playlists.json"
    private const val PREV_FILE = "playlists.prev.json"
    private const val STATE_FILE = "sync_state.json"
    const val ALL_SONGS_NAME = "All Songs"
    const val STINGERS_NAME = "Stingers"

    fun isBuiltIn(name: String?) = name == ALL_SONGS_NAME || name == STINGERS_NAME

    private fun genreSays(genre: String, word: String): Boolean =
        genre.split(';', ',', '/').any { it.trim().equals(word, ignoreCase = true) }

    fun refreshStingers(playlists: MutableList<Playlist>) {
        val allSongs = playlists.find { it.name == ALL_SONGS_NAME } ?: return
        val stingers = playlists.find { it.name == STINGERS_NAME }
            ?: Playlist(name = STINGERS_NAME).also { playlists.add(it) }

        val qualifying = allSongs.entries.filter {
            it.stingerMark ?: when {
                genreSays(it.genre, "stinger") -> true
                genreSays(it.genre, "song") -> false
                else -> it.durationMs in 1 until AppSettings.autoStingerMs && it.album.isBlank()
            }
        }
        val qualifyingUris = qualifying.map { it.songUri }.toSet()
        stingers.entries.removeAll { it.songUri !in qualifyingUris }

        val known = stingers.entries.map { it.songUri }.toSet()
        qualifying.forEach { e ->
            if (e.songUri !in known) {
                stingers.entries.add(e.copy(eqBandLevels = emptyList(), pitch = 1f, speed = 1f, reverbPreset = 0))
            }
        }
    }

    private const val DURATION_TOLERANCE_MS = 2000L

    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private var backedUpThisSession = false

    fun writeAtomic(file: java.io.File, text: String) {
        val af = android.util.AtomicFile(file)
        var out: java.io.FileOutputStream? = null
        try {
            out = af.startWrite()
            out.write(text.toByteArray())
            af.finishWrite(out)
        } catch (e: Exception) {
            e.printStackTrace()
            if (out != null) af.failWrite(out)
        }
    }
    fun readAtomic(file: java.io.File): String? = try {
        String(android.util.AtomicFile(file).readFully())
    } catch (e: java.io.FileNotFoundException) {
        null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    fun parsePlaylists(text: String): MutableList<Playlist>? = try {
        json.decodeFromString<MutableList<Playlist>>(text)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    fun loadPlaylists(context: Context): MutableList<Playlist> {
        val main = java.io.File(context.filesDir, FILE_NAME)
        val text = readAtomic(main)
            ?: return mutableListOf(Playlist(name = ALL_SONGS_NAME))

        parsePlaylists(text)?.let { return it }

        try {
            java.io.File(context.filesDir, "playlists.corrupt-${System.currentTimeMillis()}.json")
                .writeText(text)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        readAtomic(java.io.File(context.filesDir, PREV_FILE))
            ?.let { parsePlaylists(it) }
            ?.let {
                android.util.Log.w("Save", "playlists.json was unreadable → restored last session's backup")
                return it
            }

        android.util.Log.e("Save", "playlists.json unreadable and no backup → starting fresh (broken copy kept)")
        return mutableListOf(Playlist(name = ALL_SONGS_NAME))
    }

    fun savePlaylists(context: Context, playlists: List<Playlist>) {
        val main = java.io.File(context.filesDir, FILE_NAME)

        if (!backedUpThisSession) {
            readAtomic(main)?.let { old ->
                if (parsePlaylists(old) != null) {
                    writeAtomic(java.io.File(context.filesDir, PREV_FILE), old)
                }
            }
            backedUpThisSession = true
        }

        writeAtomic(main, json.encodeToString<List<Playlist>>(playlists))
    }

    fun loadSyncState(context: Context): SyncState? {
        val text = readAtomic(java.io.File(context.filesDir, STATE_FILE)) ?: return null
        return try {
            json.decodeFromString<SyncState>(text)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveSyncState(context: Context, state: SyncState) {
        writeAtomic(java.io.File(context.filesDir, STATE_FILE), json.encodeToString(state))
    }
    private fun tagKey(title: String, artist: String): String? {
        if (title.isBlank() || artist.isBlank()) return null
        return title.trim().lowercase() + "|" + artist.trim().lowercase()
    }

    private fun PlaylistEntry.linkedTo(song: DeviceSong): PlaylistEntry {
        val tagsStillValid = tagModified != 0L && tagModified == song.dateModified
        return copy(
            songUri = song.uri,
            songName = song.fileName,
            fileName = song.fileName,
            title = song.title,
            artist = song.artist,
            genre = song.genre,
            album = if (tagsStillValid) album else song.album,
            durationMs = song.durationMs,
            fileModified = song.dateModified,
            tagModified = if (tagsStillValid) tagModified else 0L,
            dateAdded = song.dateAdded
        )
    }

    fun syncLibrary(playlists: MutableList<Playlist>, deviceSongs: List<DeviceSong>): Set<String> {
        val byUri = deviceSongs.associateBy { it.uri }
        val byName = deviceSongs.groupBy { it.fileName }
        val byTags = deviceSongs
            .mapNotNull { s -> tagKey(s.title, s.artist)?.let { it to s } }
            .groupBy({ it.first }, { it.second })

        for (playlist in playlists) {
            val claimed = playlist.entries.map { it.songUri }.filter { it in byUri }.toMutableSet()

            for (i in playlist.entries.indices) {
                val entry = playlist.entries[i]
                val live = byUri[entry.songUri]
                if (live != null) {
                    playlist.entries[i] = entry.linkedTo(live)
                    continue
                }
                val match = findMatch(entry, byName, byTags, claimed) ?: continue
                android.util.Log.d("Sync", "RELINKED ${entry.fileName}: ${entry.songUri} -> ${match.uri}")
                claimed += match.uri
                playlist.entries[i] = entry.linkedTo(match)
            }
        }

        val allSongs = playlists.find { it.name == ALL_SONGS_NAME }
            ?: Playlist(name = ALL_SONGS_NAME).also { playlists.add(it) }

        val known = allSongs.entries.map { it.songUri }.toSet()
        deviceSongs.forEach { song ->
            if (song.uri !in known) {
                allSongs.entries.add(
                    PlaylistEntry(songUri = song.uri, songName = song.fileName).linkedTo(song)
                )
            }
        }

        refreshStingers(playlists)

        return byUri.keys
    }
    private fun findMatch(
        entry: PlaylistEntry,
        byName: Map<String, List<DeviceSong>>,
        byTags: Map<String, List<DeviceSong>>,
        claimed: Set<String>
    ): DeviceSong? {
        if (entry.durationMs <= 0L) return null

        val nameHits = byName[entry.fileName].orEmpty()
        val tagHits = tagKey(entry.title, entry.artist)?.let { byTags[it] }.orEmpty()

        val candidates = (nameHits + tagHits)
            .distinctBy { it.uri }
            .filter { it.uri !in claimed }
            .filter { kotlin.math.abs(it.durationMs - entry.durationMs) <= DURATION_TOLERANCE_MS }
        if (candidates.isEmpty()) return null

        val scored = candidates.map { c ->
            c to ((if (c in nameHits) 1 else 0) + (if (c in tagHits) 1 else 0))
        }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        return if (top.size == 1) top[0].first else null
    }
}

object AppSettings {
    private var prefs: android.content.SharedPreferences? = null

    var pauseOnInterrupt by mutableStateOf(false)
        private set
    var duckPercent by mutableStateOf(20)
        private set
    var autoStingerSec by mutableStateOf(15f)
        private set

    val autoStingerMs get() = (autoStingerSec * 1000).toLong()

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        pauseOnInterrupt = p.getBoolean("pauseOnInterrupt", false)
        duckPercent = p.getInt("duckPercent", 20)
        autoStingerSec = p.getFloat("autoStingerSec", 15f)
    }

    fun updatePauseOnInterrupt(v: Boolean) {
        pauseOnInterrupt = v
        prefs?.edit()?.putBoolean("pauseOnInterrupt", v)?.apply()
    }

    fun updateDuckPercent(v: Int) {
        duckPercent = v.coerceIn(0, 100)
        prefs?.edit()?.putInt("duckPercent", duckPercent)?.apply()
    }

    fun updateAutoStingerSec(v: Float) {
        autoStingerSec = v.coerceIn(1f, 600f)
        prefs?.edit()?.putFloat("autoStingerSec", autoStingerSec)?.apply()
    }
}

object LibraryManager {
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var availableUris by mutableStateOf<Set<String>>(emptySet())
        private set

    var nowPlayingUri by mutableStateOf<String?>(null)

    var stingerPopupOpen by mutableStateOf(false)

    var activePlaylistName by mutableStateOf<String?>(null)

    fun activeEntriesByUri(): Map<String, PlaylistEntry> =
        playlists.find { it.name == activePlaylistName }?.entries?.associateBy { it.songUri } ?: emptyMap()

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main
    )
    private val pendingEdits = mutableListOf<(MutableList<Playlist>) -> Unit>()
    private var flushJob: kotlinx.coroutines.Job? = null

    private fun copyOf(list: List<Playlist>): MutableList<Playlist> =
        list.map { it.copy(entries = it.entries.toMutableList()) }.toMutableList()

    private fun edit(context: Context, change: (MutableList<Playlist>) -> Unit) {
        playlists = copyOf(playlists).also(change)
        pendingEdits += change
        if (flushJob?.isActive == true) return

        val appContext = context.applicationContext
        flushJob = appScope.launch {
            while (pendingEdits.isNotEmpty()) {
                delay(400)
                mutex.withLock {
                    val changes = pendingEdits.toList()
                    pendingEdits.clear()

                    val working = copyOf(playlists)
                    changes.forEach { it(working) }
                    playlists = working
                    withContext(Dispatchers.IO) { PlaylistRepository.savePlaylists(appContext, working) }
                }
            }
        }
    }

    fun setPlaylistEq(context: Context, playlistName: String, levels: List<Int>) = edit(context) { list ->
        val i = list.indexOfFirst { it.name == playlistName }
        if (i >= 0) list[i] = list[i].copy(eqBandLevels = levels)
    }

    fun setSongEq(context: Context, playlistName: String, uri: String, levels: List<Int>) = edit(context) { list ->
        val p = list.find { it.name == playlistName } ?: return@edit
        val i = p.entries.indexOfFirst { it.songUri == uri }
        if (i >= 0) p.entries[i] = p.entries[i].copy(eqBandLevels = levels)
    }

    fun updateSong(
        context: Context,
        playlistName: String,
        uri: String,
        change: (PlaylistEntry) -> PlaylistEntry
    ) = edit(context) { list ->
        val p = list.find { it.name == playlistName } ?: return@edit
        val i = p.entries.indexOfFirst { it.songUri == uri }
        if (i >= 0) p.entries[i] = change(p.entries[i])
    }

    fun effectiveEq(uri: String): List<Int> {
        val p = playlists.find { it.name == activePlaylistName } ?: return emptyList()
        val song = p.entries.find { it.songUri == uri }?.eqBandLevels.orEmpty()
        return song.ifEmpty { p.eqBandLevels }
    }

    fun setPlaylistSound(context: Context, playlistName: String, speed: Float, pitch: Float) = edit(context) { list ->
        val i = list.indexOfFirst { it.name == playlistName }
        if (i >= 0) list[i] = list[i].copy(speed = speed, pitch = pitch)
    }

    fun effectiveSound(uri: String): Pair<Float, Float> {
        val p = playlists.find { it.name == activePlaylistName } ?: return 1f to 1f
        val e = p.entries.find { it.songUri == uri }
        return if (e != null && (e.speed != 1f || e.pitch != 1f)) e.speed to e.pitch
        else p.speed to p.pitch
    }
    fun setPlaylistStinger(context: Context, playlistName: String, on: Boolean) = edit(context) { list ->
        val i = list.indexOfFirst { it.name == playlistName }
        if (i >= 0) list[i] = list[i].copy(stinger = on)
    }

    fun effectiveStinger(uri: String): Boolean {
        val p = playlists.find { it.name == activePlaylistName } ?: return false
        return p.entries.find { it.songUri == uri }?.stinger ?: p.stinger
    }

    data class StingerRules(val max: Int, val chance: Int, val fromMs: Long, val toMs: Long, val scatter: Boolean)

    fun effectiveStingerRules(uri: String): StingerRules {
        val p = playlists.find { it.name == activePlaylistName }
        val e = p?.entries?.find { it.songUri == uri }
        return StingerRules(
            max = e?.stingerMax ?: p?.stingerMax ?: 1,
            chance = e?.stingerChance ?: p?.stingerChance ?: 100,
            fromMs = e?.stingerFromMs ?: p?.stingerFromMs ?: 2_000L,
            toMs = e?.stingerToMs ?: p?.stingerToMs ?: 10_000L,
            scatter = e?.stingerScatter ?: p?.stingerScatter ?: false
        )
    }

    fun updatePlaylist(context: Context, name: String, change: (Playlist) -> Playlist) = edit(context) { list ->
        val i = list.indexOfFirst { it.name == name }
        if (i >= 0) list[i] = change(list[i])
    }

    data class StingerSound(val eq: List<Int>, val speed: Float, val pitch: Float)

    fun tempoMatch(songBpm: Float?, stingerBpm: Float?): Float {
        if (songBpm == null || stingerBpm == null || stingerBpm <= 0f) return 1f
        var r = songBpm / stingerBpm
        while (r > 1.414f) r /= 2f
        while (r < 0.707f) r *= 2f
        return r
    }

    fun stingerSound(stingerUri: String, songUri: String, songBpm: Float? = null, stingerBpm: Float? = null): StingerSound {
        val list = playlists.find { it.name == PlaylistRepository.STINGERS_NAME }
        val e = list?.entries?.find { it.songUri == stingerUri }

        val ownEq = e?.eqBandLevels.orEmpty().ifEmpty { list?.eqBandLevels.orEmpty() }
        val (ownSpeed, ownPitch) =
            if (e != null && (e.speed != 1f || e.pitch != 1f)) e.speed to e.pitch
            else (list?.speed ?: 1f) to (list?.pitch ?: 1f)

        val followEq = e?.followSongEq ?: list?.followSongEq ?: true
        val followSound = e?.followSongSound ?: list?.followSongSound ?: true
        val matchTempo = e?.matchTempo ?: list?.matchTempo ?: true

        val songEq = if (followEq) effectiveEq(songUri) else emptyList()
        val (songSpeed, songPitch) = if (followSound) effectiveSound(songUri) else 1f to 1f
        val match = if (matchTempo) tempoMatch(songBpm, stingerBpm) else 1f

        val bands = maxOf(ownEq.size, songEq.size)
        val eq = List(bands) { ownEq.getOrElse(it) { 0 } + songEq.getOrElse(it) { 0 } }
        return StingerSound(
            eq = eq,
            speed = (ownSpeed * match * songSpeed).coerceIn(0.25f, 4f),
            pitch = (ownPitch * songPitch).coerceIn(0.25f, 4f)
        )
    }

    fun setStingersFollow(
        context: Context,
        followEq: Boolean? = null,
        followSound: Boolean? = null,
        matchTempo: Boolean? = null
    ) = edit(context) { list ->
        val i = list.indexOfFirst { it.name == PlaylistRepository.STINGERS_NAME }
        if (i >= 0) list[i] = list[i].copy(
            followSongEq = followEq ?: list[i].followSongEq,
            followSongSound = followSound ?: list[i].followSongSound,
            matchTempo = matchTempo ?: list[i].matchTempo
        )
    }

    fun checkPlaylistName(name: String, except: String? = null): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Give it a name"
        if (n.equals(PlaylistRepository.ALL_SONGS_NAME, ignoreCase = true) ||
            n.equals(PlaylistRepository.STINGERS_NAME, ignoreCase = true)
        ) return "That name is taken"
        if (playlists.any { it.name.equals(n, ignoreCase = true) && it.name != except }) {
            return "You already have a playlist with that name"
        }
        return null
    }

    fun createPlaylist(context: Context, name: String): String? {
        checkPlaylistName(name)?.let { return it }
        val n = name.trim()
        edit(context) { list -> if (list.none { it.name == n }) list.add(Playlist(name = n)) }
        return null
    }

    fun renamePlaylist(context: Context, oldName: String, newName: String): String? {
        if (PlaylistRepository.isBuiltIn(oldName)) return "That one can't be renamed"
        val n = newName.trim()
        if (n == oldName) return null
        checkPlaylistName(n, except = oldName)?.let { return it }
        edit(context) { list ->
            val i = list.indexOfFirst { it.name == oldName }
            if (i >= 0) list[i] = list[i].copy(name = n)
        }
        if (activePlaylistName == oldName) activePlaylistName = n
        return null
    }

    fun deletePlaylist(context: Context, name: String) {
        if (PlaylistRepository.isBuiltIn(name)) return
        edit(context) { list -> list.removeAll { it.name == name } }
    }

    fun addToPlaylist(context: Context, playlistName: String, song: PlaylistEntry) = edit(context) { list ->
        val p = list.find { it.name == playlistName } ?: return@edit
        if (p.entries.none { it.songUri == song.songUri }) {
            p.entries.add(song.copy(
                eqBandLevels = emptyList(), pitch = 1f, speed = 1f, reverbPreset = 0, stinger = null,
                stingerMax = null, stingerChance = null, stingerFromMs = null, stingerToMs = null,
                stingerScatter = null
            ))
        }
    }

    fun removeFromPlaylist(context: Context, playlistName: String, uri: String) = edit(context) { list ->
        list.find { it.name == playlistName }?.entries?.removeAll { it.songUri == uri }
    }

    fun refreshStingersNow(context: Context) = edit(context) { list ->
        PlaylistRepository.refreshStingers(list)
    }

    fun setStingerMark(context: Context, uri: String, mark: Boolean?) = edit(context) { list ->
        val all = list.find { it.name == PlaylistRepository.ALL_SONGS_NAME } ?: return@edit
        val i = all.entries.indexOfFirst { it.songUri == uri }
        if (i >= 0) all.entries[i] = all.entries[i].copy(stingerMark = mark)
        PlaylistRepository.refreshStingers(list)
    }

    fun isStinger(uri: String): Boolean =
        playlists.find { it.name == PlaylistRepository.STINGERS_NAME }?.entries?.any { it.songUri == uri } == true

    fun stingerMark(uri: String): Boolean? =
        playlists.find { it.name == PlaylistRepository.ALL_SONGS_NAME }?.entries?.find { it.songUri == uri }?.stingerMark

    private var tagJob: kotlinx.coroutines.Job? = null

    private fun startTagPass(context: Context) {
        if (tagJob?.isActive == true) return
        val appContext = context.applicationContext

        tagJob = appScope.launch {
            while (true) {
                val todo = playlists.asSequence()
                    .flatMap { it.entries.asSequence() }
                    .filter { it.tagModified == 0L && it.fileModified != 0L && it.songUri in availableUris }
                    .distinctBy { it.songUri }
                    .take(25)
                    .map { it.songUri to it.fileModified }
                    .toList()
                if (todo.isEmpty()) return@launch

                val results = withContext(Dispatchers.IO) {
                    todo.associate { (uri, modified) -> uri to (readTagAlbum(appContext, uri) to modified) }
                }

                edit(appContext) { list ->
                    for (p in list) {
                        for (i in p.entries.indices) {
                            val e = p.entries[i]
                            val (album, modified) = results[e.songUri] ?: continue
                            if (e.fileModified != modified) continue
                            p.entries[i] = e.copy(
                                album = album ?: e.album,
                                tagModified = modified
                            )
                        }
                    }
                    PlaylistRepository.refreshStingers(list)
                }
                android.util.Log.d("Tags", "read real tags for ${results.size} songs")
            }
        }
    }

    private fun readTagAlbum(context: Context, uri: String): String? {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, Uri.parse(uri))
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim() ?: ""
        } catch (e: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var loaded = false
    private var lastStamp: String? = null
    suspend fun sync(context: Context, force: Boolean = false) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (!hasAudioPermission(context)) return@withContext

                val stamp = mediaStamp(context)

                if (!force && loaded && stamp == lastStamp) return@withContext

                if (!force && !loaded) {
                    val saved = PlaylistRepository.loadSyncState(context)
                    if (saved != null && saved.stamp == stamp) {
                        val cached = PlaylistRepository.loadPlaylists(context)
                        withContext(Dispatchers.Main) {
                            playlists = cached
                            availableUris = saved.availableUris
                            startTagPass(context)
                        }
                        loaded = true
                        lastStamp = stamp
                        android.util.Log.d("Sync", "cold start: nothing changed, skipped scan")
                        return@withContext
                    }
                }

                val deviceSongs = loadDeviceSongs(context)
                val source = if (loaded) playlists else PlaylistRepository.loadPlaylists(context)
                val working = source.map { it.copy(entries = it.entries.toMutableList()) }.toMutableList()

                val available = PlaylistRepository.syncLibrary(working, deviceSongs)

                PlaylistRepository.savePlaylists(context, working)
                PlaylistRepository.saveSyncState(context, SyncState(stamp, available))

                withContext(Dispatchers.Main) {
                    playlists = working
                    availableUris = available
                    startTagPass(context)
                }
                loaded = true
                lastStamp = stamp
            }
        }
    }

    fun entriesFor(name: String): List<PlaylistEntry> =
        playlists.find { it.name == name }?.entries?.filter { it.songUri in availableUris } ?: emptyList()

    private const val SYNC_VERSION = 5

    private fun mediaStamp(context: Context): String =
        "v$SYNC_VERSION|" + MediaStore.getExternalVolumeNames(context).sorted().joinToString { v ->
            "$v:${MediaStore.getVersion(context, v)}:${MediaStore.getGeneration(context, v)}"
        }

    private fun hasAudioPermission(context: Context): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

object AudioFx {
    private var eq: android.media.audiofx.Equalizer? = null

    var bandFreqsHz by mutableStateOf<List<Int>>(emptyList())
        private set
    var bandLevels by mutableStateOf<List<Int>>(emptyList())
        private set
    var minLevel by mutableStateOf(0)
        private set
    var maxLevel by mutableStateOf(0)
        private set

    fun attach(sessionId: Int) {
        release()
        if (sessionId == 0) return
        try {
            val e = android.media.audiofx.Equalizer(0, sessionId).apply { enabled = true }
            eq = e
            val n = e.numberOfBands.toInt()
            minLevel = e.bandLevelRange[0].toInt()
            maxLevel = e.bandLevelRange[1].toInt()
            bandFreqsHz = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
            bandLevels = (0 until n).map { e.getBandLevel(it.toShort()).toInt() }
        } catch (ex: Exception) {
            ex.printStackTrace()
            eq = null
        }
    }

    fun setBand(band: Int, level: Int) {
        val e = eq ?: return
        if (band !in bandLevels.indices) return
        val clamped = level.coerceIn(minLevel, maxLevel)
        try {
            e.setBandLevel(band.toShort(), clamped.toShort())
        } catch (ex: Exception) {
            ex.printStackTrace()
            return
        }
        bandLevels = bandLevels.toMutableList().also { it[band] = clamped }
    }

    fun applyLevels(levels: List<Int>) {
        levels.forEachIndexed { band, level -> setBand(band, level) }
    }

    fun flat() {
        applyLevels(List(bandLevels.size) { 0 })
    }

    fun release() {
        eq?.release()
        eq = null
        bandLevels = emptyList()
        bandFreqsHz = emptyList()
    }
}

object LyricsReader {
    fun read(context: Context, uri: Uri): String? {
        val raw = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val s = java.io.BufferedInputStream(input)
                s.mark(4)
                val head = ByteArray(4)
                val n = readFully(s, head)
                s.reset()
                val magic = String(head, 0, n, Charsets.ISO_8859_1)
                when {
                    magic.startsWith("ID3") -> readId3(s) ?: readFlac(s)
                    magic == "fLaC" -> readFlac(s)
                    else -> null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        return clean(raw)
    }

    private val timeTag = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]""")
    private val infoLine = Regex("""^\[[a-zA-Z]+:.*]$""")

    private fun clean(raw: String?): String? {
        if (raw == null) return null
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
            .lines()
            .filterNot { infoLine.matches(it.trim()) }
            .map { timeTag.replace(it, "").trim() }
            .joinToString("\n")
            .trim()
        if (text.isEmpty()) return null
        if (text.trim('[', ']', '(', ')', ' ').lowercase() == "instrumental") return null
        return text
    }

    private fun readId3(s: java.io.InputStream): String? {
        val header = ByteArray(10)
        if (readFully(s, header) < 10) return null
        val major = header[3].toInt()
        val flags = header[5].toInt()
        val unsync = (flags and 0x80) != 0
        var remaining = syncsafe(header, 6)

        if ((flags and 0x40) != 0 && major >= 3) {
            val ext = ByteArray(4)
            readFully(s, ext); remaining -= 4
            val extSize = if (major == 4) syncsafe(ext, 0) - 4 else bigEndian(ext, 0)
            skipFully(s, extSize.toLong()); remaining -= extSize
        }

        val idLen = if (major == 2) 3 else 4
        val hdrLen = if (major == 2) 6 else 10
        val lyricsId = if (major == 2) "ULT" else "USLT"
        var found: String? = null

        while (remaining >= hdrLen) {
            val fh = ByteArray(hdrLen)
            if (readFully(s, fh) < hdrLen) break
            remaining -= hdrLen
            if (fh[0].toInt() == 0) break

            val id = String(fh, 0, idLen, Charsets.ISO_8859_1)
            val size = when (major) {
                2 -> be3(fh, 3)
                3 -> bigEndian(fh, 4)
                else -> syncsafe(fh, 4)
            }
            if (size <= 0 || size > remaining) break

            if (id == lyricsId && found == null) {
                var body = ByteArray(size)
                readFully(s, body)
                if (unsync) body = deUnsync(body)
                found = parseUslt(body)
            } else {
                skipFully(s, size.toLong())
            }
            remaining -= size
        }
        if (remaining > 0) skipFully(s, remaining.toLong())
        return found
    }

    private fun parseUslt(b: ByteArray): String? {
        if (b.size < 4) return null
        val enc = b[0].toInt()
        val start = skipTerminated(b, 4, enc)
        if (start >= b.size) return null
        val cs = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(b, start, b.size - start, cs).trimEnd('\u0000')
    }

    private fun skipTerminated(b: ByteArray, start: Int, enc: Int): Int {
        var i = start
        if (enc == 1 || enc == 2) {
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2
                i += 2
            }
        } else {
            while (i < b.size) {
                if (b[i].toInt() == 0) return i + 1
                i++
            }
        }
        return b.size
    }

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun readFlac(s: java.io.InputStream): String? {
        val magic = ByteArray(4)
        if (readFully(s, magic) < 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        while (true) {
            val h = ByteArray(4)
            if (readFully(s, h) < 4) return null
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = be3(h, 1)
            if (type == 4) {
                val body = ByteArray(len)
                if (readFully(s, body) < len) return null
                return parseVorbis(body)
            }
            skipFully(s, len.toLong())
            if (last) return null
        }
    }

    private fun parseVorbis(b: ByteArray): String? {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val vendorLen = buf.int
        buf.position(buf.position() + vendorLen)
        val count = buf.int
        repeat(count) {
            if (buf.remaining() < 4) return null
            val len = buf.int
            if (len < 0 || len > buf.remaining()) return null
            val entry = String(b, buf.position(), len, Charsets.UTF_8)
            buf.position(buf.position() + len)
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS") return entry.substring(eq + 1)
            }
        }
        return null
    }

    private fun readFully(s: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = s.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(s: java.io.InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = s.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (s.read() < 0) return
                left--
            }
        }
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
                ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun bigEndian(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun be3(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
}

object BpmReader {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Float>()

    fun get(context: Context, uri: String): Float? {
        cache[uri]?.let { return if (it > 0f) it else null }
        val bpm = try { read(context, Uri.parse(uri)) } catch (e: Exception) { null }
        cache[uri] = bpm ?: 0f
        return bpm
    }

    private fun read(context: Context, uri: Uri): Float? {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val s = java.io.BufferedInputStream(input)
            s.mark(4)
            val head = ByteArray(4)
            val n = readFully(s, head)
            s.reset()
            val magic = String(head, 0, n, Charsets.ISO_8859_1)
            when {
                magic.startsWith("ID3") -> readId3(s)
                magic == "fLaC" -> readFlac(s)
                else -> null
            }
        } ?: return null

        return Regex("""\d+(?:[.,]\d+)?""").find(text)?.value?.replace(',', '.')?.toFloatOrNull()
            ?.takeIf { it in 40f..300f }
    }

    private fun readId3(s: java.io.InputStream): String? {
        val header = ByteArray(10)
        if (readFully(s, header) < 10) return null
        val major = header[3].toInt()
        val flags = header[5].toInt()
        val unsync = (flags and 0x80) != 0
        var remaining = syncsafe(header, 6)

        if ((flags and 0x40) != 0 && major >= 3) {
            val ext = ByteArray(4)
            readFully(s, ext); remaining -= 4
            val extSize = if (major == 4) syncsafe(ext, 0) - 4 else bigEndian(ext, 0)
            skipFully(s, extSize.toLong()); remaining -= extSize
        }

        val idLen = if (major == 2) 3 else 4
        val hdrLen = if (major == 2) 6 else 10
        val bpmId = if (major == 2) "TBP" else "TBPM"

        while (remaining >= hdrLen) {
            val fh = ByteArray(hdrLen)
            if (readFully(s, fh) < hdrLen) return null
            remaining -= hdrLen
            if (fh[0].toInt() == 0) return null

            val id = String(fh, 0, idLen, Charsets.ISO_8859_1)
            val size = when (major) {
                2 -> be3(fh, 3)
                3 -> bigEndian(fh, 4)
                else -> syncsafe(fh, 4)
            }
            if (size <= 0 || size > remaining) return null

            if (id == bpmId) {
                var body = ByteArray(size)
                readFully(s, body)
                if (unsync) body = deUnsync(body)
                return textFrame(body)
            }
            skipFully(s, size.toLong())
            remaining -= size
        }
        return null
    }

    private fun textFrame(b: ByteArray): String? {
        if (b.isEmpty()) return null
        val cs = when (b[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(b, 1, b.size - 1, cs).trim('\u0000', ' ')
    }

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun readFlac(s: java.io.InputStream): String? {
        val magic = ByteArray(4)
        if (readFully(s, magic) < 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        while (true) {
            val h = ByteArray(4)
            if (readFully(s, h) < 4) return null
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = be3(h, 1)
            if (type == 4) {
                val body = ByteArray(len)
                if (readFully(s, body) < len) return null
                return vorbisBpm(body)
            }
            skipFully(s, len.toLong())
            if (last) return null
        }
    }

    private fun vorbisBpm(b: ByteArray): String? {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val vendorLen = buf.int
        buf.position(buf.position() + vendorLen)
        val count = buf.int
        repeat(count) {
            if (buf.remaining() < 4) return null
            val len = buf.int
            if (len < 0 || len > buf.remaining()) return null
            val entry = String(b, buf.position(), len, Charsets.UTF_8)
            buf.position(buf.position() + len)
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (key == "BPM" || key == "TEMPO") return entry.substring(eq + 1)
            }
        }
        return null
    }

    private fun readFully(s: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = s.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(s: java.io.InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = s.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (s.read() < 0) return
                left--
            }
        }
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
                ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun bigEndian(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun be3(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
}

enum class Tab {
    MUSIC, LIBRARY, SETTINGS
}

data class QueueItem(
    val entry: PlaylistEntry,
    val timelineIndex: Int,
    val isCurrent: Boolean
)

@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    return this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

@kotlinx.serialization.Serializable
data class LoudnessInfo(
    val name: String = "",
    val lufs: Float,
    val startMs: Long = -1,
    val endMs: Long = -1,
    val trimChecked: Boolean = false
)

object LoudnessStore {
    private const val FILE = "loudness.json"
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private var cache: MutableMap<String, LoudnessInfo>? = null

    @Synchronized
    fun get(context: Context, uri: String): Float? = load(context)[uri]?.lufs

    @Synchronized
    fun put(context: Context, uri: String, name: String, lufs: Float, startMs: Long = -1, endMs: Long = -1) {
        val map = load(context)
        map[uri] = LoudnessInfo(name, lufs, startMs, endMs, trimChecked = true)
        PlaylistRepository.writeAtomic(
            java.io.File(context.filesDir, FILE),
            json.encodeToString<Map<String, LoudnessInfo>>(HashMap(map))
        )
    }

    @Synchronized
    fun trim(context: Context, uri: String): Pair<Long, Long>? =
        load(context)[uri]
            ?.takeIf { it.trimChecked && it.startMs >= 0 && it.endMs > it.startMs }
            ?.let { it.startMs to it.endMs }

    @Synchronized
    fun trimChecked(context: Context, uri: String): Boolean = load(context)[uri]?.trimChecked == true

    private fun load(context: Context): MutableMap<String, LoudnessInfo> {
        cache?.let { return it }
        val text = PlaylistRepository.readAtomic(java.io.File(context.filesDir, FILE))
        val map = text?.let {
            try {
                json.decodeFromString<Map<String, LoudnessInfo>>(it).toMutableMap()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: mutableMapOf()
        cache = map
        return map
    }
}

object LoudnessMeter {
    data class Measurement(val lufs: Float?, val soundStartMs: Long, val soundEndMs: Long)

    fun measure(context: Context, uri: Uri, keepGoing: () -> Boolean): Measurement? {
        val extractor = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it)
                    .getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(android.media.MediaFormat.KEY_MIME) ?: return null

            val c = android.media.MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(inFormat, null, null, 0)
            c.start()

            var meter: LoudnessR128? = null
            var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var idleSpins = 0

            while (true) {
                if (!keepGoing()) return null

                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx) ?: return null
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        encoding = if (f.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING))
                            f.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)
                        else android.media.AudioFormat.ENCODING_PCM_16BIT
                        val rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                        val ch = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                        android.util.Log.d("Loudness", "decoding $mime: $rate Hz, $ch ch, encoding=$encoding")
                        meter = LoudnessR128(rate, ch)
                    }
                    outIdx >= 0 -> {
                        idleSpins = 0
                        if (info.size > 0) {
                            val out = c.getOutputBuffer(outIdx)
                            if (out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val m = meter ?: LoudnessR128(
                                    inFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE),
                                    inFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                                ).also { meter = it }
                                val ordered = out.order(java.nio.ByteOrder.nativeOrder())
                                when (encoding) {
                                    android.media.AudioFormat.ENCODING_PCM_16BIT -> m.addShort(ordered.asShortBuffer())
                                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> m.addFloat(ordered.asFloatBuffer())
                                    android.media.AudioFormat.ENCODING_PCM_32BIT -> m.addInt(ordered.asIntBuffer())
                                    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> m.add24(ordered)
                                    android.media.AudioFormat.ENCODING_PCM_8BIT -> m.add8(ordered)
                                    else -> {
                                        android.util.Log.w("Loudness", "unknown PCM encoding $encoding, skipping")
                                        c.releaseOutputBuffer(outIdx, false)
                                        return null
                                    }
                                }
                            }
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                    else -> {
                        if (inputDone && ++idleSpins > 500) break
                    }
                }
            }
            val m = meter ?: return null

            val delayMs = if (inFormat.containsKey(android.media.MediaFormat.KEY_ENCODER_DELAY))
                inFormat.getInteger(android.media.MediaFormat.KEY_ENCODER_DELAY) * 1000L / m.rate else 0L
            fun shift(ms: Long) = if (ms < 0) -1L else (ms - delayMs).coerceAtLeast(0)
            return Measurement(m.integrated(), shift(m.soundStartMs()), shift(m.soundEndMs()))
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}

private class LoudnessR128(val rate: Int, private val channels: Int) {
    private val pb = DoubleArray(3)
    private val pa = DoubleArray(3)
    private val hb = doubleArrayOf(1.0, -2.0, 1.0)
    private val ha = DoubleArray(3)
    private val s1 = Array(channels) { DoubleArray(4) }
    private val s2 = Array(channels) { DoubleArray(4) }

    private val segLen = maxOf(1, rate / 10)
    private val segments = ArrayList<Double>()
    private var segPos = 0
    private var segSum = 0.0
    private var frameCh = 0
    private var frameSum = 0.0
    private var frame = 0L
    private var firstLoud = -1L
    private var lastLoud = -1L

    init {
        run {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = kotlin.math.tan(Math.PI * f0 / rate)
            val vh = Math.pow(10.0, g / 20.0)
            val vb = Math.pow(vh, 0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            pb[0] = (vh + vb * k / q + k * k) / a0
            pb[1] = 2.0 * (k * k - vh) / a0
            pb[2] = (vh - vb * k / q + k * k) / a0
            pa[0] = 1.0
            pa[1] = 2.0 * (k * k - 1.0) / a0
            pa[2] = (1.0 - k / q + k * k) / a0
        }
        run {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = kotlin.math.tan(Math.PI * f0 / rate)
            val d = 1.0 + k / q + k * k
            ha[0] = 1.0
            ha[1] = 2.0 * (k * k - 1.0) / d
            ha[2] = (1.0 - k / q + k * k) / d
        }
    }

    private fun filter(ch: Int, x: Double): Double {
        val a = s1[ch]
        val y1 = pb[0] * x + pb[1] * a[0] + pb[2] * a[1] - pa[1] * a[2] - pa[2] * a[3]
        a[1] = a[0]; a[0] = x; a[3] = a[2]; a[2] = y1
        val b = s2[ch]
        val y2 = hb[0] * y1 + hb[1] * b[0] + hb[2] * b[1] - ha[1] * b[2] - ha[2] * b[3]
        b[1] = b[0]; b[0] = y1; b[3] = b[2]; b[2] = y2
        return y2
    }

    private fun addSample(x: Double) {
        if (kotlin.math.abs(x) > SILENCE_LINEAR) {
            if (firstLoud < 0) firstLoud = frame
            lastLoud = frame
        }
        val y = filter(frameCh, x)
        frameSum += y * y
        if (++frameCh == channels) {
            frameCh = 0
            frame++
            segSum += frameSum
            frameSum = 0.0
            if (++segPos == segLen) {
                segments.add(segSum / segLen)
                segSum = 0.0
                segPos = 0
            }
        }
    }

    fun soundStartMs(): Long = if (firstLoud < 0) -1L else firstLoud * 1000 / rate
    fun soundEndMs(): Long = if (lastLoud < 0) -1L else (lastLoud + 1) * 1000 / rate

    companion object {
        val SILENCE_LINEAR = Math.pow(10.0, -50.0 / 20.0)
    }

    fun addShort(b: java.nio.ShortBuffer) {
        while (b.hasRemaining()) addSample(b.get() / 32768.0)
    }

    fun addFloat(b: java.nio.FloatBuffer) {
        while (b.hasRemaining()) addSample(b.get().toDouble())
    }
    fun addInt(b: java.nio.IntBuffer) {
        while (b.hasRemaining()) addSample(b.get() / 2147483648.0)
    }

    fun add24(b: java.nio.ByteBuffer) {
        while (b.remaining() >= 3) {
            val lo = b.get().toInt() and 0xFF
            val mid = b.get().toInt() and 0xFF
            val hi = b.get().toInt()
            addSample(((hi shl 16) or (mid shl 8) or lo) / 8388608.0)
        }
    }

    fun add8(b: java.nio.ByteBuffer) {
        while (b.hasRemaining()) addSample(((b.get().toInt() and 0xFF) - 128) / 128.0)
    }

    fun integrated(): Float? {
        if (segments.size < 4) return null

        val blocks = (0..segments.size - 4).map { i ->
            (segments[i] + segments[i + 1] + segments[i + 2] + segments[i + 3]) / 4.0
        }
        fun lufs(power: Double) = -0.691 + 10.0 * kotlin.math.log10(power)

        val aboveAbsolute = blocks.filter { it > 0.0 && lufs(it) > -70.0 }
        if (aboveAbsolute.isEmpty()) return null
        val relativeGate = lufs(aboveAbsolute.average()) - 10.0
        val gated = aboveAbsolute.filter { lufs(it) > relativeGate }
        if (gated.isEmpty()) return null
        return lufs(gated.average()).toFloat()
    }
}

class GainProcessor : androidx.media3.common.audio.BaseAudioProcessor() {
    @Volatile var gain = 1f
    @Volatile var uri: String? = null

    fun refresh(forUri: String, newGain: Float) {
        if (uri == forUri) gain = newGain
    }

    override fun onConfigure(
        inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat
    ): androidx.media3.common.audio.AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT,
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val g = gain
        if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
            while (inputBuffer.hasRemaining()) {
                out.putShort((inputBuffer.short * g).toInt().coerceIn(-32768, 32767).toShort())
            }
        } else {
            while (inputBuffer.hasRemaining()) out.putFloat(inputBuffer.float * g)
        }
        out.flip()
    }
}

class SongStartSink(
    inner: androidx.media3.exoplayer.audio.AudioSink,
    private val onSongStart: (uri: String) -> androidx.media3.common.PlaybackParameters
) : androidx.media3.exoplayer.audio.ForwardingAudioSink(inner) {
    var renderer: SongAwareAudioRenderer? = null
    private var lastStream: Pair<Long, String>? = null

    override fun handleBuffer(buffer: java.nio.ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        renderer?.currentStream()?.let { stream ->
            if (stream != lastStream) {
                lastStream = stream
                setPlaybackParameters(onSongStart(stream.second))
            }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}

class SongAwareAudioRenderer(
    context: Context,
    mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: android.os.Handler?,
    eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener?,
    sink: SongStartSink
) : androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(
    context, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, sink
) {
    private val uriByOffset = HashMap<Long, String>()

    init { sink.renderer = this }

    override fun onStreamChanged(
        formats: Array<androidx.media3.common.Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
    ) {
        if (uriByOffset.size > 20) uriByOffset.clear()
        uriFor(mediaPeriodId)?.let { uriByOffset[offsetUs] = it }
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    fun currentStream(): Pair<Long, String>? {
        val offset = outputStreamOffsetUs
        val uri = uriByOffset[offset] ?: return null
        return offset to uri
    }

    private fun uriFor(id: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId): String? = try {
        val tl = timeline
        val period = tl.getPeriodByUid(id.periodUid, androidx.media3.common.Timeline.Period())
        tl.getWindow(period.windowIndex, androidx.media3.common.Timeline.Window())
            .mediaItem.localConfiguration?.uri?.toString()
    } catch (e: Exception) {
        null
    }
}

private fun buildQueueDisplay(controller: MediaController?): List<QueueItem> {
    val c = controller ?: return emptyList()
    val timeline = c.currentTimeline
    val itemCount = timeline.windowCount
    if (itemCount == 0) return emptyList()

    val currentIndex = c.currentMediaItemIndex

    val before = mutableListOf<Int>()
    var backIdx = timeline.getPreviousWindowIndex(currentIndex, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
    var backGuard = 0
    while (backIdx != androidx.media3.common.C.INDEX_UNSET && backGuard < itemCount) {
        before.add(backIdx)
        backIdx = timeline.getPreviousWindowIndex(backIdx, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        backGuard++
    }
    before.reverse()

    val after = mutableListOf<Int>()
    var idx = currentIndex
    var guard = 0
    while (idx != androidx.media3.common.C.INDEX_UNSET && guard < itemCount) {
        after.add(idx)
        idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        guard++
    }

    val order = before + after
    val byUri = LibraryManager.activeEntriesByUri()

    return order.map { i ->
        val uri = c.getMediaItemAt(i).localConfiguration?.uri?.toString()
        val matchedEntry = byUri[uri] ?: PlaylistEntry(songUri = uri ?: "", songName = "Unknown")
        QueueItem(entry = matchedEntry, timelineIndex = i, isCurrent = i == currentIndex)
    }
}
private data class LiveSound(val uri: String, val speed: Float, val pitch: Float)

class PlaybackService : MediaSessionService() {
    companion object {
        enum class FocusLossBehavior { DUCK, PAUSE }
        const val FULL_VOLUME = 1.0f
        const val STINGER_DRIFT_MS =
            250L
        const val TARGET_LUFS = -14f
        const val MAX_CUT_DB = -20f
        const val UNMEASURED_GUESS_LUFS =
            -10f
        const val LOOKAHEAD =
            10
        const val STINGER_CHAIN_GAP_MS =
            50L
        const val STINGER_PAD_START_MS =
            10L
        const val STINGER_PAD_END_MS = 30L

    }

    val focusLossBehavior get() = if (AppSettings.pauseOnInterrupt) FocusLossBehavior.PAUSE else FocusLossBehavior.DUCK

    private var plannedNextUri: String? =
        null
    private var appliedStingerEq: List<Int>? =
        null
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: android.media.AudioManager
    private lateinit var telephonyManager: android.telephony.TelephonyManager
    private var wasPlayingBeforeCall = false
    private var wasPlayingBeforeFocusLoss = false
    private var softErrorRetries = 0

    private data class StingerSlice(
        val clipStartMs: Long,
        val clipEndMs: Long?,
        val leadMs: Long,
        val lengthMs: Long
    ) {
        val isTrimmed get() = clipStartMs > 0 || clipEndMs != null
    }

    private var loadedSlice: StingerSlice? = null
    private val untrimmable = mutableSetOf<String>()

    private val gainProcessor = GainProcessor()
    private var duckFactor = 1f

    private lateinit var stingerPlayer: ExoPlayer
    private var stingerGain = 1f
    private val stingerBag =
        ArrayDeque<String>()
    private var lastStinger: String? = null
    private var stingerEq: android.media.audiofx.Equalizer? = null
    private var stingerResync =
        true
    private var lastSeekAt = 0L
    private val stingerErrors =
        mutableMapOf<String, Int>()

    private data class StingerPlan(
        val stingerUris: List<String>,
        val atMs: Long,
        val scatterAtMs: List<Long>? = null
    )

    private data class ChainLink(
        val uri: String,
        val slice: StingerSlice,
        val sound: LibraryManager.StingerSound,
        val startSongMs: Double,
        val endSongMs: Double
    )

    private val stingerPlans = HashMap<String, StingerPlan>()
    private var loadedStinger: String? =
        null
    private val failedStingers =
        mutableSetOf<String>()
    private var lastSongUri: String? = null

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main
    )
    private var workerJob: kotlinx.coroutines.Job? = null
    private var workingOn: String? = null
    private val failedAnalysis = mutableSetOf<String>()

    private val callStateCallback = object : android.telephony.TelephonyCallback(),
        android.telephony.TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                android.telephony.TelephonyManager.CALL_STATE_RINGING,
                android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (player.isPlaying) {
                        wasPlayingBeforeCall = true
                        player.pause()
                    }
                }

                android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                    if (wasPlayingBeforeCall) {
                        player.play()
                        wasPlayingBeforeCall = false
                    }
                }
            }
        }
    }

    private val focusListener =
        android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                    when (focusLossBehavior) {
                        FocusLossBehavior.DUCK -> {
                            duckFactor = FULL_VOLUME
                            applyVolume()
                        }

                        FocusLossBehavior.PAUSE -> {
                            if (wasPlayingBeforeFocusLoss) {
                                player.play()
                                wasPlayingBeforeFocusLoss = false
                            }
                        }
                    }
                }

                android.media.AudioManager.AUDIOFOCUS_LOSS,
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    when (focusLossBehavior) {
                        FocusLossBehavior.DUCK -> {
                            duckFactor = AppSettings.duckPercent / 100f
                            applyVolume()
                        }

                        FocusLossBehavior.PAUSE -> {
                            if (player.isPlaying) {
                                wasPlayingBeforeFocusLoss = true
                                player.pause()
                            }
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        AppSettings.load(this)

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: androidx.media3.exoplayer.audio.AudioSink,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                val sink = SongStartSink(
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(gainProcessor))
                        .build()
                ) { uri -> onSongStartInPipeline(uri) }
                out.add(
                    SongAwareAudioRenderer(
                        context,
                        mediaCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        sink
                    )
                )
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        mediaSession = MediaSession.Builder(this, player).build()

        AudioFx.attach(player.audioSessionId)

        stingerPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .build()
        attachStingerEq(stingerPlayer.audioSessionId)
        stingerPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val uri = loadedStinger ?: return
                android.util.Log.w(
                    "Stinger",
                    "stinger player error on $uri: ${error.errorCodeName}",
                    error
                )
                val count = (stingerErrors[uri] ?: 0) + 1
                stingerErrors[uri] = count
                when {
                    count >= 3 -> failedStingers += uri
                    loadedSlice?.isTrimmed == true -> untrimmable += uri
                }
                loadedStinger = null
                loadedSlice = null
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachStingerEq(audioSessionId)
            }
        })

        serviceScope.launch {
            androidx.compose.runtime.snapshotFlow {
                val uri = currentUri()

                val (speed, pitch) = LibraryManager.effectiveSound(uri ?: "")
                if (uri == null) null else LiveSound(uri, speed, pitch)
            }.collect { s ->
                if (s == null) return@collect
                player.playbackParameters =
                    androidx.media3.common.PlaybackParameters(s.speed, s.pitch)
            }
        }

        serviceScope.launch {
            var last: Pair<String?, List<Any?>>? = null
            var reroll: kotlinx.coroutines.Job? = null
            androidx.compose.runtime.snapshotFlow { stingerSettingsKey() }.collect { now ->
                val prev = last

                if (prev != null && prev.first == now.first && prev.second != now.second) {
                    reroll?.cancel()
                    reroll = serviceScope.launch {
                        androidx.compose.runtime.snapshotFlow { LibraryManager.stingerPopupOpen }
                            .first { open -> !open }
                        delay(300)
                        stingerPlans.clear()
                        pauseStinger()
                        planStingersAround()
                    }
                }
                last = now
            }
        }

        serviceScope.launch { stingerConductor() }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val fileProblem = error.errorCode in 2000..4999

                if (!fileProblem) {
                    if (softErrorRetries < 2) {
                        softErrorRetries++
                        player.prepare()
                    }
                    return
                }

                val bad = player.currentMediaItemIndex
                if (player.mediaItemCount <= 1) {
                    player.clearMediaItems()
                    return
                }
                player.seekToNextMediaItem()
                player.removeMediaItem(bad)
                player.prepare()
                player.play()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) softErrorRetries = 0
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                if (enabled) reshuffleKeepingCurrent()
                kickWorker()
                planStingersAround()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                kickWorker()
                planStingersAround()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    stingerResync = true
                    lastSeekAt = android.os.SystemClock.elapsedRealtime()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applySongSettings(mediaItem)
                val newUri = mediaItem?.localConfiguration?.uri?.toString()

                when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> lastSongUri?.let {
                        stingerPlans.remove(
                            it
                        )
                    }

                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ->
                        if (newUri != null && newUri != plannedNextUri) stingerPlans.remove(newUri)

                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> stingerPlans.clear()
                }
                lastSongUri = newUri
                planStingersAround()

                if (player.shuffleModeEnabled) {
                    when (reason) {
                        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> reshuffleKeepingCurrent()

                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                            if (player.currentMediaItemIndex ==
                                player.currentTimeline.getFirstWindowIndex(true)
                            ) {
                                reshuffleKeepingCurrent()
                            }
                        }
                    }
                }

                kickWorker()
            }
        })

        audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        requestFocus()

        telephonyManager =
            getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            telephonyManager.registerTelephonyCallback(mainExecutor, callStateCallback)
        }
    }

    private lateinit var audioFocusRequest: android.media.AudioFocusRequest

    private fun requestFocus() {
        audioFocusRequest =
            android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()

        audioManager.requestAudioFocus(audioFocusRequest)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        pauseStinger()
        player.pause()
        player.stop()
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        AudioFx.release()
        try {
            stingerEq?.release()
        } catch (_: Exception) {
        }
        stingerEq = null
        stingerPlayer.release()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        telephonyManager.unregisterTelephonyCallback(callStateCallback)
        mediaSession.player.release()
        mediaSession.release()
        super.onDestroy()
    }

    private fun applySongSettings(mediaItem: MediaItem?) {
        val uri = mediaItem?.localConfiguration?.uri?.toString() ?: return

        val (speed, pitch) = LibraryManager.effectiveSound(uri)
        player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, pitch)

        val eq = LibraryManager.effectiveEq(uri)
        if (eq.isNotEmpty()) AudioFx.applyLevels(eq) else AudioFx.flat()

        updateNormalization(uri)
    }

    private fun applyVolume() {
        player.volume = duckFactor
        if (::stingerPlayer.isInitialized) stingerPlayer.volume = stingerGain * duckFactor
    }

    private fun gainFor(uri: String): Float {
        val lufs = LoudnessStore.get(this, uri) ?: UNMEASURED_GUESS_LUFS
        val db = (TARGET_LUFS - lufs).coerceIn(MAX_CUT_DB, 0f)
        return Math.pow(10.0, db / 20.0).toFloat()
    }

    private fun onSongStartInPipeline(uri: String): androidx.media3.common.PlaybackParameters {
        gainProcessor.uri = uri
        gainProcessor.gain = gainFor(uri)
        val (speed, pitch) = LibraryManager.effectiveSound(uri)
        return androidx.media3.common.PlaybackParameters(speed, pitch)
    }

    private fun currentUri(): String? =
        player.currentMediaItem?.localConfiguration?.uri?.toString()

    private fun needsMeasuring(uri: String): Boolean {
        if (uri in failedAnalysis) return false
        if (LoudnessStore.get(this, uri) == null) return true

        return stingerEntry(uri) != null && !LoudnessStore.trimChecked(this, uri)
    }

    private fun updateNormalization(uri: String) {
        val gain = gainFor(uri)
        gainProcessor.refresh(uri, gain)
        val name = LibraryManager.activeEntriesByUri()[uri]?.songName ?: uri
        android.util.Log.d("Loudness", "$name → gain ${"%.2f".format(gain)}")
    }

    private fun nextToMeasure(): String? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null

        stingerPlans.values.flatMap { it.stingerUris }.firstOrNull { needsMeasuring(it) }
            ?.let { return it }

        currentUri()?.let { if (needsMeasuring(it)) return it }

        val walkMode =
            if (player.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        var idx = player.currentMediaItemIndex
        repeat(LOOKAHEAD + 1) {
            if (idx == androidx.media3.common.C.INDEX_UNSET) return null
            val uri = player.getMediaItemAt(idx).localConfiguration?.uri?.toString()
            if (uri != null && needsMeasuring(uri)) return uri
            idx = timeline.getNextWindowIndex(idx, walkMode, player.shuffleModeEnabled)
        }
        return null
    }

    private fun kickWorker() {
        val mostUrgent = nextToMeasure()
        if (workerJob?.isActive == true) {
            if (mostUrgent != null && mostUrgent != workingOn) {
                workerJob?.cancel()
            } else {
                return
            }
        }
        workerJob = serviceScope.launch { workLoop() }
    }

    private suspend fun workLoop() {
        while (true) {
            val uri = nextToMeasure() ?: return
            val name = LibraryManager.activeEntriesByUri()[uri]?.songName
                ?: stingerEntry(uri)?.songName ?: ""
            workingOn = uri
            try {
                val result = withContext(Dispatchers.Default) {
                    val job = this
                    LoudnessMeter.measure(this@PlaybackService, android.net.Uri.parse(uri)) {
                        job.coroutineContext[kotlinx.coroutines.Job]?.isActive != false
                    }
                }

                val lufs = result?.lufs
                if (result == null || lufs == null) {
                    failedAnalysis += uri
                    continue
                }

                withContext(Dispatchers.IO) {
                    LoudnessStore.put(
                        this@PlaybackService,
                        uri,
                        name,
                        lufs,
                        result.soundStartMs,
                        result.soundEndMs
                    )
                }
                android.util.Log.d(
                    "Loudness", "measured $name: %.1f LUFS → gain %.1f dB".format(
                        lufs, (TARGET_LUFS - lufs).coerceIn(MAX_CUT_DB, 0f)
                    )
                )

                if (currentUri() == uri) updateNormalization(uri)
                if (loadedStinger == uri) {
                    stingerGain = gainFor(uri)
                    applyVolume()
                }
            } finally {
                if (workingOn == uri) workingOn = null
            }
        }
    }

    private fun stingersAllowedOn(songUri: String): Boolean =
        LibraryManager.activePlaylistName != PlaylistRepository.STINGERS_NAME &&
                LibraryManager.effectiveStinger(songUri)

    private fun stingerSettingsKey(): Pair<String?, List<Any?>> {
        val name = LibraryManager.activePlaylistName
        val p = LibraryManager.playlists.find { it.name == name } ?: return name to emptyList()
        val settings = listOf<Any?>(
            p.stinger,
            p.stingerMax,
            p.stingerChance,
            p.stingerFromMs,
            p.stingerToMs,
            p.stingerScatter
        ) + p.entries.map { e ->
            listOf(
                e.songUri,
                e.stinger,
                e.stingerMax,
                e.stingerChance,
                e.stingerFromMs,
                e.stingerToMs,
                e.stingerScatter
            )
        }
        return name to settings
    }

    private fun stingerSlice(uri: String, durationMs: Long): StingerSlice {
        val trim = (if (uri in untrimmable) null else LoudnessStore.trim(this, uri))
            ?: return StingerSlice(0L, null, 0L, durationMs)
        val start = (trim.first - STINGER_PAD_START_MS).coerceAtLeast(0L)
        val endPadded = trim.second + STINGER_PAD_END_MS
        val end = if (endPadded >= durationMs) null else endPadded
        return StingerSlice(start, end, trim.first - start, (end ?: durationMs) - start)
    }

    private fun snapToBeat(songUri: String, ms: Long): Long {
        val bpm = BpmReader.get(this, songUri) ?: return ms
        val beatMs = 60_000.0 / bpm
        val phase = (LoudnessStore.trim(this, songUri)?.first ?: 0L).toDouble()
        val n = kotlin.math.round((ms - phase) / beatMs)
        return (phase + n * beatMs).toLong().coerceAtLeast(0L)
    }

    private fun planFor(songUri: String): StingerPlan? {
        if (!stingersAllowedOn(songUri)) return null
        stingerPlans[songUri]?.let { return it }

        val rules = LibraryManager.effectiveStingerRules(songUri)
        val hits =
            (1..rules.max.coerceAtLeast(0)).count { kotlin.random.Random.nextInt(100) < rules.chance }
        val stingers = List(hits) { nextStinger() }.filterNotNull()
        val from = rules.fromMs.coerceAtLeast(0L)
        val to = rules.toMs.coerceAtLeast(from)

        val plan = if (!rules.scatter) {
            StingerPlan(stingers, snapToBeat(songUri, kotlin.random.Random.nextLong(from, to + 1)))
        } else {
            val songLenMs = LibraryManager.activeEntriesByUri()[songUri]?.durationMs ?: 0L
            val placed = mutableListOf<Pair<String, LongRange>>()
            for (s in stingers) {
                val len = stingerSongLengthMs(s, songUri)
                val latest = if (songLenMs > 0) minOf(to, songLenMs - len) else to
                if (latest < from) continue
                for (attempt in 0 until 20) {
                    val raw = kotlin.random.Random.nextLong(from, latest + 1)
                    val t = snapToBeat(
                        songUri,
                        raw
                    ).let { if (it in from..latest) it else raw }
                    val window = t..(t + len)
                    val clear = placed.none { (_, w) ->
                        window.first < w.last + STINGER_CHAIN_GAP_MS && w.first < window.last + STINGER_CHAIN_GAP_MS
                    }
                    if (clear) {
                        placed += s to window
                        break
                    }
                }
            }
            placed.sortBy { it.second.first }
            StingerPlan(
                placed.map { it.first },
                placed.firstOrNull()?.second?.first ?: from,
                placed.map { it.second.first })
        }

        stingerPlans[songUri] = plan
        val bpm = BpmReader.get(this, songUri)
        android.util.Log.d(
            "Stinger",
            "planned ${plan.stingerUris.size}/${rules.max} ${if (rules.scatter) "scattered" else "chained"}" +
                    (if (bpm != null) " on beat (♩$bpm)" else " (no BPM tag)") + ": " +
                    plan.stingerUris.withIndex().joinToString { (i, s) ->
                        "${stingerEntry(s)?.songName ?: s} @${plan.scatterAtMs?.get(i) ?: plan.atMs}ms"
                    }
        )
        if (plan.stingerUris.isNotEmpty()) kickWorker()
        return plan
    }

    private fun stingerSoundFor(stingerUri: String, songUri: String): LibraryManager.StingerSound =
        LibraryManager.stingerSound(
            stingerUri, songUri,
            songBpm = BpmReader.get(this, songUri),
            stingerBpm = BpmReader.get(this, stingerUri)
        )

    private fun stingerSongLengthMs(stingerUri: String, songUri: String): Long {
        val durationMs = stingerEntry(stingerUri)?.durationMs ?: return 0L
        val slice = stingerSlice(stingerUri, durationMs)
        val songSpeed = LibraryManager.effectiveSound(songUri).first
        val stingerSpeed = stingerSoundFor(stingerUri, songUri).speed
        return (slice.lengthMs * songSpeed / stingerSpeed).toLong()
    }

    private fun attachStingerEq(sessionId: Int) {
        try {
            stingerEq?.release()
        } catch (_: Exception) {
        }
        stingerEq = null
        if (sessionId == 0) return
        stingerEq = try {
            android.media.audiofx.Equalizer(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        setStingerEq(emptyList())
        appliedStingerEq = null
    }

    private fun setStingerEq(levels: List<Int>) {
        val eq = stingerEq ?: return
        try {
            val min = eq.bandLevelRange[0].toInt()
            val max = eq.bandLevelRange[1].toInt()
            for (band in 0 until eq.numberOfBands) {
                val level = levels.getOrElse(band) { 0 }.coerceIn(min, max)
                eq.setBandLevel(band.toShort(), level.toShort())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun neighbourUris(): List<String> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val cur = player.currentMediaItemIndex
        val mode =
            if (player.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        return listOf(cur, timeline.getNextWindowIndex(cur, mode, player.shuffleModeEnabled))
            .filter { it != androidx.media3.common.C.INDEX_UNSET }
            .mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.toString() }
    }

    private fun planStingersAround() {
        val uris = neighbourUris()
        uris.forEach { planFor(it) }
        plannedNextUri = uris.getOrNull(1)
    }

    private fun nextStinger(): String? {
        var refilled = false
        while (true) {
            if (stingerBag.isEmpty()) {
                if (refilled) return null
                val all = LibraryManager.entriesFor(PlaylistRepository.STINGERS_NAME)
                    .map { it.songUri }
                    .filter { it !in failedStingers }
                if (all.isEmpty()) return null
                val round = all.shuffled().toMutableList()
                if (round.size > 1 && round.first() == lastStinger) round.add(round.removeAt(0))
                stingerBag.addAll(round)
                refilled = true
            }
            val uri = stingerBag.removeFirst()
            if (uri in LibraryManager.availableUris && uri !in failedStingers) {
                lastStinger = uri
                return uri
            }
        }
    }

    private fun stingerEntry(uri: String): PlaylistEntry? =
        LibraryManager.playlists.find { it.name == PlaylistRepository.STINGERS_NAME }
            ?.entries?.find { it.songUri == uri }

    private suspend fun stingerConductor() {
        while (true) {
            delay(if (player.isPlaying) 50L else 250L)
            syncStinger()
        }
    }

    private fun syncStinger() {
        if (android.os.SystemClock.elapsedRealtime() - lastSeekAt < 250) {
            pauseStinger()
            return
        }

        val songUri = currentUri()
        val plan = songUri?.let { planFor(it) }
        if (songUri == null || plan == null) {
            pauseStinger()
            return
        }

        val links = chainLinks(plan, songUri)
        val pos = player.currentPosition.toDouble()
        val current = links.firstOrNull { pos >= it.startSongMs && pos < it.endSongMs }

        if (current == null) {
            pauseStinger()
            stingerResync = true
            links.firstOrNull { it.startSongMs > pos }
                ?.let { loadStinger(it.uri, it.slice, it.sound) }
            return
        }

        loadStinger(current.uri, current.slice, current.sound)
        val songSpeed = player.playbackParameters.speed
        val expected = ((pos - current.startSongMs) * current.sound.speed / songSpeed).toLong()

        val state = stingerPlayer.playbackState
        if (stingerResync && (state == Player.STATE_READY || state == Player.STATE_ENDED)) {
            val drift = kotlin.math.abs(stingerPlayer.currentPosition - expected)
            if (drift > STINGER_DRIFT_MS || state == Player.STATE_ENDED) stingerPlayer.seekTo(
                expected
            )
            stingerResync = false
        }

        if (player.isPlaying) {
            if (!stingerPlayer.playWhenReady) stingerPlayer.play()
        } else {
            pauseStinger()
        }
    }

    private fun chainLinks(plan: StingerPlan, songUri: String): List<ChainLink> {
        val songSpeed = player.playbackParameters.speed
        val songEnd = player.duration.takeIf { it > 0 }?.toDouble() ?: Double.MAX_VALUE
        val links = mutableListOf<ChainLink>()
        var soundAt = plan.atMs.toDouble()
        for ((i, uri) in plan.stingerUris.withIndex()) {
            if (uri in failedStingers) continue
            val durationMs = stingerEntry(uri)?.durationMs ?: continue
            if (durationMs <= 0L) continue
            plan.scatterAtMs?.let { soundAt = it[i].toDouble() }
            val sound = stingerSoundFor(uri, songUri)
            val slice = stingerSlice(uri, durationMs)
            val scale = songSpeed / sound.speed
            val start = soundAt - slice.leadMs * scale
            val end = start + slice.lengthMs * scale
            if (end > songEnd) {
                if (plan.scatterAtMs == null) break else continue
            }
            links += ChainLink(uri, slice, sound, start, end)
            soundAt = end + STINGER_CHAIN_GAP_MS * songSpeed
        }
        return links
    }

    private fun loadStinger(uri: String, slice: StingerSlice, sound: LibraryManager.StingerSound) {
        val newFile = loadedStinger != uri

        val newSlice = !newFile && loadedSlice != slice && !stingerPlayer.isPlaying
        if (newFile || newSlice) {
            loadedStinger = uri
            loadedSlice = slice
            stingerResync = true
            stingerPlayer.playWhenReady = false

            val item = MediaItem.Builder().setUri(android.net.Uri.parse(uri))
            if (slice.isTrimmed) {
                item.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(slice.clipStartMs)
                        .apply { slice.clipEndMs?.let { setEndPositionMs(it) } }
                        .build()
                )
            }
            stingerPlayer.setMediaItem(item.build())
            stingerPlayer.prepare()

            stingerGain = gainFor(uri)
            applyVolume()
            android.util.Log.d(
                "Stinger",
                "loaded ${stingerEntry(uri)?.songName ?: uri}" +
                        (if (slice.isTrimmed) " (trimmed ${slice.clipStartMs}–${slice.clipEndMs ?: "end"} ms)" else "") +
                        " at %.2f× speed, %.2f× pitch".format(sound.speed, sound.pitch)
            )
        }
        val params = androidx.media3.common.PlaybackParameters(sound.speed, sound.pitch)
        if (stingerPlayer.playbackParameters != params) stingerPlayer.playbackParameters = params
        if (sound.eq != appliedStingerEq) {
            setStingerEq(sound.eq)
            appliedStingerEq = sound.eq
        }
    }

    private fun pauseStinger() {
        if (stingerPlayer.playWhenReady) stingerPlayer.pause()
    }

    private fun reshuffleKeepingCurrent() {
        val count = player.mediaItemCount
        if (count < 2) return
        val current = player.currentMediaItemIndex
        val rest = (0 until count).filter { it != current }.shuffled()
        val order = (listOf(current) + rest).toIntArray()
        player.setShuffleOrder(
            androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order, kotlin.random.Random.nextLong())
        )
    }
}

class MainActivity : ComponentActivity() {
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scheduleSync()
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    private val mediaObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            scheduleSync()
        }
    }

    private fun scheduleSync(force: Boolean = false) {
        syncJob?.cancel()
        syncJob = lifecycleScope.launch {
            delay(500)
            LibraryManager.sync(applicationContext, force)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppSettings.load(this)

        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )

        val audioPermission =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        requestPermissions.launch(
            arrayOf(
                audioPermission,
                Manifest.permission.READ_PHONE_STATE
            )
        )

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.parseColor("#323232")
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        setContent {
            EvilWizardRadioTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scheduleSync()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(mediaObserver)
        super.onDestroy()
    }
}

private enum class EqTab { SONG, PLAYLIST }

object ArtCache {
    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(300)
    private val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun get(uri: String): android.graphics.Bitmap? = cache.get(uri)
    fun isMissing(uri: String) = uri in missing

    fun load(context: Context, uri: String): android.graphics.Bitmap? {
        cache.get(uri)?.let { return it }
        if (uri in missing) return null
        val bmp = try {
            context.contentResolver.loadThumbnail(
                android.net.Uri.parse(uri),
                android.util.Size(160, 160),
                null
            )
        } catch (e: Exception) {
            loadEmbedded(
                context,
                uri
            )
        }
        if (bmp != null) cache.put(uri, bmp) else missing += uri
        return bmp
    }

    private fun loadEmbedded(context: Context, uri: String): android.graphics.Bitmap? {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, android.net.Uri.parse(uri))
            val bytes = mmr.embeddedPicture ?: return null
            val bounds =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun SongArt(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf(ArtCache.get(uri)) }

    LaunchedEffect(uri) {
        if (bitmap != null || ArtCache.isMissing(uri)) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) { ArtCache.load(context, uri) }
    }

    Box(
        modifier = modifier.background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_kitty),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(6.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
fun SongRow(
    entry: PlaylistEntry,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    onOptions: () -> Unit
) {
    val title = entry.title.ifBlank { entry.fileName.substringBeforeLast('.') }
        .ifBlank { entry.songName }
    val ellipsis = androidx.compose.ui.text.style.TextOverflow.Ellipsis

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlighted) Color(0xFF2E2E2E) else Color.Transparent)
            .noRippleClickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        SongArt(entry.songUri, Modifier.size(56.dp))

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = ellipsis
            )
            Text(
                entry.artist,
                color = Color.LightGray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = ellipsis
            )
            Text(
                entry.album,
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = ellipsis
            )
        }

        if (entry.durationMs > 0) {
            Text(
                formatMs(entry.durationMs.toFloat()),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Image(
            painter = painterResource(R.drawable.ic_options),
            contentDescription = "Song options",
            modifier = Modifier
                .size(40.dp)
                .noRippleClickable { onOptions() }
        )
    }
}

@Composable
fun PopupCard(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .noRippleClickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .heightIn(max = 480.dp)
                .background(Color(0xFF1E1E1E))
                .padding(16.dp)
                .noRippleClickable { }
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun NamePrompt(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> String?,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    PopupCard(onDismiss) {
        Text(title, color = Color.White, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            placeholder = { Text("Playlist name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, color = Color(0xFFE57373), fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            confirmLabel,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .align(Alignment.End)
                .noRippleClickable { error = onConfirm(text) }
                .padding(8.dp)
        )
    }
}

private enum class OptionsPage { MENU, SOUND, ADD_TO_PLAYLIST }

@Composable
fun StingerPopup(entry: PlaylistEntry, playlistName: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val playlist = LibraryManager.playlists.find { it.name == playlistName }
    val live = playlist?.entries?.find { it.songUri == entry.songUri } ?: entry
    val title =
        live.title.ifBlank { live.fileName.substringBeforeLast('.') }.ifBlank { live.songName }
    var tab by remember { mutableStateOf(EqTab.PLAYLIST) }
    var resetKey by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        LibraryManager.stingerPopupOpen = true
        onDispose { LibraryManager.stingerPopupOpen = false }
    }

    PopupCard(onDismiss) {
        Text("Stinger", color = Color.White, fontSize = 18.sp)
        Text(
            title,
            color = Color.Gray,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            playlistName == null || playlist == null ->
                Text("Play something first", color = Color.Gray, fontSize = 12.sp)

            playlistName == PlaylistRepository.STINGERS_NAME ->
                Text("Stingers don't get stingers.", color = Color.Gray, fontSize = 12.sp)

            else -> {
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        EqTab.PLAYLIST to "Playlist",
                        EqTab.SONG to "Song"
                    ).forEach { (t, label) ->
                        val selected = tab == t
                        Text(
                            label,
                            color = if (selected) Color.White else Color.Gray,
                            fontSize = 15.sp,
                            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            modifier = Modifier
                                .noRippleClickable { tab = t }
                                .padding(end = 20.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                val isSong = tab == EqTab.SONG

                val on = if (isSong) live.stinger ?: playlist.stinger else playlist.stinger
                val scatter = if (isSong) live.stingerScatter
                    ?: playlist.stingerScatter else playlist.stingerScatter
                val max =
                    if (isSong) live.stingerMax ?: playlist.stingerMax else playlist.stingerMax
                val chance = if (isSong) live.stingerChance
                    ?: playlist.stingerChance else playlist.stingerChance
                val fromMs = if (isSong) live.stingerFromMs
                    ?: playlist.stingerFromMs else playlist.stingerFromMs
                val toMs = if (isSong) live.stingerToMs
                    ?: playlist.stingerToMs else playlist.stingerToMs

                fun saveSong(change: (PlaylistEntry) -> PlaylistEntry) =
                    LibraryManager.updateSong(context, playlistName, live.songUri, change)

                fun savePlaylist(change: (Playlist) -> Playlist) =
                    LibraryManager.updatePlaylist(context, playlistName, change)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        "Stingers",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = on,
                        onCheckedChange = { v ->
                            if (isSong) saveSong { it.copy(stinger = v) } else savePlaylist {
                                it.copy(
                                    stinger = v
                                )
                            }
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF777777),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        "Mode",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.width(76.dp)
                    )
                    listOf(false to "Chain", true to "Scatter").forEach { (value, label) ->
                        val selected = scatter == value
                        Text(
                            label,
                            color = if (selected) Color.White else Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            modifier = Modifier
                                .noRippleClickable {
                                    if (isSong) saveSong { it.copy(stingerScatter = value) }
                                    else savePlaylist { it.copy(stingerScatter = value) }
                                }
                                .padding(end = 16.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }
                }

                SoundSlider(
                    label = "Max",
                    valueText = "$max",
                    value = max.toFloat(),
                    range = 1f..10f,
                    steps = 8
                ) { v ->
                    val n = kotlin.math.round(v).toInt()
                    if (isSong) saveSong { it.copy(stingerMax = n) } else savePlaylist {
                        it.copy(
                            stingerMax = n
                        )
                    }
                }

                SoundSlider(
                    label = "Chance",
                    valueText = "$chance%",
                    value = chance.toFloat(),
                    range = 0f..100f,
                    steps = 19
                ) { v ->
                    val n = kotlin.math.round(v / 5f).toInt() * 5
                    if (isSong) saveSong { it.copy(stingerChance = n) } else savePlaylist {
                        it.copy(
                            stingerChance = n
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (scatter) "Drop anywhere between (seconds)" else "Chain starts between (seconds)",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SecondsField(
                        fromMs,
                        key = Triple(isSong, resetKey, "from"),
                        modifier = Modifier.weight(1f)
                    ) { ms ->
                        if (isSong) saveSong { it.copy(stingerFromMs = ms) } else savePlaylist {
                            it.copy(
                                stingerFromMs = ms
                            )
                        }
                    }
                    Text("  –  ", color = Color.Gray, fontSize = 14.sp)
                    SecondsField(
                        toMs,
                        key = Triple(isSong, resetKey, "to"),
                        modifier = Modifier.weight(1f)
                    ) { ms ->
                        if (isSong) saveSong { it.copy(stingerToMs = ms) } else savePlaylist {
                            it.copy(
                                stingerToMs = ms
                            )
                        }
                    }
                }

                if (isSong) {
                    val hasOwn =
                        live.stinger != null || live.stingerMax != null || live.stingerChance != null ||
                                live.stingerFromMs != null || live.stingerToMs != null || live.stingerScatter != null
                    Text(
                        "Use Playlist Settings",
                        color = if (hasOwn) Color.White else Color.DarkGray,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.End)
                            .noRippleClickable {
                                if (!hasOwn) return@noRippleClickable
                                saveSong {
                                    it.copy(
                                        stinger = null,
                                        stingerMax = null,
                                        stingerChance = null,
                                        stingerFromMs = null,
                                        stingerToMs = null,
                                        stingerScatter = null
                                    )
                                }
                                resetKey++
                            }
                            .padding(top = 12.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionsMenuItem(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable { onClick() }
            .padding(vertical = 14.dp)
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("›", color = Color.Gray, fontSize = 20.sp)
    }
}

@Composable
private fun OptionsPageHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable { onBack() }
            .padding(bottom = 8.dp)
    ) {
        Text("‹", color = Color.Gray, fontSize = 20.sp, modifier = Modifier.padding(end = 8.dp))
        Text(title.uppercase(), color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun SoundSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        androidx.compose.material3.Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.SliderDefaults.colors(
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
        Text(
            valueText,
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun MusicScreen(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatCycle: () -> Unit,
    songMeta: SongMeta?,
    currentSongUri: String?,
    currentPosition: Float,
    duration: Float,
    onSeek: (Float) -> Unit,
    onSeekStart: () -> Unit,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    queueItems: List<QueueItem>,
    onQueueItemClick: (QueueItem) -> Unit,
    onDragPreview: (Float) -> Unit

) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var showEq by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var queueOptionsFor by remember { mutableStateOf<PlaylistEntry?>(null) }
    var showNowPlayingOptions by remember { mutableStateOf(false) }
    var showStingerPopup by remember { mutableStateOf(false) }
    val hasSong = currentSongUri != null

    LaunchedEffect(hasSong) {
        if (!hasSong) {
            showEq = false
            showQueue = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(1f))

                val art = songMeta?.art
                val lyrics = songMeta?.lyrics
                val lyricsScroll = androidx.compose.foundation.rememberScrollState()
                LaunchedEffect(lyrics) { lyricsScroll.scrollTo(0) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(if (showLyrics && lyrics != null) Color(0xFF121212) else Color.DarkGray)
                        .clipToBounds()
                        .noRippleClickable { if (lyrics != null) showLyrics = !showLyrics },
                    contentAlignment = Alignment.Center
                ) {
                    if (showLyrics && lyrics != null) {
                        Text(
                            text = lyrics,
                            color = Color.White,
                            fontSize = 16.sp,
                            lineHeight = 26.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(lyricsScroll)
                                .padding(20.dp)
                        )
                    } else if (art != null) {
                        Image(
                            bitmap = art.asImageBitmap(),
                            contentDescription = "Album Art",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_kitty),
                            contentDescription = "Evil Art",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .padding(horizontal = 24.dp)
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (songMeta == null) "" else songMeta.title
                                ?: "Unknown Title",
                            color = Color.White,
                            fontSize = 18.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(initialDelayMillis = 1000)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .padding(horizontal = 32.dp)
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (songMeta == null) "" else songMeta.artist
                                ?: "Unknown Artist",
                            color = Color.LightGray,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            fontSize = 14.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(initialDelayMillis = 1000)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .padding(horizontal = 32.dp)
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = songMeta?.album ?: "",
                            color = Color.LightGray,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            fontSize = 14.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(initialDelayMillis = 1000)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_eq),
                        contentDescription = "EQ",
                        modifier = Modifier
                            .size(48.dp)
                            .noRippleClickable { if (hasSong) showEq = true }
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_queue),
                        contentDescription = "Queue",
                        modifier = Modifier
                            .size(48.dp)
                            .noRippleClickable { if (hasSong) showQueue = true }
                    )
                    val stingerOn =
                        currentSongUri?.let { LibraryManager.effectiveStinger(it) } == true
                    Image(
                        painter = painterResource(R.drawable.ic_stingers),
                        contentDescription = "Stinger",
                        modifier = Modifier
                            .size(48.dp)
                            .noRippleClickable {
                                if (currentSongUri != null) showStingerPopup = true
                            }
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_options),
                        contentDescription = "Options",
                        modifier = Modifier
                            .size(48.dp)
                            .noRippleClickable {
                                if (currentSongUri != null) showNowPlayingOptions = true
                            }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(
                            id = if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_single else R.drawable.ic_repeat_queue
                        ),
                        contentDescription = "Repeat",
                        modifier = Modifier
                            .size(48.dp)
                            .noRippleClickable { onRepeatCycle() }
                    )
                    Image(
                        painter = painterResource(
                            id = if (shuffleEnabled) R.drawable.ic_shuffle else R.drawable.ic_single
                        ),
                        contentDescription = "Shuffle",
                        modifier = Modifier
                            .size(48.dp)
                            .noRippleClickable { onShuffleToggle() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatMs(currentPosition), color = Color.White, fontSize = 12.sp)

                Spacer(modifier = Modifier.width(16.dp))

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                        .pointerInput(duration, hasSong) {
                            if (!hasSong) return@pointerInput
                            var dragTarget = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { onSeekStart() },
                                onHorizontalDrag = { change, _ ->
                                    val percent =
                                        (change.position.x / size.width).coerceIn(0f, 1f)
                                    dragTarget = percent * duration
                                    onDragPreview(dragTarget)
                                },
                                onDragEnd = { onSeek(dragTarget) },
                                onDragCancel = { onSeek(dragTarget) }
                            )
                        }
                        .pointerInput(duration, hasSong) {
                            if (!hasSong) return@pointerInput
                            detectTapGestures { tapOffset ->
                                val percent = (tapOffset.x / size.width).coerceIn(0f, 1f)
                                onSeek(percent * duration)
                            }
                        }
                ) {
                    val trackY = size.height / 2
                    val progress = if (duration > 0f) currentPosition / duration else 0f

                    drawLine(
                        color = Color(0xFF555555),
                        start = Offset(0f, trackY),
                        end = Offset(size.width, trackY),
                        strokeWidth = 16f
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(0f, trackY),
                        end = Offset(size.width * progress, trackY),
                        strokeWidth = 16f
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 24f,
                        center = Offset(size.width * progress, trackY)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(formatMs(duration), color = Color.White, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_previous),
                    contentDescription = "Previous",
                    modifier = Modifier
                        .size(48.dp)
                        .noRippleClickable { onPrevious() }
                )

                Spacer(modifier = Modifier.width(48.dp))

                Image(
                    painter = painterResource(
                        id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = "Play Pause",
                    modifier = Modifier
                        .size(48.dp)
                        .noRippleClickable { onPlayPause() }
                )

                Spacer(modifier = Modifier.width(48.dp))

                Image(
                    painter = painterResource(R.drawable.ic_skip),
                    contentDescription = "Skip",
                    modifier = Modifier
                        .size(48.dp)
                        .noRippleClickable { onSkip() }
                )
            }
        }

        if (showEq) {
            EqPopup(
                currentSongUri,
                LibraryManager.activePlaylistName,
                onDismiss = { showEq = false })
        }

        if (showQueue) {
            BackHandler { showQueue = false }

            val currentIndex = queueItems.indexOfFirst { it.isCurrent }

            val queueListState = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0)
            )
            LaunchedEffect(Unit) {
                if (currentIndex < 0) return@LaunchedEffect
                val info = queueListState.layoutInfo
                val viewport = info.viewportEndOffset - info.viewportStartOffset
                val itemSize = info.visibleItemsInfo.firstOrNull()?.size ?: 0
                queueListState.scrollToItem(currentIndex, -(viewport / 2 - itemSize / 2))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .noRippleClickable { showQueue = false },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .heightIn(max = 480.dp)
                        .background(Color(0xFF1E1E1E))
                        .padding(16.dp)
                        .noRippleClickable { }
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Queue", color = Color.White, fontSize = 18.sp)
                            if (currentIndex >= 0) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "${currentIndex + 1} / ${queueItems.size}",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        androidx.compose.foundation.lazy.LazyColumn(state = queueListState) {
                            items(queueItems.size) { index ->
                                val item = queueItems[index]
                                SongRow(
                                    entry = item.entry,
                                    highlighted = item.isCurrent,
                                    onClick = {
                                        onQueueItemClick(item)
                                        showQueue = false
                                    },
                                    onOptions = { queueOptionsFor = item.entry }
                                )
                            }
                        }
                    }
                }
            }
        }

        queueOptionsFor?.let { entry ->
            SongOptionsPopup(
                entry,
                LibraryManager.activePlaylistName,
                onDismiss = { queueOptionsFor = null })
        }

        val nowEntry = currentSongUri?.let { LibraryManager.activeEntriesByUri()[it] }
        if (showNowPlayingOptions && nowEntry != null) {
            SongOptionsPopup(
                nowEntry,
                LibraryManager.activePlaylistName,
                onDismiss = { showNowPlayingOptions = false })
        }
        if (showStingerPopup && nowEntry != null) {
            StingerPopup(
                nowEntry,
                LibraryManager.activePlaylistName,
                onDismiss = { showStingerPopup = false })
        }
    }
}

@Composable
fun StingerFollowToggle(
    label: String,
    isSong: Boolean,
    songValue: Boolean?,
    playlistValue: Boolean,
    onSet: (Boolean?) -> Unit
) {
    val checked = if (isSong) songValue ?: playlistValue else playlistValue
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable { onSet(!checked) }
                .padding(vertical = 4.dp)
        ) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = { onSet(it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF777777),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }
        if (isSong && songValue != null) {
            Text(
                "Use Playlist Setting",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .noRippleClickable { onSet(null) }
                    .padding(8.dp)
            )
        }
    }
}

@Composable
fun EqPopup(songUri: String?, playlistName: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var eqTab by remember { mutableStateOf(EqTab.PLAYLIST) }

    val playlist = LibraryManager.playlists.find { it.name == playlistName }
    val entry = playlist?.entries?.find { it.songUri == songUri }
    val songLevels = entry?.eqBandLevels.orEmpty()
    val playlistLevels = playlist?.eqBandLevels.orEmpty()
    val bandCount = AudioFx.bandLevels.size
    val flatLevels = List(bandCount) { 0 }

    val shown = when (eqTab) {
        EqTab.SONG -> songLevels.ifEmpty { playlistLevels.ifEmpty { flatLevels } }
        EqTab.PLAYLIST -> playlistLevels.ifEmpty { flatLevels }
    }

    val isLive = songUri != null &&
            songUri == LibraryManager.nowPlayingUri &&
            playlistName == LibraryManager.activePlaylistName

    val playingEntry = playlist?.entries?.find { it.songUri == LibraryManager.nowPlayingUri }
    val playlistIsLive = LibraryManager.nowPlayingUri != null &&
            playlistName == LibraryManager.activePlaylistName &&
            playingEntry?.eqBandLevels.orEmpty().isEmpty()

    PopupCard(onDismiss) {
        Text("Equalizer", color = Color.White, fontSize = 18.sp)
        entry?.let { e ->
            Text(
                e.title.ifBlank { e.fileName.substringBeforeLast('.') }.ifBlank { e.songName },
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(EqTab.PLAYLIST to "Playlist", EqTab.SONG to "Song").forEach { (tab, label) ->
                val selected = eqTab == tab
                Text(
                    label,
                    color = if (selected) Color.White else Color.Gray,
                    fontSize = 15.sp,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    modifier = Modifier
                        .noRippleClickable { eqTab = tab }
                        .padding(end = 20.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        when {
            bandCount == 0 ->
                Text(
                    "Equalizer isn't available right now",
                    color = Color.Gray,
                    fontSize = 12.sp
                )

            playlist == null || songUri == null || playlistName == null ->
                Text("Play something first", color = Color.Gray, fontSize = 12.sp)

            else -> {
                shown.indices.reversed()
                    .forEach { band ->
                        val level = shown[band]
                        SoundSlider(
                            label = AudioFx.bandFreqsHz.getOrElse(band) { 0 }.let { f ->
                                if (f >= 1000) "${f / 1000}kHz" else "${f}Hz"
                            },
                            valueText = "${level / 100} dB",
                            value = level.toFloat(),
                            range = AudioFx.minLevel.toFloat()..AudioFx.maxLevel.toFloat(),
                            steps = 0
                        ) { v ->
                            val newLevels = shown.toMutableList().also { it[band] = v.toInt() }
                            when (eqTab) {
                                EqTab.SONG -> {
                                    LibraryManager.setSongEq(
                                        context,
                                        playlistName,
                                        songUri,
                                        newLevels
                                    )
                                    if (isLive) AudioFx.applyLevels(newLevels)
                                }

                                EqTab.PLAYLIST -> {
                                    LibraryManager.setPlaylistEq(
                                        context,
                                        playlistName,
                                        newLevels
                                    )
                                    if (playlistIsLive) AudioFx.applyLevels(newLevels)
                                }
                            }
                        }
                    }

                Spacer(modifier = Modifier.height(8.dp))

                if (playlistName == PlaylistRepository.STINGERS_NAME) {
                    StingerFollowToggle(
                        label = "Follow song EQ",
                        isSong = eqTab == EqTab.SONG,
                        songValue = entry?.followSongEq,
                        playlistValue = playlist.followSongEq
                    ) { v ->
                        if (eqTab == EqTab.SONG) {
                            LibraryManager.updateSong(
                                context,
                                PlaylistRepository.STINGERS_NAME,
                                songUri
                            ) {
                                it.copy(followSongEq = v)
                            }
                        } else {
                            LibraryManager.setStingersFollow(context, followEq = v ?: true)
                        }
                    }
                }

                val (resetLabel, resetEnabled) = when (eqTab) {
                    EqTab.SONG -> "Reset And Use playlist EQ Instead" to songLevels.isNotEmpty()
                    EqTab.PLAYLIST -> "Reset" to playlistLevels.isNotEmpty()
                }
                Text(
                    resetLabel,
                    color = if (resetEnabled) Color.White else Color.DarkGray,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.End)
                        .noRippleClickable {
                            if (!resetEnabled) return@noRippleClickable
                            when (eqTab) {
                                EqTab.SONG -> {
                                    LibraryManager.setSongEq(
                                        context,
                                        playlistName,
                                        songUri,
                                        emptyList()
                                    )
                                    if (isLive) {
                                        if (playlistLevels.isNotEmpty()) AudioFx.applyLevels(
                                            playlistLevels
                                        ) else AudioFx.flat()
                                    }
                                }

                                EqTab.PLAYLIST -> {
                                    LibraryManager.setPlaylistEq(
                                        context,
                                        playlistName,
                                        emptyList()
                                    )
                                    if (playlistIsLive) AudioFx.flat()
                                }
                            }
                        }
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun SongOptionsPopup(entry: PlaylistEntry, playlistName: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val playlist = LibraryManager.playlists.find { it.name == playlistName }
    val live = playlist?.entries?.find { it.songUri == entry.songUri } ?: entry
    val title =
        live.title.ifBlank { live.fileName.substringBeforeLast('.') }.ifBlank { live.songName }

    var page by remember { mutableStateOf(OptionsPage.MENU) }
    var soundTab by remember { mutableStateOf(EqTab.PLAYLIST) }
    var openEq by remember { mutableStateOf(false) }
    var openStinger by remember { mutableStateOf(false) }

    var songSpeed by remember { mutableStateOf(live.speed) }
    var songPitch by remember { mutableStateOf(live.pitch) }
    var plSpeed by remember { mutableStateOf(playlist?.speed ?: 1f) }
    var plPitch by remember { mutableStateOf(playlist?.pitch ?: 1f) }
    val songOverride = songSpeed != 1f || songPitch != 1f

    val saveDrafts = {
        if (playlistName != null) {
            if (songSpeed != live.speed || songPitch != live.pitch) {
                val s = songSpeed
                val p = songPitch
                LibraryManager.updateSong(
                    context,
                    playlistName,
                    live.songUri
                ) { it.copy(speed = s, pitch = p) }
            }
            if (plSpeed != (playlist?.speed ?: 1f) || plPitch != (playlist?.pitch ?: 1f)) {
                LibraryManager.setPlaylistSound(context, playlistName, plSpeed, plPitch)
            }
        }
    }
    val closeAndSave = {
        saveDrafts()
        onDismiss()
    }

    if (openEq) {
        EqPopup(live.songUri, playlistName, onDismiss = onDismiss)
        return
    }
    if (openStinger) {
        StingerPopup(live, playlistName, onDismiss = onDismiss)
        return
    }

    PopupCard(closeAndSave) {
        BackHandler(enabled = page != OptionsPage.MENU) { page = OptionsPage.MENU }

        Text(
            title,
            color = Color.White,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        if (live.artist.isNotBlank()) {
            Text(live.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
        }
        Spacer(modifier = Modifier.height(12.dp))

        when (page) {
            OptionsPage.MENU -> {
                OptionsMenuItem("Pitch & speed") { page = OptionsPage.SOUND }
                OptionsMenuItem("Equalizer") { saveDrafts(); openEq = true }
                if (playlistName != PlaylistRepository.STINGERS_NAME) {
                    OptionsMenuItem("Stinger") { saveDrafts(); openStinger = true }
                }
                OptionsMenuItem("Add to playlist") { page = OptionsPage.ADD_TO_PLAYLIST }

                val isStinger = LibraryManager.isStinger(live.songUri)
                OptionsMenuItem(if (isStinger) "Mark as song" else "Mark as stinger") {
                    LibraryManager.setStingerMark(context, live.songUri, !isStinger)
                    saveDrafts()
                    onDismiss()
                }

                if (playlistName != null && !PlaylistRepository.isBuiltIn(playlistName)) {
                    OptionsMenuItem("Remove from this playlist") {
                        LibraryManager.removeFromPlaylist(context, playlistName, live.songUri)
                        onDismiss()
                    }
                }
            }

            OptionsPage.SOUND -> {
                OptionsPageHeader("Pitch & speed") { page = OptionsPage.MENU }

                if (playlistName == null) {
                    Text("Play something first", color = Color.Gray, fontSize = 12.sp)
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            EqTab.PLAYLIST to "Playlist",
                            EqTab.SONG to "Song"
                        ).forEach { (tab, label) ->
                            val selected = soundTab == tab
                            Text(
                                label,
                                color = if (selected) Color.White else Color.Gray,
                                fontSize = 15.sp,
                                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                                modifier = Modifier
                                    .noRippleClickable { soundTab = tab }
                                    .padding(end = 20.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    val isSong = soundTab == EqTab.SONG

                    val shownSpeed = if (isSong && songOverride) songSpeed else plSpeed
                    val shownPitch = if (isSong && songOverride) songPitch else plPitch

                    fun setSound(speed: Float, pitch: Float) {
                        if (isSong) {
                            songSpeed = speed
                            songPitch = pitch
                        } else {
                            plSpeed = speed
                            plPitch = pitch
                        }
                    }

                    SoundSlider(
                        label = "Speed",
                        valueText = "%.2f×".format(shownSpeed),
                        value = shownSpeed,
                        range = 0.5f..2f,
                        steps = 29
                    ) { v -> setSound(kotlin.math.round(v * 20f) / 20f, shownPitch) }

                    SoundSlider(
                        label = "Pitch",
                        valueText = "%.2f×".format(shownPitch),
                        value = shownPitch,
                        range = 0.5f..2f,
                        steps = 29
                    ) { v -> setSound(shownSpeed, kotlin.math.round(v * 20f) / 20f) }

                    Spacer(modifier = Modifier.height(8.dp))
                    if (playlistName == PlaylistRepository.STINGERS_NAME) {
                        StingerFollowToggle(
                            label = "Follow song speed/pitch",
                            isSong = isSong,
                            songValue = live.followSongSound,
                            playlistValue = playlist?.followSongSound ?: true
                        ) { v ->
                            if (isSong) {
                                LibraryManager.updateSong(
                                    context,
                                    PlaylistRepository.STINGERS_NAME,
                                    live.songUri
                                ) {
                                    it.copy(followSongSound = v)
                                }
                            } else {
                                LibraryManager.setStingersFollow(
                                    context,
                                    followSound = v ?: true
                                )
                            }
                        }

                        StingerFollowToggle(
                            label = "Match song tempo",
                            isSong = isSong,
                            songValue = live.matchTempo,
                            playlistValue = playlist?.matchTempo ?: true
                        ) { v ->
                            if (isSong) {
                                LibraryManager.updateSong(
                                    context,
                                    PlaylistRepository.STINGERS_NAME,
                                    live.songUri
                                ) {
                                    it.copy(matchTempo = v)
                                }
                            } else {
                                LibraryManager.setStingersFollow(
                                    context,
                                    matchTempo = v ?: true
                                )
                            }
                        }
                    }

                    val (resetLabel, resetEnabled) = if (isSong)
                        "Use Playlist Settings" to songOverride
                    else
                        "Reset" to (plSpeed != 1f || plPitch != 1f)
                    Text(
                        resetLabel,
                        color = if (resetEnabled) Color.White else Color.DarkGray,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.End)
                            .noRippleClickable { if (resetEnabled) setSound(1f, 1f) }
                            .padding(8.dp)
                    )
                }
            }

            OptionsPage.ADD_TO_PLAYLIST -> {
                OptionsPageHeader("Add to playlist") { page = OptionsPage.MENU }

                var creating by remember { mutableStateOf(false) }
                var newName by remember { mutableStateOf("") }
                var newError by remember { mutableStateOf<String?>(null) }

                if (creating) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; newError = null },
                        placeholder = { Text("Playlist name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    newError?.let { Text(it, color = Color(0xFFE57373), fontSize = 12.sp) }
                    Text(
                        "Create & add",
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .align(Alignment.End)
                            .noRippleClickable {
                                val err = LibraryManager.createPlaylist(context, newName)
                                if (err != null) {
                                    newError = err
                                } else {
                                    LibraryManager.addToPlaylist(context, newName.trim(), live)
                                    creating = false
                                    newName = ""
                                }
                            }
                            .padding(8.dp)
                    )
                } else {
                    OptionsMenuItem("+ New playlist") { creating = true }
                }

                val userPlaylists =
                    LibraryManager.playlists.filter { !PlaylistRepository.isBuiltIn(it.name) }
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    userPlaylists.forEach { p ->
                        val already = p.entries.any { it.songUri == live.songUri }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .noRippleClickable {
                                    if (!already) LibraryManager.addToPlaylist(
                                        context,
                                        p.name,
                                        live
                                    )
                                }
                                .padding(vertical = 14.dp)
                        ) {
                            Text(
                                p.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (already) "✓" else "+",
                                color = if (already) Color.White else Color.Gray,
                                fontSize = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondsField(ms: Long, key: Any, modifier: Modifier = Modifier, onChange: (Long) -> Unit) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    var text by remember(key) {
        mutableStateOf(String.format(java.util.Locale.US, "%.1f", ms / 1000.0).removeSuffix(".0"))
    }
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toFloatOrNull()
                ?.takeIf { it in 0f..600f }
                ?.let { onChange((it * 1000).toLong()) }
        },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = colors.onSurface, fontSize = 14.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
        ),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) { inner() }
        }
    )
}

private fun applyEqBandLevels(equalizer: android.media.audiofx.Equalizer?, entry: PlaylistEntry?) {
    if (equalizer == null || entry == null) return
    entry.eqBandLevels.forEachIndexed { index, level ->
        if (index < equalizer.numberOfBands) {
            equalizer.setBandLevel(index.toShort(), level.toShort())
        }
    }
}

fun formatMs(ms: Float): String {
    val total = (ms / 1000).toInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

private enum class LibrarySubScreen { MENU, ALL_SONGS, STINGERS, PLAYLIST_DETAIL }

@Composable
fun LibraryScreen(
    onSongClick: (playlistName: String, entries: List<PlaylistEntry>, index: Int) -> Unit
) {
    var subScreen by remember { mutableStateOf(LibrarySubScreen.MENU) }
    var openPlaylist by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = subScreen != LibrarySubScreen.MENU) {
        subScreen = LibrarySubScreen.MENU
    }

    when (subScreen) {
        LibrarySubScreen.MENU -> LibraryMenuScreen(
            onAllSongsClick = { subScreen = LibrarySubScreen.ALL_SONGS },
            onStingersClick = { subScreen = LibrarySubScreen.STINGERS },
            onOpenPlaylist = { name ->
                openPlaylist = name
                subScreen = LibrarySubScreen.PLAYLIST_DETAIL
            }
        )
        LibrarySubScreen.ALL_SONGS -> AllSongsScreen(onSongClick = onSongClick)
        LibrarySubScreen.STINGERS -> PlaylistDetailScreen(
            name = PlaylistRepository.STINGERS_NAME,
            onSongClick = onSongClick,
            onRenamed = {},
            onDeleted = {},
            builtIn = true
        )
        LibrarySubScreen.PLAYLIST_DETAIL -> {
            val name = openPlaylist
            if (name == null) {
                subScreen = LibrarySubScreen.MENU
            } else {
                PlaylistDetailScreen(
                    name = name,
                    onSongClick = onSongClick,
                    onRenamed = { openPlaylist = it },
                    onDeleted = {
                        openPlaylist = null
                        subScreen = LibrarySubScreen.MENU
                    }
                )
            }
        }
    }
}

@Composable
fun PlaylistsScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    val userPlaylists = LibraryManager.playlists.filter { !PlaylistRepository.isBuiltIn(it.name) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text("Playlists", color = Color.White, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "+ New playlist",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable { showCreate = true }
                    .padding(vertical = 14.dp)
            )

            if (userPlaylists.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("No playlists yet.", color = Color.Gray)
            }

            androidx.compose.foundation.lazy.LazyColumn {
                items(userPlaylists.size) { i ->
                    val p = userPlaylists[i]
                    val count = LibraryManager.entriesFor(p.name).size
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .noRippleClickable { onOpen(p.name) }
                            .padding(vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                if (count == 1) "1" else "$count",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        Text("›", color = Color.Gray, fontSize = 20.sp)
                    }
                }
            }
        }

        if (showCreate) {
            NamePrompt(
                title = "New playlist",
                initial = "",
                confirmLabel = "Create",
                onConfirm = { name ->
                    LibraryManager.createPlaylist(context, name).also { if (it == null) showCreate = false }
                },
                onDismiss = { showCreate = false }
            )
        }
    }
}

@Composable
fun PlaylistDetailScreen(
    name: String,
    onSongClick: (playlistName: String, entries: List<PlaylistEntry>, index: Int) -> Unit,
    onRenamed: (String) -> Unit,
    onDeleted: () -> Unit,
    builtIn: Boolean = false
) {
    val context = LocalContext.current
    val entries = remember(LibraryManager.playlists, LibraryManager.availableUris, name) {
        LibraryManager.entriesFor(name)
    }
    var optionsFor by remember { mutableStateOf<PlaylistEntry?>(null) }
    var showPlaylistMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    var searchText by remember { mutableStateOf("") }
    val query = searchText.trim()
    val shown = remember(query, entries) {
        if (query.isEmpty()) entries else entries.filter { it.matches(query) }
    }
    val onlyResult = query.isNotEmpty() && shown.size == 1
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        color = Color.White,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        songCountLabel(shown.size, entries.size, query.isNotEmpty()),
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                if (!builtIn) {
                    Image(
                        painter = painterResource(R.drawable.ic_options),
                        contentDescription = "Playlist options",
                        modifier = Modifier
                            .size(40.dp)
                            .noRippleClickable { showPlaylistMenu = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (entries.isNotEmpty()) {
                SongSearchField(searchText, { searchText = it })
                Spacer(modifier = Modifier.height(16.dp))
            }

            when {
                entries.isEmpty() -> Text(
                    if (name == PlaylistRepository.STINGERS_NAME)
                        "No stingers"
                    else
                        "No songs",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
                shown.isEmpty() -> Text("No songs match \"$query\"", color = Color.Gray, fontSize = 13.sp)
            }

            androidx.compose.foundation.lazy.LazyColumn(state = listState) {
                items(shown.size) { i ->
                    val entry = shown[i]
                    SongRow(
                        entry = entry,
                        highlighted = onlyResult,
                        onClick = { onSongClick(name, entries, entries.indexOf(entry)) },
                        onOptions = { optionsFor = entry }
                    )
                }
            }
        }

        optionsFor?.let { entry ->
            SongOptionsPopup(entry, name, onDismiss = { optionsFor = null })
        }

        if (showPlaylistMenu) {
            PopupCard(onDismiss = { showPlaylistMenu = false }) {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                OptionsMenuItem("Rename") {
                    showPlaylistMenu = false
                    showRename = true
                }
                OptionsMenuItem("Delete playlist") {
                    showPlaylistMenu = false
                    confirmDelete = true
                }
            }
        }

        if (showRename) {
            NamePrompt(
                title = "Rename playlist",
                initial = name,
                confirmLabel = "Save",
                onConfirm = { newName ->
                    LibraryManager.renamePlaylist(context, name, newName).also {
                        if (it == null) {
                            showRename = false
                            onRenamed(newName.trim())
                        }
                    }
                },
                onDismiss = { showRename = false }
            )
        }

        if (confirmDelete) {
            PopupCard(onDismiss = { confirmDelete = false }) {
                Text("Delete \"$name\"?", color = Color.White, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Your songs stay on your phone. Only this playlist and its settings are removed.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.align(Alignment.End)) {
                    Text(
                        "Cancel",
                        color = Color.Gray,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .noRippleClickable { confirmDelete = false }
                            .padding(8.dp)
                    )
                    Text(
                        "Delete",
                        color = Color(0xFFE57373),
                        fontSize = 15.sp,
                        modifier = Modifier
                            .noRippleClickable {
                                LibraryManager.deletePlaylist(context, name)
                                confirmDelete = false
                                onDeleted()
                            }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryMenuScreen(
    onAllSongsClick: () -> Unit,
    onStingersClick: () -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    val userPlaylists = LibraryManager.playlists.filter { !PlaylistRepository.isBuiltIn(it.name) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                listOf(
                    "All Songs" to onAllSongsClick,
                    "Stingers" to onStingersClick
                ).forEach { (label, onClick) ->
                    Text(
                        label,
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .noRippleClickable { onClick() }
                            .padding(vertical = 16.dp)
                    )
                }
            }

            item {
                Text(
                    "Playlists",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Text(
                    "+ New playlist",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRippleClickable { showCreate = true }
                        .padding(vertical = 14.dp)
                )
                if (userPlaylists.isEmpty()) {
                    Text("No playlists yet.", color = Color.Gray, fontSize = 13.sp)
                }
            }

            items(userPlaylists.size) { i ->
                val p = userPlaylists[i]
                val count = LibraryManager.entriesFor(p.name).size
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRippleClickable { onOpenPlaylist(p.name) }
                        .padding(vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            p.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text("$count", color = Color.Gray, fontSize = 12.sp)
                    }
                    Text("›", color = Color.Gray, fontSize = 20.sp)
                }
            }
        }

        if (showCreate) {
            NamePrompt(
                title = "New playlist",
                initial = "",
                confirmLabel = "Create",
                onConfirm = { name ->
                    LibraryManager.createPlaylist(context, name).also { if (it == null) showCreate = false }
                },
                onDismiss = { showCreate = false }
            )
        }
    }
}

fun PlaylistEntry.matches(query: String): Boolean =
    listOf(title, songName, artist, album, genre).any { it.contains(query, ignoreCase = true) }

@Composable
fun SongSearchField(text: String, onChange: (String) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
        interactionSource = interaction,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = { focusManager.clearFocus() }
        ),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (focused) colors.primary else colors.outline,
                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        "Search",
                        style = textStyle.copy(color = colors.onSurfaceVariant)
                    )
                }
                innerTextField()
            }
        }
    )
}

fun songCountLabel(shown: Int, total: Int, searching: Boolean): String {
    val totalText = if (total == 1) "1" else "$total"
    return if (searching) "$shown of $totalText" else totalText
}
@Composable
fun AllSongsScreen(
    onSongClick: (playlistName: String, entries: List<PlaylistEntry>, index: Int) -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    var optionsFor by remember { mutableStateOf<PlaylistEntry?>(null) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val allEntries = remember(LibraryManager.playlists, LibraryManager.availableUris) {
        val stingerUris = LibraryManager.entriesFor(PlaylistRepository.STINGERS_NAME)
            .map { it.songUri }.toSet()
        LibraryManager.entriesFor(PlaylistRepository.ALL_SONGS_NAME)
            .filter { it.songUri !in stingerUris }
            .sortedWith(
                compareByDescending<PlaylistEntry> { it.dateAdded }
                    .thenBy { it.songName.lowercase() }
            )
    }

    val query = searchText.trim()
    val shown = remember(query, allEntries) {
        if (query.isEmpty()) allEntries
        else allEntries.filter { it.matches(query) }
    }
    val onlyResult = query.isNotEmpty() && shown.size == 1

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text("All Songs", color = Color.White, fontSize = 20.sp)

            Text(
                songCountLabel(shown.size, allEntries.size, query.isNotEmpty()),
                color = Color.Gray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            SongSearchField(searchText, { searchText = it })

            Spacer(modifier = Modifier.height(16.dp))

            if (query.isNotEmpty() && shown.isEmpty()) {
                Text("No songs match \"$query\"", color = Color.Gray, fontSize = 13.sp)
            }

            androidx.compose.foundation.lazy.LazyColumn(state = listState) {
                items(shown.size) { index ->
                    val entry = shown[index]
                    SongRow(
                        entry = entry,
                        highlighted = onlyResult,
                        onClick = {
                            onSongClick(PlaylistRepository.ALL_SONGS_NAME, allEntries, allEntries.indexOf(entry))
                        },
                        onOptions = { optionsFor = entry }
                    )
                }
            }
        }

        optionsFor?.let { entry ->
            SongOptionsPopup(entry, PlaylistRepository.ALL_SONGS_NAME, onDismiss = { optionsFor = null })
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Text("Settings", color = Color.White, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(20.dp))

        Text("When other apps make sound", color = Color.Gray, fontSize = 12.sp)
        Row(modifier = Modifier.padding(vertical = 6.dp)) {
            listOf(false to "Duck", true to "Pause").forEach { (value, label) ->
                val selected = AppSettings.pauseOnInterrupt == value
                Text(
                    label,
                    color = if (selected) Color.White else Color.Gray,
                    fontSize = 16.sp,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    modifier = Modifier
                        .noRippleClickable { AppSettings.updatePauseOnInterrupt(value) }
                        .padding(end = 24.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }

        if (!AppSettings.pauseOnInterrupt) {
            Spacer(modifier = Modifier.height(4.dp))
            SettingNumberField(initial = "${AppSettings.duckPercent}", suffix = "% volume") { v ->
                if (v in 0f..100f) { AppSettings.updateDuckPercent(v.toInt()); true } else false
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text("Auto-stinger length", color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(6.dp))
        SettingNumberField(
            initial = String.format(java.util.Locale.US, "%.1f", AppSettings.autoStingerSec).removeSuffix(".0"),
            suffix = "seconds"
        ) { v ->
            if (v in 1f..600f) {
                AppSettings.updateAutoStingerSec(v)
                LibraryManager.refreshStingersNow(context)
                true
            } else false
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Songs shorter than this with no album count as stingers.",
            color = Color.DarkGray,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SettingNumberField(initial: String, suffix: String, onChange: (Float) -> Boolean) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initial) }
    var bad by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { t ->
                text = t
                val v = t.replace(',', '.').toFloatOrNull()
                bad = v == null || !onChange(v)
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.onSurface, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
            ),
            modifier = Modifier.width(90.dp),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (bad) Color(0xFFE57373) else colors.outline,
                            androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { inner() }
            }
        )
        Text("  $suffix", color = Color.Gray, fontSize = 14.sp)
    }
}

private const val PREFETCH_RADIUS = 2

object MetaCache {
    private val cache = object : android.util.LruCache<String, SongMeta>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: SongMeta) = (value.art?.byteCount ?: 0) + 1024
    }
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun get(uri: String): SongMeta? = cache.get(uri)

    fun load(context: Context, uri: String): SongMeta? {
        cache.get(uri)?.let { return it }
        val meta = loadSongMeta(context, Uri.parse(uri)) ?: return null
        cache.put(uri, meta)
        return meta
    }

    fun prefetch(context: Context, uri: String, scope: kotlinx.coroutines.CoroutineScope) {
        if (cache.get(uri) != null || !inFlight.add(uri)) return
        scope.launch(Dispatchers.IO) {
            try { load(context, uri) } finally { inFlight.remove(uri) }
        }
    }
}

private fun decodeArt(bytes: ByteArray, maxDim: Int = 1080): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
    return android.graphics.BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    )
}

private fun loadSongMeta(context: Context, uri: Uri): SongMeta? {
    val mmr = android.media.MediaMetadataRetriever()
    val base = try {
        mmr.setDataSource(context, uri)
        SongMeta(
            title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
            art = mmr.embeddedPicture?.let { decodeArt(it) }
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        try { mmr.release() } catch (_: Exception) {}
    }
    return base?.copy(lyrics = LyricsReader.read(context, uri))
}

@Composable
fun MainScreen() {
    var tabResetKey by remember { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf(Tab.MUSIC) }
    var currentPosition by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentSongMeta by remember { mutableStateOf<SongMeta?>(null) }
    var currentSongUri by remember { mutableStateOf<String?>(null) }
    var repeatMode by remember { mutableStateOf(Player.REPEAT_MODE_ALL) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var queueItems by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var playWhenReady by remember { mutableStateOf(false) }
    var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }

    val showPause = playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED

    var screenVisible by remember { mutableStateOf(true) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> screenVisible = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> screenVisible = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        var metaJob: kotlinx.coroutines.Job? = null

        controllerFuture.addListener({
            val c = controllerFuture.get()
            mediaController = c

            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                    playWhenReady = ready
                }

                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                }

                override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                    shuffleEnabled = enabled
                    queueItems = buildQueueDisplay(c)
                }

                override fun onRepeatModeChanged(mode: Int) {
                    repeatMode = mode
                }

                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    queueItems = buildQueueDisplay(c)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    queueItems = buildQueueDisplay(c)
                    metaJob?.cancel()

                    val uriString = mediaItem?.localConfiguration?.uri?.toString()
                    currentSongUri = uriString
                    LibraryManager.nowPlayingUri = uriString
                    if (uriString == null) {
                        currentSongMeta = null
                        currentPosition = 0f
                        duration = 0f
                        return
                    }

                    currentPosition = c.currentPosition.toFloat()
                    duration = c.duration.toFloat().coerceAtLeast(0f)

                    val cached = MetaCache.get(uriString)
                    if (cached != null) {
                        currentSongMeta = cached
                    } else {
                        val known = LibraryManager.activeEntriesByUri()[uriString]
                        currentSongMeta = SongMeta(
                            title = known?.title?.ifBlank { known.fileName.substringBeforeLast('.') }?.ifBlank { null },
                            artist = known?.artist?.ifBlank { null },
                            album = known?.album?.ifBlank { null },
                            art = ArtCache.get(uriString)
                        )
                        metaJob = scope.launch {
                            withContext(Dispatchers.IO) { MetaCache.load(context, uriString) }
                                ?.let { currentSongMeta = it }
                        }
                    }

                    val timeline = c.currentTimeline
                    val walkMode = if (c.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
                    var ahead = c.currentMediaItemIndex
                    var behind = c.currentMediaItemIndex
                    repeat(PREFETCH_RADIUS) {
                        if (ahead != androidx.media3.common.C.INDEX_UNSET)
                            ahead = timeline.getNextWindowIndex(ahead, walkMode, c.shuffleModeEnabled)
                        if (behind != androidx.media3.common.C.INDEX_UNSET)
                            behind = timeline.getPreviousWindowIndex(behind, walkMode, c.shuffleModeEnabled)
                        listOf(ahead, behind)
                            .filter { it != androidx.media3.common.C.INDEX_UNSET }
                            .mapNotNull { c.getMediaItemAt(it).localConfiguration?.uri?.toString() }
                            .forEach { MetaCache.prefetch(context, it, scope) }
                    }
                }
            }
            c.addListener(listener)

            isPlaying = c.isPlaying
            playWhenReady = c.playWhenReady
            playbackState = c.playbackState
            shuffleEnabled = c.shuffleModeEnabled
            repeatMode = c.repeatMode
            queueItems = buildQueueDisplay(c)
            c.currentMediaItem?.let {
                listener.onMediaItemTransition(it, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
        }, MoreExecutors.directExecutor())

        onDispose {
            metaJob?.cancel()
            mediaController?.release()
        }
    }

    fun playQueue(playlistName: String, entries: List<PlaylistEntry>, startIndex: Int) {
        LibraryManager.activePlaylistName = playlistName
        val items = entries.map { MediaItem.fromUri(android.net.Uri.parse(it.songUri)) }
        mediaController?.let {
            it.setMediaItems(items, startIndex, 0L)
            it.prepare()
            it.play()
        }
        queueItems = buildQueueDisplay(mediaController)
    }

    LaunchedEffect(isPlaying, screenVisible) {
        if (!isSeeking) {
            mediaController?.let {
                currentPosition = it.currentPosition.toFloat()
                duration = it.duration.toFloat().coerceAtLeast(0f)
            }
        }
        while (isPlaying && screenVisible) {
            if (!isSeeking) {
                currentPosition = mediaController?.currentPosition?.toFloat() ?: 0f
                duration = mediaController?.duration?.toFloat()?.coerceAtLeast(0f) ?: 0f
            }
            delay(500)
        }
    }

    LaunchedEffect(LibraryManager.availableUris, mediaController) {
        val c = mediaController ?: return@LaunchedEffect
        val available = LibraryManager.availableUris
        if (available.isEmpty()) return@LaunchedEffect

        val count = c.mediaItemCount
        val gone = (0 until count).filter { i ->
            c.getMediaItemAt(i).localConfiguration?.uri?.toString() !in available
        }.toSet()
        if (gone.isEmpty()) return@LaunchedEffect

        if (gone.size == count) {
            c.clearMediaItems()
            return@LaunchedEffect
        }

        if (c.currentMediaItemIndex in gone) {
            val timeline = c.currentTimeline
            var next = timeline.getNextWindowIndex(c.currentMediaItemIndex, Player.REPEAT_MODE_ALL, c.shuffleModeEnabled)
            var steps = 0
            while (next in gone && steps < count) {
                next = timeline.getNextWindowIndex(next, Player.REPEAT_MODE_ALL, c.shuffleModeEnabled)
                steps++
            }
            c.seekTo(next, 0L)
        }

        gone.sortedDescending().forEach { c.removeMediaItem(it) }
    }

    fun shuffleAllSongs() {
        val stingerUris = LibraryManager.entriesFor(PlaylistRepository.STINGERS_NAME)
            .map { it.songUri }.toSet()
        val songs = LibraryManager.entriesFor(PlaylistRepository.ALL_SONGS_NAME)
            .filter { it.songUri !in stingerUris }
        if (songs.isEmpty()) return

        playQueue(PlaylistRepository.ALL_SONGS_NAME, songs, songs.indices.random())
        mediaController?.shuffleModeEnabled = true
    }

    fun togglePlayPause() {
        val c = mediaController ?: return
        if (c.mediaItemCount == 0) {
            shuffleAllSongs()
            return
        }
        if (showPause) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun cycleRepeat() {
        val next = if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        mediaController?.repeatMode = next
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                Tab.MUSIC -> MusicScreen(
                    isPlaying = showPause,
                    onPrevious = { mediaController?.seekToPrevious() },
                    onSkip = { mediaController?.seekToNext() },
                    onDragPreview = { pos -> currentPosition = pos },
                    onShuffleToggle = {
                        mediaController?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                    },
                    shuffleEnabled = shuffleEnabled,
                    onPlayPause = { togglePlayPause() },
                    songMeta = currentSongMeta,
                    currentSongUri = currentSongUri,
                    currentPosition = currentPosition,
                    duration = duration,
                    onSeek = { pos -> currentPosition = pos; isSeeking = false; mediaController?.seekTo(pos.toLong()) },
                    onSeekStart = { isSeeking = true },
                    onRepeatCycle = { cycleRepeat() },
                    repeatMode = repeatMode,
                    queueItems = queueItems,
                    onQueueItemClick = { item -> mediaController?.seekTo(item.timelineIndex, 0L) }
                )
                Tab.LIBRARY -> androidx.compose.runtime.key(tabResetKey) {
                    LibraryScreen(onSongClick = { name, entries, index ->
                        playQueue(name, entries, index)
                        selectedTab = Tab.MUSIC
                    })
                }
                Tab.SETTINGS -> androidx.compose.runtime.key(tabResetKey) { SettingsScreen() }
            }
        }
        BottomBar(
            selectedTab = selectedTab,
            onTabSelected = { tab ->
                if (tab == selectedTab) tabResetKey++
                else selectedTab = tab
            }
        )
    }
}

@Composable
fun BottomBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF323232))
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            BottomTabItem(Tab.MUSIC, "Music", selectedTab, onTabSelected)
            BottomTabItem(Tab.LIBRARY, "Library", selectedTab, onTabSelected)
            BottomTabItem(Tab.SETTINGS, "Settings", selectedTab, onTabSelected)
        }
    }
}

@Composable
fun RowScope.BottomTabItem(
    tab: Tab,
    label: String,
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit
) {
    val selected = tab == selectedTab

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(
                Color(0xFF323232)
            )
            .noRippleClickable { onTabSelected(tab) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White
        )
    }
}

private val GENERIC_FOLDERS = setOf(
    "", "download", "downloads", "music", "audio", "documents", "recordings",
    "ringtones", "notifications", "alarms", "podcasts", "audiobooks", "bluetooth",
    "telegram audio", "whatsapp audio"
)

data class DeviceSong(
    val uri: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val genre: String
)

fun loadDeviceSongs(context: Context): List<DeviceSong> {
    val songs = mutableListOf<DeviceSong>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.RELATIVE_PATH,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.GENRE
    )

    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

    context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val genreCol = cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)

            val folder = (cursor.getString(pathCol) ?: "").trimEnd('/').substringAfterLast('/')
            val rawAlbum = cursor.getString(albumCol) ?: ""
            val looksFake = rawAlbum.equals(folder, ignoreCase = true) &&
                    folder.lowercase() in GENERIC_FOLDERS
            val album = rawAlbum.takeUnless {
                it.isBlank() || it == MediaStore.UNKNOWN_STRING || looksFake
            } ?: ""

            songs.add(
                DeviceSong(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    fileName = cursor.getString(nameCol) ?: "",
                    title = cursor.getString(titleCol) ?: "",
                    artist = cursor.getString(artistCol)
                        ?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "",
                    album = album,
                    durationMs = cursor.getLong(durCol),
                    genre = (if (genreCol >= 0) cursor.getString(genreCol) else null)
                        ?.takeUnless { it == MediaStore.UNKNOWN_STRING }?.trim() ?: "",
                    dateAdded = cursor.getLong(dateCol),
                    dateModified = cursor.getLong(modCol)
                )
            )
        }
    }
    return songs
}
 