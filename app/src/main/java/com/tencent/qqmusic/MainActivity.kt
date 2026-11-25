package com.tencent.qqmusic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tencent.qqmusic.data.MediaScanner
import com.tencent.qqmusic.data.TrackItem
import com.tencent.qqmusic.data.UserPlaylist
import com.tencent.qqmusic.service.PlaybackService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay

private data class LrcEntry(
    val timeMs: Long,
    val text: String
)

private fun parseSimpleLrc(rawContent: String): List<LrcEntry> {
    val entries = mutableListOf<LrcEntry>()
    val regex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?](.*)""")
    rawContent.lines().forEach { line ->
        val match = regex.find(line.trim()) ?: return@forEach
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val millisRaw = match.groupValues[3]
        val millis = when (millisRaw.length) {
            1 -> (millisRaw.toLongOrNull() ?: 0L) * 100L
            2 -> (millisRaw.toLongOrNull() ?: 0L) * 10L
            3 -> millisRaw.toLongOrNull() ?: 0L
            else -> 0L
        }
        val lyricText = match.groupValues[4].trim()
        if (lyricText.isNotEmpty()) {
            val totalMs = minutes * 60_000L + seconds * 1_000L + millis
            entries.add(LrcEntry(timeMs = totalMs, text = lyricText))
        }
    }
    return entries.sortedBy { it.timeMs }
}

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GlassSpotifyTheme {
                GlassMusicPlayerApp(
                    onBindController = { onReady ->
                        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
                        val future = MediaController.Builder(this, token).buildAsync()
                        controllerFuture = future
                        future.addListener({
                            if (future.isDone) {
                                runCatching { onReady(future.get()) }
                            }
                        }, MoreExecutors.directExecutor())
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
fun GlassSpotifyTheme(content: @Composable () -> Unit) {
    val darkGlassScheme = darkColorScheme(
        primary = Color(0xFF4FD1C5),
        onPrimary = Color(0xFF061316),
        secondary = Color(0xFF818CF8),
        background = Color(0xFF080B10),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF111622),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF192132),
        onSurfaceVariant = Color(0xFF94A3B8)
    )
    MaterialTheme(
        colorScheme = darkGlassScheme,
        content = content
    )
}

@Composable
fun GlassMusicPlayerApp(
    onBindController: ((MediaController) -> Unit) -> Unit
) {
    val context = LocalContext.current

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        hasAudioPermission = grants[audioKey] == true || hasAudioPermission
    }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    var tracks by remember { mutableStateOf<List<TrackItem>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<UserPlaylist>>(emptyList()) }
    var selectedNavTab by remember { mutableIntStateOf(0) }
    var selectedLibrarySection by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showFullPlayer by remember { mutableStateOf(false) }

    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var currentTrack by remember { mutableStateOf<TrackItem?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var trackForPlaylist by remember { mutableStateOf<TrackItem?>(null) }

    fun refreshLibrary() {
        tracks = MediaScanner.scanMusicTracks(context)
        playlists = MediaScanner.loadPlaylists(context)
    }

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) {
            refreshLibrary()
        }
    }

    LaunchedEffect(Unit) {
        onBindController { ctrl ->
            mediaController = ctrl
            isPlaying = ctrl.isPlaying
            durationMs = ctrl.duration.coerceAtLeast(0L)
            currentPosMs = ctrl.currentPosition.coerceAtLeast(0L)

            ctrl.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val mediaIdLong = mediaItem?.mediaId?.toLongOrNull()
                    if (mediaIdLong != null) {
                        currentTrack = tracks.find { it.id == mediaIdLong }
                    }
                    durationMs = ctrl.duration.coerceAtLeast(0L)
                }
            })
        }
    }

    LaunchedEffect(mediaController, isPlaying) {
        val ctrl = mediaController ?: return@LaunchedEffect
        while (isPlaying) {
            currentPosMs = ctrl.currentPosition.coerceAtLeast(0L)
            val dur = ctrl.duration
            if (dur > 0L) durationMs = dur
            delay(400L)
        }
    }

    fun playTrackList(queue: List<TrackItem>, startIndex: Int) {
        if (queue.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, queue.lastIndex)
        val selected = queue[safeIndex]
        currentTrack = selected
        val ctrl = mediaController ?: return
        val items = queue.map { item ->
            MediaItem.Builder()
                .setMediaId(item.id.toString())
                .setUri(item.contentUri)
                .build()
        }
        ctrl.setMediaItems(items, safeIndex, 0L)
        ctrl.prepare()
        ctrl.play()
        isPlaying = true
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF152238),
            Color(0xFF0A0E17),
            Color(0xFF06080D)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .statusBarsPadding()
    ) {
        if (!hasAudioPermission) {
            PermissionPromptCard(
                onGrantClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (currentTrack != null) 146.dp else 76.dp)
            ) {
                TopGlassHeader(
                    title = if (selectedNavTab == 0) "Your Library" else "YouTube Audio",
                    subtitle = if (selectedNavTab == 0) "${tracks.size} filtered tracks" else "Search & save locally",
                    onRefreshClick = { refreshLibrary() }
                )

                if (selectedNavTab == 0) {
                    LibraryTabsRow(
                        selectedIndex = selectedLibrarySection,
                        onSelect = { selectedLibrarySection = it }
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search songs, artists, albums...", color = Color(0xFF94A3B8)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF94A3B8)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0x331E293B),
                            unfocusedContainerColor = Color(0x221E293B),
                            focusedBorderColor = Color(0xFF4FD1C5),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    val filteredTracks = remember(tracks, searchQuery) {
                        if (searchQuery.isBlank()) tracks
                        else {
                            val q = searchQuery.trim().lowercase()
                            tracks.filter {
                                it.title.lowercase().contains(q) ||
                                    it.artist.lowercase().contains(q) ||
                                    it.album.lowercase().contains(q)
                            }
                        }
                    }

                    when (selectedLibrarySection) {
                        0 -> SongsListView(
                            tracks = filteredTracks,
                            currentTrackId = currentTrack?.id,
                            onPlayTrack = { idx -> playTrackList(filteredTracks, idx) },
                            onAddToPlaylist = { trackForPlaylist = it }
                        )
                        1 -> PlaylistsView(
                            playlists = playlists,
                            allTracks = tracks,
                            onCreatePlaylist = { showCreatePlaylistDialog = true },
                            onPlayPlaylistTracks = { list -> playTrackList(list, 0) }
                        )
                        2 -> GroupedCategoryView(
                            items = filteredTracks.groupBy { it.artist }.toList().sortedBy { it.first },
                            subtitleSuffix = "songs",
                            onSelectGroup = { _, groupTracks -> playTrackList(groupTracks, 0) }
                        )
                        3 -> GroupedCategoryView(
                            items = filteredTracks.groupBy { it.album }.toList().sortedBy { it.first },
                            subtitleSuffix = "tracks",
                            onSelectGroup = { _, groupTracks -> playTrackList(groupTracks, 0) }
                        )
                    }
                } else {
                    YouTubeSearchTabView(
                        onLibraryUpdated = { refreshLibrary() }
                    )
                }
            }

            currentTrack?.let { active ->
                FloatingGlassMiniPlayer(
                    track = active,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 82.dp),
                    onOpenFullPlayer = { showFullPlayer = true },
                    onTogglePlay = {
                        mediaController?.let { ctrl ->
                            if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
                        }
                    }
                )
            }

            BottomGlassNavBar(
                selectedTab = selectedNavTab,
                onSelectTab = { selectedNavTab = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }

        if (showFullPlayer && currentTrack != null) {
            FullGlassPlayerSheet(
                track = currentTrack!!,
                isPlaying = isPlaying,
                positionMs = currentPosMs,
                durationMs = if (durationMs > 0L) durationMs else currentTrack!!.durationMs,
                onDismiss = { showFullPlayer = false },
                onTogglePlay = {
                    mediaController?.let { ctrl ->
                        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
                    }
                },
                onSeekTo = { target ->
                    mediaController?.seekTo(target)
                    currentPosMs = target
                },
                onNext = { mediaController?.seekToNext() },
                onPrevious = { mediaController?.seekToPrevious() }
            )
        }

        if (showCreatePlaylistDialog) {
            AlertDialog(
                onDismissRequest = { showCreatePlaylistDialog = false },
                containerColor = Color(0xFF121826),
                title = { Text("New Playlist", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        placeholder = { Text("Playlist name", color = Color(0xFF94A3B8)) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val cleanName = newPlaylistName.trim()
                            if (cleanName.isNotEmpty()) {
                                val updated = playlists + UserPlaylist(
                                    id = UUID.randomUUID().toString(),
                                    name = cleanName,
                                    trackIds = emptyList()
                                )
                                playlists = updated
                                MediaScanner.savePlaylists(context, updated)
                            }
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                        }
                    ) {
                        Text("Create", color = Color(0xFF4FD1C5))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreatePlaylistDialog = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                }
            )
        }

        trackForPlaylist?.let { targetTrack ->
            AlertDialog(
                onDismissRequest = { trackForPlaylist = null },
                containerColor = Color(0xFF121826),
                title = { Text("Add to Playlist", color = Color.White) },
                text = {
                    if (playlists.isEmpty()) {
                        Text("Create a playlist first from the Playlists tab.", color = Color(0xFF94A3B8))
                    } else {
                        Column {
                            playlists.forEach { pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val updated = playlists.map { existing ->
                                                if (existing.id == pl.id && !existing.trackIds.contains(targetTrack.id)) {
                                                    existing.copy(trackIds = existing.trackIds + targetTrack.id)
                                                } else {
                                                    existing
                                                }
                                            }
                                            playlists = updated
                                            MediaScanner.savePlaylists(context, updated)
                                            trackForPlaylist = null
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = Color(0xFF4FD1C5))
                                    Spacer(Modifier.width(10.dp))
                                    Text(pl.name, color = Color.White)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { trackForPlaylist = null }) {
                        Text("Close", color = Color(0xFF94A3B8))
                    }
                }
            )
        }
    }
}

@Composable
private fun TopGlassHeader(
    title: String,
    subtitle: String,
    onRefreshClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )
        }
        IconButton(
            onClick = onRefreshClick,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0x22FFFFFF))
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = Color.White)
        }
    }
}

@Composable
private fun LibraryTabsRow(
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val tabs = listOf("Songs", "Playlists", "Artists", "Albums")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { idx, label ->
            val active = idx == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) Color(0xFF4FD1C5).copy(alpha = 0.22f) else Color(0x18FFFFFF))
                    .border(
                        width = 1.dp,
                        color = if (active) Color(0xFF4FD1C5) else Color(0x28FFFFFF),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    color = if (active) Color(0xFF4FD1C5) else Color(0xFFE2E8F0),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun SongsListView(
    tracks: List<TrackItem>,
    currentTrackId: Long?,
    onPlayTrack: (Int) -> Unit,
    onAddToPlaylist: (TrackItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tracks.size) { index ->
            val item = tracks[index]
            val playingThis = item.id == currentTrackId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (playingThis) Color(0x264FD1C5) else Color(0x14FFFFFF))
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
                    .clickable { onPlayTrack(index) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackArtworkBox(uri = item.albumArtUri, size = 52)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = if (playingThis) Color(0xFF4FD1C5) else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.artist} • ${item.album}",
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onAddToPlaylist(item) }) {
                    Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Add to playlist", tint = Color(0xFFCBD5E1))
                }
            }
        }
    }
}

@Composable
private fun PlaylistsView(
    playlists: List<UserPlaylist>,
    allTracks: List<TrackItem>,
    onCreatePlaylist: () -> Unit,
    onPlayPlaylistTracks: (List<TrackItem>) -> Unit
) {
    val trackMap = remember(allTracks) { allTracks.associateBy { it.id } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x1A4FD1C5))
                    .border(1.dp, Color(0x444FD1C5), RoundedCornerShape(16.dp))
                    .clickable { onCreatePlaylist() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, tint = Color(0xFF4FD1C5))
                Spacer(Modifier.width(10.dp))
                Text("Create New Playlist", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        items(playlists, key = { it.id }) { pl ->
            val resolved = pl.trackIds.mapNotNull { trackMap[it] }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
                    .clickable { if (resolved.isNotEmpty()) onPlayPlaylistTracks(resolved) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackArtworkBox(uri = resolved.firstOrNull()?.albumArtUri, size = 50)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(pl.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${resolved.size} tracks", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color(0xFF4FD1C5))
            }
        }
    }
}

@Composable
private fun GroupedCategoryView(
    items: List<Pair<String, List<TrackItem>>>,
    subtitleSuffix: String,
    onSelectGroup: (String, List<TrackItem>) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items, key = { it.first }) { (groupName, groupTracks) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
                    .clickable { onSelectGroup(groupName, groupTracks) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackArtworkBox(uri = groupTracks.firstOrNull()?.albumArtUri, size = 50)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(groupName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${groupTracks.size} $subtitleSuffix", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color(0xFF94A3B8))
            }
        }
    }
}

@Composable
private fun YouTubeSearchTabView(
    onLibraryUpdated: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf("") }
    var statusMessage by remember {
        mutableStateOf("Enter a song query. Connect a personal downloader API/endpoint URL to save audio files locally.")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search song on YouTube...", color = Color(0xFF94A3B8)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF4FD1C5)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        OutlinedTextField(
            value = endpointUrl,
            onValueChange = { endpointUrl = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Optional downloader / search API URL", color = Color(0xFF94A3B8)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        Button(
            onClick = {
                if (query.isBlank()) {
                    statusMessage = "Type a song title first."
                } else if (endpointUrl.isBlank()) {
                    statusMessage = "Ready for \"$query\": enter a downloader API/endpoint URL to fetch and save locally."
                } else {
                    statusMessage = "Queued \"$query\". Refresh library once the audio file is saved locally."
                    onLibraryUpdated()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Search & Download to Local Music")
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x16FFFFFF))
                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Text(text = statusMessage, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FloatingGlassMiniPlayer(
    track: TrackItem,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onOpenFullPlayer: () -> Unit,
    onTogglePlay: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xCC162032))
            .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(22.dp))
            .clickable { onOpenFullPlayer() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtworkBox(uri = track.albumArtUri, size = 44)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onTogglePlay) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun FullGlassPlayerSheet(
    track: TrackItem,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val lyricsLines: List<LrcEntry> = remember(track.dataPath) {
        if (track.dataPath.isBlank()) {
            emptyList()
        } else {
            val audioFile = File(track.dataPath)
            val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
            if (lrcFile.exists()) {
                runCatching { parseSimpleLrc(lrcFile.readText()) }.getOrElse { emptyList() }
            } else {
                emptyList()
            }
        }
    }

    val activeLyric: String = remember(lyricsLines, positionMs) {
        lyricsLines.lastOrNull { entry: LrcEntry -> entry.timeMs <= positionMs }?.text ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1B2B46), Color(0xFF0A0E17), Color(0xFF05070B))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", tint = Color.White)
            }
            Text("NOW PLAYING", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(48.dp))
        }

        TrackArtworkBox(uri = track.albumArtUri, size = 250)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (activeLyric.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = activeLyric,
                    color = Color(0xFF4FD1C5),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            val safeDur = durationMs.coerceAtLeast(1L)
            val safePos = positionMs.coerceIn(0L, safeDur)
            Slider(
                value = safePos.toFloat() / safeDur.toFloat(),
                onValueChange = { fraction -> onSeekTo((fraction * safeDur).toLong()) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4FD1C5))
                        .clickable { onTogglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF061316),
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

@Composable
private fun BottomGlassNavBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE60B101B))
            .border(1.dp, Color(0x1CFFFFFF))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val navItems = listOf(
            Triple(0, "Library", Icons.Rounded.LibraryMusic),
            Triple(1, "YouTube", Icons.Rounded.Search)
        )
        navItems.forEach { (idx, label, icon) ->
            val active = selectedTab == idx
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectTab(idx) }
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = label, tint = if (active) Color(0xFF4FD1C5) else Color(0xFF94A3B8))
                Text(label, color = if (active) Color(0xFF4FD1C5) else Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TrackArtworkBox(
    uri: Uri?,
    size: Int
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF1E3A5F), Color(0xFF0F172A), Color(0xFF134E4A))
                )
            )
            .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color(0x88FFFFFF), modifier = Modifier.size((size * 0.42f).dp))
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun PermissionPromptCard(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Audio Permission Needed", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Allow audio access to scan your local songs and album covers.", color = Color(0xFF94A3B8))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrantClick) {
            Text("Grant Permission")
        }
    }
}
