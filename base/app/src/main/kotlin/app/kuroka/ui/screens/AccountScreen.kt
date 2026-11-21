package app.kuroka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kuroka.api.AccountPhase
import app.kuroka.api.AccountUser
import app.kuroka.host.library.Stats
import app.kuroka.ui.components.AccentButton
import app.kuroka.ui.components.BarAction
import app.kuroka.ui.components.KurokaTopBar
import app.kuroka.ui.components.NetworkImage
import app.kuroka.ui.components.Notice
import app.kuroka.ui.components.SettingGroup
import app.kuroka.ui.components.SettingRow
import app.kuroka.ui.components.StatTile
import app.kuroka.ui.theme.LocalAccent
import app.kuroka.ui.viewmodel.AccountViewModel
import kotlinx.coroutines.delay

/*
 * The account: AniList's sign-in, with the browser doing the asking (a PKCE flow, so no secret lives
 * on the phone) and the app taking the code back through its own deep link.
 *
 * Signed in, the page shows who is signed in, the numbers AniList keeps, what is waiting to be
 * pushed up, and the buttons that pull the lists down or take the sign-in away. A token can be
 * pasted instead, for a user who would rather not open a browser.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../../../LICENSE).
 */

@Composable
fun AccountScreen(
    account: AccountViewModel,
    onBack: () -> Unit,
) {
    val phase by account.phase.collectAsState()
    val user by account.user.collectAsState()
    val notice by account.notice.collectAsState()
    val busy by account.busy.collectAsState()
    val stats by account.stats.collectAsState()
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4200)
            account.clearNotice()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            KurokaTopBar(
                title = "Your account",
                onBack = onBack,
                actions = {
                    if (phase == AccountPhase.SIGNED_IN) {
                        BarAction(Icons.Rounded.Sync, "Pull your lists down", onClick = { account.sync() })
                        BarAction(Icons.Rounded.Refresh, "Ask AniList again", onClick = { account.refresh() })
                    }
                },
            )

            when (phase) {
                AccountPhase.SIGNED_IN -> SignedIn(
                    user = user,
                    stats = stats,
                    busy = busy,
                    onSync = { account.sync() },
                    onSignOut = { account.signOut() },
                )

                AccountPhase.SIGNING_IN -> Column(
                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = LocalAccent.current, strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Waiting for AniList…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "The browser has the asking; Kuroka takes the answer when it comes back " +
                            "through its own link. If nothing happens, sign in with a token instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    AccentButton(text = "Sign in with a token", onClick = { showToken = true })
                }

                else -> SignedOut(
                    phase = phase,
                    canOAuth = account.canOAuth,
                    busy = busy,
                    showToken = showToken,
                    token = token,
                    onToken = { token = it },
                    onShowToken = { showToken = !showToken },
                    onBrowser = { account.signInWithBrowser(context) },
                    onTokenSignIn = {
                        account.signInWithToken(token.trim())
                        showToken = false
                    },
                    onOpenTokenPage = { account.openTokenPage(context) },
                )
            }
            Spacer(Modifier.height(30.dp))
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            Notice(text = notice, onDismiss = { account.clearNotice() })
        }
    }
}

/** Who is signed in, and what AniList keeps of them. */
@Composable
private fun SignedIn(
    user: AccountUser?,
    stats: Stats,
    busy: Boolean,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            NetworkImage(
                url = user?.banner,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 900,
            )
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    NetworkImage(
                        url = user?.avatar,
                        contentDescription = user?.name,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        widthPx = 200,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = user?.name ?: "Signed in",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Talking to AniList…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingGroup("What AniList keeps")
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = (user?.episodesWatched ?: 0).toString(),
                    label = "episodes watched",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = "${(user?.minutesWatched ?: 0L) / 60} h",
                    label = "of anime",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = if ((user?.meanScore ?: 0.0) > 0.0) String.format("%.1f", user?.meanScore ?: 0.0) else "—",
                    label = "mean score there",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = if (stats.rated > 0) String.format("%.1f", stats.averageStars) else "—",
                    label = "your stars here (${stats.rated} rated)",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(value = stats.anime.toString(), label = "anime on your lists", modifier = Modifier.weight(1f))
                StatTile(value = stats.favourites.toString(), label = "favourites", modifier = Modifier.weight(1f))
            }
        }

        SettingGroup("Your lists")
        SettingRow(
            title = "Pull them down",
            subtitle = "AniList's lists and favourites come to the phone; what is only here goes up.",
            onClick = onSync,
        )
        user?.siteUrl?.takeIf { it.isNotBlank() }?.let { url ->
            SettingRow(
                title = "Your AniList page",
                subtitle = url,
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            )
                        )
                    }
                },
            )
        }
        SettingRow(
            title = "Sign out",
            subtitle = "The token goes, and your lists stay on the phone as local ones.",
            onClick = onSignOut,
        )
    }
}

/** The two ways in: the browser, and a token pasted by hand. */
@Composable
private fun SignedOut(
    phase: AccountPhase,
    canOAuth: Boolean,
    busy: Boolean,
    showToken: Boolean,
    token: String,
    onToken: (String) -> Unit,
    onShowToken: () -> Unit,
    onBrowser: () -> Unit,
    onTokenSignIn: () -> Unit,
    onOpenTokenPage: () -> Unit,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (phase == AccountPhase.FAILED) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "The sign-in did not go through. Try again, or paste a token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        Text(
            text = "AniList is the catalog Kuroka reads: the anime, the episodes' names, the airing " +
                "times and the genres. Signing in adds your own lists to it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        listOf(
            "Your lists and favourites come down to the phone.",
            "What you watch here goes up, so another device goes on where this one stopped.",
            "Everything works signed out too: the lists are kept on the phone alone.",
        ).forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(10.dp),
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        if (canOAuth) {
            AccentButton(
                text = if (busy) "Opening the browser…" else "Sign in with AniList",
                onClick = onBrowser,
                enabled = !busy,
                icon = Icons.Rounded.AccountCircle,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = "This build has no AniList client id, so the browser sign-in is not there. " +
                    "A token still works.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingRow(
            title = "Sign in with a token",
            subtitle = if (showToken) "Paste it below" else "For a build with no browser sign-in",
            onClick = onShowToken,
        )

        if (showToken) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token,
                onValueChange = onToken,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Your AniList token") },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(
                    text = "Sign in",
                    onClick = onTokenSignIn,
                    enabled = token.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onOpenTokenPage)
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = "Where do I find it?",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
            Text(
                text = "AniList's own settings page has the token; Kuroka keeps it in the phone's " +
                    "encrypted storage and never sends it anywhere else.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
