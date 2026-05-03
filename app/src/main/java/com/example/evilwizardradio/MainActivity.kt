package com.example.evilwizardradio

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.evilwizardradio.ui.theme.EvilWizardRadioTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import android.media.MediaPlayer
import androidx.compose.ui.platform.LocalContext
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class SongMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: android.graphics.Bitmap?
)

enum class Tab {
    MUSIC, LIBRARY, SETTINGS
}

class MainActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermission.launch(permission)

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
}

@Composable
fun MusicScreen(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    songMeta: SongMeta?,
    currentPosition: Float,
    duration: Float,
    onSeek: (Float) -> Unit,
    onSeekStart: () -> Unit,
    equalizer: android.media.audiofx.Equalizer?
){
    var showLyrics by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    //Blank Canvas
    Box(
        modifier = Modifier
            //big as the box can go
            .fillMaxSize()
            //same color as nav bar
            .background(Color(0xFF121212))
            //nice padding
            .padding(16.dp)
    ) {
        Column(
            //big column
            modifier = Modifier.fillMaxSize(),
            //center'd
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            //top hr for album
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(modifier = Modifier.weight(1f))

                //ALBUM
                val art = songMeta?.art

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    if (art != null) {
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
                            modifier = Modifier
                                .fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                //Meta Data
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    //SONG TITLE
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    ) {
                        Text(
                            text = songMeta?.title ?: "Unknown Title",
                            color = Color.White,
                            fontSize = 18.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    initialDelayMillis = 1000
                                )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    //ARTIST
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp)
                    ) {
                        Text(
                            text = songMeta?.artist ?: "Unknown Artist",
                            color = Color.LightGray,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            fontSize = 14.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    initialDelayMillis = 1000
                                )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    //ALBUM
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp)
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
                                .basicMarquee(
                                    initialDelayMillis = 1000
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            //options row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // left side - options/eq/queue
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_eq),
                        contentDescription = "EQ",
                        modifier = Modifier.size(48.dp).clickable { showEq = true }
                    )

                    Image(
                        painter = painterResource(R.drawable.ic_queue),
                        contentDescription = "Queue",
                        modifier = Modifier.size(48.dp).clickable { }
                    )

                    Image(
                        painter = painterResource(R.drawable.ic_stingers),
                        contentDescription = "Queue",
                        modifier = Modifier.size(48.dp).clickable { }
                    )

                    Image(
                        painter = painterResource(R.drawable.ic_options),
                        contentDescription = "Options",
                        modifier = Modifier.size(48.dp).clickable { }
                    )
                }

                // right side - shuffle/repeat
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_shuffle),
                        contentDescription = "Shuffle",
                        modifier = Modifier.size(48.dp).clickable { }
                    )

                    Image(
                        painter = painterResource(R.drawable.ic_single),
                        contentDescription = "Repeat",
                        modifier = Modifier.size(48.dp).clickable { }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            //PLAYBACK SLIDER
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatMs(currentPosition), color = Color.White, fontSize = 12.sp)

                Spacer(modifier = Modifier.width(16.dp))

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                        .pointerInput(duration) {
                            detectHorizontalDragGestures(
                                onDragStart = { onSeekStart() },
                                onHorizontalDrag = { change, _ ->
                                    val percent = (change.position.x / size.width).coerceIn(0f, 1f)
                                    onSeek(percent * duration)
                                }
                            )
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

            //PLAY/PAUSE ROW
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_previous),
                    contentDescription = "Repeat",
                    modifier = Modifier.size(48.dp).clickable { }
                )

                Spacer(modifier = Modifier.width(48.dp))

                Image(
                    painter = painterResource(
                        id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = "Play Pause",
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { onPlayPause() }
                )

                Spacer(modifier = Modifier.width(48.dp))

                Image(
                    painter = painterResource(R.drawable.ic_skip),
                    contentDescription = "Repeat",
                    modifier = Modifier.size(48.dp).clickable { }
                )
            }

            //EQ popup
            if (showEq) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000))
                        .clickable { showEq = false },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E))
                            .padding(16.dp)
                            .clickable { } // prevent dismiss on content tap
                    ) {
                        Column {
                            Text("Equalizer", color = Color.White, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(16.dp))

                            if (equalizer != null) {
                                val numBands = equalizer.numberOfBands
                                val minLevel = equalizer.bandLevelRange[0].toFloat()
                                val maxLevel = equalizer.bandLevelRange[1].toFloat()

                                for (band in 0 until numBands) {
                                    val freq = equalizer.getCenterFreq(band.toShort()) / 1000
                                    val label = if (freq >= 1000) "${freq/1000}kHz" else "${freq}Hz"

                                    var level by remember {
                                        mutableStateOf(equalizer.getBandLevel(band.toShort()).toFloat())
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            label,
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.width(52.dp)
                                        )
                                        androidx.compose.material3.Slider(
                                            value = level,
                                            onValueChange = {
                                                level = it
                                                equalizer.setBandLevel(band.toShort(), it.toInt().toShort())
                                            },
                                            valueRange = minLevel..maxLevel,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "${(level/100).toInt()}dB",
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.width(40.dp),
                                            textAlign = TextAlign.End
                                        )
                                    }
                                }
                            } else {
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Close",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .clickable { showEq = false }
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


fun formatMs(ms: Float): String {
    val total = (ms / 1000).toInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun LibraryScreen(
    playSong: (android.net.Uri) -> Unit
) {
    val context = LocalContext.current
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var allSongs by remember { mutableStateOf<List<Pair<String, android.net.Uri>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val songs = loadDeviceSongs(context)
            withContext(Dispatchers.Main) {
                allSongs = songs
            }
        }
    }

    val filteredSongs = allSongs.filter {
        it.first.contains(searchText, ignoreCase = true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(24.dp))

            androidx.compose.material3.OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Search songs...") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            androidx.compose.foundation.lazy.LazyColumn {
                items(filteredSongs.size) { index ->
                    val (name, uri) = filteredSongs[index]
                    Text(
                        text = name,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { playSong(uri) }
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    Text(
        text = "Settings Screen",
        color = Color.White)
}

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(Tab.MUSIC) }
    var currentPosition by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentSongMeta by remember { mutableStateOf<SongMeta?>(null) }
    var equalizer by remember { mutableStateOf<android.media.audiofx.Equalizer?>(null) }

    fun playSong(uri: android.net.Uri) {
        mediaPlayer?.release()
        equalizer?.release()

        val player = MediaPlayer().apply {
            setDataSource(context, uri)
            prepare()
            start()
        }
        mediaPlayer = player

        equalizer = android.media.audiofx.Equalizer(0, player.audioSessionId).apply {
            enabled = true
        }

        isPlaying = true

        try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(context, uri)

            val artBytes = mmr.embeddedPicture
            val bitmap = if (artBytes != null) {
                android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            } else null

            val title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)

            mmr.release()
            currentSongMeta = SongMeta(title, artist, album, bitmap)

        } catch (e: Exception) {
            e.printStackTrace()
            currentSongMeta = null
        }

        // stinger
        Handler(Looper.getMainLooper()).postDelayed({
            val stinger = MediaPlayer.create(context, R.raw.stinger)
            stinger.start()
            stinger.setOnCompletionListener { it.release() }
        }, 2000)
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isSeeking) {
                currentPosition = mediaPlayer?.currentPosition?.toFloat() ?: 0f
                duration = mediaPlayer?.duration?.toFloat() ?: 0f
            }
            delay(500)
        }
    }

    fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) { it.pause(); isPlaying = false }
            else { it.start(); isPlaying = true }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212))
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                Tab.MUSIC -> MusicScreen(
                    isPlaying = isPlaying,
                    onPlayPause = { togglePlayPause() },
                    songMeta = currentSongMeta,
                    currentPosition = currentPosition,
                    duration = duration,
                    onSeek = { pos -> currentPosition = pos; isSeeking = false; mediaPlayer?.seekTo(pos.toInt()) },
                    onSeekStart = { isSeeking = true },
                    equalizer = equalizer
                )
                Tab.LIBRARY -> LibraryScreen(playSong = { uri -> playSong(uri) })
                Tab.SETTINGS -> SettingsScreen()
            }
        }
        BottomBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
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
            .clickable { onTabSelected(tab) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White
        )
    }
}


fun loadDeviceSongs(context: Context): List<Pair<String, Uri>> {
    val songs = mutableListOf<Pair<String, Uri>>()

    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.IS_MUSIC
    )
    val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
    val selectionArgs = arrayOf("Music%")

    context.contentResolver.query(
        collection, projection, selection, selectionArgs, null
    )?.use { cursor ->
        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
        val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol)
            val uri = ContentUris.withAppendedId(collection, id)
            songs.add(Pair(name, uri))
        }
    }
    return songs
}